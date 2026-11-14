"""Role segmentation and the experience timeline.

Experience sections are cut into roles. A role starts at an *anchor*: a line holding a date range
(or, when the section has no date ranges at all, a short line stating a duration such as
"Python developer, Acme, 2 years"). The title/employer comes from the anchor's own text, or is
borrowed from the one or two header-like lines right above a bare date line, or taken from the
line right after it (date-first layouts). Bullets and body lines belong to the open role.

Totals merge overlapping intervals so concurrent roles are not double counted; undated roles
with a stated duration are added on top (their overlap is unknowable). A skill mentioned in a
role block accrues that role's months.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field, replace
from datetime import date
from typing import Callable

from app.pipeline.dates import DateRange, find_date_ranges, find_duration_months, merged_months
from app.pipeline.text import Line

_TITLE_WORDS = (
    "engineer", "developer", "programmer", "architect", "analyst", "scientist", "manager", "lead", "head", "director",
    "executive", "officer", "associate", "assistant", "consultant", "specialist", "coordinator", "administrator",
    "intern", "trainee", "apprentice", "accountant", "auditor", "nurse", "teacher", "lecturer", "professor", "tutor",
    "designer", "writer", "editor", "recruiter", "representative", "agent", "advisor", "adviser", "counsel", "lawyer",
    "advocate", "attorney", "paralegal", "technician", "therapist", "pharmacist", "physician", "doctor", "clerk",
    "cashier", "supervisor", "operator", "planner", "buyer", "strategist", "marketer", "founder", "co-founder",
    "partner", "president", "vp", "cto", "ceo", "cfo", "coo", "sde", "sre", "tester", "devops", "fellow",
    "researcher", "instructor", "coach", "counsellor", "counselor", "controller", "secretary", "receptionist",
    "salesperson", "owner", "generalist", "bdm", "bde", "educator", "principal", "dietitian", "physiotherapist",
    "radiographer", "phlebotomist", "storekeeper", "merchandiser", "chemist", "biologist", "statistician",
)
TITLE_RE = re.compile(r"\b(?:" + "|".join(re.escape(word) for word in _TITLE_WORDS) + r")s?\b", re.I)
_EMPLOYER_RE = re.compile(
    r"\b(?:ltd|limited|inc|llc|llp|pvt|private|corp|corporation|company|co|technologies|technology|solutions|services|"
    r"systems|bank|hospital|clinic|school|university|college|institute|group|labs|consulting|consultancy|partners|"
    r"associates|enterprises|industries|foundation|agency|studio|studios|ventures|health|healthcare|pharma|"
    r"global|international|academy)\b\.?",
    re.I,
)
_LOCATIONS = {
    "remote", "hybrid", "onsite", "on-site", "india", "usa", "us", "uk", "uae", "bengaluru", "bangalore", "mumbai",
    "delhi", "new delhi", "hyderabad", "chennai", "pune", "kolkata", "bhubaneswar", "noida", "gurgaon", "gurugram",
    "ahmedabad", "jaipur", "kochi", "cuttack", "lucknow", "indore", "chandigarh", "london", "singapore", "dubai",
    "new york", "san francisco", "seattle", "toronto", "berlin", "sydney", "odisha", "karnataka", "maharashtra",
    "tamil nadu", "telangana", "west bengal",
}
_BODY_VERB_RE = re.compile(
    r"^(?:\w+ed|built|led|ran|wrote|drove|won|made|taught|grew|cut|set|took|gave|held|kept|sold|met|"
    r"manage[sd]?|develop(?:s|ed)?|design(?:s|ed)?|handle[sd]?|own(?:s|ed)?|work(?:s|ed)?|responsible|"
    r"implement(?:s|ed)?|create[sd]?|deliver(?:s|ed)?|support(?:s|ed)?|maintain(?:s|ed)?|prepare[sd]?)\b",
    re.I,
)
_SEPARATORS_RE = re.compile(r"\s*(?:\||,|@|–|—|\s-\s|\bat\b|\(|\))\s*", re.I)
_ANCHOR_MAX_WORDS = 16


def has_title_word(text: str) -> bool:
    return bool(TITLE_RE.search(text))


@dataclass
class Role:
    anchor: Line  # the line holding the date range or stated duration
    anchor_span: tuple[int, int]  # that range/duration inside ``anchor.text``
    header_in_anchor: bool  # title/employer written on the anchor line itself
    header_lines: list[Line]  # otherwise: borrowed lines above, or the line after a bare date line
    date_range: DateRange | None
    stated_months: int | None
    lines: list[Line] = field(default_factory=list)  # every line of the role block, in order

    def header(self, render: Callable[[Line], str] = lambda line: line.text) -> str | None:
        """Title/employer text, rendered line by line through ``render`` (e.g. de-identification)."""
        if self.header_in_anchor:
            rendered = render(self.anchor)
            marker = self.anchor.text[self.anchor_span[0]:self.anchor_span[1]]
            return _clean(rendered.replace(marker, " | ", 1)) or None
        if self.header_lines:
            return " | ".join(render(line) for line in self.header_lines)
        return None

    @property
    def months(self) -> int | None:
        return self.date_range.months if self.date_range else self.stated_months

    @property
    def start_date(self) -> str | None:
        return self.date_range.start.iso() if self.date_range else None

    @property
    def end_date(self) -> str | None:
        return self.date_range.end.iso() if self.date_range and not self.date_range.current else None

    @property
    def current(self) -> bool:
        return bool(self.date_range and self.date_range.current)


@dataclass(frozen=True)
class TimelineRole:
    title: str | None
    employer: str | None
    start_date: str | None
    end_date: str | None
    current: bool
    months: int | None
    skills: tuple[str, ...]


@dataclass(frozen=True)
class Timeline:
    total_months: int
    roles: tuple[TimelineRole, ...]
    skill_months: dict[str, int]


def _words(text: str) -> list[str]:
    return text.split()


def _is_header_like(line: Line) -> bool:
    text = line.text
    if line.is_bullet or len(_words(text)) > 14 or re.match(r"^[A-Za-z ]{2,25}:\s", text):
        return False
    if text.endswith(".") and not re.search(r"\b(?:ltd|inc|co|corp|pvt|llc)\.$", text, re.I):
        return False
    return not _BODY_VERB_RE.match(text)


def _looks_like_role_header(line: Line) -> bool:
    return _is_header_like(line) and (has_title_word(line.text) or bool(_EMPLOYER_RE.search(line.text))
                                      or bool(re.search(r"[|,@–—]| at ", line.text)))


def _has_words(text: str) -> bool:
    return len(re.findall(r"[A-Za-z]{2,}", re.sub(r"\b(?:present|current|now|till|date|to)\b", "", text, flags=re.I))) > 0


def _clean(text: str) -> str:
    return re.sub(r"^[\s|,@\-–—:()]+|[\s|,@\-–—:()]+$", "", " ".join(text.split()))


def split_title_employer(header: str | None) -> tuple[str | None, str | None]:
    if not header:
        return None, None
    parts = [_clean(part) for part in _SEPARATORS_RE.split(header)]
    parts = [part for part in parts if part and part.lower() not in _LOCATIONS and re.search(r"[A-Za-z]{2,}", part)]
    if not parts:
        return None, None
    title = next((part for part in parts if has_title_word(part) and not _EMPLOYER_RE.search(part)), None)
    title = title or next((part for part in parts if has_title_word(part)), None)
    if title is None:
        employer_part = next((part for part in parts if _EMPLOYER_RE.search(part)), None)
        if employer_part is not None and len(parts) > 1:
            return next(part for part in parts if part is not employer_part), employer_part
        return parts[0], (parts[1] if len(parts) > 1 else None)
    employer = next((part for part in parts if part is not title), None)
    return title, employer


def _anchor(line: Line, today: date, dated_mode: bool) -> tuple[DateRange | None, int | None, tuple[int, int]] | None:
    if len(_words(line.text)) > _ANCHOR_MAX_WORDS or (line.is_bullet and len(_words(line.text)) > 8):
        return None
    if dated_mode:
        ranges = find_date_ranges(line.text, today)
        return (ranges[0], None, ranges[0].span) if ranges else None
    if not _is_header_like(line):
        return None
    duration = find_duration_months(line.text)
    return (None, duration[0], duration[1]) if duration else None


def join_wrapped_dates(lines: list[Line], today: date) -> list[Line]:
    """Re-join a date range that line wrapping split ("... | Jun 2024 -" + "Present")."""
    joined: list[Line] = []
    for line in lines:
        previous = joined[-1] if joined else None
        if previous is not None and not line.is_bullet and len(_words(line.text)) <= 4:
            combined = f"{previous.text} {line.text}"
            if any(r.span[0] < len(previous.text) < r.span[1] for r in find_date_ranges(combined, today)):
                joined[-1] = replace(previous, text=combined, end=line.end)
                continue
        joined.append(line)
    return joined


def segment_roles(lines: list[Line], today: date) -> tuple[list[Role], list[Line]]:
    """Cut experience-section lines into roles; returns (roles, lines before the first role)."""
    lines = join_wrapped_dates(lines, today)
    dated_mode = any(_anchor(line, today, True) for line in lines)
    roles: list[Role] = []
    preface: list[Line] = []
    for line in lines:
        anchor = _anchor(line, today, dated_mode)
        if anchor is None:
            role = roles[-1] if roles else None
            if (role is not None and not role.header_in_anchor and not role.header_lines and len(role.lines) == 1
                    and _is_header_like(line)):
                role.header_lines.append(line)  # date-first layout: the title line follows the dates
            (role.lines if role is not None else preface).append(line)
            continue
        date_range, stated, span = anchor
        rest = _clean(line.text[:span[0]] + " | " + line.text[span[1]:])
        borrowed: list[Line] = []
        if not _has_words(rest):
            pool = roles[-1].lines if roles else preface
            while pool and len(borrowed) < 2 and _looks_like_role_header(pool[-1]) and not find_date_ranges(pool[-1].text, today):
                if borrowed and len(_words(pool[-1].text)) > 6:
                    break
                borrowed.insert(0, pool.pop())
            if not borrowed and pool and pool is preface and _is_header_like(pool[-1]):
                borrowed.append(pool.pop())
        roles.append(Role(line, span, _has_words(rest), borrowed, date_range, stated, borrowed + [line]))
    return roles, preface


def build_timeline(roles: list[Role], role_skills: list[set[str]],
                   render: Callable[[Line], str] = lambda line: line.text) -> Timeline:
    """Timeline over all roles; ``render`` controls how header text is shown (de-identified in the API)."""
    dated = [role.date_range.interval for role in roles if role.date_range]
    undated = sum(role.stated_months or 0 for role in roles if not role.date_range)
    skill_intervals: dict[str, list[tuple[int, int]]] = {}
    skill_undated: dict[str, int] = {}
    for role, skills in zip(roles, role_skills):
        for skill in skills:
            if role.date_range:
                skill_intervals.setdefault(skill, []).append(role.date_range.interval)
            elif role.stated_months:
                skill_undated[skill] = skill_undated.get(skill, 0) + role.stated_months
    skill_months = {
        skill: merged_months(skill_intervals.get(skill, [])) + skill_undated.get(skill, 0)
        for skill in sorted(set(skill_intervals) | set(skill_undated))
    }
    timeline_roles = []
    for role, skills in zip(roles, role_skills):
        title, employer = split_title_employer(role.header(render))
        timeline_roles.append(TimelineRole(title, employer, role.start_date, role.end_date, role.current, role.months,
                                           tuple(sorted(skills))))
    return Timeline(merged_months(dated) + undated, tuple(timeline_roles), skill_months)
