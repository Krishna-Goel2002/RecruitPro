"""Reference verifier (deterministic; no network).

Per claim, the referee's answer decides:
* ``CONFIRM`` from a ``MANAGER``, ``PEER`` or ``CLIENT`` whose e-mail domain equals the employer domain (or is a
  subdomain of it) -> ``VERIFIED`` with ``ownershipProven = true`` (an independent anchor);
* ``CONFIRM`` otherwise -> ``PARTLY_VERIFIED`` (``ownershipProven = false``);
* ``PARTLY`` -> ``PARTLY_VERIFIED``; ``CANNOT_CONFIRM`` -> ``NOT_FOUND``; ``DISAGREE`` -> ``CONTRADICTED`` (the reason
  quotes the comment); no answer for a claim -> ``NOT_FOUND``.
Free e-mail domains (gmail.com etc.) never count as employer domains, on either side.
"""
from __future__ import annotations

from datetime import datetime

from app.deidentify import Identities
from app.evidence import make_item
from app.models import EvidenceItem, Reason, ReferenceVerifyRequest, Usage
from app.verdicts import DraftVerdict, VerifierOutput

FREE_MAIL_DOMAINS = frozenset({
    "gmail.com", "googlemail.com", "yahoo.com", "yahoo.co.in", "yahoo.co.uk", "ymail.com", "rocketmail.com",
    "hotmail.com", "hotmail.co.uk", "outlook.com", "outlook.in", "live.com", "msn.com", "aol.com", "icloud.com",
    "me.com", "mac.com", "proton.me", "protonmail.com", "pm.me", "gmx.com", "gmx.net", "gmx.de", "web.de",
    "mail.com", "zoho.com", "zohomail.com", "yandex.com", "yandex.ru", "mail.ru", "rediffmail.com", "qq.com",
    "163.com", "126.com", "tutanota.com", "tuta.io", "fastmail.com", "hey.com", "inbox.com", "libero.it",
    "orange.fr", "free.fr", "t-online.de", "btinternet.com", "comcast.net", "verizon.net", "att.net",
})
ANCHOR_RELATIONSHIPS = {"MANAGER", "PEER", "CLIENT"}


def normalise_domain(domain: str | None) -> str:
    value = (domain or "").strip().lower().rstrip(".")
    if "@" in value:
        value = value.rsplit("@", 1)[1]
    return value[4:] if value.startswith("www.") else value


def is_free_mail(domain: str | None) -> bool:
    return normalise_domain(domain) in FREE_MAIL_DOMAINS


def domains_match(email_domain: str | None, employer_domain: str | None) -> bool:
    email, employer = normalise_domain(email_domain), normalise_domain(employer_domain)
    if not email or not employer or "." not in employer or is_free_mail(email) or is_free_mail(employer):
        return False
    return email == employer or email.endswith("." + employer)


def verify_reference(request: ReferenceVerifyRequest, *, now: datetime) -> VerifierOutput:
    referee = request.referee
    anchored = referee.relationship in ANCHOR_RELATIONSHIPS and domains_match(referee.email_domain,
                                                                             referee.employer_domain)
    answers = {answer.claim_id: answer for answer in request.answers}
    evidence: list[EvidenceItem] = []
    drafts: list[DraftVerdict] = []
    warnings = ["FREE_MAIL_REFEREE"] if is_free_mail(referee.email_domain) else []
    for index, claim in enumerate(request.claims, start=1):
        answer = answers.get(claim.id)
        if answer is None:
            drafts.append(DraftVerdict(claim, "NOT_FOUND", [Reason(
                text="The referee did not answer for this claim.", evidence_keys=[], quote=None)],
                ownership_proven=False))
            continue
        key = f"reference-{index}"
        comment = (answer.comment or "").strip()
        evidence.append(make_item(key, "REFERENCE_RESPONSE", url=None, title=f"Reference answer for {claim.type.lower()}",
                                  text="\n".join([f"Claim: {claim.value}", f"Relationship: {referee.relationship}",
                                                  f"Referee e-mail domain: {normalise_domain(referee.email_domain)}",
                                                  f"Employer domain: {normalise_domain(referee.employer_domain) or 'not given'}",
                                                  f"Answer: {answer.answer}", f"Comment: {comment or 'none'}"]),
                                  retrieved_at=now, attributes={"relationship": referee.relationship,
                                                                "answer": answer.answer,
                                                                "domainMatch": anchored}))
        relation = referee.relationship.lower()
        if answer.answer == "CONFIRM" and anchored:
            drafts.append(DraftVerdict(claim, "VERIFIED", [Reason(
                text=f"A referee ({relation}) writing from the employer's e-mail domain confirmed the claim.",
                evidence_keys=[key], quote="Answer: CONFIRM")], ownership_proven=True))
        elif answer.answer == "CONFIRM":
            why = ("the referee's e-mail domain is a free e-mail service" if is_free_mail(referee.email_domain) else
                   "the referee's e-mail domain does not match the employer" if referee.relationship
                   in ANCHOR_RELATIONSHIPS else f"a {relation} reference is not an independent anchor")
            drafts.append(DraftVerdict(claim, "PARTLY_VERIFIED", [Reason(
                text=f"A referee ({relation}) confirmed the claim, but {why}.", evidence_keys=[key],
                quote="Answer: CONFIRM")], ownership_proven=False))
        elif answer.answer == "PARTLY":
            drafts.append(DraftVerdict(claim, "PARTLY_VERIFIED", [Reason(
                text=f"A referee ({relation}) confirmed the claim in part.", evidence_keys=[key],
                quote=comment or "Answer: PARTLY")], ownership_proven=anchored))
        elif answer.answer == "CANNOT_CONFIRM":
            drafts.append(DraftVerdict(claim, "NOT_FOUND", [Reason(
                text=f"A referee ({relation}) could not confirm the claim.", evidence_keys=[key],
                quote="Answer: CANNOT_CONFIRM")], ownership_proven=anchored))
        else:
            drafts.append(DraftVerdict(claim, "CONTRADICTED", [Reason(
                text=f"A referee ({relation}) disagreed with the claim.", evidence_keys=[key],
                quote=comment or "Answer: DISAGREE")], ownership_proven=anchored))
    return VerifierOutput(verifier="reference", evidence=evidence, drafts=drafts, ownership_proven=anchored,
                          identities=Identities(names=[request.holder_name] if request.holder_name else [],
                                                hosts=[normalise_domain(referee.email_domain),
                                                       normalise_domain(referee.employer_domain)]),
                          warnings=warnings, usage=Usage())
