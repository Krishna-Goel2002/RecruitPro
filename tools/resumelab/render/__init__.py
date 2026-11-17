"""Renderers: PDF (reportlab), DOCX (python-docx), scanned PDF (Pillow), TXT, legacy DOC (LibreOffice, optional)."""
from __future__ import annotations

import shutil
import subprocess
import tempfile
from pathlib import Path

from resumelab.compose import Document

_WARNED: set[str] = set()


def soffice() -> str | None:
    return shutil.which("soffice") or shutil.which("libreoffice")


def render_document(doc: Document, fmt: str, path_stem: Path, seed: str) -> tuple[str, str]:
    """Write ``path_stem.<ext>``; returns (extension, format actually produced)."""
    from resumelab.render.docx_render import render_docx
    from resumelab.render.pdf import render_pdf
    from resumelab.render.scan import scanned_pdf
    from resumelab.render.text_render import render_txt

    if fmt == "txt":
        path_stem.with_suffix(".txt").write_bytes(render_txt(doc, seed))
        return "txt", "txt"
    if fmt == "docx" or (fmt == "doc" and not soffice()):
        if fmt == "doc" and "doc" not in _WARNED:
            _WARNED.add("doc")
            print("  note: LibreOffice not found - writing .docx instead of legacy .doc")
        path_stem.with_suffix(".docx").write_bytes(render_docx(doc, seed))
        return "docx", "docx"
    if fmt == "doc":
        data = render_docx(doc, seed)
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / f"{path_stem.name}.docx"
            source.write_bytes(data)
            try:
                subprocess.run([soffice(), "--headless", "--convert-to", "doc", "--outdir", tmp, str(source)], check=True,
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=120)
                path_stem.with_suffix(".doc").write_bytes((Path(tmp) / f"{path_stem.name}.doc").read_bytes())
                return "doc", "doc"
            except (subprocess.SubprocessError, OSError, FileNotFoundError):
                path_stem.with_suffix(".docx").write_bytes(data)
                return "docx", "docx"
    pdf = render_pdf(doc, seed)
    if fmt == "scan":
        path_stem.with_suffix(".pdf").write_bytes(scanned_pdf(pdf, seed))
        return "pdf", "scan"
    path_stem.with_suffix(".pdf").write_bytes(pdf)
    return "pdf", "pdf"
