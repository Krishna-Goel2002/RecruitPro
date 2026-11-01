from typing import Literal

from pydantic import BaseModel, Field


FactType = Literal["SKILL", "EDUCATION", "EXPERIENCE", "CERTIFICATION", "EMAIL", "PHONE"]
AnalysisStatus = Literal["COMPLETED", "PARTIAL", "NEEDS_OCR", "FAILED"]


class ExtractedFact(BaseModel):
    type: FactType
    value: str = Field(min_length=1, max_length=1000)
    normalizedValue: str = Field(min_length=1, max_length=1000)
    evidence: str = Field(min_length=1, max_length=1000)
    sourceSection: str = Field(min_length=1, max_length=80)
    sourceReference: str | None = Field(default=None, max_length=80)


class ResumeParseResponse(BaseModel):
    status: AnalysisStatus
    errorCode: str | None = None
    parserVersion: str
    facts: list[ExtractedFact] = Field(default_factory=list, max_length=250)


class MatchRequirement(BaseModel):
    id: int
    type: str = Field(max_length=30)
    description: str = Field(min_length=1, max_length=500)
    mandatory: bool
    minimumYears: int | None = Field(default=None, ge=0)


class MatchFact(BaseModel):
    id: int
    type: FactType
    value: str = Field(min_length=1, max_length=1000)
    evidence: str = Field(min_length=1, max_length=1000)
    sourceSection: str = Field(min_length=1, max_length=80)
    reviewStatus: Literal["UNREVIEWED", "CONFIRMED", "CORRECTED"]


class MatchRequest(BaseModel):
    requirements: list[MatchRequirement] = Field(max_length=100)
    facts: list[MatchFact] = Field(max_length=250)


class RequirementMatch(BaseModel):
    requirementId: int
    status: Literal["MATCHED", "UNCLEAR", "NOT_OBSERVED"]
    evidence: str | None = None
    sourceSection: str | None = None
    provenance: Literal["RESUME", "CANDIDATE_REVIEW"] | None = None
    explanation: str


class MatchResponse(BaseModel):
    score: float | None
    scoreLabel: str
    algorithmVersion: str
    requirements: list[RequirementMatch]


class MatchCandidate(BaseModel):
    applicationId: int
    facts: list[MatchFact] = Field(max_length=250)


class BatchMatchRequest(BaseModel):
    requirements: list[MatchRequirement] = Field(max_length=100)
    candidates: list[MatchCandidate] = Field(max_length=50)


class CandidateMatch(BaseModel):
    applicationId: int
    score: float | None
    scoreLabel: str
    algorithmVersion: str
    requirements: list[RequirementMatch]


class BatchMatchResponse(BaseModel):
    candidates: list[CandidateMatch]
