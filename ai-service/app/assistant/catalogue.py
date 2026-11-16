"""The per-user tool catalogue sent by the backend: arg-schema parsing, argument building and validation.

The catalogue in the request is the contract: the planner only ever names tools that are in it, and only with the
argument names it declares, converted to the declared types. Argument schemas are strings or objects:

* the backend's ``ArgSpec.describe()`` text, ``"<type label>, required|optional[, one of A|B][, min..max][, hint]"``,
  where the type label is ``integer``, ``number``, ``string``, ``boolean``, ``ISO-8601 date-time``,
  ``application code like A-3014``, ``list of application codes``, ``string or list of strings`` or
  ``list of {type, description, ...}`` (job requirements);
* short forms such as ``"array of integer, required"``;
* objects ``{"type": "integer", "required": true, "enum": [...], "format": "date-time", "minimum": 1}``.

The planner's slot values (``jobId``, ``applicationCode``, ``startsAt``, ``days`` ...) are mapped to whatever names the
catalogue uses (``_ALIASES``); values the model sends under a synonym are renamed, and unknown arguments are dropped
(``normalise_args``).
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import date, datetime
from typing import Any

from app.assistant.text import application_codes, code_to_id
from app.models.assistant import ToolSpec

_TYPES = ("integer", "number", "string", "boolean", "array", "object")
MAX_STRING_ARG = 4000
MAX_ARRAY_ARG = 50
MAX_CODES = 20
REQUIREMENT_KEYS = ("type", "description", "mandatory", "knockout", "minimumYears", "alternatives", "proficiency")

# Formats (beyond the JSON type) the planner must honour.
APPLICATION_CODE = "application_code"
APPLICATION_CODES = "application_codes"
DATE_TIME = "date_time"
STRING_LIST = "string_list"
REQUIREMENTS = "requirements"


@dataclass(frozen=True)
class ArgSpec:
    name: str
    type: str
    required: bool
    enum: tuple[str, ...] = ()
    format: str = ""
    minimum: int | None = None
    maximum: int | None = None


def _kind_of(label: str) -> tuple[str, str]:
    """(JSON type, format) of a type label."""
    lowered = label.strip().lower()
    if "application code" in lowered:
        return ("array", APPLICATION_CODES) if re.search(r"\blist\b|\barray\b|codes\b", lowered) else ("string", APPLICATION_CODE)
    if re.search(r"date-?time|iso-?8601|timestamp", lowered):
        return "string", DATE_TIME
    if re.search(r"\bstring or (?:a )?list of strings\b", lowered):
        return "array", STRING_LIST
    if lowered.startswith(("list of {", "array of {")) or "minimumyears" in lowered.replace(" ", ""):
        return "array", REQUIREMENTS
    if lowered.startswith(("list", "array")) or "array" in lowered:
        return "array", ""
    if re.search(r"\b(?:int|integer|long|id)\b", lowered):
        return "integer", ""
    return next((t for t in _TYPES if re.search(rf"\b{t[:3]}", lowered)), "string"), ""


def parse_arg_spec(name: str, raw: Any) -> ArgSpec:
    if isinstance(raw, dict):
        kind = str(raw.get("type", "string")).lower()
        kind = next((t for t in _TYPES if kind.startswith(t[:3])), "string")
        enum = tuple(str(item) for item in raw.get("enum", []) if isinstance(item, (str, int)))
        fmt = str(raw.get("format") or "").lower().replace("-", "_")
        fmt = {"date_time": DATE_TIME, "datetime": DATE_TIME, "date": DATE_TIME}.get(fmt, fmt)
        bounds = [raw.get(key) if isinstance(raw.get(key), int) and not isinstance(raw.get(key), bool) else None
                  for key in ("minimum", "maximum")]
        return ArgSpec(name, kind, bool(raw.get("required", False)), enum, fmt, bounds[0], bounds[1])
    text = str(raw or "")
    head = re.match(r"\s*(.*?),\s*(required|optional)\b", text, re.I | re.S)
    label = head.group(1) if head else text.split(",")[0]
    kind, fmt = _kind_of(label)
    if not fmt and kind == "string" and re.search(r"\bdate-?time\b", text, re.I):
        fmt = DATE_TIME  # "string, required (local ISO date-time)"
    if head:
        required = head.group(2).lower() == "required"
    else:
        required = bool(re.search(r"\brequired\b", text, re.I)) and not re.search(r"\boptional\b", text, re.I)
    rest = text[head.end():] if head else text
    enum_match = re.search(r"(?:one of|enum)\s*:?\s*\(?\s*([A-Za-z0-9_]+(?:\s*\|\s*[A-Za-z0-9_]+)+)", rest) or \
        re.search(r"(?:one of|enum)\s*:?\s*\(?([A-Za-z0-9_]+(?:\s*,\s*[A-Za-z0-9_]+)+)\)?\s*$", rest) or \
        re.search(r"\(([A-Z0-9_]+(?:\s*\|\s*[A-Z0-9_]+)+)\)", rest)
    enum = tuple(part.strip() for part in re.split(r"[|,]", enum_match.group(1)) if part.strip()) if enum_match else ()
    if len(enum) == 1:
        enum = ()
    minimum = maximum = None
    if kind == "integer":
        bounds = re.search(r"(?:^|,)\s*(-?\d+)?\s*\.\.\s*(-?\d+)?\s*(?:,|$)", rest)
        if bounds and (bounds.group(1) or bounds.group(2)):
            minimum = int(bounds.group(1)) if bounds.group(1) else None
            maximum = int(bounds.group(2)) if bounds.group(2) else None
    return ArgSpec(name, kind, required, enum, fmt, minimum, maximum)


# Slot name -> argument names that mean the same thing (lower-case, without underscores). The first group whose
# aliases contain a catalogue argument's name decides which slot fills it.
_ALIASES: dict[str, tuple[str, ...]] = {
    "jobId": ("jobid", "job", "postingid", "id"),
    "applicationCode": ("applicationcode", "code", "applicantcode", "candidatecode"),
    "applicationId": ("applicationid", "applicantid", "application", "applicant", "candidate", "candidateid"),
    "applicationCodes": ("applicationcodes", "codes", "applicantcodes", "candidatecodes"),
    "applicationIds": ("applicationids", "applicantids", "ids", "applicants", "candidates", "candidateids"),
    "topic": ("topic", "subject", "term", "question"),
    "query": ("query", "search", "q", "title", "jobtitle", "text", "keyword", "keywords"),
    "status": ("status", "targetstatus", "newstatus", "state", "jobstatus"),
    "decision": ("decision", "outcome", "action", "transition"),
    "reason": ("reason", "rejectionreason", "comment", "note", "notes"),
    "range": ("range", "period", "when", "window", "scope"),
    "upcomingOnly": ("upcomingonly", "upcoming", "onlyupcoming", "futureonly", "future"),
    "startsAt": ("startsat", "startat", "scheduledat", "scheduledfor", "datetime", "start", "starttime", "time", "at",
                 "interviewat", "slot"),
    "durationMinutes": ("durationminutes", "duration", "minutes", "length", "durationmins"),
    "days": ("days", "dueindays", "deadlinedays", "duedays"),
    "dueAt": ("dueat", "duedate", "due", "dueby", "deadline", "dueon"),
    "workSampleId": ("worksampleid", "sampleid", "worksample", "assignmentid"),
    "interviewer": ("interviewer", "interviewername", "intervieweremail", "panelist", "panellist", "with"),
    "interviewerUserId": ("intervieweruserid", "interviewerid"),
    "location": ("location", "venue", "place", "room", "where", "meetinglocation"),
    "limit": ("limit", "top", "count", "size"),
}
# Slots that carry the same thing in another form (code <-> numeric id; a date <-> days from today is not reversible).
_SIBLINGS = {"applicationCode": "applicationId", "applicationId": "applicationCode",
             "applicationCodes": "applicationIds", "applicationIds": "applicationCodes"}
# Enum words the planner (or a model) uses -> the value they mean, applied only when that value is offered.
_ENUM_SYNONYMS: dict[str, tuple[str, ...]] = {
    "INTERVIEW": ("MOVE_TO_INTERVIEW", "INTERVIEWING"), "MOVE_TO_INTERVIEW": ("INTERVIEW", "INTERVIEWING"),
    "HOLD": ("ON_HOLD",), "PAUSE": ("ON_HOLD",), "PAUSED": ("ON_HOLD",), "OPEN": ("PUBLISHED",), "LIVE": ("PUBLISHED",),
    "HIRE": ("SELECT",), "OFFER": ("SELECT",),
    # help topics (offline keys -> backend HelpTopics)
    "COVERAGE": ("VERIFIED_COVERAGE",), "VERIFIED_VS_CLAIMED": ("VERIFIED_COVERAGE",),
    "EVIDENCE_LADDER": ("EVIDENCE_TIERS",), "EVIDENCE": ("EVIDENCE_TIERS",), "TIERS": ("EVIDENCE_TIERS",),
    "GATES": ("KNOCKOUTS",), "KNOCKOUT": ("KNOCKOUTS",), "SHADOW_MODE": ("VERIFICATION",),
    "INTERVIEWS": ("INTERVIEW_KITS",), "INTERVIEW_KIT": ("INTERVIEW_KITS",), "SCORECARDS": ("INTERVIEW_KITS",),
    "FAIRNESS": ("FAIRNESS_REPORT",), "ADVERSE_IMPACT": ("FAIRNESS_REPORT",), "BLIND": ("BLIND_REVIEW",),
}


def _key(name: str) -> str:
    return name.lower().replace("_", "").replace("-", "")


def slot_of(arg_name: str) -> str | None:
    """The planner slot an argument name means (None when unknown)."""
    key = _key(arg_name)
    for slot, aliases in _ALIASES.items():
        if key == slot.lower() or key in aliases:
            return slot
    return None


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

    def spec(self, tool: str, arg: str) -> ArgSpec | None:
        return next((spec for spec in self.specs.get(tool, []) if spec.name == arg), None)

    def arg_for(self, tool: str, slot: str) -> ArgSpec | None:
        """The argument of ``tool`` that the planner slot ``slot`` fills (by name or alias), if any."""
        for spec in self.specs.get(tool, []):
            if spec.name == slot or slot_of(spec.name) == slot:
                return spec
        return None

    def build_args(self, name: str, values: dict[str, Any]) -> tuple[dict[str, Any], list[ArgSpec]]:
        """Arguments for ``name`` from the planner's slot values; returns (args, missing required args).

        Slot values are matched to the catalogue's argument names by meaning (``jobId``/``job_id``,
        ``applicationCode``/``code``/``applicationId`` ...), then converted to the declared type and format.
        Only declared arguments are ever returned.
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

    def normalise_args(self, name: str, args: Any) -> tuple[dict[str, Any], str | None]:
        """``args`` renamed to the catalogue's argument names and converted; unknown arguments are dropped.

        Returns (args, problem): ``problem`` names a value that cannot be converted or a missing required argument.
        """
        if not isinstance(args, dict):
            return {}, "args must be an object"
        specs = {spec.name: spec for spec in self.specs.get(name, [])}
        chosen: dict[str, tuple[str, Any]] = {}
        for key, value in args.items():
            if not isinstance(key, str):
                continue
            target = key if key in specs else self._target(name, key)
            if target is None:
                continue  # unknown argument: dropped
            if target in chosen and chosen[target][0] == target:
                continue  # the exact name wins over a synonym
            chosen[target] = (key, value)
        result: dict[str, Any] = {}
        for target, (key, value) in chosen.items():
            if value is None or value == "" or value == []:
                continue
            spec = specs[target]
            coerced = coerce(spec, _convert(spec, slot_of(key), value))
            if coerced is None:
                return {}, f"argument {key!r} is not a valid {spec.format or spec.type}"
            result[target] = coerced
        for spec in specs.values():
            if spec.required and spec.name not in result:
                return {}, f"missing required argument {spec.name!r}"
        return {spec: result[spec] for spec in specs if spec in result}, None

    def validate_args(self, name: str, args: Any) -> str | None:
        """Why ``args`` do not fit the catalogue schema of ``name`` exactly (None when they do)."""
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

    def _target(self, tool: str, key: str) -> str | None:
        slot = slot_of(key)
        if slot is None:
            return None
        spec = self.arg_for(tool, slot) or (self.arg_for(tool, _SIBLINGS[slot]) if slot in _SIBLINGS else None)
        return spec.name if spec else None


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


def _convert(spec: ArgSpec, slot: str | None, value: Any) -> Any:
    """A value of ``slot`` in the form ``spec`` wants (an application code for a numeric id argument and back)."""
    if spec.type == "integer" and isinstance(value, str) and application_codes(value) and \
            (slot in {"applicationCode", "applicationId"} or slot_of(spec.name) == "applicationId"):
        return code_to_id(application_codes(value)[0])
    if spec.type == "array" and spec.format != APPLICATION_CODES and isinstance(value, list) and \
            slot_of(spec.name) == "applicationIds":
        ids = [code_to_id(application_codes(item)[0]) if isinstance(item, str) and application_codes(item) else item
               for item in value]
        return ids
    return value


def _value_for(spec: ArgSpec, values: dict[str, Any]) -> Any:
    if values.get(spec.name) is not None:
        return _convert(spec, spec.name, values[spec.name])
    key = _key(spec.name)
    for slot, aliases in _ALIASES.items():
        if key != slot.lower() and key not in aliases:
            continue
        if slot == "jobId" and key == "id" and values.get("applicationId") is not None and values.get("_idMeans") == "application":
            return values["applicationId"]
        if values.get(slot) is not None:
            return _convert(spec, slot, values[slot])
        sibling = _SIBLINGS.get(slot)
        if sibling and values.get(sibling) is not None:
            return _convert(spec, sibling, values[sibling])
        return None
    return None


def _code(value: Any) -> str | None:
    if isinstance(value, bool):
        return None
    if isinstance(value, int):
        return f"A-{value}" if 1 <= value <= 2**53 else None
    if isinstance(value, str):
        codes = application_codes(value.strip())
        return codes[0] if len(codes) == 1 else None
    return None


def _date_time(value: Any) -> str | None:
    if isinstance(value, datetime):
        return value.isoformat(timespec="minutes")
    if isinstance(value, date):
        return value.isoformat()
    if not isinstance(value, str):
        return None
    text = value.strip()
    try:
        if re.fullmatch(r"\d{4}-\d{2}-\d{2}", text):
            date.fromisoformat(text)
        else:
            datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError:
        return None
    return text


def _requirements(value: Any) -> list[dict[str, Any]] | None:
    if not isinstance(value, list) or len(value) > 100:
        return None
    result = []
    for item in value:
        if not isinstance(item, dict) or not isinstance(item.get("description"), str) or not item["description"].strip():
            return None
        result.append({key: item[key] for key in REQUIREMENT_KEYS if key in item})
    return result


def _in_range(spec: ArgSpec, number: int | float) -> bool:
    return (spec.minimum is None or number >= spec.minimum) and (spec.maximum is None or number <= spec.maximum)


def coerce(spec: ArgSpec, value: Any) -> Any:
    """``value`` converted to the declared type and format (None when impossible or out of bounds)."""
    if value is None:
        return None
    if spec.type == "integer":
        if isinstance(value, bool):
            return None
        if isinstance(value, float) and value.is_integer():
            value = int(value)
        if isinstance(value, int):
            return value if -2**53 <= value <= 2**53 and _in_range(spec, value) else None
        if isinstance(value, str) and re.fullmatch(r"-?\d{1,15}", value.strip()):
            number = int(value.strip())
            return number if _in_range(spec, number) else None
        return None
    if spec.type == "number":
        if isinstance(value, bool):
            return None
        if not isinstance(value, (int, float)):
            try:
                value = float(str(value).replace(",", ""))
            except ValueError:
                return None
        return value if _in_range(spec, value) else None
    if spec.type == "boolean":
        if isinstance(value, bool):
            return value
        if isinstance(value, str) and value.strip().lower() in {"true", "yes", "false", "no"}:
            return value.strip().lower() in {"true", "yes"}
        return None
    if spec.type == "array":
        if spec.format == APPLICATION_CODES:
            items = value if isinstance(value, list) else re.split(r"[,;\s]+", value) if isinstance(value, str) else None
            if items is None:
                return None
            codes: list[str] = []
            for item in items:
                if item in ("", None):
                    continue
                code = _code(item)
                if code is None:
                    return None
                if code not in codes:
                    codes.append(code)
            return codes if codes and len(codes) <= (spec.maximum or MAX_CODES) else None
        if spec.format == STRING_LIST:
            items = value if isinstance(value, list) else [value]
            if any(isinstance(item, (dict, list)) for item in items):
                return None
            strings = [str(item).strip() for item in items if item is not None and str(item).strip()]
            return strings if strings and len(strings) <= (spec.maximum or MAX_ARRAY_ARG) else None
        if spec.format == REQUIREMENTS:
            return _requirements(value)
        if not isinstance(value, list) or len(value) > MAX_ARRAY_ARG:
            return None
        return value
    if spec.type == "object":
        return value if isinstance(value, dict) else None
    if spec.format == APPLICATION_CODE:
        return _code(value)
    if spec.format == DATE_TIME:
        return _date_time(value)
    if isinstance(value, (dict, list)) or isinstance(value, bool):
        return None
    text = str(value).strip()
    if not text or len(text) > MAX_STRING_ARG:
        return None
    if spec.enum:
        return match_enum(text, spec.enum)
    return text


def match_enum(value: str, options: tuple[str, ...]) -> str | None:
    """The enum entry ``value`` means: exact (case-insensitive), a known synonym ("INTERVIEW" -> "MOVE_TO_INTERVIEW"),
    the only entry ending in ``_<value>`` ("HOLD" -> "ON_HOLD"), then a shared stem ("SHORTLIST" ~ "SHORTLISTED")."""
    upper = value.strip().upper().replace(" ", "_").replace("-", "_")
    by_upper = {option.upper(): option for option in options}
    if upper in by_upper:
        return by_upper[upper]
    for synonym in _ENUM_SYNONYMS.get(upper, ()):
        if synonym in by_upper:
            return by_upper[synonym]
    suffix = [option for option in options if option.upper().endswith("_" + upper)]
    if len(suffix) == 1:
        return suffix[0]
    for option in options:
        stem = min(len(option), len(upper), 5)
        if stem >= 4 and option.upper()[:stem] == upper[:stem]:
            return option
    return None
