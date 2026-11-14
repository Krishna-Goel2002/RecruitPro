"""Date-range and duration parsing for resumes (finding H5).

Recognised forms: ``Jan 2019 – Mar 2021``, ``January, 2019 to March 2021``, ``01/2019 - 03/2021``,
``2019-01 to 2021-03``, ``2019 – 2021``, ``Jan '19 – Present``, ``Since June 2022`` and open ends
``Present/Current/Now/Till date/To date/Ongoing``. "Present" is *today* (passed in, never a
hard-coded year).

Month arithmetic is inclusive (Jan–Mar = 3 months). Year-only dates carry ``precision="year"``
and are assumed to be mid-year, so ``2019 – 2021`` counts 24 months rather than 12 or 36.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import date

MONTHS = {
    "jan": 1, "january": 1, "feb": 2, "february": 2, "mar": 3, "march": 3, "apr": 4, "april": 4, "may": 5,
    "jun": 6, "june": 6, "jul": 7, "july": 7, "aug": 8, "august": 8, "sep": 9, "sept": 9, "september": 9,
    "oct": 10, "october": 10, "nov": 11, "november": 11, "dec": 12, "december": 12,
}
_MONTH = r"(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)"
_YEAR = r"(?:19[5-9]\d|20\d\d)"
_DATE = (
    rf"(?:{_MONTH}\.?[\s,\-/]*(?:{_YEAR}|'\d\d|’\d\d)"  # Jan 2019, January, 2019, Jan-2019, Jan '19
    rf"|(?:0?[1-9]|1[0-2])\s*[/.\-]\s*{_YEAR}"  # 01/2019, 1-2019
    rf"|{_YEAR}\s*[/.\-]\s*(?:0[1-9]|1[0-2])(?!\d)"  # 2019-01, 2019/01
    rf"|{_YEAR})"
)
_PRESENT = r"(?:present|current(?:ly)?|now|till\s+(?:date|now|present)|to\s+date|today|ongoing|continuing|date)"
_SEPARATOR = r"\s*(?:[\-‐‑‒–—―~→]+|\bto\b|\buntil\b|\btill\b|\bthrough\b|\bthru\b)\s*"
_RANGE_RE = re.compile(rf"(?<![\w/.\-])(?P<start>{_DATE}){_SEPARATOR}(?P<end>{_DATE}|{_PRESENT})(?![\w/])", re.I)
_SINCE_RE = re.compile(rf"\b(?:since|from)\s+(?P<start>{_DATE})(?![\w/])(?!{_SEPARATOR}(?:{_DATE}|{_PRESENT}))", re.I)
_PRESENT_RE = re.compile(rf"^{_PRESENT}$", re.I)

_NUMBER_WORDS = {
    "one": 1, "two": 2, "three": 3, "four": 4, "five": 5, "six": 6, "seven": 7, "eight": 8, "nine": 9, "ten": 10,
    "eleven": 11, "twelve": 12, "fifteen": 15, "twenty": 20, "a": 1, "an": 1,
}
_NUM = r"(?:\d{1,2}(?:\.\d)?|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|twenty)"
_DURATION_RE = re.compile(
    rf"(?<![\w.])(?P<years>{_NUM})\s*\+?\s*(?:years?|yrs?)\b(?!\s*old)(?:\s*(?:and\s*)?(?P<ymonths>\d{{1,2}})\s*(?:months?|mos?)\b)?"
    rf"|(?<![\w.])(?P<months>\d{{1,3}})\s*\+?\s*(?:months?|mos)\b",
    re.I,
)


@dataclass(frozen=True)
class YearMonth:
    year: int
    month: int
    precision: str  # "month" or "year"

    @property
    def ordinal(self) -> int:
        return self.year * 12 + self.month - 1

    def iso(self) -> str:
        return f"{self.year:04d}" if self.precision == "year" else f"{self.year:04d}-{self.month:02d}"


@dataclass(frozen=True)
class DateRange:
    start: YearMonth
    end: YearMonth  # today's month when current
    current: bool
    span: tuple[int, int]  # character offsets in the parsed text

    @property
    def months(self) -> int:
        return self.end.ordinal - self.start.ordinal + 1

    @property
    def interval(self) -> tuple[int, int]:
        """Inclusive month ordinals."""
        return self.start.ordinal, self.end.ordinal


def parse_date(value: str, today: date, *, is_end: bool) -> YearMonth | None:
    value = value.strip().lower().rstrip(".")
    match = re.fullmatch(rf"({_MONTH})\.?[\s,\-/]*(?:({_YEAR})|['’](\d\d))", value)
    if match:
        year = int(match.group(2)) if match.group(2) else _two_digit_year(int(match.group(3)), today)
        return YearMonth(year, MONTHS[match.group(1)[:3]], "month")
    match = re.fullmatch(rf"(0?[1-9]|1[0-2])\s*[/.\-]\s*({_YEAR})", value)
    if match:
        return YearMonth(int(match.group(2)), int(match.group(1)), "month")
    match = re.fullmatch(rf"({_YEAR})\s*[/.\-]\s*(0[1-9]|1[0-2])", value)
    if match:
        return YearMonth(int(match.group(1)), int(match.group(2)), "month")
    if re.fullmatch(_YEAR, value):
        # Mid-year assumption: start = July, end = June of the following span.
        return YearMonth(int(value), 6 if is_end else 7, "year")
    return None


def _two_digit_year(value: int, today: date) -> int:
    return 2000 + value if 2000 + value <= today.year + 1 else 1900 + value


def find_date_ranges(text: str, today: date) -> list[DateRange]:
    """All valid date ranges in ``text`` (start not after end, end not in the future)."""
    ranges: list[DateRange] = []
    current_month = YearMonth(today.year, today.month, "month")
    for match in _RANGE_RE.finditer(text):
        start = parse_date(match.group("start"), today, is_end=False)
        end_text = match.group("end")
        current = bool(_PRESENT_RE.match(end_text.strip()))
        end = current_month if current else parse_date(end_text, today, is_end=True)
        if start is None or end is None:
            continue
        if start.precision == "year" and end.precision == "year" and start.year == end.year:
            start, end = YearMonth(start.year, 1, "year"), YearMonth(end.year, 6, "year")
        if end.ordinal > current_month.ordinal:
            end, current = current_month, True
        if start.ordinal > end.ordinal or start.ordinal > current_month.ordinal:
            continue
        ranges.append(DateRange(start, end, current, match.span()))
    for match in _SINCE_RE.finditer(text):
        if any(r.span[0] <= match.start() < r.span[1] for r in ranges):
            continue
        start = parse_date(match.group("start"), today, is_end=False)
        if start and start.ordinal <= current_month.ordinal:
            ranges.append(DateRange(start, current_month, True, match.span()))
    ranges.sort(key=lambda item: item.span[0])
    return ranges


def find_duration_months(text: str) -> tuple[int, tuple[int, int]] | None:
    """An explicit duration such as "4 years", "2.5 yrs", "18 months" or "3 years 6 months"."""
    for match in _DURATION_RE.finditer(text):
        if match.group("years"):
            raw = match.group("years").lower()
            years = float(_NUMBER_WORDS.get(raw, 0) or raw)
            months = round(years * 12) + int(match.group("ymonths") or 0)
        else:
            months = int(match.group("months"))
        if 0 < months <= 600:
            return months, match.span()
    return None


def merged_months(intervals: list[tuple[int, int]]) -> int:
    """Total months covered by inclusive month intervals, counting overlaps once."""
    total = 0
    current: tuple[int, int] | None = None
    for start, end in sorted(intervals):
        if current is None or start > current[1] + 1:
            if current is not None:
                total += current[1] - current[0] + 1
            current = (start, end)
        else:
            current = (current[0], max(current[1], end))
    if current is not None:
        total += current[1] - current[0] + 1
    return total
