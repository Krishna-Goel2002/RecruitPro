from typing import Annotated, Any, Literal

from pydantic import BaseModel, Field, field_validator

from app.models.common import EvidenceTier, FactType, RequirementType
from app.models.resume import Timeline

MatchStatus = Literal["MATCHED", "UNCLEAR", "NOT_OBSERVED"]
Provenance = Literal["RESUME", "CANDIDATE_CLAIM", "CORROBORATED", "WORK_SAMPLE", "INTERVIEW"]


class MatchRequirement(BaseModel):
    id: int
    type: RequirementType
    description: str = Field(min_length=1, max_length=500)
    mandatory: bool
    knockout: bool = False
    minimumYears: int | None = Field(default=None, ge=0, le=50)
    alternatives: list[Annotated[str, Field(max_length=500)]] = Field(default_factory=list, max_length=10)
    proficiency: str | None = Field(default=None, max_length=20)


def _unique_requirement_ids(requirements: list[MatchRequirement]) -> list[MatchRequirement]:
    """Requirement ids key the weights and the work-sample/interview links, so they must be unique."""
    ids = [requirement.id for requirement in requirements]
    if len(ids) != len(set(ids)):
        raise ValueError("requirement ids must be unique")
    return requirements


class MatchFact(BaseModel):
    # Origin-tagged id: "fact:12" (resume), "claim:3" (candidate), "ws:4:r9" (work sample 4,
    # requirement 9), "iv:7:r9" (interview 7, requirement 9).
    id: str = Field(min_length=1, max_length=40)
    type: FactType
    value: str = Field(min_length=1, max_length=1000)
    evidence: str = Field(default="", max_length=1000)
    sourceSection: str = Field(default="", max_length=80)
    tier: EvidenceTier
    attributes: dict[str, Any] | None = None


class MatchRequest(BaseModel):
    requirements: list[MatchRequirement] = Field(max_length=100)
    facts: list[MatchFact] = Field(max_length=500)
    timeline: Timeline | None = None

    _unique = field_validator("requirements")(_unique_requirement_ids)


class RequirementResult(BaseModel):
    requirementId: int
    status: MatchStatus
    tier: EvidenceTier | None = None
    evidence: str | None = None
    factIds: list[str] = Field(default_factory=list)
    sourceSection: str | None = None
    provenance: Provenance | None = None
    explanation: str
    verificationHint: str | None = None


class MatchResponse(BaseModel):
    algorithmVersion: Literal["evidence-ladder-4"] = "evidence-ladder-4"
    scoreLabel: str
    verifiedCoverage: float | None
    claimedCoverage: float | None
    knockoutsVerified: bool
    unverifiedKnockouts: list[int] = Field(default_factory=list)
    score: float | None
    requirements: list[RequirementResult]


class BatchCandidate(BaseModel):
    applicationId: int
    facts: list[MatchFact] = Field(max_length=500)
    timeline: Timeline | None = None


class BatchMatchRequest(BaseModel):
    requirements: list[MatchRequirement] = Field(max_length=100)
    candidates: list[BatchCandidate] = Field(max_length=50)

    _unique = field_validator("requirements")(_unique_requirement_ids)


class CandidateMatch(MatchResponse):
    applicationId: int


class BatchMatchResponse(BaseModel):
    candidates: list[CandidateMatch]
