"""Certification name normalisation for token-overlap matching (threshold 0.8)."""
from __future__ import annotations

import re

from app.pipeline.text import content_tokens

# Acronym -> expansion, applied to both sides so "PMP" meets "Project Management Professional".
ACRONYMS = {
    "pmp": "project management professional", "capm": "certified associate in project management",
    "csm": "certified scrum master", "psm": "professional scrum master", "cspo": "certified scrum product owner",
    "cissp": "certified information systems security professional", "cism": "certified information security manager",
    "cisa": "certified information systems auditor", "ceh": "certified ethical hacker",
    "oscp": "offensive security certified professional", "ccna": "cisco certified network associate",
    "ccnp": "cisco certified network professional", "cka": "certified kubernetes administrator",
    "ckad": "certified kubernetes application developer", "cks": "certified kubernetes security specialist",
    "rhce": "red hat certified engineer", "rhcsa": "red hat certified system administrator",
    "ocjp": "oracle certified java professional", "saa": "solutions architect associate",
    "az-900": "azure fundamentals", "az-104": "azure administrator associate", "az-204": "azure developer associate",
    "az-305": "azure solutions architect expert", "dp-900": "azure data fundamentals",
    "ace": "associate cloud engineer", "ca": "chartered accountant", "cpa": "certified public accountant",
    "acca": "association of chartered certified accountants", "cfa": "chartered financial analyst",
    "cma": "certified management accountant", "frm": "financial risk manager", "cia": "certified internal auditor",
    "cs": "company secretary", "shrm-cp": "shrm certified professional", "shrm-scp": "shrm senior certified professional",
    "phr": "professional in human resources", "sphr": "senior professional in human resources",
    "bls": "basic life support", "acls": "advanced cardiac life support", "pals": "pediatric advanced life support",
    "rn": "registered nurse", "tefl": "teaching english as a foreign language",
    "tesol": "teaching english to speakers of other languages", "ctet": "central teacher eligibility test",
    "gaiq": "google analytics individual qualification", "cscp": "certified supply chain professional",
    "cpim": "certified in planning and inventory management", "itil": "itil foundation",
}
_ACRONYM_RE = re.compile(r"(?<![A-Za-z0-9-])(" + "|".join(sorted(map(re.escape, ACRONYMS), key=len, reverse=True)) + r")(?![A-Za-z0-9-])", re.I)
_GENERIC = frozenset(content_tokens("certified certification certificate certifications license licence licensed "
                                    "credential exam passed valid active current course program programme holder"))


def certification_tokens(text: str) -> frozenset[str]:
    expanded = _ACRONYM_RE.sub(lambda match: f" {ACRONYMS[match.group(1).lower()]} ", text)
    return frozenset(content_tokens(expanded) - _GENERIC)


def split_certification_options(text: str) -> list[str]:
    """ "PMP or PRINCE2", "AZ-104 / AZ-305" -> separate options (any one satisfies)."""
    parts = re.split(r"\s+or\s+|\s*/\s*(?=[A-Za-z])", text, flags=re.I)
    return [part.strip() for part in parts if len(part.strip()) >= 2] or [text]
