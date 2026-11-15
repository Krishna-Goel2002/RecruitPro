"""The shared verdict layer: every verifier's draft verdicts pass through ``finalize`` and nowhere else.

Rules applied here, in this order, for all verifiers:

1. **Injection screen.** Every evidence item's snapshot text is scanned for prompt-injection markers; matches add an
   integrity note ``INSTRUCTION_TEXT`` (the text stays data and never changes a verdict).
2. **Ownership cap.** ``ownershipProven = false`` => the verdict is at most ``PARTLY_VERIFIED`` (a ``VERIFIED`` draft is
   lowered and a reason says why). ``CONTRADICTED``, ``NOT_FOUND`` and ``NOT_CHECKABLE`` are not raised or lowered.
3. **Source notes.** Source-level integrity notes are attached to every verdict of the response.
4. **Probe questions.** ``PARTLY_VERIFIED``, ``NOT_CHECKABLE`` and ``CONTRADICTED`` get a neutral, template-based
   structured-interview question about the claim (no identity; <= 500 chars).
5. **De-identification** of reason texts and quotes, note texts and probe questions.
6. **Summary.** With an LLM configured, a cited narrative is requested and passed through the citation validator.
"""
from __future__ import annotations

import logging
from dataclasses import dataclass, field
from typing import TYPE_CHECKING

from app.citations import validate_summary
from app.core.config import VERIFIER_VERSIONS
from app.deidentify import Identities, deidentify
from app.injection import find_markers
from app.models import (MAX_PROBE, ClaimIn, EvidenceItem, IntegrityNote, Reason, SkillEvidence, Usage, Verdict,
                        VerdictValue, VerifyResponse)

if TYPE_CHECKING:
    from app.llm.summary import SummaryWriter

logger = logging.getLogger(__name__)

RANK = {"NOT_CHECKABLE": 0, "NOT_FOUND": 0, "CONTRADICTED": 0, "PARTLY_VERIFIED": 1, "VERIFIED": 2}
CAP_REASON = "Ownership of this source is not proven, so the result is capped at partly verified."
INSTRUCTION_TEXT = "INSTRUCTION_TEXT"

_PROBES: dict[str, str] = {
    "SKILL": "Walk me through a recent piece of work where you used {value}. What was your part, what decisions did "
             "you make, and what would you do differently?",
    "CERTIFICATION": "Tell me about {value}: how did you prepare for it, and where have you applied what it covers?",
    "EDUCATION": "Tell me about {value}: which course or project from it is most relevant to this role, and why?",
    "EXPERIENCE": "Describe your responsibilities in {value} and one outcome you were accountable for.",
    "OTHER": "Can you tell me more about {value} and how it relates to this role?",
}
_CONTRADICTED_PROBE = "Some sources describe {value} differently from your application. Can you walk me through the " \
                      "details as you see them?"


@dataclass
class DraftVerdict:
    """A verifier's evidence-only verdict for one claim (before the shared rules)."""
    claim: ClaimIn
    verdict: VerdictValue
    reasons: list[Reason] = field(default_factory=list)
    notes: list[IntegrityNote] = field(default_factory=list)
    ownership_proven: bool | None = None  # per-claim override; None = the output's ownership_proven


@dataclass
class VerifierOutput:
    verifier: str
    evidence: list[EvidenceItem]
    drafts: list[DraftVerdict]
    ownership_proven: bool
    identities: Identities = field(default_factory=Identities)
    skills: list[SkillEvidence] = field(default_factory=list)
    notes: list[IntegrityNote] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
    usage: Usage = field(default_factory=Usage)


def apply_ownership_cap(verdict: VerdictValue, ownership_proven: bool) -> VerdictValue:
    """The single ownership rule: without proven ownership a verdict is at most PARTLY_VERIFIED."""
    if not ownership_proven and verdict == "VERIFIED":
        return "PARTLY_VERIFIED"
    return verdict


def probe_question(claim: ClaimIn, verdict: str) -> str | None:
    if verdict not in {"PARTLY_VERIFIED", "NOT_CHECKABLE", "CONTRADICTED"}:
        return None
    value = " ".join(claim.value.split())
    value = value if len(value) <= 200 else value[:199].rstrip() + "…"
    template = _CONTRADICTED_PROBE if verdict == "CONTRADICTED" else _PROBES.get(claim.type, _PROBES["OTHER"])
    return template.format(value=value)[:MAX_PROBE]


def injection_notes(evidence: list[EvidenceItem]) -> list[IntegrityNote]:
    flagged: dict[str, list[str]] = {}
    for item in evidence:
        kinds = find_markers(item.snapshot_text)
        if kinds:
            flagged[item.key] = kinds
    if not flagged:
        return []
    kinds = sorted({kind for values in flagged.values() for kind in values})
    return [IntegrityNote(code=INSTRUCTION_TEXT, evidence_keys=list(flagged),
                          text="Fetched content contains text addressed to an automated reviewer ("
                               + ", ".join(kinds) + "). It was treated as data and did not change any verdict.")]


def _merge_notes(*groups: list[IntegrityNote]) -> list[IntegrityNote]:
    seen: set[tuple[str, tuple[str, ...], str]] = set()
    merged = []
    for group in groups:
        for note in group:
            key = (note.code, tuple(note.evidence_keys), note.text)
            if key not in seen:
                seen.add(key)
                merged.append(note)
    return merged


def _clean_reason(reason: Reason, known: set[str], identities: Identities) -> Reason:
    return Reason(text=deidentify(reason.text, identities) or "",
                  evidence_keys=[key for key in reason.evidence_keys if key in known],
                  quote=deidentify(reason.quote, identities))


def finalize(output: VerifierOutput, *, summary_writer: "SummaryWriter | None" = None) -> VerifyResponse:
    """Apply the shared rules to a verifier's output and build the contract response."""
    known = {item.key for item in output.evidence}
    ids = output.identities
    source_notes = _merge_notes(injection_notes(output.evidence), output.notes)
    verdicts: list[Verdict] = []
    for draft in output.drafts:
        owned = output.ownership_proven if draft.ownership_proven is None else draft.ownership_proven
        value = apply_ownership_cap(draft.verdict, owned)
        reasons = list(draft.reasons)
        if value != draft.verdict:
            reasons.append(Reason(text=CAP_REASON, evidence_keys=[], quote=None))
        notes = _merge_notes(draft.notes, source_notes)
        verdicts.append(Verdict(
            claim_id=draft.claim.id, claim_type=draft.claim.type, claim_value=draft.claim.value, verdict=value,
            ownership_proven=owned,
            reasons=[_clean_reason(reason, known, ids) for reason in reasons],
            integrity_notes=[IntegrityNote(code=note.code, text=deidentify(note.text, ids) or "",
                                           evidence_keys=[key for key in note.evidence_keys if key in known])
                             for note in notes],
            probe_question=deidentify(probe_question(draft.claim, value), ids),
        ))
    warnings = list(dict.fromkeys(output.warnings))
    usage = output.usage.model_copy()
    summary: list[Reason] = []
    if summary_writer is not None and output.evidence and verdicts:
        raw = summary_writer.write(output.verifier, verdicts, output.evidence, ids)
        usage.llm_calls += summary_writer.calls
        if raw is None:
            warnings.append("LLM_SUMMARY_UNAVAILABLE")
        else:
            summary, dropped = validate_summary(raw, output.evidence, ids)
            if dropped:
                warnings.append(f"LLM_SUMMARY_DROPPED:{dropped}")
    return VerifyResponse(verifier=output.verifier, verifier_version=VERIFIER_VERSIONS[output.verifier],
                          evidence=output.evidence, verdicts=verdicts, summary=summary, skills=output.skills,
                          usage=usage, warnings=warnings)

