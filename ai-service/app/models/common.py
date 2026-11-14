from typing import Literal

EvidenceTier = Literal["SELF_CLAIMED", "RESUME_STATED", "CORROBORATED", "DEMONSTRATED", "INTERVIEW_VERIFIED"]
TIER_ORDER: tuple[str, ...] = ("SELF_CLAIMED", "RESUME_STATED", "CORROBORATED", "DEMONSTRATED", "INTERVIEW_VERIFIED")
RequirementType = Literal["SKILL", "EDUCATION", "CERTIFICATION", "EXPERIENCE", "OTHER"]
FactType = Literal["SKILL", "EDUCATION", "EXPERIENCE", "CERTIFICATION", "EMAIL", "PHONE", "LINK", "OTHER"]
Proficiency = Literal["BASIC", "INTERMEDIATE", "ADVANCED", "EXPERT"]
Engine = Literal["rules", "llm"]


def tier_rank(tier: str) -> int:
    return TIER_ORDER.index(tier)
