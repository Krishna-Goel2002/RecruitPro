"""Scanned-looking, image-only PDFs: rasterise the rendered PDF, then add tilt, noise, blur and JPEG artefacts."""
from __future__ import annotations

import io
import random
import time

from PIL import Image, ImageFilter, ImageOps

DPI = 150


def _rasterise(pdf: bytes) -> list[Image.Image]:
    try:
        import pypdfium2 as pdfium
    except ImportError:  # pragma: no cover - fallback documented in README
        return []
    document = pdfium.PdfDocument(pdf)
    pages = []
    for index in range(len(document)):
        page = document[index]
        pages.append(page.render(scale=DPI / 72).to_pil().convert("L"))
    document.close()
    return pages


def _noise(img: Image.Image, rng: random.Random, amount: int) -> Image.Image:
    """Salt-and-pepper speckle plus a faint uneven background, from a seeded RNG (deterministic)."""
    w, h = img.size
    speckle = Image.frombytes("L", (w, h), rng.randbytes(w * h))
    speckle = speckle.point(lambda v: 0 if v < amount else (255 if v > 255 - amount // 3 else 128))
    mask = speckle.point(lambda v: 255 if v != 128 else 0)
    img = Image.composite(speckle, img, mask)
    return img


def scanned_pdf(pdf: bytes, seed: str) -> bytes:
    rng = random.Random(f"scan:{seed}")
    pages = _rasterise(pdf)
    if not pages:
        raise RuntimeError("pypdfium2 is required for scanned output (pip install -r requirements.txt)")
    out_pages = []
    for page in pages:
        img = ImageOps.autocontrast(page, cutoff=1)
        img = img.point(lambda v: min(255, int(v * 0.93 + rng.randint(8, 16))))  # greyish paper
        angle = rng.uniform(-1.4, 1.4)
        img = img.rotate(angle, resample=Image.BICUBIC, expand=False, fillcolor=rng.randint(225, 245))
        img = _noise(img, rng, rng.randint(1, 3))
        img = img.filter(ImageFilter.GaussianBlur(radius=rng.uniform(0.35, 0.9)))
        if rng.random() < 0.5:  # photocopier shadow on one edge
            w, h = img.size
            shadow = Image.linear_gradient("L").rotate(90, expand=True).resize((max(1, w // 18), h))
            img.paste(Image.composite(Image.new("L", shadow.size, 90), img.crop((0, 0) + shadow.size), shadow.point(lambda v: 255 - v)), (0, 0))
        buf = io.BytesIO()
        img.save(buf, format="JPEG", quality=rng.randint(55, 75))
        out_pages.append(Image.open(io.BytesIO(buf.getvalue())).convert("L"))
    result = io.BytesIO()
    fixed = time.gmtime(1704067200)  # 2024-01-01, so the same seed gives byte-identical files
    out_pages[0].save(result, format="PDF", save_all=True, append_images=out_pages[1:], resolution=DPI, creationDate=fixed, modDate=fixed)
    return result.getvalue()
