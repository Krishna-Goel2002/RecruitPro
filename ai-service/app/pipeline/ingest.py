"""Document ingestion: PDF, DOCX and legacy DOC to visible text lines, with layout and hidden-text handling.

* PDF - pdfplumber words with positions. Characters that are white (luminance >= 0.95, unless
  drawn on a dark filled rectangle), smaller than 4 pt, or outside the page are hidden: they are
  excluded and reported as ``HIDDEN_TEXT``. A vertical gutter found by x-coverage clustering marks
  a two-column layout; each band is then read left column first, then right (``TWO_COLUMN_LAYOUT``).
* DOCX - every paragraph in document order (body, tables, text boxes, headers, footers). Runs
  that are hidden (``w:vanish``), white or smaller than 4 pt - directly or through styles - are
  excluded and reported.
* DOC - converted with LibreOffice when ``soffice`` is available (then parsed as DOCX, so hidden
  text is still detected); otherwise the Word 97 piece table is read with ``olefile``. Both report
  ``LEGACY_DOC``.
* Image-only documents are OCR'd when ``OCR_ENABLED=true`` and rapidocr is installed
  (``OCR_USED``); otherwise the result is ``NEEDS_OCR``.
"""
from __future__ import annotations

import io
import re
import statistics
import struct
import subprocess
import tempfile
import zipfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterator

import olefile
import pdfplumber
from docx import Document

from app.core.config import Settings
from app.pipeline import ocr
from app.pipeline.text import Flag, RawLine

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


def ingest(filename: str, content: bytes, settings: Settings, ocr_engine: ocr.OcrEngine | None = None) -> IngestResult:
    extension = extension_of(filename)
    if extension not in SUPPORTED_EXTENSIONS:
        raise DocumentParseError("UNSUPPORTED_FORMAT")
    if not content:
        raise DocumentParseError("EMPTY_DOCUMENT")
    if len(content) > MAX_FILE_BYTES:
        raise DocumentParseError("FILE_TOO_LARGE")
    engine = ocr_engine or (ocr.get_engine() if settings.ocr_enabled and ocr.ocr_installed() else None)
    if extension == "pdf":
        result = _read_pdf(content, engine)
    elif extension == "docx":
        result = _read_docx(content, engine)
    else:
        result = _read_doc(content, settings, engine)
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
    for char in page.chars:
        lum = _luminance(char.get("non_stroking_color"))
        off_page = char["x1"] <= x0 or char["x0"] >= x1 or char["bottom"] <= top or char["top"] >= bottom
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
        # A real right column is left-aligned; words past a ragged edge start anywhere.
        starts = [row[0]["x0"] for row in right_rows]
        margin = statistics.median(starts)
        if sum(1 for x in starts if abs(x - margin) <= 8) / len(starts) >= 0.6:
            return gap
    return None


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


def _layout_lines(words: list[dict[str, Any]], width: float, reference: str, body_size: float) -> tuple[list[RawLine], bool]:
    gap = _column_gap(words, width)
    if gap is None:
        return [_row_to_line(row, body_size, reference) for row in _group_rows(words)], False
    gap_left, gap_right = gap
    centre = (gap_left + gap_right) / 2
    lines: list[RawLine] = []
    left: list[dict[str, Any]] = []
    right: list[dict[str, Any]] = []

    def flush() -> None:
        lines.extend(_row_to_line(row, body_size, reference) for row in _group_rows(left))
        lines.extend(_row_to_line(row, body_size, reference) for row in _group_rows(right))
        left.clear()
        right.clear()

    def full_width(segment: list[dict[str, Any]]) -> bool:
        # Runs through the gutter and well into the right column (a name or contact line above the
        # columns); a sidebar heading that merely overflows into the gutter does not.
        return segment[0]["x0"] < gap_right - 2 and segment[-1]["x1"] > gap_right + 20 \
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
    return lines, True


def _read_pdf(content: bytes, engine: ocr.OcrEngine | None) -> IngestResult:
    if not content.startswith(b"%PDF-"):
        raise DocumentParseError("MALFORMED_PDF")
    result = IngestResult("pdf")
    hidden_parts: list[str] = []
    two_column_pages: list[int] = []
    try:
        with pdfplumber.open(io.BytesIO(content)) as pdf:
            if not pdf.pages:
                raise DocumentParseError("EMPTY_DOCUMENT")
            if len(pdf.pages) > MAX_PDF_PAGES:
                raise DocumentParseError("FILE_TOO_COMPLEX")
            characters = 0
            for number, page in enumerate(pdf.pages, start=1):
                result.has_images = result.has_images or bool(page.images)
                hidden_ids, hidden_text = _hidden_char_ids(page)
                if hidden_text.strip():
                    hidden_parts.append(hidden_text)
                visible = page.filter(lambda obj, ids=hidden_ids: obj.get("object_type") != "char" or id(obj) not in ids)
                words = visible.extract_words(x_tolerance=1.5, y_tolerance=2.5, keep_blank_chars=False,
                                              use_text_flow=False, extra_attrs=["size", "fontname"])
                if not words:
                    continue
                body_size = statistics.median(float(word.get("size") or 10) for word in words)
                lines, two_column = _layout_lines(words, float(page.width), f"page:{number}", body_size)
                if two_column:
                    two_column_pages.append(number)
                result.lines.extend(lines)
                characters += sum(len(word["text"]) for word in words)
                if characters > MAX_TEXT_CHARACTERS:
                    raise DocumentParseError("TEXT_LIMIT_EXCEEDED")
            if result.text_length < MIN_TEXT_CHARACTERS and result.has_images:
                if engine is not None:
                    result.lines = ocr.ocr_pdf_pages(pdf, engine, MAX_PDF_PAGES)
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
                                                      "columns were read left then right."))
    return result


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
    roots: list[Any] = []
    for section in document.sections:
        if not section.header.is_linked_to_previous:
            roots.append(section.header._element)
    roots.append(document.element.body)
    for section in document.sections:
        if not section.footer.is_linked_to_previous:
            roots.append(section.footer._element)
    seen_header_text: set[str] = set()
    for root in roots:
        block: list[RawLine] = []
        for paragraph in _walk_paragraphs(root):
            lines, hidden = _paragraph_lines(paragraph, styles)
            block.extend(lines)
            if hidden.strip():
                hidden_parts.append(hidden)
        key = "\n".join(line.text for line in block)
        if root is not document.element.body and key in seen_header_text:
            continue
        seen_header_text.add(key)
        result.lines.extend(block)
    result.hidden_text = " ".join(hidden_parts)
    _add_hidden_flag(result)
    if result.text_length < MIN_TEXT_CHARACTERS and result.has_images:
        if engine is not None and images:
            result.lines.extend(ocr.ocr_images(images, engine))
            result.flags.append(Flag("OCR_USED", "Text was recognised from embedded images with OCR; check evidence carefully."))
        else:
            result.needs_ocr = True
    return result


# ---------------------------------------------------------------------------------------- DOC
def _read_doc(content: bytes, settings: Settings, engine: ocr.OcrEngine | None) -> IngestResult:
    if not olefile.isOleFile(data=content):
        raise DocumentParseError("MALFORMED_DOC")
    if settings.soffice_path:
        converted = _convert_with_libreoffice(content, settings.soffice_path)
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


def _convert_with_libreoffice(content: bytes, soffice: str) -> bytes | None:
    with tempfile.TemporaryDirectory(prefix="recruitpro-doc-") as directory:
        workdir = Path(directory)
        source = workdir / "resume.doc"
        source.write_bytes(content)
        command = [soffice, "--headless", "--norestore", "--nologo", "--nolockcheck",
                   f"-env:UserInstallation={(workdir / 'profile').as_uri()}",
                   "--convert-to", "docx", "--outdir", str(workdir), str(source)]
        try:
            subprocess.run(command, capture_output=True, timeout=60, check=False)
        except (OSError, subprocess.SubprocessError):
            return None
        target = workdir / "resume.docx"
        return target.read_bytes() if target.exists() else None


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
        if sum(map(len, pieces)) > MAX_TEXT_CHARACTERS:
            raise DocumentParseError("TEXT_LIMIT_EXCEEDED")
    text = "".join(pieces)
    text = re.sub(r"\x13[^\x14\x15]*\x14", "", text)  # field instructions; keep the displayed result
    return re.sub(r"[\x00-\x06\x08\x0e-\x1f\x13\x14\x15]", "", text)
