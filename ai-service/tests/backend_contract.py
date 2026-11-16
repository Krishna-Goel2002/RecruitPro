"""The backend's assistant tool registry, as data, for the planner contract tests.

``parse_java_tools`` reads the Java sources (``backend/src/main/java/com/recruitpro/assistant/tool/**``): every
``AssistantTool`` with its name, description, capability, read/write kind and ``ArgSpec`` list (type, required, enum
values, limits, hint). ``tests/fixtures/backend_tools.json`` is that output, checked in so the ai-service tests also
run where the backend tree is absent; a test fails when the two differ.

``catalogue`` renders the tools exactly as the backend sends them to the planner (``AssistantTool.catalogueEntry``:
``{name, description, args: {name: ArgSpec.describe()}, writes}``) for one role, and ``validate`` is a strict port
of the backend's ``ToolArgs.validate``: it also refuses unknown keys and non-canonical values, so a planner step that
passes it is accepted by the backend unchanged.
"""
from __future__ import annotations

import json
import re
from datetime import date, datetime
from pathlib import Path
from typing import Any

AI_SERVICE = Path(__file__).resolve().parents[1]
REPO = AI_SERVICE.parent
JAVA_ROOT = REPO / "backend" / "src" / "main" / "java"
TOOL_DIR = JAVA_ROOT / "com" / "recruitpro" / "assistant" / "tool"
FIXTURE = Path(__file__).resolve().parent / "fixtures" / "backend_tools.json"

TYPE_LABELS = {
    "INTEGER": "integer", "NUMBER": "number", "STRING": "string", "BOOLEAN": "boolean", "ENUM": "string",
    "DATE_TIME": "ISO-8601 date-time", "APPLICATION_CODE": "application code like A-3014",
    "APPLICATION_CODES": "list of application codes", "STRING_LIST": "string or list of strings",
    "REQUIREMENTS": "list of {type, description, mandatory, knockout, minimumYears, alternatives, proficiency}",
}
CAPABILITY_ROLES = {
    "ANY_STAFF": {"ADMIN", "HR_MANAGER", "RECRUITER", "INTERVIEWER"},
    "JOBS_READ": {"ADMIN", "HR_MANAGER", "RECRUITER"},
    "JOBS_MANAGE": {"ADMIN", "HR_MANAGER"},
}
_LONG_MIN, _LONG_MAX = -(2**63), 2**63 - 1


# ------------------------------------------------------------------------------------------------ Java parsing
def _strip_comments(source: str) -> str:
    source = re.sub(r"/\*.*?\*/", " ", source, flags=re.S)
    return re.sub(r"(?m)//[^\n\"]*$", " ", source)


def _method_body(source: str, signature: str) -> str | None:
    match = re.search(signature + r"\s*\([^)]*\)\s*\{", source)
    if not match:
        return None
    depth, start = 1, match.end()
    for index in range(start, len(source)):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1
            if depth == 0:
                return source[start:index]
    return None


def _string_literals(expression: str) -> str:
    """The concatenation of the string literals in ``expression`` ("a " + "b" -> "a b")."""
    return "".join(json.loads(literal) for literal in re.findall(r'"(?:[^"\\]|\\.)*"', expression))


def _constants(source: str) -> dict[str, str]:
    """``static final`` String / int / List<String> constants of a class, as source text."""
    found = {}
    for match in re.finditer(r"static\s+final\s+[\w<>]+\s+(\w+)\s*=\s*([^;]+);", source):
        found[match.group(1)] = match.group(2).strip()
    return found


def _enum_constants(enum_name: str) -> list[str]:
    for path in JAVA_ROOT.rglob(f"{enum_name}.java"):
        body = re.search(r"enum\s+" + enum_name + r"\s*\{([^;}]*)", _strip_comments(path.read_text(encoding="utf-8")))
        if body:
            return [name.strip() for name in body.group(1).split(",") if re.fullmatch(r"\s*[A-Z][A-Z0-9_]*\s*", name)]
    raise ValueError(f"enum {enum_name} not found")


def _help_topics() -> list[str]:
    source = (TOOL_DIR / "read" / "HelpTopics.java").read_text(encoding="utf-8")
    return re.findall(r'TOPICS\.put\(\s*"([A-Z_]+)"', source)


def _list_values(expression: str, constants: dict[str, str]) -> list[str]:
    expression = expression.strip()
    if expression in constants:
        return _list_values(constants[expression], constants)
    enum = re.fullmatch(r"Tools\.names\((\w+)\.values\(\)\)", expression)
    if enum:
        return _enum_constants(enum.group(1))
    if expression == "HelpTopics.names()":
        return _help_topics()
    listed = re.fullmatch(r"List\.of\((.*)\)", expression, flags=re.S)
    if listed:
        return [json.loads(item) for item in re.findall(r'"(?:[^"\\]|\\.)*"', listed.group(1))]
    raise ValueError(f"cannot resolve enum values {expression!r}")


def _number(expression: str, constants: dict[str, str]) -> int:
    expression = expression.strip()
    if expression in constants:
        return _number(constants[expression], constants)
    if expression == "Long.MAX_VALUE":
        return _LONG_MAX
    if expression == "Long.MIN_VALUE":
        return _LONG_MIN
    return int(expression.replace("_", "").rstrip("Ll"))


def _split_top(text: str, separator: str = ",") -> list[str]:
    parts, depth, current, quoted = [], 0, [], False
    index = 0
    while index < len(text):
        char = text[index]
        if quoted:
            current.append(char)
            if char == "\\":
                current.append(text[index + 1])
                index += 1
            elif char == '"':
                quoted = False
        elif char == '"':
            quoted = True
            current.append(char)
        elif char in "([{":
            depth += 1
            current.append(char)
        elif char in ")]}":
            depth -= 1
            current.append(char)
        elif char == separator and depth == 0:
            parts.append("".join(current))
            current = []
        else:
            current.append(char)
        index += 1
    if "".join(current).strip():
        parts.append("".join(current))
    return parts


def _calls(expression: str) -> list[tuple[str, str]]:
    """``ArgSpec.required("a", ArgType.X).range(1, 2)`` -> [("required", '"a", ArgType.X'), ("range", "1, 2")]."""
    calls, index = [], 0
    for match in re.finditer(r"\.?(\w+)\s*\(", expression):
        if match.start() < index:
            continue
        depth, start = 1, match.end()
        position = start
        quoted = False
        while depth and position < len(expression):
            char = expression[position]
            if quoted:
                if char == "\\":
                    position += 1
                elif char == '"':
                    quoted = False
            elif char == '"':
                quoted = True
            elif char == "(":
                depth += 1
            elif char == ")":
                depth -= 1
            position += 1
        calls.append((match.group(1), expression[start:position - 1]))
        index = position
    return calls


def _arg(expression: str, constants: dict[str, str]) -> dict[str, Any]:
    calls = _calls(expression.strip())
    head = calls[0]
    if head[0] not in {"required", "optional"} or not expression.strip().startswith("ArgSpec."):
        raise ValueError(f"unexpected ArgSpec expression {expression!r}")
    name_expression, type_expression = (part.strip() for part in _split_top(head[1]))
    name = json.loads(name_expression) if name_expression.startswith('"') else json.loads(constants[name_expression])
    arg: dict[str, Any] = {"name": name, "type": type_expression.split(".")[-1], "required": head[0] == "required",
                           "values": [], "min": None, "max": None, "hint": None}
    for method, inside in calls[1:]:
        if method == "oneOf":
            arg["values"] = _list_values(inside, constants)
        elif method == "range":
            low, high = (_number(part, constants) for part in _split_top(inside))
            arg["min"] = None if low == _LONG_MIN else low
            arg["max"] = None if high == _LONG_MAX else high
        elif method == "maxLength":
            arg["max"] = _number(inside, constants)
        elif method == "hint":
            arg["hint"] = _string_literals(inside)
        else:
            raise ValueError(f"unknown ArgSpec method {method!r}")
    return arg


def parse_java_tools(tool_dir: Path = TOOL_DIR) -> list[dict[str, Any]]:
    """Every assistant tool of the backend, in the registry's catalogue order."""
    registry = (tool_dir / "ToolRegistry.java").read_text(encoding="utf-8")
    order = re.findall(r'"([a-z_]+)"', re.search(r"ORDER\s*=\s*List\.of\((.*?)\);", registry, re.S).group(1))
    tools = []
    for path in sorted(tool_dir.rglob("*.java")):
        source = _strip_comments(path.read_text(encoding="utf-8"))
        kind = re.search(r"class\s+\w+\s+implements\s+(ReadTool|WriteTool)\b", source)
        if not kind:
            continue
        constants = _constants(source)
        name_body = _method_body(source, r"public\s+String\s+name")
        name_expression = re.search(r"return\s+([^;]+);", name_body).group(1).strip()
        name = json.loads(name_expression) if name_expression.startswith('"') else json.loads(constants[name_expression])
        description = _string_literals(re.search(r"return\s+(.+?);\s*$", _method_body(source, r"public\s+String\s+description").strip(), re.S).group(1))
        args_body = _method_body(source, r"public\s+List<ArgSpec>\s+args")
        listed = re.search(r"return\s+List\.of\((.*)\)\s*;", args_body, re.S).group(1)
        args = [_arg(part, constants) for part in _split_top(listed) if part.strip()]
        capability = re.search(r"ToolCapability\.(\w+)", _method_body(source, r"public\s+ToolCapability\s+capability")).group(1)
        tools.append({"name": name, "description": description, "capability": capability,
                      "writes": kind.group(1) == "WriteTool", "args": args})
    tools.sort(key=lambda tool: (order.index(tool["name"]) if tool["name"] in order else len(order), tool["name"]))
    return tools


def load_fixture() -> list[dict[str, Any]]:
    return json.loads(FIXTURE.read_text(encoding="utf-8"))


# ------------------------------------------------------------------------------------------------ catalogue
def describe(arg: dict[str, Any]) -> str:
    """Port of ``ArgSpec.describe()``."""
    text = TYPE_LABELS[arg["type"]] + (", required" if arg["required"] else ", optional")
    if arg["values"]:
        text += ", one of " + "|".join(arg["values"])
    if arg["type"] == "INTEGER" and (arg["min"] is not None or arg["max"] is not None):
        text += ", " + ("" if arg["min"] is None else str(arg["min"])) + ".." + ("" if arg["max"] is None else str(arg["max"]))
    if arg["hint"]:
        text += ", " + arg["hint"]
    return text


def catalogue(tools: list[dict[str, Any]], role: str = "HR_MANAGER") -> list[dict[str, Any]]:
    """What the backend sends as ``tools`` for a user of ``role`` (``ToolRegistry.catalogueFor``)."""
    return [{"name": tool["name"], "description": tool["description"],
             "args": {arg["name"]: describe(arg) for arg in tool["args"]}, "writes": tool["writes"]}
            for tool in tools if role in CAPABILITY_ROLES[tool["capability"]]]


# ------------------------------------------------------------------------------------------------ validation
_CODE = re.compile(r"A-[1-9]\d{0,14}")
_REQUIREMENT_KEYS = {"type", "description", "mandatory", "knockout", "minimumYears", "alternatives", "proficiency"}
_REQUIREMENT_TYPES = ("SKILL", "EDUCATION", "CERTIFICATION", "EXPERIENCE", "OTHER")
_PROFICIENCIES = ("BASIC", "INTERMEDIATE", "ADVANCED", "EXPERT")


def _date_time(value: Any) -> bool:
    if not isinstance(value, str):
        return False
    try:
        if re.fullmatch(r"\d{4}-\d{2}-\d{2}", value):
            date.fromisoformat(value)
        else:
            datetime.fromisoformat(value.replace("Z", "+00:00"))
        return True
    except ValueError:
        return False


def _check(arg: dict[str, Any], value: Any) -> str | None:
    kind, low, high = arg["type"], arg["min"], arg["max"]
    if kind == "INTEGER":
        if not isinstance(value, int) or isinstance(value, bool):
            return "not an integer"
        if low is not None and value < low or high is not None and value > high:
            return "out of range"
    elif kind == "NUMBER":
        if not isinstance(value, (int, float)) or isinstance(value, bool):
            return "not a number"
        if low is not None and value < low or high is not None and value > high:
            return "out of range"
    elif kind == "STRING":
        if not isinstance(value, str) or not value.strip():
            return "not a non-blank string"
        if len(value.strip()) > (high or 2000):
            return "too long"
    elif kind == "BOOLEAN":
        if not isinstance(value, bool):
            return "not a boolean"
    elif kind == "ENUM":
        if value not in arg["values"]:
            return f"{value!r} is not one of {arg['values']}"
    elif kind == "DATE_TIME":
        if not _date_time(value):
            return "not an ISO-8601 date or date-time"
    elif kind == "APPLICATION_CODE":
        if not isinstance(value, str) or not _CODE.fullmatch(value):
            return "not an application code like A-3014"
    elif kind == "APPLICATION_CODES":
        if not isinstance(value, list) or not value or not all(isinstance(item, str) and _CODE.fullmatch(item) for item in value):
            return "not a list of application codes"
        if len(set(value)) != len(value) or len(value) > (high or 20):
            return "duplicate or too many codes"
    elif kind == "STRING_LIST":
        if not isinstance(value, list) or not all(isinstance(item, str) and item.strip() for item in value):
            return "not a list of strings"
        if len(value) > (high or 20):
            return "too many items"
    elif kind == "REQUIREMENTS":
        if not isinstance(value, list) or len(value) > 100:
            return "not a list of at most 100 requirements"
        for item in value:
            if not isinstance(item, dict) or set(item) - _REQUIREMENT_KEYS:
                return f"requirement with unknown keys {sorted(set(item) - _REQUIREMENT_KEYS) if isinstance(item, dict) else item}"
            if item.get("type") is not None and item["type"] not in _REQUIREMENT_TYPES:
                return f"requirement type {item['type']!r}"
            if not isinstance(item.get("description"), str) or not item["description"].strip() or len(item["description"]) > 500:
                return "requirement description"
            for flag in ("mandatory", "knockout"):
                if item.get(flag) is not None and not isinstance(item[flag], bool):
                    return f"requirement {flag}"
            if item.get("knockout") and item.get("mandatory") is False:
                return "knockout requirement that is not mandatory"
            years = item.get("minimumYears")
            if years is not None and (not isinstance(years, int) or isinstance(years, bool) or not 0 <= years <= 50):
                return "requirement minimumYears"
            if not isinstance(item.get("alternatives", []), list):
                return "requirement alternatives"
            if item.get("proficiency") is not None and item["proficiency"] not in _PROFICIENCIES:
                return f"requirement proficiency {item['proficiency']!r}"
    return None


def validate(tools: list[dict[str, Any]], name: str, args: Any) -> str | None:
    """Why ``args`` would not be accepted unchanged by the backend for tool ``name`` (None when they would)."""
    tool = next((tool for tool in tools if tool["name"] == name), None)
    if tool is None:
        return f"unknown tool {name!r}"
    if not isinstance(args, dict):
        return "args is not an object"
    specs = {arg["name"]: arg for arg in tool["args"]}
    unknown = sorted(set(args) - set(specs))
    if unknown:
        return f"unknown args {unknown}"
    for arg in tool["args"]:
        if arg["name"] not in args or args[arg["name"]] is None:
            if arg["required"]:
                return f"missing required arg {arg['name']!r}"
            continue
        problem = _check(arg, args[arg["name"]])
        if problem:
            return f"{name}.{arg['name']}: {problem}"
    if name == "decide_application" and args.get("decision") == "REJECT" and not args.get("reason"):
        return "decide_application: REJECT without a reason"  # DecideApplicationTool.prepare refuses it
    return None
