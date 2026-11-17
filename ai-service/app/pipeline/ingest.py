"""Document ingestion: PDF, DOCX and legacy DOC to visible text lines, with layout and hidden-text handling.

* PDF - pdfplumber words with positions. Characters that are white (luminance >= 0.95, unless
  drawn on a dark filled rectangle), smaller than 4 pt, or outside the page are hidden: they are
  excluded and reported as ``HIDDEN_TEXT``. A vertical gutter found by x-coverage clustering marks
  a two-column layout (``TWO_COLUMN_LAYOUT``): a sidebar is read after the whole main text, balanced
  columns band by band, left then right. Label/value tables and right-aligned date/rating columns
  are not columns.
* DOCX - every paragraph in document order (body, tables, text boxes, headers, footers). Runs
  that are hidden (``w:vanish``), white or smaller than 4 pt - directly or through styles - are
  excluded and reported.
* DOC - converted with LibreOffice when ``soffice`` is available (then parsed as DOCX, so hidden
  text is still detected); otherwise the Word 97 piece table is read with ``olefile``. Both report
  ``LEGACY_DOC``.
* Image-only documents (and pages whose text was converted to vector outlines) are OCR'd when
  ``OCR_ENABLED=true`` and rapidocr is installed (``OCR_USED``); otherwise, or when OCR itself fails,
  the result is ``NEEDS_OCR``.
* The format is taken from the file's magic bytes, not its extension: a DOCX renamed to .doc, a PDF
  with leading junk, an RTF (LibreOffice only) or an HTML page saved as .doc are all read. DOCX table
  rows whose cells hold one line each become one line ("Staff Nurse | Apollo | 2018 - 2021"), and
  first-page/even-page headers are read when Word displays them (otherwise they are hidden text).
"""
from __future__ import annotations

import io
import logging
import os
import re
import signal
import statistics
import struct
import subprocess
import tempfile
import zipfile
from dataclasses import dataclass, field
from html.parser import HTMLParser
from pathlib import Path
from typing import Any, Iterator

import olefile
import pdfplumber
from docx import Document

from app.core.config import Settings
from app.pipeline import ocr
from app.pipeline.text import Flag, RawLine

logger = logging.getLogger(__name__)

MAX_FILE_BYTES = 10 * 1024 * 1024
MAX_UNCOMPRESSED_DOCX_BYTES = 100 * 1024 * 1024
MAX_DOCX_ENTRIES = 1000
MAX_PDF_PAGES = 100
MAX_TEXT_CHARACTERS = 1_000_000
MIN_TEXT_CHARACTERS = 80
LOW_DENSITY_CHARS_PER_PAGE = 200
HIDDEN_LUMINANCE = 0.95
HIDDEN_FONT_SIZE = 4.0
SUPPORTED_EXTENSIONS = ("pdf", "docx", "doc")
# A page whose only "text" is many vector paths (text converted to outlines, e.g. design-tool exports)
# looks empty to a text extractor; it needs OCR like a scan.
VECTOR_TEXT_PATHS = 150
LIBREOFFICE_MAX_SECONDS = 60
_OLE_MAGIC = bytes.fromhex("D0CF11E0A1B11AE1")

_W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
_MC_FALLBACK = "{http://schemas.openxmlformats.org/markup-compatibility/2006}Fallback"
_RUN_CONTAINERS = {f"{_W}{tag}" for tag in ("hyperlink", "smartTag", "ins", "sdt", "sdtContent", "customXml", "fldSimple", "moveTo")}


class DocumentParseError(Exception):
    def __init__(self, code: str, status: str = "FAILED") -> None:
        self.code = code
        self.status = status
        super().__init__(code)


@dataclass
class IngestResult:
    kind: str
    lines: list[RawLine] = field(default_factory=list)
    hidden_text: str = ""
    flags: list[Flag] = field(default_factory=list)
    has_images: bool = False
    needs_ocr: bool = False

    @property
    def text_length(self) -> int:
        return sum(len(line.text.strip()) for line in self.lines)


def extension_of(filename: str) -> str:
    return filename.rsplit(".", 1)[-1].lower() if "." in filename else ""


def sniff_format(content: bytes) -> str | None:
    """The real format from the file's magic bytes: pdf, docx (any ZIP), doc (OLE2), rtf, html or None.

    Candidates routinely upload a DOCX renamed to .doc, an RTF or HTML file saved as .doc by an old
    word processor, or a PDF with a byte-order mark or junk before ``%PDF-`` (readers accept the
    header anywhere in the first 1024 bytes)."""
    head = content[:1024]
    if content.startswith(b"PK\x03\x04"):
        return "docx"
    if content.startswith(_OLE_MAGIC):
        return "doc"
    if b"%PDF-" in head:
        return "pdf"
    stripped = head.lstrip(b"\xef\xbb\xbf\r\n\t ").lower()
    if stripped.startswith(b"{\\rtf"):
        return "rtf"
    if stripped.startswith((b"<html", b"<!doctype html", b"mime-version:")) or b"<html" in stripped[:400]:
        return "html"
    return None


def ingest(filename: str, content: bytes, settings: Settings, ocr_engine: ocr.OcrEngine | None = None) -> IngestResult:
    extension = extension_of(filename)
    if extension not in SUPPORTED_EXTENSIONS:
        raise DocumentParseError("UNSUPPORTED_FORMAT")
    if not content:
        raise DocumentParseError("EMPTY_DOCUMENT")
    if len(content) > MAX_FILE_BYTES:
        raise DocumentParseError("FILE_TOO_LARGE")
    engine = ocr_engine or (ocr.get_engine() if settings.ocr_enabled and ocr.ocr_installed() else None)
    kind = sniff_format(content) or extension
    if kind == "pdf":
        result = _read_pdf(content, engine)
    elif kind == "docx":
        result = _read_docx(content, engine)
    elif kind == "doc":
        result = _read_doc(content, settings, engine)
    elif kind == "html":  # a web page saved as ".doc"; read directly (LibreOffice would fetch linked resources)
        result = _read_html(content)
    else:  # RTF (usually a ".doc" saved by another word processor): only LibreOffice reads it
        result = _read_via_libreoffice(content, kind, settings, engine)
    if result.text_length > MAX_TEXT_CHARACTERS:
        raise DocumentParseError("TEXT_LIMIT_EXCEEDED")
    if result.text_length == 0 and not result.has_images and not result.needs_ocr:
        raise DocumentParseError("EMPTY_DOCUMENT")
    return result


# ---------------------------------------------------------------------------------------- PDF
def _luminance(color: Any) -> float | None:
    if color is None or isinstance(color, str):
        return None
    values = [float(value) for value in (color if isinstance(color, (list, tuple)) else (color,)) if isinstance(value, (int, float))]
    if len(values) == 1:
        return values[0]
    if len(values) == 3:
        red, green, blue = values
        return 0.2126 * red + 0.7152 * green + 0.0722 * blue
    if len(values) == 4:
        cyan, magenta, yellow, black = values
        red, green, blue = ((1 - cyan) * (1 - black), (1 - magenta) * (1 - black), (1 - yellow) * (1 - black))
        return 0.2126 * red + 0.7152 * green + 0.0722 * blue
    return None


def _hidden_char_ids(page: Any) -> tuple[set[int], str]:
    x0, top, x1, bottom = page.bbox
    dark_fills = [
        rect for rect in page.rects
        if rect.get("fill") and (_luminance(rect.get("non_stroking_color")) or 0.0) < 0.5
    ][:200]
    hidden: set[int] = set()
    hidden_chars: list[str] = []
    previous: dict[str, Any] | None = None
    last_char: dict[str, Any] | None = None  # the previous character, visible or not
    overflowing = False
    for char in page.chars:
        lum = _luminance(char.get("non_stroking_color"))
        off_page = char["x1"] <= x0 or char["x0"] >= x1 or char["bottom"] <= top or char["top"] >= bottom
        # A long URL or line that simply runs past the right edge is a layout accident, not hidden text:
        # it is excluded from analysis without a HIDDEN_TEXT flag.
        continues_run = last_char is not None and abs(char["top"] - last_char["top"]) <= 2 \
            and -1.5 <= char["x0"] - last_char["x1"] <= max(2.0, 0.6 * float(char.get("size") or 10)) \
            and (last_char["x1"] > x0 or overflowing)
        overflowing = off_page and char["x0"] >= x1 - 1 and continues_run and (overflowing or last_char["x0"] < x1)
        last_char = char
        if overflowing:
            hidden.add(id(char))
            continue
        tiny = float(char.get("size") or 0) < HIDDEN_FONT_SIZE
        white = lum is not None and lum >= HIDDEN_LUMINANCE
        if white:
            cx, cy = (char["x0"] + char["x1"]) / 2, (char["top"] + char["bottom"]) / 2
            white = not any(r["x0"] <= cx <= r["x1"] and r["top"] <= cy <= r["bottom"] for r in dark_fills)
        if off_page or tiny or white:
            hidden.add(id(char))
            if previous is not None and (abs(char["top"] - previous["top"]) > 2 or abs(char["x0"] - previous["x1"]) > 1.5):
                hidden_chars.append(" ")  # a new text run or line
            hidden_chars.append(char.get("text", ""))
            previous = char
    return hidden, "".join(hidden_chars)


def _is_bold(fontname: str) -> bool:
    return bool(re.search(r"bold|black|heavy|semibold|demi", fontname or "", re.I))


def _group_rows(words: list[dict[str, Any]]) -> list[list[dict[str, Any]]]:
    rows: list[list[dict[str, Any]]] = []
    for word in sorted(words, key=lambda item: (item["top"], item["x0"])):
        if rows and abs(rows[-1][0]["top"] - word["top"]) <= 3:
            rows[-1].append(word)
        else:
            rows.append([word])
    return [sorted(row, key=lambda item: item["x0"]) for row in rows]


def _column_gap(words: list[dict[str, Any]], width: float) -> tuple[float, float] | None:
    """The (left, right) x-bounds of a vertical gutter separating two text columns, if there is one."""
    if len(words) < 30:
        return None
    bins = int(width // 2) + 1
    coverage = [0] * bins
    for word in words:
        for index in range(max(0, int(word["x0"] // 2)), min(bins, int(word["x1"] // 2) + 1)):
            coverage[index] += 1
    low, high = int(width * 0.2 // 2), int(width * 0.8 // 2)
    allowed = max(3, len(_group_rows(words)) // 15)
    runs: list[tuple[int, int]] = []
    start: int | None = None
    for index in range(low, high + 1):
        if index < high and coverage[index] <= allowed:
            start = index if start is None else start
            continue
        if start is not None and (index - start) * 2 >= 12:
            runs.append((start, index))
        start = None
    minimum = max(12, int(len(words) * 0.06))
    # Try every sufficiently wide low-coverage band, widest first: the widest one is not always the
    # gutter (e.g. a ragged right edge), and the choice must not hinge on how long the header is.
    for first, last in sorted(runs, key=lambda run: run[0] - run[1]):
        gap = (first * 2.0, last * 2.0)
        centre = sum(gap) / 2
        left = [word for word in words if (word["x0"] + word["x1"]) / 2 < centre]
        right = [word for word in words if (word["x0"] + word["x1"]) / 2 >= centre]
        left_rows, right_rows = _group_rows(left), _group_rows(right)
        if len(left) < minimum or len(right) < minimum or len(left_rows) < 4 or len(right_rows) < 4:
            continue
        if _key_value_table(left_rows, right_rows):
            continue
        # A real right column is left-aligned; words past a ragged edge start anywhere.
        starts = [row[0]["x0"] for row in right_rows]
        margin = statistics.median(starts)
        if sum(1 for x in starts if abs(x - margin) <= 8) / len(starts) >= 0.6:
            return gap
    return None


# Cells that annotate the row they sit on: dates ("Dec-2025 - Till date", "2019 - 2021"), rating glyphs, scores.
_ANNOTATION_RE = re.compile(r"(?:19|20)\d{2}|\b(?:present|till\s+date|current|now|ongoing)\b|[●○◯★☆■□◆◇]{2,}|\d+(?:\.\d+)?\s*%|"
                            r"\b\d+(?:\.\d+)?\s*/\s*(?:10|5|4)\b", re.I)


def _key_value_table(left_rows: list[list[dict[str, Any]]], right_rows: list[list[dict[str, Any]]]) -> bool:
    """Two "columns" that are really one table: label/value rows ("Father's Name   : ...") or rows with a
    right-aligned annotation (dates, rating dots, scores) next to a title or a skill.

    Columns of a two-column page usually have independent baselines; the cells of a table row share one.
    Shared baselines alone are not enough (two columns with the same leading line up too), so the rows
    must also read as label/value pairs: a colon at the boundary on at least half of them."""
    fewer, other = (left_rows, right_rows) if len(left_rows) <= len(right_rows) else (right_rows, left_rows)
    tops = [row[0]["top"] for row in other]
    aligned = sum(1 for row in fewer if any(abs(row[0]["top"] - top) <= 2.0 for top in tops))
    if aligned < 4 or aligned / len(fewer) < 0.75:
        return False
    annotations = sum(1 for row in fewer if _ANNOTATION_RE.search(" ".join(word["text"] for word in row)) and len(row) <= 6)
    if annotations / len(fewer) >= 0.6:
        return True  # a right-aligned date or rating column next to role titles / skills, not a second column
    right_tops = {round(row[0]["top"]): row for row in right_rows}
    colons = sum(1 for row in left_rows
                 if row[-1]["text"].endswith(":") or any(abs(top - row[0]["top"]) <= 2.0 and right[0]["text"].startswith(":")
                                                         for top, right in right_tops.items()))
    return colons / len(left_rows) >= 0.5


def _row_to_line(row: list[dict[str, Any]], body_size: float, reference: str) -> RawLine:
    parts: list[str] = []
    previous: dict[str, Any] | None = None
    for word in row:
        if previous is not None:
            gap = word["x0"] - previous["x1"]
            parts.append(" | " if gap > max(12.0, 2.5 * float(word.get("size") or 10)) else " ")
        parts.append(word["text"])
        previous = word
    size = statistics.median(float(word.get("size") or body_size) for word in row)
    bold = all(_is_bold(word.get("fontname", "")) for word in row)
    heading = size >= body_size * 1.15 or (bold and len(row) <= 6)
    return RawLine("".join(parts), reference, heading, f"{round(size * 2) / 2}:{'b' if bold else 'r'}")


def _segments(row: list[dict[str, Any]]) -> list[list[dict[str, Any]]]:
    """Split a row at jumps wider than a normal word space (text from different columns on one baseline)."""
    segments: list[list[dict[str, Any]]] = [[row[0]]]
    for previous, word in zip(row, row[1:]):
        size = float(word.get("size") or 10)
        if word["x0"] - previous["x1"] > max(5.0, 0.8 * size):
            segments.append([])
        segments[-1].append(word)
    return segments


def _layout_lines(words: list[dict[str, Any]], width: float, reference: str,
                  body_size: float) -> tuple[list[RawLine], bool, list[RawLine]]:
    """(main lines, two-column?, sidebar lines).

    In a sidebar layout (one column clearly narrower than the other) the main column is read first and
    the sidebar is returned separately, so the caller can put every page's sidebar after the whole main
    text: the name and headline at the top of the main column stay in the header block, and a role that
    continues on the next page is not split by the sidebar's sections. Balanced columns are read left
    then right, band by band."""
    gap = _column_gap(words, width)
    if gap is None:
        return [_row_to_line(row, body_size, reference) for row in _group_rows(words)], False, []
    gap_left, gap_right = gap
    centre = (gap_left + gap_right) / 2
    left_extent = gap_left - min(word["x0"] for word in words)
    right_extent = max(word["x1"] for word in words) - gap_right
    sidebar = "left" if left_extent < 0.6 * right_extent else "right" if right_extent < 0.6 * left_extent else None
    lines: list[RawLine] = []
    side_lines: list[RawLine] = []
    left: list[dict[str, Any]] = []
    right: list[dict[str, Any]] = []

    def flush() -> None:
        left_lines = [_row_to_line(row, body_size, reference) for row in _group_rows(left)]
        right_lines = [_row_to_line(row, body_size, reference) for row in _group_rows(right)]
        if sidebar == "left":
            side_lines.extend(left_lines)
            lines.extend(right_lines)
        elif sidebar == "right":
            lines.extend(left_lines)
            side_lines.extend(right_lines)
        else:
            lines.extend(left_lines + right_lines)
        left.clear()
        right.clear()

    def full_width(segment: list[dict[str, Any]]) -> bool:
        # Runs through the gutter and well into the right column (a name or contact line above the
        # columns); a sidebar heading that merely overflows into the gutter does not.
        # It must start before the gutter: a main-column title merely outdented into the gutter is not one.
        return segment[0]["x0"] < gap_left + 0.25 * (gap_right - gap_left) and segment[-1]["x1"] > gap_right + 20 \
            and any(word["x1"] > gap_left + 2 and word["x0"] < gap_right - 2 for word in segment)

    for row in _group_rows(words):
        segments = _segments(row)
        wide = [segment for segment in segments if full_width(segment)]
        if wide:
            flush()
            lines.extend(_row_to_line(segment, body_size, reference) for segment in wide)
        for segment in segments:
            if not any(segment is item for item in wide):
                for word in segment:
                    (left if (word["x0"] + word["x1"]) / 2 < centre else right).append(word)
    flush()
    return lines, True, side_lines


def _read_pdf(content: bytes, engine: ocr.OcrEngine | None) -> IngestResult:
    if b"%PDF-" not in content[:1024]:
        raise DocumentParseError("MALFORMED_PDF")
    result = IngestResult("pdf")
    hidden_parts: list[str] = []
    two_column_pages: list[int] = []
    sidebar_lines: list[RawLine] = []
    try:
        with pdfplumber.open(io.BytesIO(content)) as pdf:
            if not pdf.pages:
                raise DocumentParseError("EMPTY_DOCUMENT")
            if len(pdf.pages) > MAX_PDF_PAGES:
                raise DocumentParseError("FILE_TOO_COMPLEX")
            characters = 0
            vector_paths = 0
            for number, page in enumerate(pdf.pages, start=1):
                result.has_images = result.has_images or bool(page.images)
                if vector_paths < VECTOR_TEXT_PATHS:
                    vector_paths += len(page.curves)
                hidden_ids, hidden_text = _hidden_char_ids(page)
                if hidden_text.strip():
                    hidden_parts.append(hidden_text)
                visible = page.filter(lambda obj, ids=hidden_ids: obj.get("object_type") != "char" or id(obj) not in ids)
                words = visible.extract_words(x_tolerance=1.5, y_tolerance=2.5, keep_blank_chars=False,
                                              use_text_flow=False, extra_attrs=["size", "fontname"])
                if not words:
                    continue
                body_size = statistics.median(float(word.get("size") or 10) for word in words)
                lines, two_column, side = _layout_lines(words, float(page.width), f"page:{number}", body_size)
                if two_column:
                    two_column_pages.append(number)
                result.lines.extend(lines)
                sidebar_lines.extend(side)
                characters += sum(len(word["text"]) for word in words)
                if characters > MAX_TEXT_CHARACTERS:
                    raise DocumentParseError("TEXT_LIMIT_EXCEEDED")
            result.lines.extend(sidebar_lines)
            if result.text_length < MIN_TEXT_CHARACTERS and (result.has_images or vector_paths >= VECTOR_TEXT_PATHS):
                recognised = _run_ocr(lambda: ocr.ocr_pdf_pages(pdf, engine, MAX_PDF_PAGES)) if engine is not None else None
                if recognised is not None:
                    result.lines = recognised
                    result.flags.append(Flag("OCR_USED", "Text was recognised from page images with OCR; check evidence carefully."))
                else:
                    result.needs_ocr = True
            elif result.has_images and result.text_length / len(pdf.pages) < LOW_DENSITY_CHARS_PER_PAGE:
                result.flags.append(Flag("LOW_TEXT_DENSITY", "Very little selectable text per page; parts of the resume may be images."))
    except DocumentParseError:
        raise
    except Exception as exc:  # pdfminer raises many exception types for damaged or encrypted files
        detail = f"{type(exc).__name__} {exc!r} {exc.__cause__!r}".lower()
        raise DocumentParseError("ENCRYPTED_PDF" if "password" in detail or "encrypt" in detail else "MALFORMED_PDF") from None
    result.hidden_text = " ".join(hidden_parts)
    _add_hidden_flag(result)
    if two_column_pages:
        result.flags.append(Flag("TWO_COLUMN_LAYOUT", f"Two-column layout detected on page(s) {', '.join(map(str, two_column_pages))}; "
                                                      "columns were read separately (main column first when one is a sidebar)."))
    return result


def _run_ocr(recognise: Any) -> list[RawLine] | None:
    """Run an OCR callable; an OCR failure (bad image data, engine error) means "needs OCR", never a crash."""
    try:
        return list(recognise())
    except Exception:  # noqa: BLE001 - the optional OCR stack raises arbitrary errors on odd images
        logger.warning("OCR failed; the document is reported as needing OCR", exc_info=True)
        return None


def _add_hidden_flag(result: IngestResult) -> None:
    hidden = " ".join(result.hidden_text.split())
    if hidden:
        result.flags.append(Flag("HIDDEN_TEXT", f"{len(hidden)} characters of hidden text (white, tiny, off-page or "
                                                "hidden formatting) were excluded from analysis."))


# --------------------------------------------------------------------------------------- DOCX
class _StyleResolver:
    """Effective run properties through direct formatting, character style, paragraph style and defaults."""

    def __init__(self, document: Any) -> None:
        self._styles: dict[str, tuple[str | None, Any]] = {}
        self._names: dict[str, str] = {}
        self._default_rpr = None
        root = document.styles.element
        for style in root.iter(f"{_W}style"):
            style_id = style.get(f"{_W}styleId")
            based = style.find(f"{_W}basedOn")
            name = style.find(f"{_W}name")
            self._styles[style_id] = (based.get(f"{_W}val") if based is not None else None, style.find(f"{_W}rPr"))
            self._names[style_id] = (name.get(f"{_W}val") if name is not None else style_id) or ""
        defaults = root.find(f"{_W}docDefaults")
        if defaults is not None:
            self._default_rpr = defaults.find(f"{_W}rPrDefault/{_W}rPr")

    def name(self, style_id: str | None) -> str:
        return self._names.get(style_id or "", style_id or "")

    def _chain(self, style_id: str | None) -> Iterator[Any]:
        seen: set[str] = set()
        while style_id and style_id in self._styles and style_id not in seen:
            seen.add(style_id)
            based, rpr = self._styles[style_id]
            if rpr is not None:
                yield rpr
            style_id = based

    def run_properties(self, run: Any, paragraph_style: str | None) -> list[Any]:
        chain: list[Any] = []
        rpr = run.find(f"{_W}rPr")
        if rpr is not None:
            chain.append(rpr)
            run_style = rpr.find(f"{_W}rStyle")
            if run_style is not None:
                chain.extend(self._chain(run_style.get(f"{_W}val")))
        chain.extend(self._chain(paragraph_style))
        if self._default_rpr is not None:
            chain.append(self._default_rpr)
        return chain


def _toggle(chain: list[Any], tag: str) -> bool:
    for rpr in chain:
        element = rpr.find(f"{_W}{tag}")
        if element is not None:
            return element.get(f"{_W}val", "true").lower() not in {"0", "false", "off"}
    return False


def _value(chain: list[Any], tag: str) -> str | None:
    for rpr in chain:
        element = rpr.find(f"{_W}{tag}")
        if element is not None and element.get(f"{_W}val") is not None:
            return element.get(f"{_W}val")
    return None


def _hex_luminance(value: str | None) -> float | None:
    if not value or not re.fullmatch(r"[0-9A-Fa-f]{6}", value):
        return None
    red, green, blue = (int(value[index:index + 2], 16) / 255 for index in (0, 2, 4))
    return 0.2126 * red + 0.7152 * green + 0.0722 * blue


def _dark_shading(element: Any) -> bool:
    if element is None:
        return False
    shading = element.find(f"{_W}shd")
    lum = _hex_luminance(shading.get(f"{_W}fill")) if shading is not None else None
    return lum is not None and lum < 0.5


def _iter_runs(element: Any) -> Iterator[Any]:
    for child in element:
        if child.tag == f"{_W}r":
            yield child
        elif child.tag in _RUN_CONTAINERS:
            yield from _iter_runs(child)


def _paragraph_lines(paragraph: Any, styles: _StyleResolver) -> tuple[list[RawLine], str]:
    ppr = paragraph.find(f"{_W}pPr")
    style_id = None
    if ppr is not None and ppr.find(f"{_W}pStyle") is not None:
        style_id = ppr.find(f"{_W}pStyle").get(f"{_W}val")
    shaded = _dark_shading(ppr)
    visible: list[str] = []
    hidden: list[str] = []
    bold_flags: list[bool] = []
    sizes: list[int] = []
    for run in _iter_runs(paragraph):
        chain = styles.run_properties(run, style_id)
        text = "".join(
            child.text or "" if child.tag == f"{_W}t" else
            " " if child.tag == f"{_W}tab" else
            "\n" if child.tag in {f"{_W}br", f"{_W}cr"} else
            "-" if child.tag == f"{_W}noBreakHyphen" else ""
            for child in run
        )
        if not text:
            continue
        size = _value(chain, "sz")
        lum = _hex_luminance(_value(chain, "color"))
        white = lum is not None and lum >= HIDDEN_LUMINANCE and not shaded and not any(_dark_shading(rpr) for rpr in chain[:1])
        tiny = size is not None and size.isdigit() and int(size) < HIDDEN_FONT_SIZE * 2
        if _toggle(chain, "vanish") or _toggle(chain, "specVanish") or white or tiny:
            hidden.append(text)
            continue
        visible.append(text)
        if text.strip():
            bold_flags.append(_toggle(chain, "b"))
            if size is not None and size.isdigit():
                sizes.append(int(size))
    joined = "".join(visible)
    style_name = styles.name(style_id).lower()
    outline = ppr is not None and ppr.find(f"{_W}outlineLvl") is not None
    bold = bool(bold_flags) and all(bold_flags)
    heading = style_name.startswith(("heading", "title")) or outline or (bold and len(joined.split()) <= 6)
    style_key = f"{style_id or 'Normal'}:{sizes[0] if sizes else ''}:{'b' if bold else 'r'}"
    list_item = (ppr is not None and ppr.find(f"{_W}numPr") is not None) or style_name.startswith("list")
    lines = [RawLine(part, None, heading and not list_item, style_key, list_item) for part in joined.split("\n") if part.strip()]
    return lines, " ".join(hidden)


def _walk_paragraphs(root: Any) -> Iterator[Any]:
    for paragraph in root.iter(f"{_W}p"):
        if any(ancestor.tag == _MC_FALLBACK for ancestor in paragraph.iterancestors()):
            continue
        yield paragraph


MAX_TABLE_ROW_CHARACTERS = 300


def _simple_row(row: Any) -> bool:
    """A table row whose cells each hold at most one line of text (e.g. "Staff Nurse | Apollo | 2018 - 2021").

    Such a row is read as one line, cells joined by " | ", so a role, its employer and its dates stay
    together. Layout tables (cells with several paragraphs or nested tables) are read cell by cell."""
    filled = 0
    length = 0
    for cell in row.findall(f"{_W}tc"):
        if cell.find(f".//{_W}tbl") is not None:
            return False
        texts = ["".join(node.text or "" for node in paragraph.iter(f"{_W}t")) for paragraph in cell.iter(f"{_W}p")]
        texts = [text for text in texts if text.strip()]
        if len(texts) > 1:
            return False
        filled += len(texts)
        length += sum(len(text) for text in texts)
    return filled >= 2 and length <= MAX_TABLE_ROW_CHARACTERS


def _nearest_row(paragraph: Any) -> Any | None:
    return next((ancestor for ancestor in paragraph.iterancestors() if ancestor.tag == f"{_W}tr"), None)


def _root_lines(root: Any, styles: _StyleResolver) -> tuple[list[RawLine], list[str]]:
    """Visible lines and hidden runs of one story (body, header or footer), simple table rows joined."""
    # Keep the row proxies alive: lxml only guarantees a stable proxy (and id) while one is referenced.
    rows = [row for row in root.iter(f"{_W}tr") if _simple_row(row)]
    simple_rows = {id(row) for row in rows}
    lines: list[RawLine] = []
    hidden_parts: list[str] = []
    row_owner: Any = None
    row_cells: list[RawLine] = []

    def flush_row() -> None:
        nonlocal row_owner
        if row_cells:
            lines.append(RawLine(" | ".join(cell.text.strip() for cell in row_cells), None, False, row_cells[0].style_key))
        row_cells.clear()
        row_owner = None

    for paragraph in _walk_paragraphs(root):
        paragraph_lines, hidden = _paragraph_lines(paragraph, styles)
        if hidden.strip():
            hidden_parts.append(hidden)
        row = _nearest_row(paragraph)
        if row is not None and id(row) in simple_rows:
            if row is not row_owner:
                flush_row()
                row_owner = row
            row_cells.extend(paragraph_lines)
            continue
        flush_row()
        lines.extend(paragraph_lines)
    flush_row()
    return lines, hidden_parts


def _read_docx(content: bytes, engine: ocr.OcrEngine | None) -> IngestResult:
    if not content.startswith(b"PK"):
        raise DocumentParseError("MALFORMED_DOCX")
    try:
        with zipfile.ZipFile(io.BytesIO(content)) as archive:
            entries = archive.infolist()
            if len(entries) > MAX_DOCX_ENTRIES or sum(entry.file_size for entry in entries) > MAX_UNCOMPRESSED_DOCX_BYTES:
                raise DocumentParseError("FILE_TOO_COMPLEX")
            names = set(archive.namelist())
            if "[Content_Types].xml" not in names or "word/document.xml" not in names:
                raise DocumentParseError("MALFORMED_DOCX")
            media = sorted(name for name in names if name.startswith("word/media/") and not name.endswith("/"))
            images = [archive.read(name) for name in media] if engine is not None else []
        document = Document(io.BytesIO(content))
    except DocumentParseError:
        raise
    except Exception:
        raise DocumentParseError("MALFORMED_DOCX") from None

    result = IngestResult("docx", has_images=bool(media))
    styles = _StyleResolver(document)
    hidden_parts: list[str] = []
    headers, footers, never_shown = _header_footer_parts(document)
    seen_header_text: set[str] = set()
    for root in headers + [document.element.body] + footers:
        block, hidden = _root_lines(root, styles)
        hidden_parts.extend(hidden)
        key = "\n".join(line.text for line in block)
        if root is not document.element.body and key in seen_header_text:
            continue
        seen_header_text.add(key)
        result.lines.extend(block)
    for root in never_shown:  # a first-page/even-page header Word never displays is hidden text ...
        block, hidden = _root_lines(root, styles)
        if "\n".join(line.text for line in block) in seen_header_text:
            continue  # ... unless it merely repeats a header that is displayed (common in templates)
        hidden_parts.extend([line.text for line in block] + hidden)
    result.hidden_text = " ".join(hidden_parts)
    _add_hidden_flag(result)
    if result.text_length < MIN_TEXT_CHARACTERS and result.has_images:
        recognised = _run_ocr(lambda: ocr.ocr_images(images, engine)) if engine is not None and images else None
        if recognised is not None:
            result.lines.extend(recognised)
            result.flags.append(Flag("OCR_USED", "Text was recognised from embedded images with OCR; check evidence carefully."))
        else:
            result.needs_ocr = True
    return result


def _header_footer_parts(document: Any) -> tuple[list[Any], list[Any], list[Any]]:
    """(headers, footers, defined-but-never-displayed parts) of every section, in order.

    Besides the default header/footer, a section can define a first-page header (shown only with
    "different first page") and an even-page header (shown only with "different odd and even pages");
    resumes often keep the contact block in the first-page header."""
    try:
        even_pages = bool(document.settings.odd_and_even_pages_header_footer)
    except Exception:  # noqa: BLE001 - a missing or odd settings part simply means "no even pages"
        even_pages = False
    headers: list[Any] = []
    footers: list[Any] = []
    never_shown: list[Any] = []
    for section in document.sections:
        first_page = bool(section.different_first_page_header_footer)
        for kind, target in (("header", headers), ("footer", footers)):
            for attribute, shown in ((f"first_page_{kind}", first_page), (kind, True), (f"even_page_{kind}", even_pages)):
                part = getattr(section, attribute)
                if part.is_linked_to_previous:
                    continue
                (target if shown else never_shown).append(part._element)
    return headers, footers, never_shown


# ---------------------------------------------------------------------------------------- DOC
def _libreoffice_seconds(settings: Settings) -> float:
    """Conversion budget: well inside the parse time-out, so the worker (not the pool's time-out)
    stops LibreOffice and never leaves an orphaned soffice process behind."""
    return max(5.0, min(float(LIBREOFFICE_MAX_SECONDS), settings.parse_timeout_seconds * 0.6))


def _read_doc(content: bytes, settings: Settings, engine: ocr.OcrEngine | None) -> IngestResult:
    if not olefile.isOleFile(data=content):
        raise DocumentParseError("MALFORMED_DOC")
    if settings.soffice_path:
        converted = _convert_with_libreoffice(content, settings.soffice_path, "doc", _libreoffice_seconds(settings))
        if converted is not None:
            try:
                result = _read_docx(converted, engine)
            except DocumentParseError:
                result = None
            if result is not None and result.text_length:
                result.kind = "doc"
                result.flags.append(Flag("LEGACY_DOC", "Legacy Word (.doc) file converted with LibreOffice before analysis."))
                return result
    text = _doc_text(content)
    result = IngestResult("doc", lines=[RawLine(line) for line in re.split(r"[\r\n\x0b\x0c\x07]+", text) if line.strip()])
    result.flags.append(Flag("LEGACY_DOC", "Best-effort text extraction from a legacy Word (.doc) file; layout and hidden "
                                           "formatting could not be checked. Ask for a PDF or DOCX if evidence looks incomplete."))
    return result


class _HtmlText(HTMLParser):
    """Visible text of an HTML document as lines; display:none / visibility:hidden content is hidden text."""

    _BLOCKS = frozenset({"p", "div", "br", "li", "tr", "h1", "h2", "h3", "h4", "h5", "h6", "section", "article",
                         "header", "footer", "ul", "ol", "table", "blockquote", "pre", "dt", "dd"})
    _SKIP = frozenset({"script", "style", "head", "title", "noscript", "template"})
    _VOID = frozenset({"br", "img", "hr", "meta", "link", "input", "col", "area", "base", "wbr", "source"})
    _HIDDEN_STYLE = re.compile(r"display\s*:\s*none|visibility\s*:\s*hidden|font-size\s*:\s*0(?![.\d]*[1-9])", re.I)

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.lines: list[str] = []
        self.hidden: list[str] = []
        self._current: list[str] = []
        self._stack: list[tuple[str, bool]] = []  # (tag, hides its content)

    def _hidden(self) -> bool:
        return any(hides for _, hides in self._stack)

    def _break(self) -> None:
        text = " ".join("".join(self._current).split())
        if text:
            self.lines.append(text)
        self._current = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        values = dict(attrs)
        hides = tag in self._SKIP or "hidden" in values or bool(self._HIDDEN_STYLE.search(values.get("style") or ""))
        if tag in self._BLOCKS:
            self._break()
        if tag in ("td", "th") and self._current and "".join(self._current).strip():
            self._current.append(" | ")
        if tag not in self._VOID:
            self._stack.append((tag, hides))

    def handle_endtag(self, tag: str) -> None:
        for index in range(len(self._stack) - 1, -1, -1):
            if self._stack[index][0] == tag:
                del self._stack[index:]
                break
        if tag in self._BLOCKS:
            self._break()

    def handle_data(self, data: str) -> None:
        if self._hidden():
            if self._stack and self._stack[-1][0] not in self._SKIP and data.strip():
                self.hidden.append(data.strip())
            return
        self._current.append(data)

    def close(self) -> None:
        super().close()
        self._break()


def _read_html(content: bytes) -> IngestResult:
    try:
        text = content.decode("utf-8")
    except UnicodeDecodeError:
        text = content.decode("cp1252", errors="replace")
    parser = _HtmlText()
    try:
        parser.feed(text)
        parser.close()
    except Exception:  # noqa: BLE001 - html.parser is lenient; anything left is a damaged file
        raise DocumentParseError("MALFORMED_DOC") from None
    result = IngestResult("doc", lines=[RawLine(line) for line in parser.lines])
    result.hidden_text = " ".join(parser.hidden)
    _add_hidden_flag(result)
    result.flags.append(Flag("LEGACY_DOC", "The file is a web page (HTML) saved under a Word file name; its text was read "
                                           "without layout. Ask for a PDF or DOCX if evidence looks incomplete."))
    return result


def _read_via_libreoffice(content: bytes, kind: str, settings: Settings, engine: ocr.OcrEngine | None) -> IngestResult:
    """RTF uploaded under a .doc/.docx/.pdf name: converted with LibreOffice when available."""
    if not settings.soffice_path:
        raise DocumentParseError("UNSUPPORTED_FORMAT")
    converted = _convert_with_libreoffice(content, settings.soffice_path, kind, _libreoffice_seconds(settings))
    if converted is None:
        raise DocumentParseError("UNSUPPORTED_FORMAT")
    result = _read_docx(converted, engine)
    result.kind = "doc"
    result.flags.append(Flag("LEGACY_DOC", f"The file is {kind.upper()} rather than its file extension's format; it was "
                                           "converted with LibreOffice before analysis."))
    return result


def _convert_with_libreoffice(content: bytes, soffice: str, suffix: str = "doc",
                              timeout: float = LIBREOFFICE_MAX_SECONDS) -> bytes | None:
    with tempfile.TemporaryDirectory(prefix="recruitpro-doc-") as directory:
        workdir = Path(directory)
        source = workdir / f"resume.{suffix}"
        source.write_bytes(content)
        command = [soffice, "--headless", "--norestore", "--nologo", "--nolockcheck", "--nodefault",
                   f"-env:UserInstallation={(workdir / 'profile').as_uri()}",
                   "--convert-to", "docx:MS Word 2007 XML", "--outdir", str(workdir), str(source)]
        try:
            # Own process group, so a time-out kills LibreOffice's helper processes too.
            process = subprocess.Popen(command, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                       stdin=subprocess.DEVNULL, start_new_session=True)
        except OSError:
            return None
        try:
            process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            _kill_group(process)
            return None
        target = workdir / "resume.docx"
        return target.read_bytes() if target.exists() else None


def _kill_group(process: subprocess.Popen) -> None:
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except (AttributeError, OSError):  # no process groups (Windows) or already gone
        process.kill()
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        pass


def _doc_text(content: bytes) -> str:
    """Read the text of a Word 97-2003 binary document through its piece table (CLX)."""
    try:
        with olefile.OleFileIO(io.BytesIO(content)) as ole:
            if not ole.exists("WordDocument"):
                raise DocumentParseError("MALFORMED_DOC")
            word = ole.openstream("WordDocument").read()
            if len(word) < 0x1AA or struct.unpack_from("<H", word, 0)[0] != 0xA5EC:
                raise DocumentParseError("MALFORMED_DOC")
            flags = struct.unpack_from("<H", word, 0x0A)[0]
            if flags & 0x0100:
                raise DocumentParseError("ENCRYPTED_DOC")
            table_name = "1Table" if flags & 0x0200 else "0Table"
            if not ole.exists(table_name):
                raise DocumentParseError("MALFORMED_DOC")
            table = ole.openstream(table_name).read()
    except DocumentParseError:
        raise
    except Exception:
        raise DocumentParseError("MALFORMED_DOC") from None
    try:
        return _doc_pieces(word, table)
    except struct.error:  # a truncated or inconsistent piece table
        raise DocumentParseError("MALFORMED_DOC") from None


def _doc_pieces(word: bytes, table: bytes) -> str:
    fc_clx, lcb_clx = struct.unpack_from("<II", word, 0x01A2)
    clx = table[fc_clx:fc_clx + lcb_clx]
    index = 0
    while index < len(clx) and clx[index] == 0x01:  # skip Prc (property modifiers)
        index += 3 + struct.unpack_from("<H", clx, index + 1)[0]
    if index + 5 > len(clx) or clx[index] != 0x02:
        raise DocumentParseError("MALFORMED_DOC")
    size = struct.unpack_from("<I", clx, index + 1)[0]
    plc = clx[index + 5:index + 5 + size]
    count = (len(plc) - 4) // 12
    if count <= 0:
        raise DocumentParseError("MALFORMED_DOC")
    positions = struct.unpack_from(f"<{count + 1}I", plc, 0)
    pieces: list[str] = []
    total = 0
    for piece in range(count):
        fc = struct.unpack_from("<I", plc, 4 * (count + 1) + 8 * piece + 2)[0]
        length = positions[piece + 1] - positions[piece]
        if length <= 0:
            continue
        if fc & 0x40000000:
            start = (fc & 0x3FFFFFFF) // 2
            pieces.append(word[start:start + length].decode("cp1252", errors="replace"))
        else:
            pieces.append(word[fc:fc + 2 * length].decode("utf-16-le", errors="replace"))
        total += len(pieces[-1])
        if total > MAX_TEXT_CHARACTERS:
            raise DocumentParseError("TEXT_LIMIT_EXCEEDED")
    text = "".join(pieces)
    text = re.sub(r"\x13[^\x14\x15]*\x14", "", text)  # field instructions; keep the displayed result
    return re.sub(r"[\x00-\x06\x08\x0e-\x1f\x13\x14\x15]", "", text)
