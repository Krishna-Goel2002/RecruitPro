"""Text and metadata from PDF (pypdf), DOCX (python-docx) and PPTX (python-pptx). Nothing is rendered or executed.

Guards: input size is capped by the caller; OOXML packages are refused when the declared uncompressed size exceeds
200 MB or they hold more than 5000 entries (zip-bomb guard); PDF text is read from at most 200 pages; extracted text
is capped at 262144 characters.
"""
from __future__ import annotations

import io
import zipfile
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any

from app.core.clock import iso_z, parse_instant
from app.models import MAX_SNAPSHOT

MAX_PDF_PAGES = 200
MAX_ZIP_UNCOMPRESSED = 200 * 1024 * 1024
MAX_ZIP_ENTRIES = 5000


class ExtractionError(ValueError):
    """The document cannot be read (code: UNSUPPORTED_DOCUMENT or DOCUMENT_UNREADABLE)."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


@dataclass
class DocumentContent:
    format: str  # pdf | docx | pptx
    text: str
    metadata: dict[str, Any] = field(default_factory=dict)  # author, created, modified, producer, lastModifiedBy
    pages: int | None = None


def sniff_format(content: bytes, filename: str = "") -> str:
    if content.startswith(b"%PDF-"):
        return "pdf"
    if content.startswith(b"PK\x03\x04"):
        try:
            with zipfile.ZipFile(io.BytesIO(content)) as archive:
                names = set(archive.namelist())
        except zipfile.BadZipFile as exc:
            raise ExtractionError("DOCUMENT_UNREADABLE", "broken zip container") from exc
        if "word/document.xml" in names:
            return "docx"
        if "ppt/presentation.xml" in names:
            return "pptx"
    raise ExtractionError("UNSUPPORTED_DOCUMENT", "only PDF, DOCX and PPTX documents are supported")


def _instant(value: Any) -> str | None:
    if isinstance(value, datetime):
        return iso_z(value)
    if isinstance(value, str):
        text = value.strip()
        if text.startswith("D:"):  # PDF date: D:YYYYMMDDHHmmSS
            digits = text[2:16]
            try:
                return iso_z(datetime.strptime(digits[:14].ljust(14, "0"), "%Y%m%d%H%M%S"))
            except ValueError:
                return None
        moment = parse_instant(text)
        return iso_z(moment) if moment else None
    return None


def _check_zip(content: bytes) -> None:
    with zipfile.ZipFile(io.BytesIO(content)) as archive:
        infos = archive.infolist()
        if len(infos) > MAX_ZIP_ENTRIES or sum(info.file_size for info in infos) > MAX_ZIP_UNCOMPRESSED:
            raise ExtractionError("DOCUMENT_UNREADABLE", "document package is too large when unpacked")


def _pdf(content: bytes) -> DocumentContent:
    from pypdf import PdfReader

    try:
        reader = PdfReader(io.BytesIO(content))
        if reader.is_encrypted:
            try:
                reader.decrypt("")
            except Exception as exc:  # noqa: BLE001 - pypdf raises several types for unsupported encryption
                raise ExtractionError("DOCUMENT_UNREADABLE", "encrypted PDF") from exc
        texts: list[str] = []
        total = 0
        for page in reader.pages[:MAX_PDF_PAGES]:
            piece = page.extract_text() or ""
            texts.append(piece)
            total += len(piece)
            if total > MAX_SNAPSHOT:
                break
        info: Any = reader.metadata or {}
        metadata = {
            "author": str(info.get("/Author") or "") or None,
            "created": _instant(info.get("/CreationDate")),
            "modified": _instant(info.get("/ModDate")),
            "producer": str(info.get("/Producer") or "") or None,
            "creator": str(info.get("/Creator") or "") or None,
            "title": str(info.get("/Title") or "") or None,
        }
        return DocumentContent("pdf", "\n".join(texts)[:MAX_SNAPSHOT], metadata, len(reader.pages))
    except ExtractionError:
        raise
    except Exception as exc:  # noqa: BLE001 - malformed PDFs surface many pypdf types (PdfReadError, LimitReachedError,
        # PyPdfError, AttributeError, IndexError, zlib/struct errors, RecursionError ...); none may become a 500
        raise ExtractionError("DOCUMENT_UNREADABLE", "the PDF could not be read") from exc


def _core(properties: Any) -> dict[str, Any]:
    return {
        "author": properties.author or None,
        "created": _instant(properties.created),
        "modified": _instant(properties.modified),
        "lastModifiedBy": properties.last_modified_by or None,
        "title": properties.title or None,
    }


def _docx(content: bytes) -> DocumentContent:
    import docx

    _check_zip(content)
    try:
        document = docx.Document(io.BytesIO(content))
        lines = [paragraph.text for paragraph in document.paragraphs]
        for table in document.tables:
            for row in table.rows:
                lines.append(" | ".join(cell.text for cell in row.cells))
        metadata = _core(document.core_properties)  # parsed lazily: a broken docProps/core.xml fails here
    except Exception as exc:  # noqa: BLE001 - python-docx surfaces lxml/zip/key errors
        raise ExtractionError("DOCUMENT_UNREADABLE", "the DOCX could not be read") from exc
    app_xml = _app_property(content, "docProps/app.xml", "Application")
    metadata["producer"] = app_xml
    return DocumentContent("docx", "\n".join(lines)[:MAX_SNAPSHOT], metadata)


def _pptx(content: bytes) -> DocumentContent:
    from pptx import Presentation

    _check_zip(content)
    try:
        presentation = Presentation(io.BytesIO(content))
        lines: list[str] = []
        for slide in presentation.slides:
            for shape in slide.shapes:
                if getattr(shape, "has_text_frame", False) and shape.has_text_frame:
                    lines.append(shape.text_frame.text)
        metadata = _core(presentation.core_properties)
    except Exception as exc:  # noqa: BLE001 - python-pptx surfaces lxml/zip/key errors, also from lazily read parts
        raise ExtractionError("DOCUMENT_UNREADABLE", "the PPTX could not be read") from exc
    metadata["producer"] = _app_property(content, "docProps/app.xml", "Application")
    return DocumentContent("pptx", "\n".join(lines)[:MAX_SNAPSHOT], metadata, len(presentation.slides))


def _app_property(content: bytes, member: str, tag: str) -> str | None:
    import re

    try:
        with zipfile.ZipFile(io.BytesIO(content)) as archive:
            if member not in archive.namelist() or archive.getinfo(member).file_size > 1_000_000:
                return None
            xml = archive.read(member).decode("utf-8", errors="replace")
    except (zipfile.BadZipFile, KeyError):
        return None
    match = re.search(rf"<{tag}>([^<]{{1,200}})</{tag}>", xml)
    return match.group(1) if match else None


def extract_document(content: bytes, filename: str = "") -> DocumentContent:
    kind = sniff_format(content, filename)
    if kind == "pdf":
        return _pdf(content)
    if kind == "docx":
        return _docx(content)
    return _pptx(content)
