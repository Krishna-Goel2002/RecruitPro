"""The per-user tool catalogue sent by the backend: arg-schema parsing, argument building and validation.

An argument schema is either a short string, ``"integer, required"`` / ``"string, optional, one of A|B|C"``,
or an object ``{"type": "integer", "required": true, "enum": [...]}``. The planner only ever uses tools that
are in the catalogue, and only with arguments that type-check against it.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any

from app.models.assistant import ToolSpec

_TYPES = ("integer", "number", "string", "boolean", "array", "object")
MAX_STRING_ARG = 4000
MAX_ARRAY_ARG = 50


@dataclass(frozen=True)
class ArgSpec:
    name: str
    type: str
    required: bool
    enum: tuple[str, ...] = ()


def parse_arg_spec(name: str, raw: Any) -> ArgSpec:
    if isinstance(raw, dict):
        kind = str(raw.get("type", "string")).lower()
        kind = next((t for t in _TYPES if kind.startswith(t[:3])), "string")
        enum = tuple(str(item) for item in raw.get("enum", []) if isinstance(item, (str, int)))
        return ArgSpec(name, kind, bool(raw.get("required", False)), enum)
    text = str(raw or "")
    lowered = text.lower()
    kind = next((t for t in _TYPES if re.search(rf"\b{t[:3]}", lowered.split(",")[0])), "string")
    if re.search(r"\b(?:int|integer|long|id)\b", lowered.split(",")[0]):
        kind = "integer"
    if lowered.startswith(("list", "array")) or "array" in lowered.split(",")[0]:
        kind = "array"
    required = bool(re.search(r"\brequired\b", lowered)) and not re.search(r"\boptional\b", lowered)
    enum_match = re.search(r"(?:one of|enum)\s*:?\s*\(?([A-Za-z0-9_|, ]+?)\)?\s*(?:$|;)", text) or \
        re.search(r"\(([A-Z0-9_]+(?:\s*\|\s*[A-Z0-9_]+)+)\)", text)
    enum = tuple(part.strip() for part in re.split(r"[|,]", enum_match.group(1)) if part.strip()) if enum_match else ()
    if len(enum) == 1:
        enum = ()
    return ArgSpec(name, kind, required, enum)


class Catalogue:
    """The tools this user may use in this conversation."""

    def __init__(self, tools: list[ToolSpec]) -> None:
        self.tools = {tool.name: tool for tool in tools}
        self.specs = {tool.name: [parse_arg_spec(name, raw) for name, raw in tool.args.items()] for tool in tools}

    def has(self, name: str) -> bool:
        return name in self.tools

    def writes(self, name: str) -> bool:
        return name in self.tools and self.tools[name].writes

    def reads(self, name: str) -> bool:
        return name in self.tools and not self.tools[name].writes

    def arg_names(self, name: str) -> list[str]:
        return [spec.name for spec in self.specs.get(name, [])]

    def build_args(self, name: str, values: dict[str, Any]) -> tuple[dict[str, Any], list[ArgSpec]]:
        """Arguments for ``name`` from the planner's slot values; returns (args, missing required args).

        Slot values are matched to the catalogue's argument names by meaning (``jobId``/``job_id``,
        ``applicationCode``/``code``/``applicationId`` ...), then coerced to the declared type.
        """
        args: dict[str, Any] = {}
        missing: list[ArgSpec] = []
        for spec in self.specs.get(name, []):
            value = _value_for(spec, values)
            coerced = coerce(spec, value) if value is not None else None
            if coerced is None:
                if spec.required:
                    missing.append(spec)
                continue
            args[spec.name] = coerced
        return args, missing

    def validate_args(self, name: str, args: Any) -> str | None:
        """Why ``args`` do not fit the catalogue schema of ``name`` (None when they do)."""
        if not isinstance(args, dict):
            return "args must be an object"
        specs = {spec.name: spec for spec in self.specs.get(name, [])}
        for key, value in args.items():
            spec = specs.get(key)
            if spec is None:
                return f"unknown argument {key!r}"
            if coerce(spec, value) is None or not _exact_type(spec, value):
                return f"argument {key!r} is not a valid {spec.type}"
        for spec in specs.values():
            if spec.required and spec.name not in args:
                return f"missing required argument {spec.name!r}"
        return None


def _exact_type(spec: ArgSpec, value: Any) -> bool:
    checks = {
        "integer": lambda v: isinstance(v, int) and not isinstance(v, bool),
        "number": lambda v: isinstance(v, (int, float)) and not isinstance(v, bool),
        "string": lambda v: isinstance(v, str),
        "boolean": lambda v: isinstance(v, bool),
        "array": lambda v: isinstance(v, list),
        "object": lambda v: isinstance(v, dict),
    }
    return checks[spec.type](value)


def coerce(spec: ArgSpec, value: Any) -> Any:
    """``value`` converted to the declared type (None when impossible or out of bounds)."""
    if value is None:
        return None
    if spec.type == "integer":
        if isinstance(value, bool):
            return None
        if isinstance(value, int):
            return value if -2**53 <= value <= 2**53 else None
        if isinstance(value, str) and re.fullmatch(r"-?\d{1,15}", value.strip()):
            return int(value.strip())
        return None
    if spec.type == "number":
        if isinstance(value, bool):
            return None
        if isinstance(value, (int, float)):
            return value
        try:
            return float(str(value))
        except ValueError:
            return None
    if spec.type == "boolean":
        return value if isinstance(value, bool) else None
    if spec.type == "array":
        if not isinstance(value, list) or len(value) > MAX_ARRAY_ARG:
            return None
        return value
    if spec.type == "object":
        return value if isinstance(value, dict) else None
    if isinstance(value, (dict, list)) or isinstance(value, bool):
        return None
    text = str(value).strip()
    if not text or len(text) > MAX_STRING_ARG:
        return None
    if spec.enum:
        return match_enum(text, spec.enum)
    return text


def match_enum(value: str, options: tuple[str, ...]) -> str | None:
    """The enum entry ``value`` means: exact (case-insensitive), then a shared stem ("SHORTLIST" ~ "SHORTLISTED")."""
    upper = value.strip().upper().replace(" ", "_").replace("-", "_")
    for option in options:
        if option.upper() == upper:
            return option
    for option in options:
        stem = min(len(option), len(upper), 5)
        if stem >= 4 and option.upper()[:stem] == upper[:stem]:
            return option
    return None


# Slot name -> argument names that mean the same thing (lower-case, without underscores).
_ALIASES: dict[str, tuple[str, ...]] = {
    "jobId": ("jobid", "job", "postingid", "id"),
    "applicationCode": ("applicationcode", "code", "applicantcode", "candidatecode"),
    "applicationId": ("applicationid", "applicantid", "application"),
    "applicationCodes": ("applicationcodes", "codes", "applicantcodes", "candidatecodes"),
    "applicationIds": ("applicationids", "applicantids", "ids"),
    "topic": ("topic", "subject", "term", "question"),
    "query": ("query", "search", "q", "title", "jobtitle", "text"),
    "status": ("status", "targetstatus", "newstatus", "state"),
    "decision": ("decision", "outcome", "action", "transition"),
    "reason": ("reason", "rejectionreason", "comment", "note", "notes"),
    "range": ("range", "period", "when", "window", "scope"),
    "startsAt": ("startsat", "startat", "scheduledat", "datetime", "start", "starttime", "time", "at"),
    "durationMinutes": ("durationminutes", "duration", "minutes", "length"),
    "days": ("days", "dueindays", "deadlinedays"),
    "limit": ("limit", "top", "count", "size"),
}


def _value_for(spec: ArgSpec, values: dict[str, Any]) -> Any:
    key = spec.name.lower().replace("_", "")
    if spec.name in values:
        return values[spec.name]
    for slot, aliases in _ALIASES.items():
        if key in aliases and values.get(slot) is not None:
            if slot == "jobId" and key == "id" and values.get("applicationId") is not None and values.get("_idMeans") == "application":
                return values["applicationId"]
            return values[slot]
    return None
