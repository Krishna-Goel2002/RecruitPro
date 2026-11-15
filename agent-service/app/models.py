"""Pydantic models of the agent-service contract (IMPLEMENTATION_PLAN_V2 section 4). JSON is camelCase.

Field limits mirror the backend columns: item keys <= 40 chars, excerpts <= 1000, snapshot text <= 262144,
probe questions <= 500, claim values <= 1000.
"""
from __future__ import annotations

from typing import Any, Literal, Optional, Union

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel

VerdictValue = Literal["VERIFIED", "PARTLY_VERIFIED", "NOT_FOUND", "CONTRADICTED", "NOT_CHECKABLE"]
ClaimType = Literal["SKILL", "CERTIFICATION", "EDUCATION", "EXPERIENCE", "OTHER"]
EvidenceKind = Literal["PROFILE", "REPOSITORY", "MANIFEST", "COMMIT_SUMMARY", "README", "PAGE", "DOCUMENT", "BADGE",
                       "OWNERSHIP_PROOF", "REFERENCE_RESPONSE"]
OwnershipMethod = Literal["DNS_TXT", "WELL_KNOWN_FILE", "PAGE_CODE", "PROFILE_BIO_CODE"]
Relationship = Literal["MANAGER", "PEER", "REPORT", "CLIENT", "TEACHER", "OTHER"]
ReferenceAnswerValue = Literal["CONFIRM", "PARTLY", "CANNOT_CONFIRM", "DISAGREE"]

MAX_EXCERPT = 1000
MAX_SNAPSHOT = 262144
MAX_PROBE = 500
MAX_CLAIMS = 200


class CamelModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="ignore")


# ----------------------------------------------------------------------------------------------- output
class EvidenceItem(CamelModel):
    key: str = Field(max_length=40)
    kind: EvidenceKind
    url: Optional[str] = None
    title: str = Field(default="", max_length=300)
    retrieved_at: str
    content_hash: str
    excerpt: str = Field(default="", max_length=MAX_EXCERPT)
    snapshot_text: str = Field(default="", max_length=MAX_SNAPSHOT)
    attributes: dict[str, Any] = Field(default_factory=dict)


class Reason(CamelModel):
    text: str
    evidence_keys: list[str] = Field(default_factory=list)
    quote: Optional[str] = None


class IntegrityNote(CamelModel):
    code: str
    text: str
    evidence_keys: list[str] = Field(default_factory=list)


class Verdict(CamelModel):
    claim_id: str
    claim_type: ClaimType
    claim_value: str
    verdict: VerdictValue
    ownership_proven: bool
    reasons: list[Reason] = Field(default_factory=list)
    integrity_notes: list[IntegrityNote] = Field(default_factory=list)
    probe_question: Optional[str] = None


class SkillEvidence(CamelModel):
    skill_id: str
    name: str
    evidence_keys: list[str] = Field(default_factory=list)
    repo_count: int = 0


class Usage(CamelModel):
    requests: int = 0
    llm_calls: int = 0
    truncated: bool = False


class VerifyResponse(CamelModel):
    verifier: str
    verifier_version: str
    evidence: list[EvidenceItem] = Field(default_factory=list)
    verdicts: list[Verdict] = Field(default_factory=list)
    summary: list[Reason] = Field(default_factory=list)
    skills: list[SkillEvidence] = Field(default_factory=list)
    usage: Usage = Field(default_factory=Usage)
    warnings: list[str] = Field(default_factory=list)


class OwnershipCheckResponse(CamelModel):
    proven: bool
    method: OwnershipMethod
    detail: str
    evidence: Optional[EvidenceItem] = None


# ------------------------------------------------------------------------------------------------ input
class ClaimIn(CamelModel):
    id: str = Field(min_length=1, max_length=80)
    type: ClaimType
    value: str = Field(min_length=1, max_length=1000)


LOGIN_PATTERN = r"^[A-Za-z0-9](?:[A-Za-z0-9_.-]{0,98}[A-Za-z0-9_])?$"


class CodeLimits(CamelModel):
    max_repos: int = Field(default=30, ge=1, le=100)
    max_requests: int = Field(default=150, ge=1, le=500)


class CodeVerifyRequest(CamelModel):
    provider: Literal["github", "gitlab"]
    login: str = Field(pattern=LOGIN_PATTERN)
    access_token: Optional[str] = Field(default=None, max_length=500, repr=False)
    ownership_proven: bool = False
    claims: list[ClaimIn] = Field(default_factory=list, max_length=MAX_CLAIMS)
    limits: CodeLimits = Field(default_factory=CodeLimits)


class WebLimits(CamelModel):
    max_pages: int = Field(default=8, ge=1, le=25)
    max_bytes: int = Field(default=2_000_000, ge=10_000, le=10_000_000)


class WebVerifyRequest(CamelModel):
    url: str = Field(min_length=4, max_length=2000)
    ownership_proven: bool = False
    claims: list[ClaimIn] = Field(default_factory=list, max_length=MAX_CLAIMS)
    limits: WebLimits = Field(default_factory=WebLimits)
    holder_name: Optional[str] = Field(default=None, max_length=200)  # optional extension (see WP-AG notes)


class DocumentVerifyRequest(CamelModel):
    claims: list[ClaimIn] = Field(default_factory=list, max_length=MAX_CLAIMS)
    holder_name: Optional[str] = Field(default=None, max_length=200)


class BadgeVerifyRequest(CamelModel):
    credential: Optional[Union[str, dict[str, Any]]] = None
    url: Optional[str] = Field(default=None, max_length=2000)
    claims: list[ClaimIn] = Field(default_factory=list, max_length=MAX_CLAIMS)
    holder_name: Optional[str] = Field(default=None, max_length=200)
    holder_email: Optional[str] = Field(default=None, max_length=320)


class Referee(CamelModel):
    relationship: Relationship
    email_domain: str = Field(min_length=1, max_length=253)
    employer_domain: Optional[str] = Field(default=None, max_length=253)


class ReferenceAnswer(CamelModel):
    claim_id: str = Field(min_length=1, max_length=80)
    answer: ReferenceAnswerValue
    comment: Optional[str] = Field(default=None, max_length=2000)


class ReferenceVerifyRequest(CamelModel):
    claims: list[ClaimIn] = Field(default_factory=list, max_length=MAX_CLAIMS)
    referee: Referee
    answers: list[ReferenceAnswer] = Field(default_factory=list, max_length=MAX_CLAIMS)
    holder_name: Optional[str] = Field(default=None, max_length=200)  # optional extension: de-identifies comments


class OwnershipCheckRequest(CamelModel):
    method: OwnershipMethod
    target: str = Field(min_length=3, max_length=2000)
    # A short or empty code would match almost anything, so the code must look like a random challenge.
    code: str = Field(min_length=8, max_length=64, pattern=r"^[A-Za-z0-9_-]+$")
