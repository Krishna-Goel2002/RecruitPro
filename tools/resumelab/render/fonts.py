"""Font registration for reportlab: bundled OFL/Apache fonts, falling back to the PDF standard fonts."""
from __future__ import annotations

from functools import lru_cache
from pathlib import Path

from reportlab.lib.fonts import addMapping
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont

FONT_DIR = Path(__file__).resolve().parents[1] / "fonts"
FAMILIES = {
    # family: (regular, bold, italic) file names
    "LibSans": ("LiberationSans-Regular.ttf", "LiberationSans-Bold.ttf", "LiberationSans-Italic.ttf"),
    "LibSerif": ("LiberationSerif-Regular.ttf", "LiberationSerif-Bold.ttf", "LiberationSerif-Italic.ttf"),
    "Caladea": ("Caladea-Regular.ttf", "Caladea-Bold.ttf", "Caladea-Italic.ttf"),
    "Poppins": ("Poppins-Regular.ttf", "Poppins-Bold.ttf", "Poppins-Italic.ttf"),
    "PoppinsMedium": ("Poppins-Medium.ttf", "Poppins-Bold.ttf", "Poppins-Italic.ttf"),
    "DejaVu": ("DejaVuSans.ttf", "DejaVuSans.ttf", "DejaVuSans.ttf"),
}
FALLBACK = {"LibSans": "Helvetica", "Poppins": "Helvetica", "PoppinsMedium": "Helvetica", "DejaVu": "Helvetica", "LibSerif": "Times-Roman",
            "Caladea": "Times-Roman"}
STANDARD = {"Helvetica": ("Helvetica", "Helvetica-Bold", "Helvetica-Oblique"), "Times-Roman": ("Times-Roman", "Times-Bold", "Times-Italic")}


@lru_cache(maxsize=None)
def family(name: str) -> tuple[str, str, str]:
    """(regular, bold, italic) reportlab font names for a family; registers TTFs on first use."""
    files = FAMILIES.get(name)
    if files and all((FONT_DIR / f).is_file() for f in files):
        names = (f"{name}", f"{name}-Bold", f"{name}-Italic")
        for font_name, file_name in zip(names, files):
            if font_name not in pdfmetrics.getRegisteredFontNames():
                pdfmetrics.registerFont(TTFont(font_name, str(FONT_DIR / file_name)))
        addMapping(name, 0, 0, names[0])
        addMapping(name, 1, 0, names[1])
        addMapping(name, 0, 1, names[2])
        addMapping(name, 1, 1, names[1])
        return names
    if name in STANDARD:
        return STANDARD[name]
    return STANDARD[FALLBACK.get(name, "Helvetica")]


@lru_cache(maxsize=None)
def has_glyph(font_name: str, char: str) -> bool:
    try:
        font = pdfmetrics.getFont(font_name)
    except KeyError:
        return False
    face = getattr(font, "face", None)
    if face is None or not hasattr(face, "charToGlyph"):
        return char in "•–—’" or ord(char) < 256  # standard fonts: WinAnsi
    return ord(char) in face.charToGlyph
