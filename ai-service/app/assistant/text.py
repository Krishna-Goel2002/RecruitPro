"""Text helpers for the offline planner: normalisation, typo repair, codes, numbers, dates and times."""
from __future__ import annotations

import calendar
import difflib
import re
from datetime import date, datetime, timedelta
from functools import lru_cache

# Words the intent rules look for. A misspelt token close to one of them is repaired ("shortlsit" -> "shortlist").
# Inflected forms are listed as themselves so they are never "repaired" into another word ("closed" stays "closed").
VOCABULARY = frozenset("""
shortlist shortlisted shortlisting reject rejected rejecting rejection interview interviews interviewed interviewing
interviewer schedule scheduled scheduling reschedule applicant applicants candidate candidates application applications
compare comparison fairness attention pipeline posting postings position positions opening openings vacancy vacancies
requirement requirements verified verification verify coverage evidence ladder knockout knockouts blind review reviews
reviewed shadow scorecard scorecards sample samples assign assigned assignment generate generated create created
publish published close closed closing select selected selection explain explained status report reports analyst
engineer developer manager deadline deadlines department location salary remote hybrid internship intern contract
experience senior junior mandatory dashboard today tomorrow upcoming insights summary digest posted opened applied
pending urgent overview performing performance doing ranking ranked unscored claimed verified progress funnel kit
question questions hiring recruit recruiter recruitment percentage difference between strongest weakest delete
unverified unscored interviewing reviewer reviewers verdict verdicts schedules applies applying candidates
""".split())
_WORD_RE = re.compile(r"[a-z]+")
_NUMBER_WORDS = {
    "zero": 0, "one": 1, "a single": 1, "single": 1, "two": 2, "couple": 2, "three": 3, "four": 4, "five": 5, "six": 6,
    "seven": 7, "eight": 8, "nine": 9, "ten": 10, "eleven": 11, "twelve": 12, "thirteen": 13, "fourteen": 14,
    "fifteen": 15, "sixteen": 16, "seventeen": 17, "eighteen": 18, "nineteen": 19, "twenty": 20, "thirty": 30,
}
NUMBER_WORD_RE = "|".join(sorted((re.escape(word) for word in _NUMBER_WORDS), key=len, reverse=True))
CODE_RE = re.compile(r"(?<![\w-])#?\s?[Aa]\s?-\s?(\d{1,9})\b|(?<![\w-])#?[Aa](\d{2,9})\b|"
                     r"\b(?:application|applicant|candidate|app)\s*(?:no\.?|number|#)?\s*#?\s*(\d{2,9})\b", re.I)
_SHORTHAND = (
    (r"\bpls\b|\bplz\b|\bplease\b|\bkindly\b", " "), (r"\bu\b", "you"), (r"\bur\b", "your"), (r"\br\b(?=\s+(?:there|the))", "are"),
    (r"\bwat\b|\bwht\b", "what"), (r"\bhw\b", "how"), (r"\babt\b", "about"), (r"\bthx\b|\bthanks\b|\bthank you\b", " thanks "),
    (r"\bwfh\b|\bwork from home\b", "remote"), (r"\bwfo\b", "onsite"), (r"\bjd\b", "job description"),
    (r"\bdo the needful\b", " "), (r"\bpl\b", " "), (r"\binterviw\b|\bintrview\b|\binterveiw\b", "interview"),
    (r"\bcandi?dates?\b", lambda m: "candidates" if m.group(0).endswith("s") else "candidate"),
    (r"\btmrw\b|\btmr\b|\btomorow\b|\btommorow\b|\btommorrow\b", "tomorrow"), (r"\btdy\b|\btoday's\b", "today"),
    (r"\bshort[- ]list\b", "shortlist"), (r"\bwork[- ]sample\b", "work sample"), (r"\bhrs\b", "hours"),
)


def normalise(text: str) -> str:
    """Lower-case, straighten quotes, expand chat shorthand and repair near-miss keywords."""
    lowered = (text or "").replace("’", "'").replace("‘", "'").replace("“", '"').replace("”", '"')
    lowered = lowered.replace("–", "-").replace("—", "-").lower()
    for pattern, replacement in _SHORTHAND:
        lowered = re.sub(pattern, replacement, lowered)
    lowered = _WORD_RE.sub(lambda match: repair(match.group(0)), lowered)
    return " ".join(lowered.split())


@lru_cache(maxsize=4096)
def repair(word: str) -> str:
    if len(word) < 5 or word in VOCABULARY:
        return word
    close = difflib.get_close_matches(word, VOCABULARY, n=1, cutoff=0.84)
    return close[0] if close else word


def application_codes(text: str) -> list[str]:
    """Application codes in order of appearance, normalised to ``A-<digits>``."""
    codes: list[str] = []
    for match in CODE_RE.finditer(text or ""):
        digits = next(group for group in match.groups() if group)
        code = f"A-{int(digits)}"
        if code not in codes:
            codes.append(code)
    return codes


def code_to_id(code: str | None) -> int | None:
    match = re.fullmatch(r"A-(\d{1,15})", code or "")
    return int(match.group(1)) if match else None


def word_number(token: str) -> int | None:
    token = token.strip().lower()
    if token.isdigit():
        return int(token)
    return _NUMBER_WORDS.get(token)


# ----------------------------------------------------------------------------------------- dates
_MONTHS = {name.lower(): index for index, name in enumerate(calendar.month_name) if name}
_MONTHS.update({name.lower(): index for index, name in enumerate(calendar.month_abbr) if name})
_MONTHS["sept"] = 9
_MONTH_RE = "|".join(sorted(_MONTHS, key=len, reverse=True))
_WEEKDAYS = {name.lower(): index for index, name in enumerate(calendar.day_name)}
_WEEKDAYS.update({name.lower(): index for index, name in enumerate(calendar.day_abbr)})
_WEEKDAYS.update({"tues": 1, "thur": 3, "thurs": 3, "weds": 2})
_WEEKDAY_RE = "|".join(sorted(_WEEKDAYS, key=len, reverse=True))
_UNIT_DAYS = {"day": 1, "days": 1, "week": 7, "weeks": 7, "wk": 7, "wks": 7, "fortnight": 14, "fortnights": 14}


def _safe_date(year: int, month: int, day: int) -> date | None:
    try:
        return date(year, month, day)
    except ValueError:
        return None


def _add_months(start: date, months: int) -> date:
    month_index = start.month - 1 + months
    year, month = start.year + month_index // 12, month_index % 12 + 1
    return date(year, month, min(start.day, calendar.monthrange(year, month)[1]))


def _upcoming(candidate: date | None, today: date, explicit_year: bool) -> date | None:
    if candidate is None or explicit_year or candidate >= today:
        return candidate
    return _safe_date(candidate.year + 1, candidate.month, candidate.day)


def parse_date(text: str, today: date) -> date | None:
    """The first date the text names (absolute, or relative to ``today``); Indian day-first order."""
    value = (text or "").lower()
    number = rf"(\d{{1,3}}|{NUMBER_WORD_RE}|a|an)"
    match = re.search(r"\b(\d{4})-(\d{1,2})-(\d{1,2})\b", value)
    if match:
        return _safe_date(int(match.group(1)), int(match.group(2)), int(match.group(3)))
    for match in re.finditer(r"(?<![\d.])(\d{1,2})[/.\-](\d{1,2})(?:[/.\-](\d{2,4}))?(?![\d.])", value):
        tail = value[match.end():match.end() + 12]
        if re.match(r"\s*\+?\s*(?:lpa|lakhs?|lacs?|years?|yrs?|l\b|k\b|%|cr|months?|openings?|positions?|people)", tail):
            continue  # "3-5 years", "8-12 LPA": a range, not a date
        if match.group(3) is None and "-" in match.group(0):
            continue  # "4-6" without a year is a range far more often than a date
        year = int(match.group(3)) if match.group(3) else today.year
        year = year + 2000 if year < 100 else year
        found = _upcoming(_safe_date(year, int(match.group(2)), int(match.group(1))), today, bool(match.group(3)))
        if found:
            return found
    match = re.search(rf"\b(\d{{1,2}})(?:st|nd|rd|th)?\s*(?:of\s+)?({_MONTH_RE})\b\.?,?\s*(\d{{4}})?", value)
    if match:
        year = int(match.group(3)) if match.group(3) else today.year
        return _upcoming(_safe_date(year, _MONTHS[match.group(2)], int(match.group(1))), today, bool(match.group(3)))
    match = re.search(rf"\b({_MONTH_RE})\.?\s+(\d{{1,2}})(?:st|nd|rd|th)?\b,?\s*(\d{{4}})?", value)
    if match:
        year = int(match.group(3)) if match.group(3) else today.year
        return _upcoming(_safe_date(year, _MONTHS[match.group(1)], int(match.group(2))), today, bool(match.group(3)))
    if re.search(r"\bday after tomorrow\b", value):
        return today + timedelta(days=2)
    if re.search(r"\btomorrow\b", value):
        return today + timedelta(days=1)
    if re.search(r"\btoday\b|\btonight\b", value):
        return today
    match = re.search(rf"\b(?:in|within|after)\s+(?:the\s+next\s+)?{number}\s+(days?|weeks?|wks?|fortnights?|months?)\b|"
                      rf"\b{number}\s+(days?|weeks?|wks?|fortnights?|months?)\s+(?:from\s+(?:now|today)|later|time)\b", value)
    if match:
        raw, unit = (match.group(1), match.group(2)) if match.group(1) else (match.group(3), match.group(4))
        count = 1 if raw in {"a", "an"} else (word_number(raw) or 0)
        if unit.startswith("month"):
            return _add_months(today, count)
        return today + timedelta(days=count * _UNIT_DAYS[unit])
    if re.search(r"\bend of (?:the )?next month\b", value):
        following = _add_months(today.replace(day=1), 1)
        return following.replace(day=calendar.monthrange(following.year, following.month)[1])
    if re.search(r"\bend of (?:the |this )?month\b|\bmonth[- ]end\b", value):
        return today.replace(day=calendar.monthrange(today.year, today.month)[1])
    if re.search(r"\bend of (?:the |this )?year\b|\byear[- ]end\b", value):
        return date(today.year, 12, 31)
    if re.search(r"\bnext month\b", value):
        return _add_months(today, 1)
    if re.search(r"\bnext week\b", value):
        return today + timedelta(days=7)
    if re.search(r"\bend of (?:the |this )?week\b", value):
        return today + timedelta(days=(4 - today.weekday()) % 7)
    match = re.search(rf"\b(next|this|coming)?\s*({_WEEKDAY_RE})\b", value)
    if match:
        ahead = (_WEEKDAYS[match.group(2)] - today.weekday()) % 7 or 7
        if match.group(1) == "next" and ahead <= 2:
            ahead += 7
        return today + timedelta(days=ahead)
    return None


def parse_time(text: str) -> tuple[int, int] | None:
    """(hour, minute) named in the text: "3pm", "3:30 pm", "15:00", "at 11", "noon", "morning"."""
    value = (text or "").lower()
    match = re.search(r"\b(\d{1,2})(?:[:.](\d{2}))?\s*(am|pm|a\.m\.|p\.m\.)", value)
    if match:
        hour, minute = int(match.group(1)) % 12, int(match.group(2) or 0)
        if match.group(3).startswith("p"):
            hour += 12
        return (hour, minute) if hour < 24 and minute < 60 else None
    match = re.search(r"\b([01]?\d|2[0-3])[:.]([0-5]\d)\b(?!\s*(?:lpa|%|years?))", value)
    if match and not re.search(r"\d[:.]\d{2}[/.\-]\d", value[match.start():match.end() + 3]):
        return int(match.group(1)), int(match.group(2))
    if re.search(r"\bnoon\b|\bmidday\b", value):
        return 12, 0
    match = re.search(r"\b(?:at|@)\s*(\d{1,2})\b(?!\s*(?:days?|weeks?|months?|years?|[/\-]))", value)
    if match:
        hour = int(match.group(1))
        if 1 <= hour <= 7:
            hour += 12  # "at 3" in office hours means 15:00
        return (hour, 0) if hour < 24 else None
    for word, slot in (("morning", (10, 0)), ("afternoon", (14, 0)), ("evening", (17, 0))):
        if re.search(rf"\b{word}\b", value):
            return slot
    return None


def parse_datetime(text: str, today: date) -> datetime | None:
    """A local date and time for scheduling; both parts must be present."""
    day, clock = parse_date(text, today), parse_time(text)
    if day is None or clock is None:
        return None
    return datetime(day.year, day.month, day.day, clock[0], clock[1])
