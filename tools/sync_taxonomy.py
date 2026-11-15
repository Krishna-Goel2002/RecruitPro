"""Vendor the skills taxonomy from ai-service into agent-service.

Usage (from the repository root)::

    python tools/sync_taxonomy.py          # copy ai-service/app/taxonomy/{skills.json,taxonomy.py}
    python tools/sync_taxonomy.py --check  # exit 1 when the vendored copies differ (CI / tests)

``ai-service`` owns the curated taxonomy. ``agent-service`` must be deployable on its own, so it carries
byte-identical copies under ``agent-service/app/taxonomy/``. ``taxonomy.py`` has no package-relative imports,
so no import rewriting is needed; if that ever changes, add the rewrite to ``_transform`` and the drift test
compares against the transformed text.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE_DIR = ROOT / "ai-service" / "app" / "taxonomy"
TARGET_DIR = ROOT / "agent-service" / "app" / "taxonomy"
FILES = ("skills.json", "taxonomy.py")


def _transform(name: str, content: bytes) -> bytes:
    """Adjust import paths if needed (none today: taxonomy.py only imports the standard library)."""
    return content


def expected(name: str, source_dir: Path = SOURCE_DIR) -> bytes:
    return _transform(name, (source_dir / name).read_bytes())


def drifted(source_dir: Path = SOURCE_DIR, target_dir: Path = TARGET_DIR) -> list[str]:
    """Names of vendored files that are missing or differ from the ai-service source."""
    result = []
    for name in FILES:
        target = target_dir / name
        if not target.exists() or target.read_bytes() != expected(name, source_dir):
            result.append(name)
    return result


def sync(source_dir: Path = SOURCE_DIR, target_dir: Path = TARGET_DIR) -> list[str]:
    target_dir.mkdir(parents=True, exist_ok=True)
    init = target_dir / "__init__.py"
    if not init.exists():
        init.write_text('"""Skills taxonomy vendored from ai-service by tools/sync_taxonomy.py (do not edit)."""\n',
                        encoding="utf-8")
    changed = drifted(source_dir, target_dir)
    for name in changed:
        (target_dir / name).write_bytes(expected(name, source_dir))
    return changed


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="only report drift; exit 1 when the copies differ")
    args = parser.parse_args(argv)
    if args.check:
        changed = drifted()
        for name in changed:
            print(f"drift: agent-service/app/taxonomy/{name} differs from ai-service; run tools/sync_taxonomy.py")
        return 1 if changed else 0
    changed = sync()
    print("updated: " + ", ".join(changed) if changed else "taxonomy already in sync")
    return 0


if __name__ == "__main__":
    sys.exit(main())
