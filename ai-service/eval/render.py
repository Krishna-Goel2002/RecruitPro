"""Deterministic document builders for tests and the evaluation harness.

PDFs are written by hand (standard Helvetica fonts, WinAnsi encoding) so text position, size and
colour can be controlled exactly - enough to exercise two-column layouts and hidden text without
any extra dependency. DOCX files are built with python-docx.
"""
from __future__ import annotations

import io
from dataclasses import dataclass

from docx import Document
from docx.shared import Pt, RGBColor

PAGE_WIDTH, PAGE_HEIGHT = 612, 792


@dataclass(frozen=True)
class PdfText:
    text: str
    x: float = 72
    y: float | None = None  # None: placed below the previous auto-placed line
    size: float = 11
    color: tuple[float, float, float] = (0, 0, 0)
    bold: bool = False


@dataclass(frozen=True)
class PdfRect:
    """A filled rectangle (e.g. a dark banner behind white heading text)."""

    x: float
    y: float
    width: float
    height: float
    color: tuple[float, float, float] = (0, 0, 0)


def _pdf_string(text: str) -> str:
    out = []
    for byte in text.encode("cp1252", errors="replace"):
        char = chr(byte)
        if char in "\\()":
            out.append("\\" + char)
        elif 32 <= byte < 127:
            out.append(char)
        else:
            out.append(f"\\{byte:03o}")
    return "".join(out)


def _page_stream(items: list[PdfText | PdfRect | str], scanned: bool) -> bytes:
    commands = []
    cursor = PAGE_HEIGHT - 60
    for item in items:
        if isinstance(item, PdfRect):
            red, green, blue = item.color
            commands.append(f"{red:g} {green:g} {blue:g} rg {item.x:g} {item.y:g} {item.width:g} {item.height:g} re f")
            continue
        item = PdfText(item) if isinstance(item, str) else item
        y = item.y if item.y is not None else cursor
        if item.y is None:
            cursor -= item.size * 1.6
        red, green, blue = item.color
        commands.append(f"BT /{'F2' if item.bold else 'F1'} {item.size:g} Tf {red:g} {green:g} {blue:g} rg "
                        f"1 0 0 1 {item.x:g} {y:g} Tm ({_pdf_string(item.text)}) Tj ET")
    if scanned:
        commands.append("q 400 0 0 500 100 150 cm /Im0 Do Q")
    return "\n".join(commands).encode("latin-1")


def make_pdf(pages: list[list[PdfText | PdfRect | str]] | list[PdfText | PdfRect | str], *, scanned: bool = False) -> bytes:
    """Build a PDF. ``pages`` is a list of items (one page) or a list of pages."""
    if pages and not isinstance(pages[0], list):
        pages = [pages]  # type: ignore[list-item]
    pages = pages or [[]]  # type: ignore[assignment]
    objects: list[bytes] = [b"", b""]  # catalog and page tree are filled in below
    font_regular = b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"
    font_bold = b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>"
    objects += [font_regular, font_bold]
    image_ref = ""
    if scanned:
        objects.append(b"<< /Type /XObject /Subtype /Image /Width 1 /Height 1 /ColorSpace /DeviceRGB "
                       b"/BitsPerComponent 8 /Length 3 >>\nstream\n\x00\x00\x00\nendstream")
        image_ref = f" /XObject << /Im0 {len(objects)} 0 R >>"
    page_refs = []
    for items in pages:  # type: ignore[union-attr]
        stream = _page_stream(items, scanned)
        objects.append(f"<< /Length {len(stream)} >>\nstream\n".encode() + stream + b"\nendstream")
        content_ref = len(objects)
        objects.append(f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 {PAGE_WIDTH} {PAGE_HEIGHT}] "
                       f"/Resources << /Font << /F1 3 0 R /F2 4 0 R >>{image_ref} >> /Contents {content_ref} 0 R >>".encode())
        page_refs.append(len(objects))
    objects[0] = b"<< /Type /Catalog /Pages 2 0 R >>"
    objects[1] = f"<< /Type /Pages /Kids [{' '.join(f'{ref} 0 R' for ref in page_refs)}] /Count {len(page_refs)} >>".encode()
    pdf = bytearray(b"%PDF-1.4\n")
    offsets = []
    for index, body in enumerate(objects, start=1):
        offsets.append(len(pdf))
        pdf.extend(f"{index} 0 obj\n".encode() + body + b"\nendobj\n")
    xref = len(pdf)
    pdf.extend(f"xref\n0 {len(objects) + 1}\n0000000000 65535 f \n".encode())
    for offset in offsets:
        pdf.extend(f"{offset:010d} 00000 n \n".encode())
    pdf.extend(f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode())
    return bytes(pdf)


@dataclass(frozen=True)
class DocxRun:
    text: str
    hidden: bool = False
    color: str | None = None  # hex RGB, e.g. "FFFFFF"
    size: float | None = None  # points
    bold: bool = False


def make_docx(paragraphs: list[tuple[str | list[DocxRun], str]], *, header: list[str] | None = None,
              table: list[list[str]] | None = None) -> bytes:
    """Build a DOCX from (text or runs, style) pairs; optional page header lines and a trailing table."""
    document = Document()
    if header:
        section_header = document.sections[0].header
        section_header.is_linked_to_previous = False
        section_header.paragraphs[0].text = header[0]
        for line in header[1:]:
            section_header.add_paragraph(line)
    for content, style in paragraphs:
        paragraph = document.add_paragraph(style=style)
        runs = [DocxRun(content)] if isinstance(content, str) else content
        for spec in runs:
            run = paragraph.add_run(spec.text)
            run.bold = spec.bold or None
            if spec.hidden:
                run.font.hidden = True
            if spec.color:
                run.font.color.rgb = RGBColor.from_string(spec.color)
            if spec.size:
                run.font.size = Pt(spec.size)
    if table:
        grid = document.add_table(rows=len(table), cols=len(table[0]))
        for row_index, row in enumerate(table):
            for column, value in enumerate(row):
                grid.cell(row_index, column).text = value
    output = io.BytesIO()
    document.save(output)
    return output.getvalue()
