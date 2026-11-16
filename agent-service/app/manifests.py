"""Dependency extraction from repository manifests (text only; nothing is installed, built or executed).

Supported: package.json, requirements*.txt, pyproject.toml, pom.xml, build.gradle(.kts), go.mod, Cargo.toml, Gemfile,
composer.json, *.csproj. XML-ish formats are read with bounded regular expressions (no XML entity expansion).
"""
from __future__ import annotations

import json
import re

try:  # Python 3.11+
    import tomllib as _toml
except ModuleNotFoundError:  # pragma: no cover - Python 3.10
    import tomli as _toml  # type: ignore[no-redef]

MAX_MANIFEST_CHARS = 200_000
MAX_DEPENDENCIES = 300
_REQ_NAME = re.compile(r"^\s*([A-Za-z0-9][A-Za-z0-9._-]*)")
_POM_DEP = re.compile(r"<dependency>(.{0,2000}?)</dependency>", re.S)
_POM_PLUGIN = re.compile(r"<plugin>(.{0,2000}?)</plugin>", re.S)
_XML_TAG = {name: re.compile(rf"<{name}>\s*([^<\s]{{1,200}})\s*</{name}>") for name in ("groupId", "artifactId")}
_GRADLE_DEP = re.compile(
    r"\b(?:implementation|api|compile|compileOnly|runtimeOnly|testImplementation|testCompile|kapt|annotationProcessor)"
    r"\s*\(?\s*[\"']([A-Za-z0-9_.\-]+):([A-Za-z0-9_.\-]+)")
_GRADLE_PLUGIN = re.compile(r"\bid\s*\(?\s*[\"']([A-Za-z0-9_.\-]+)[\"']")
_GO_REQUIRE = re.compile(r"^\s*(?:require\s+)?([a-z0-9.\-]+\.[a-z]{2,}/[^\s]+)\s+v[0-9]", re.M)
_GEM = re.compile(r"^\s*gem\s+[\"']([A-Za-z0-9_.\-]+)[\"']", re.M)
_CSPROJ = re.compile(r"<PackageReference\s+Include\s*=\s*\"([A-Za-z0-9_.\-]+)\"", re.I)


def is_manifest(path: str) -> bool:
    name = path.rsplit("/", 1)[-1].lower()
    return (name in {"package.json", "pyproject.toml", "pom.xml", "build.gradle", "build.gradle.kts", "go.mod",
                     "cargo.toml", "gemfile", "composer.json"}
            or (name.startswith("requirements") and name.endswith(".txt")) or name.endswith(".csproj"))


def _unique(names: list[str]) -> list[str]:
    seen: dict[str, None] = {}
    for name in names:
        cleaned = name.strip()
        if cleaned and cleaned.lower() not in seen:
            seen[cleaned.lower()] = None
    return list(seen)[:MAX_DEPENDENCIES]


def _package_json(text: str) -> list[str]:
    data = json.loads(text)
    names: list[str] = []
    if isinstance(data, dict):
        for section in ("dependencies", "devDependencies", "peerDependencies", "optionalDependencies"):
            value = data.get(section)
            if isinstance(value, dict):
                names.extend(str(key) for key in value)
    return names


def _requirements(text: str) -> list[str]:
    names = []
    for line in text.splitlines():
        line = line.split("#", 1)[0].strip()
        if not line or line.startswith("-"):
            continue
        match = _REQ_NAME.match(line)
        if match:
            names.append(match.group(1))
    return names


def _pyproject(text: str) -> list[str]:
    data = _toml.loads(text)
    names: list[str] = []
    project = data.get("project", {}) if isinstance(data, dict) else {}
    for requirement in project.get("dependencies", []) or []:
        match = _REQ_NAME.match(str(requirement))
        if match:
            names.append(match.group(1))
    for group in (project.get("optional-dependencies", {}) or {}).values():
        for requirement in group or []:
            match = _REQ_NAME.match(str(requirement))
            if match:
                names.append(match.group(1))
    poetry = data.get("tool", {}).get("poetry", {}) if isinstance(data.get("tool"), dict) else {}
    for section in ("dependencies", "dev-dependencies"):
        value = poetry.get(section)
        if isinstance(value, dict):
            names.extend(key for key in value if key.lower() != "python")
    return names


def _pom(text: str) -> list[str]:
    names = []
    for block in _POM_DEP.findall(text) + _POM_PLUGIN.findall(text):
        group = _XML_TAG["groupId"].search(block)
        artifact = _XML_TAG["artifactId"].search(block)
        if group:
            names.append(group.group(1))
        if artifact:
            names.append(artifact.group(1))
    return names


def _gradle(text: str) -> list[str]:
    names = []
    for group, artifact in _GRADLE_DEP.findall(text):
        names += [group, artifact]
    names += _GRADLE_PLUGIN.findall(text)
    return names


def _cargo(text: str) -> list[str]:
    data = _toml.loads(text)
    names: list[str] = []
    for section in ("dependencies", "dev-dependencies", "build-dependencies"):
        value = data.get(section)
        if isinstance(value, dict):
            names.extend(value)
    return names


def _composer(text: str) -> list[str]:
    data = json.loads(text)
    names: list[str] = []
    if isinstance(data, dict):
        for section in ("require", "require-dev"):
            value = data.get(section)
            if isinstance(value, dict):
                names.extend(key for key in value if key != "php" and not key.startswith("ext-"))
    return names


def parse_manifest(path: str, text: str) -> list[str]:
    """Dependency names declared in one manifest; [] when the file cannot be parsed."""
    name = path.rsplit("/", 1)[-1].lower()
    text = text[:MAX_MANIFEST_CHARS]
    try:
        if name == "package.json":
            return _unique(_package_json(text))
        if name.startswith("requirements") and name.endswith(".txt"):
            return _unique(_requirements(text))
        if name == "pyproject.toml":
            return _unique(_pyproject(text))
        if name == "pom.xml":
            return _unique(_pom(text))
        if name in {"build.gradle", "build.gradle.kts"}:
            return _unique(_gradle(text))
        if name == "go.mod":
            return _unique(_GO_REQUIRE.findall(text))
        if name == "cargo.toml":
            return _unique(_cargo(text))
        if name == "gemfile":
            return _unique(_GEM.findall(text))
        if name == "composer.json":
            return _unique(_composer(text))
        if name.endswith(".csproj"):
            return _unique(_CSPROJ.findall(text))
    except (ValueError, TypeError, AttributeError, RecursionError, _toml.TOMLDecodeError):
        return []  # RecursionError: deeply nested JSON/TOML in a candidate's repository
    return []
