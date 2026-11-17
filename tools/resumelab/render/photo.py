"""A neutral photo placeholder (grey silhouette) - never a real or generated face."""
from __future__ import annotations

import io
import random

from PIL import Image, ImageDraw


def placeholder_png(rng: random.Random, circle: bool = False) -> bytes:
    w, h = (240, 240) if circle else (240, 288)
    shade = rng.choice((205, 215, 225))
    img = Image.new("RGB", (w, h), (shade, shade, shade + 5))
    draw = ImageDraw.Draw(img)
    figure = (150, 152, 160)
    draw.ellipse((w * 0.32, h * 0.16, w * 0.68, h * 0.48), fill=figure)
    draw.ellipse((w * 0.14, h * 0.52, w * 0.86, h * 1.15), fill=figure)
    if circle:
        mask = Image.new("L", (w, h), 0)
        ImageDraw.Draw(mask).ellipse((0, 0, w, h), fill=255)
        bg = Image.new("RGB", (w, h), (255, 255, 255))
        img = Image.composite(img, bg, mask)
    out = io.BytesIO()
    img.save(out, format="PNG", optimize=True)
    return out.getvalue()
