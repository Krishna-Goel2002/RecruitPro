from typing import Literal

from pydantic import BaseModel, Field

AnalysisStatus = Literal["COMPLETED", "PARTIAL", "NEEDS_OCR", "FAILED"]
FlagCode = Literal["HIDDEN_TEXT", "PROMPT_INJECTION_SUSPECTED", "TWO_COLUMN_LAYOUT", "OCR_USED", "LEGACY_DOC", "LOW_TEXT_DENSITY"]
ParsedFactType = Literal["SKILL", "EDUCATION", "EXPERIENCE", "CERTIFICATION", "EMAIL", "PHONE", "LINK"]


class ParsedFact(BaseModel):
    type: ParsedFactType
    value: str = Field(min_length=1, max_length=1000)
    normalizedValue: str = Field(min_length=1, max_length=1000)
    evidence: str = Field(min_length=1, max_length=1000)
    sourceSection: str = Field(min_length=1, max_length=80)
    sourceReference: str | None = Field(default=None, max_length=80)
    spanStart: int | None = None
    spanEnd: int | None = None
    # Only keys that apply are present: skillId, domain, title, employer, startDate, endDate, current,
    # months, degreeLevel, field, url.
    attributes: dict[str, str | int | bool] = Field(default_factory=dict)


class TimelineRole(BaseModel):
    title: str | None = None
    employer: str | None = None
    startDate: str | None = None
    endDate: str | None = None
    current: bool = False
    months: int | None = None
    skills: list[str] = Field(default_factory=list)


class Timeline(BaseModel):
    totalMonths: int = Field(ge=0)
    roles: list[TimelineRole] = Field(default_factory=list, max_length=100)
    skillMonths: dict[str, int] = Field(default_factory=dict)


class ParseFlag(BaseModel):
    code: FlagCode
    detail: str


class ParseResponse(BaseModel):
    status: AnalysisStatus
    errorCode: str | None = None
    parserVersion: str
    facts: list[ParsedFact] = Field(default_factory=list, max_length=250)
    flags: list[ParseFlag] = Field(default_factory=list)
    timeline: Timeline | None = None
