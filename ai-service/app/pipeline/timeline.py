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
    # Non-technical and Indian titles (surnames such as Cook, Driver, Baker, Mason, Dean or Teller are left out:
    # a title word in the header block stops it from being read as the candidate's name).
    "midwife", "paramedic", "surgeon", "dentist", "caregiver", "chef", "mechanic", "electrician", "plumber", "welder",
    "machinist", "illustrator", "photographer", "animator", "videographer", "copywriter", "journalist", "reporter",
    "producer", "translator", "interpreter", "librarian", "economist", "actuary", "underwriter", "inspector",
    "surveyor", "estimator", "draftsman", "draughtsman", "dispatcher", "telecaller", "tele-caller", "promoter",
    "proprietor", "entrepreneur", "superintendent", "warden", "headmaster", "headmistress", "registrar",
    "faculty", "pgt", "tgt", "prt", "mentor", "facilitator", "attendant", "stewardess", "hostess",
    "beautician", "stylist", "trainer", "housekeeper", "caretaker", "optometrist", "audiologist", "sonographer",
    "anaesthetist", "anesthetist", "psychologist", "psychiatrist", "veterinarian", "pharmacologist", "nutritionist",
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


_ORGANISATION_RE = re.compile(
    r"\b(?:ltd|limited|inc|llc|llp|pvt|private|corp|corporation|company|co|bank|hospital|hospitals|clinic|clinics|school|"
    r"university|college|institute|gmbh|plc|pte|sdn|bhd|ag|sa|nv|bv)\b\.?", re.I)


def has_employer_word(text: str) -> bool:
    """An unmistakable organisation word ("Pvt. Ltd.", "GmbH", "Hospital", "University"); words such as
    "Technologies" or "Services" are also section and skill words, so they do not count here."""
    return bool(_ORGANISATION_RE.search(text))


@dataclass
class Role:
    anchor: Line  # the line holding the date range or stated duration
    anchor_span: tuple[int, int]  # that range/duration inside ``anchor.text``
    header_in_anchor: bool  # title/employer written on the anchor line itself
    header_lines: list[Line]  # otherwise: borrowed lines above, or the line after a bare date line
    date_range: DateRange | None
    stated_months: int | None
    lines: list[Line] = field(default_factory=list)  # every line of the role block, in order
    section_heading: str | None = None  # heading of the section the role sits in ("Internships")

    @property
    def is_internship(self) -> bool:
        """An internship, traineeship or articleship: its title says so, or it sits under such a heading."""
        return bool(_INTERN_RE.search(self.header() or "")) or bool(
            self.section_heading and _INTERN_RE.search(self.section_heading))

    def header(self, render: Callable[[Line], str] = lambda line: line.text) -> str | None:
        """Title/employer text, rendered line by line through ``render`` (e.g. de-identification)."""
        parts: list[str] = []
        if self.header_in_anchor:
            rendered = render(self.anchor)
            marker = self.anchor.text[self.anchor_span[0]:self.anchor_span[1]]
            cleaned = _clean(_DATE_KEY_LABEL_RE.sub("", rendered.replace(marker, " | ", 1)))
            if cleaned:
                parts.append(cleaned)
        parts.extend(render(line) for line in self.header_lines)
        header = _clean(strip_role_keys(" | ".join(parts)))
        return header or None

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


# Key-value role blocks ("Organisation : Acme Pvt Ltd", "Designation : Sales Manager", "Duration : ...").
ROLE_KEY_RE = re.compile(
    r"^\s*(?:designation|position(?:\s+held)?|role|job\s+title|title|post|organi[sz]ation(?:\s+name)?|company(?:\s+name)?|"
    r"employer|firm|client|hospital|school|institution|department|work\s+location|location)\s*[:|\-–]\s*", re.I)
# A label that only introduces the dates: "Duration : May 2025 - Aug 2026", "Period: 2019 - 2021".
_DATE_KEY_RE = re.compile(r"\b(?:duration|period|tenure|dates?|from|working\s+period|time\s+period|tenure\s+period|"
                          r"worked\s+from)\b", re.I)
# "Label: content" lines are body (Environment:, Tools & Technologies:, Key skills used:), except role keys.
_LABEL_LINE_RE = re.compile(r"^[A-Za-z][A-Za-z &/'’().\-]{1,30}\s*:\s")
# Adjectives in job titles that look like past-tense verbs ("Registered Nurse", "Certified Trainer").
_TITLE_ADJECTIVES_RE = re.compile(r"^(?:registered|licensed|licenced|certified|qualified|chartered|enrolled|accredited|"
                                  r"authori[sz]ed|retained|appointed|seconded|contracted|outsourced|deputed)\b", re.I)
# Gaps are written in the work history but are not roles ("Career break - family (Jul 2025 - Apr 2026)").
_BREAK_RE = re.compile(r"\b(?:career\s+break|break|gap|sabbatical|parental\s+leave|maternity\s+leave|paternity\s+leave|"
                       r"relocation|relocated|caregiving|career\s+pause)\b", re.I)
_PROJECT_KEY_RE = re.compile(r"^\s*(?:project(?:\s+(?:name|title))?|client|team\s+size|domain)\s*[:|\-–]", re.I)
_INTERN_RE = re.compile(r"\b(?:intern|interns|internship|trainee|apprentice|apprenticeship|articled|articleship|"
                        r"industrial\s+training|summer\s+training)\b", re.I)


def _is_header_like(line: Line) -> bool:
    text = line.text
    if line.is_bullet or len(_words(text)) > 14:
        return False
    if _LABEL_LINE_RE.match(text) and not ROLE_KEY_RE.match(text):
        return False
    if text.endswith(".") and not re.search(r"\b(?:ltd|inc|co|corp|pvt|llc)\.$", text, re.I):
        return False
    if not _BODY_VERB_RE.match(text):
        return True
    if _TITLE_ADJECTIVES_RE.match(text) and has_title_word(text):
        return True  # "Registered Nurse - Apollo Hospitals"
    # "Design Engineer | Ganesh Polymers", "Support Engineer, Acme": a title that starts with a verb-like word.
    head = " ".join(_words(text)[:4])
    separator = re.search(r"\s[|–—-]\s|,", text)
    return bool(TITLE_RE.search(head) and separator and TITLE_RE.search(head).end() <= separator.start())


def _looks_like_role_header(line: Line) -> bool:
    return _is_header_like(line) and (has_title_word(line.text) or bool(_EMPLOYER_RE.search(line.text))
                                      or bool(re.search(r"[|,@–—]| at ", line.text)) or bool(ROLE_KEY_RE.match(line.text)))


def _has_words(text: str) -> bool:
    text = re.sub(r"\b(?:present|current|now|till|date|to)\b", "", text, flags=re.I)
    text = _DATE_KEY_RE.sub("", text)
    return len(re.findall(r"[A-Za-z]{2,}", text)) > 0


def strip_role_keys(text: str) -> str:
    """'Organisation : Acme | Designation : Sales Manager' -> 'Acme | Sales Manager'."""
    parts = [ROLE_KEY_RE.sub("", part) for part in re.split(r"\s+\|\s+", text)]
    return " | ".join(part for part in parts if part.strip())


# "Duration :" left in front of an anchor's dates.
_DATE_KEY_LABEL_RE = re.compile(r"(?:^|(?<=\|))\s*(?:duration|period|tenure|dates?|working\s+period|time\s+period)\s*[:\-–]?\s*(?=\||$)",
                                re.I)
_DANGLING_RE = re.compile(r"(?:\s+(?:from|since|between|during|for|dated|period|duration|tenure|w\.?e\.?f\.?))+\s*$", re.I)


def _clean(text: str) -> str:
    """Trim separators and a connective left behind by a removed date ("... Pvt Ltd from")."""
    cleaned = re.sub(r"^[\s|,@\-–—:()]+|[\s|,@\-–—:()]+$", "", " ".join(text.split()))
    previous = None
    while previous != cleaned:
        previous = cleaned
        cleaned = re.sub(r"^[\s|,@\-–—:()]+|[\s|,@\-–—:()]+$", "", _DANGLING_RE.sub("", cleaned))
    return cleaned


# "VP - Product", "Head – Sales", "Manager - Operations": a rank and its function, one title (Indian style).
_FUNCTIONS = (
    "product", "products", "sales", "marketing", "operations", "finance", "accounts", "hr", "human resources", "engineering",
    "technology", "it", "legal", "design", "data", "analytics", "procurement", "purchase", "supply chain", "quality",
    "admin", "administration", "business development", "customer success", "customer support", "growth", "strategy",
    "risk", "compliance", "audit", "taxation", "treasury", "logistics", "projects", "production", "maintenance",
    "mathematics", "maths", "science", "english", "physics", "chemistry", "biology", "hindi", "social science",
    "social studies", "computer science", "economics", "commerce", "history", "geography", "accountancy", "nursing",
)
_RANK_FUNCTION_RE = re.compile(
    r"\b((?:a|s|e)?vp|head|manager|director|gm|dgm|agm|chief|lead|officer|executive|engineer|analyst|consultant|"
    r"specialist|teacher|pgt|tgt|prt|department|hod)\s+[-–—]\s+(?=(?:" + "|".join(re.escape(item) for item in _FUNCTIONS) +
    r")\s*(?:$|[|,(]|\s[-–—]\s))", re.I)


def split_title_employer(header: str | None) -> tuple[str | None, str | None]:
    if not header:
        return None, None
    header = _RANK_FUNCTION_RE.sub(lambda match: match.group(1) + " \x00 ", header)
    parts = [_clean(part).replace(" \x00 ", " - ").replace("\x00", "-") for part in _SEPARATORS_RE.split(header)]
    parts = [part for part in parts if part and part.lower() not in _LOCATIONS and len(re.findall(r"[A-Za-z]", part)) >= 2]
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


def segment_roles(lines: list[Line], today: date, heading: str | None = None) -> tuple[list[Role], list[Line]]:
    """Cut experience-section lines into roles; returns (roles, lines before the first role).

    Bare date lines ("Jul 2024 - Present" alone) take their title/employer from the header-like
    lines right above them, or - in a date-first layout, where the first role's dates open the
    section - from the line right after them. An anchor whose own text has no job title also takes
    a title-only line right after it ("Northgate Software GmbH  Dec 2025 - Present" + "Senior Java
    Developer"). Career-break lines are kept as body text, never roles."""
    lines = join_wrapped_dates(lines, today)
    dated_mode = any(_anchor(line, today, True) for line in lines)
    roles: list[Role] = []
    preface: list[Line] = []
    date_first: bool | None = None
    pending_title: Role | None = None  # a role whose title may still follow on the next line
    pending_employer: Role | None = None  # a role whose employer may still follow on the next line
    for line in lines:
        anchor = _anchor(line, today, dated_mode)
        if anchor is not None and _BREAK_RE.search(line.text) and not has_title_word(line.text):
            anchor = None  # "Career break - relocation (Jul 2025 - Apr 2026)"
        if anchor is not None and roles and _PROJECT_KEY_RE.match(line.text) is None and _DATE_KEY_RE.match(line.text) \
                and any(_PROJECT_KEY_RE.match(previous.text) for previous in roles[-1].lines[-3:]):
            anchor = None  # "Project : X / Client : Y / Duration : ..." inside a role is a project, not a new role
        if anchor is None:
            role = roles[-1] if roles else None
            if role is not None and not role.header_in_anchor and not role.header_lines and len(role.lines) == 1 \
                    and _is_header_like(line):
                role.header_lines.append(line)  # date-first layout: the title line follows the dates
            elif role is not None and role is pending_title and len(role.lines) == 1 and _is_title_line(line, today):
                role.header_lines.append(line)  # the title on its own line under "Employer  dates"
            elif role is not None and role is pending_employer and len(role.lines) == 1 and _is_employer_line(line, today):
                role.header_lines.append(line)  # the employer on its own line under "Title  dates"
            pending_title = pending_employer = None
            (role.lines if role is not None else preface).append(line)
            continue
        date_range, stated, span = anchor
        rest = _clean(line.text[:span[0]] + " | " + line.text[span[1]:])
        borrowed: list[Line] = []
        has_rest = _has_words(rest)
        if not has_rest:
            pool = roles[-1].lines if roles else preface
            if date_first is None:
                # The first bare date line decides the layout: nothing header-like above it means date-first.
                date_first = not (pool and _is_header_like(pool[-1]) and not find_date_ranges(pool[-1].text, today)
                                  and (_looks_like_role_header(pool[-1]) or pool is preface))
            if not date_first:
                while pool and len(borrowed) < 3 and _looks_like_role_header(pool[-1]) and not find_date_ranges(pool[-1].text, today):
                    if borrowed and len(_words(pool[-1].text)) > (12 if ROLE_KEY_RE.match(pool[-1].text) else 6):
                        break
                    if len(borrowed) == 2 and not ROLE_KEY_RE.match(pool[-1].text):
                        break  # three header lines only in key-value blocks (Organisation / Designation / Location)
                    borrowed.insert(0, pool.pop())
                if not borrowed and pool and pool is preface and _is_header_like(pool[-1]):
                    borrowed.append(pool.pop())
        if has_rest and has_title_word(rest) and split_title_employer(rest)[1] is None:
            pool = roles[-1].lines if roles else preface
            if pool and len(pool) > (1 if roles else 0) and _is_employer_line(pool[-1], today) and not pool[-1].is_bullet:
                borrowed.append(pool.pop())  # "Little Acorns Montessori | Pune" above "Vice Principal | Jan'26 - Ongoing"
        role = Role(line, span, has_rest, borrowed, date_range, stated, borrowed + [line], heading)
        roles.append(role)
        pending_title = role if has_rest and not has_title_word(rest) else None
        title, employer = split_title_employer(role.header())
        pending_employer = role if title and has_title_word(title) and employer is None else None
    return roles, preface


def _is_employer_line(line: Line, today: date) -> bool:
    """A short organisation line ("Mudra Lending Solutions, Vadodara, Gujarat", "Skyline Telecom Ltd.")."""
    text = line.text
    words = _words(text)
    if not _is_header_like(line) or len(words) > 8 or has_title_word(text) or find_date_ranges(text, today):
        return False
    capitalised = sum(1 for word in words if word[:1].isupper() or not word[:1].isalpha())
    return bool(_EMPLOYER_RE.search(text)) or (bool(re.search(r",|\s\|\s", text)) and capitalised == len(words))


def _is_title_line(line: Line, today: date) -> bool:
    """A short line that is only a job title ("Senior Java Developer", "SDE-2")."""
    text = line.text
    return (_is_header_like(line) and len(_words(text)) <= 8 and has_title_word(text)
            and not find_date_ranges(text, today) and not re.search(r"[.;:]$", text))


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
