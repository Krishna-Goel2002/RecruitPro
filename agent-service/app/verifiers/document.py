"""Document verifier (PDF, DOCX, PPTX <= 15 MB): metadata and text, read without rendering or macros.

Ownership of an uploaded file can never be proven, so a document never yields ``VERIFIED``:
* ``PARTLY_VERIFIED`` when the claim is stated in the document text;
* ``NOT_FOUND`` otherwise.

Integrity notes: ``METADATA_AFTER_CLAIM`` (the document was created after the year the claim states),
``AUTHOR_MISMATCH`` (metadata author / last-modified-by shares no name token with ``holderName``), and the shared
``INSTRUCTION_TEXT`` screen. A document without extractable text gets warning ``NO_TEXT`` (scans are not OCR'd here).
"""
from __future__ import annotations

from datetime import datetime

from app.core.clock import parse_instant
from app.deidentify import Identities
from app.evidence import make_item
from app.extract import extract_document
from app.models import DocumentVerifyRequest, IntegrityNote, Reason, Usage
from app.verdicts import DraftVerdict, VerifierOutput
from app.verifiers.common import find_statement, tokens, years

DOCUMENT_KEY = "document-1"


def _snapshot(filename: str, fmt: str, metadata: dict[str, object], text: str) -> str:
    def value(name: str) -> str:
        item = metadata.get(name)
        return str(item) if item else "not set"

    return "\n".join([
        f"File: {filename or 'upload'}",
        f"Format: {fmt.upper()}",
        f"Author: {value('author')}",
        f"Last modified by: {value('lastModifiedBy')}",
        f"Created: {value('created')}",
        f"Modified: {value('modified')}",
        f"Producer: {value('producer')}",
        "",
        "Text:",
        text,
    ])


def _author_mismatch(metadata: dict[str, object], holder_name: str | None) -> str | None:
    if not holder_name:
        return None
    holder = set(tokens(holder_name))
    for field_name in ("author", "lastModifiedBy"):
        author = metadata.get(field_name)
        if isinstance(author, str) and author.strip() and holder and not (set(tokens(author)) & holder):
            return field_name
    return None


def verify_document(content: bytes, filename: str, request: DocumentVerifyRequest, *, now: datetime) -> VerifierOutput:
    document = extract_document(content, filename)
    snapshot = _snapshot(filename, document.format, document.metadata, document.text)
    item = make_item(DOCUMENT_KEY, "DOCUMENT", url=None, title=filename or "Uploaded document", text=snapshot,
                     retrieved_at=now, excerpt=document.text[:1000],
                     attributes={"format": document.format, "metadata": document.metadata, "pages": document.pages})
    warnings = [] if document.text.strip() else ["NO_TEXT"]
    notes: list[IntegrityNote] = []
    mismatch = _author_mismatch(document.metadata, request.holder_name)
    if mismatch:
        notes.append(IntegrityNote(code="AUTHOR_MISMATCH", evidence_keys=[DOCUMENT_KEY], text=(
            "The document's metadata names a different " + ("author" if mismatch == "author" else "last editor")
            + " than the candidate.")))
    created = parse_instant(document.metadata.get("created"))
    drafts = []
    for claim in request.claims:
        statement, _ = find_statement(claim.value, claim.type, document.text)
        claim_notes = []
        claim_years = sorted(int(year) for year in years(claim.value))
        if created and claim_years and created.year > claim_years[-1]:
            claim_notes.append(IntegrityNote(code="METADATA_AFTER_CLAIM", evidence_keys=[DOCUMENT_KEY], text=(
                f"The document was created in {created.year}, after the year the claim states ({claim_years[-1]}).")))
        if statement:
            drafts.append(DraftVerdict(claim, "PARTLY_VERIFIED", [Reason(
                text="The claim is stated in the uploaded document; a document cannot prove who holds it.",
                evidence_keys=[DOCUMENT_KEY], quote=statement.line)], claim_notes))
        else:
            drafts.append(DraftVerdict(claim, "NOT_FOUND", [Reason(
                text="The claim was not found in the uploaded document.", evidence_keys=[DOCUMENT_KEY], quote=None)],
                claim_notes))
    names = [name for name in (request.holder_name, document.metadata.get("author"),
                               document.metadata.get("lastModifiedBy")) if isinstance(name, str) and name.strip()]
    return VerifierOutput(verifier="document", evidence=[item], drafts=drafts, ownership_proven=False,
                          identities=Identities(names=names), notes=notes, warnings=warnings, usage=Usage())
