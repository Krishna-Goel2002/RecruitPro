"""Optional OCR through ``rapidocr-onnxruntime`` (install ``requirements-ocr.txt``, set ``OCR_ENABLED=true``).

Kept behind a tiny interface so ingestion can be tested with a fake engine and the service
runs without the (large) OCR dependency.
"""
from __future__ import annotations

import importlib.util
import io
from functools import lru_cache
from typing import Any, Protocol

from app.pipeline.text import RawLine


class OcrEngine(Protocol):
    def __call__(self, image: Any) -> tuple[list[Any] | None, Any]: ...


def ocr_installed() -> bool:
    return importlib.util.find_spec("rapidocr_onnxruntime") is not None


@lru_cache(maxsize=1)
def get_engine() -> OcrEngine:
    from rapidocr_onnxruntime import RapidOCR  # optional dependency, imported only when OCR is enabled

    return RapidOCR()


def lines_from_result(result: list[Any] | None, reference: str | None) -> list[RawLine]:
    """Turn RapidOCR boxes ``[[box, text, score], ...]`` into top-to-bottom lines."""
    if not result:
        return []
    boxes = sorted(result, key=lambda item: (round(min(point[1] for point in item[0]) / 12), min(point[0] for point in item[0])))
    lines: list[RawLine] = []
    current_row: int | None = None
    parts: list[str] = []
    for box, text, _score in boxes:
        row = round(min(point[1] for point in box) / 12)
        if current_row is not None and row != current_row and parts:
            lines.append(RawLine(" ".join(parts), reference))
            parts = []
        current_row = row
        parts.append(str(text))
    if parts:
        lines.append(RawLine(" ".join(parts), reference))
    return lines


def ocr_pdf_pages(pdf: Any, engine: OcrEngine, max_pages: int) -> list[RawLine]:
    """OCR each page of an open pdfplumber document rendered at 200 dpi."""
    lines: list[RawLine] = []
    for number, page in enumerate(pdf.pages[:max_pages], start=1):
        image = page.to_image(resolution=200).original
        buffer = io.BytesIO()
        image.save(buffer, format="PNG")
        result, _ = engine(buffer.getvalue())
        lines.extend(lines_from_result(result, f"page:{number}"))
    return lines


def ocr_images(images: list[bytes], engine: OcrEngine) -> list[RawLine]:
    lines: list[RawLine] = []
    for image in images:
        result, _ = engine(image)
        lines.extend(lines_from_result(result, None))
    return lines
