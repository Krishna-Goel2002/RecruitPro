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
# Seasons as the month they usually start (academic terms, internships): Spring 2020 = Apr 2020.
SEASONS = {"winter": 1, "spring": 4, "summer": 7, "fall": 10, "autumn": 10}
_MONTH = r"(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)"
_SEASON = r"(?:spring|summer|fall|autumn|winter)"
_YEAR = r"(?:19[5-9]\d|20\d\d)"
_DAY = r"(?:0?[1-9]|[12]\d|3[01])(?:st|nd|rd|th)?"
_DATE = (
    rf"(?:\d{{1,2}}\s*[/.\-]\s*\d{{1,2}}\s*[/.\-]\s*{_YEAR}"  # 01/06/2018, 31-03-2021 (day first unless impossible)
    rf"|{_DAY}\s+{_MONTH}\.?,?\s*{_YEAR}"  # 15 Jan 2019, 1st March 2020
    rf"|{_MONTH}\.?\s+{_DAY},?\s+{_YEAR}"  # Jan 15, 2019
    rf"|{_MONTH}\.?[\s,\-/]*(?:{_YEAR}|'\s?\d\d|’\s?\d\d)"  # Jan 2019, January, 2019, Jan-2019, Jan '19
    rf"|{_MONTH}\.?\s?[\-/]\s?\d\d(?!\d)"  # Apr-21, Jan/19
    rf"|{_SEASON}\s*,?\s*{_YEAR}"  # Summer 2023
    rf"|(?:0?[1-9]|1[0-2])\s*[/.\-]\s*{_YEAR}"  # 01/2019, 1-2019
    rf"|{_YEAR}\s*[/.\-]\s*(?:0[1-9]|1[0-2])(?!\d)"  # 2019-01, 2019/01
    rf"|{_YEAR})"
)
_PRESENT = r"(?:present|current(?:ly)?|now|till\s+(?:date|now|present)|to\s+date|today|ongoing|continuing|date)"
_SEPARATOR = r"\s*(?:[\-‐‑‒–—―−~→]+|\bto\b|\buntil\b|\btill\b|\bthrough\b|\bthru\b)\s*"
_RANGE_RE = re.compile(rf"(?<![\w/.\-])(?P<start>{_DATE}){_SEPARATOR}(?P<end>{_DATE}|{_PRESENT})(?![\w/])", re.I)
# "2016 - 18", "2019–21": a range whose end year is abbreviated. Without spaces or a dash other than
# "-", only an end that cannot be a month counts ("2019-11" stays November 2019).
_SHORT_RANGE_RE = re.compile(
    rf"(?<![\w/.\-])(?P<start>{_YEAR})(?P<sep>\s*(?:[\-‐‑‒–—−]|\bto\b)\s*)(?P<end>\d\d)(?![\w/.\-])", re.I)
_SINCE_RE = re.compile(rf"\b(?:since|from)\s+(?P<start>{_DATE})(?![\w/])(?!{_SEPARATOR}(?:{_DATE}|{_PRESENT}))", re.I)
_PRESENT_RE = re.compile(rf"^{_PRESENT}$", re.I)

_NUMBER_WORDS = {
    "one": 1, "two": 2, "three": 3, "four": 4, "five": 5, "six": 6, "seven": 7, "eight": 8, "nine": 9, "ten": 10,
    "eleven": 11, "twelve": 12, "fifteen": 15, "eighteen": 18, "twenty": 20, "a": 1, "an": 1,
}
_NUM = r"(?:\d{1,2}(?:\.\d)?|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|twenty)"
_MONTH_NUM = r"(?:\d{1,3}|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|eighteen)"
_DURATION_RE = re.compile(
    rf"(?<![\w.])(?P<years>{_NUM})\s*\+?\s*(?:years?|yrs?)\b(?!\s*old)"
    rf"(?:\s*,?\s*(?:and\s*)?(?P<ymonths>\d{{1,2}}|{_MONTH_NUM})\s*(?:months?|mos?)\b)?"
    rf"|(?<![\w.])(?P<months>{_MONTH_NUM})\s*\+?\s*(?:months?|mos)\b",
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
    value = " ".join(value.strip().lower().rstrip(".").split())
    match = re.fullmatch(rf"(\d{{1,2}})\s*[/.\-]\s*(\d{{1,2}})\s*[/.\-]\s*({_YEAR})", value)
    if match:
        first, second = int(match.group(1)), int(match.group(2))
        # Day first (India, UK, most of the world); month first only when the day-first reading is impossible.
        month = second if second <= 12 and first <= 31 else (first if first <= 12 and second <= 31 else 0)
        return YearMonth(int(match.group(3)), month, "month") if 1 <= month <= 12 else None
    match = re.fullmatch(rf"{_DAY}\s+({_MONTH})\.?,?\s*({_YEAR})", value) or \
        re.fullmatch(rf"({_MONTH})\.?\s+{_DAY},?\s+({_YEAR})", value)
    if match:
        return YearMonth(int(match.group(2)), MONTHS[match.group(1)[:3]], "month")
    match = re.fullmatch(rf"({_SEASON})\s*,?\s*({_YEAR})", value)
    if match:
        return YearMonth(int(match.group(2)), SEASONS[match.group(1)], "month")
    match = re.fullmatch(rf"({_MONTH})\.?(?:[\s,\-/]*({_YEAR})|\s?['’\-/]\s?(\d\d))", value)
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


_PERIOD_LABEL_RE = re.compile(r"\b(?:fy|f\.y\.?|fiscal(?:\s+year)?|financial\s+year|a\.?y\.?|academic\s+year)\s*['’]?$", re.I)


def _period_label(text: str, start: int) -> bool:
    """A fiscal/academic year such as "FY 2019-20" is a reporting period, not employment dates."""
    return bool(_PERIOD_LABEL_RE.search(text[max(0, start - 20):start]))


def find_date_ranges(text: str, today: date) -> list[DateRange]:
    """All valid date ranges in ``text`` (start not after end, end not in the future)."""
    ranges: list[DateRange] = []
    current_month = YearMonth(today.year, today.month, "month")
    for match in _RANGE_RE.finditer(text):
        if _period_label(text, match.start()):
            continue
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
    for match in _SHORT_RANGE_RE.finditer(text):
        if any(r.span[0] < match.end() and match.start() < r.span[1] for r in ranges) or _period_label(text, match.start()):
            continue
        start_year, end_two = int(match.group("start")), int(match.group("end"))
        end_year = start_year - start_year % 100 + end_two
        spaced = match.group("sep").strip() != match.group("sep") or match.group("sep").strip() not in {"-"}
        if end_year <= start_year or end_year - start_year > 15 or not (spaced or end_two > 12):
            continue
        start, end = YearMonth(start_year, 7, "year"), YearMonth(end_year, 6, "year")
        current = False
        if end.ordinal > current_month.ordinal:
            end, current = current_month, True
        if start.ordinal <= end.ordinal:
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
            months = round(years * 12) + _count(match.group("ymonths"))
        else:
            months = _count(match.group("months"))
        if 0 < months <= 600:
            return months, match.span()
    return None


def _count(value: str | None) -> int:
    if not value:
        return 0
    return _NUMBER_WORDS.get(value.lower()) or int(value)


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
