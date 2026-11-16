from typing import Literal

from pydantic import BaseModel, Field

from app.models.common import Engine, Proficiency, RequirementType
from app.models.matching import MatchRequirement

Anchors = dict[Literal["1", "2", "3", "4"], str]
WarningKind = Literal["AGE_CODED", "GENDER_CODED", "DEGREE_INFLATION", "EXCLUSIONARY", "VAGUE", "EXCESSIVE_EXPERIENCE"]


class CompileRequest(BaseModel):
    title: str = Field(min_length=1, max_length=200)
    description: str = Field(min_length=1, max_length=20000)
    experienceLevel: Literal["ENTRY", "MID", "SENIOR", "LEAD", "EXECUTIVE"] | None = None


class CompiledRequirement(BaseModel):
    type: RequirementType
    description: str = Field(min_length=1, max_length=500)
    mandatory: bool
    knockout: bool
    minimumYears: int | None = None
    alternatives: list[str] = Field(default_factory=list)
    proficiency: Proficiency | None = None
    rationale: str


class CompileWarning(BaseModel):
    phrase: str
    kind: WarningKind
    suggestion: str


class CompileResult(BaseModel):
    engine: Engine
    requirements: list[CompiledRequirement]
    warnings: list[CompileWarning]


class WorkSampleRequest(BaseModel):
    jobTitle: str = Field(min_length=1, max_length=200)
    jobDescription: str | None = Field(default=None, max_length=20000)
    requirements: list[MatchRequirement] = Field(default_factory=list, max_length=100)


class RubricCriterion(BaseModel):
    requirementId: int | None
    criterion: str
    anchors: Anchors


class WorkSampleResponse(BaseModel):
    title: str
    instructions: str
    timeLimitMinutes: int = Field(ge=15, le=45)
    rubric: list[RubricCriterion]
    engine: Engine


class InterviewKitRequest(BaseModel):
    jobTitle: str = Field(min_length=1, max_length=200)
    requirements: list[MatchRequirement] = Field(default_factory=list, max_length=100)
    focusRequirementIds: list[int] | None = Field(default=None, max_length=100)


class InterviewQuestion(BaseModel):
    id: str
    requirementId: int | None
    question: str
    followUps: list[str]
    anchors: Anchors


class InterviewKitResponse(BaseModel):
    questions: list[InterviewQuestion]
    engine: Engine
