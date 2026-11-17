import re
import sys
import zipfile
from pathlib import Path

import pytest

TOOLS = Path(__file__).resolve().parents[2]
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))

from resumelab.generate import Options, generate  # noqa: E402

AS_OF = "2026-11"


@pytest.fixture(scope="session")
def corpus(tmp_path_factory):
    out = tmp_path_factory.mktemp("corpus")
    generate(Options(out=out, count=30, seed=11, formats=["pdf", "docx", "scan", "txt"], adversarial_rate=0.35, as_of=AS_OF, jobs=3),
             log=lambda *_: None)
    return out


_W_T = re.compile(r"<w:t(?:\s[^>]*)?>([^<]*)</w:t>")
_W_P = re.compile(r"<w:p[ >].*?</w:p>", re.S)


def extract_text(path: Path) -> str:
    """Text a reader can see: pypdf for PDFs, every paragraph (incl. tables and text boxes) for DOCX, raw text for TXT."""
    if path.suffix == ".pdf":
        from pypdf import PdfReader
        return "\n".join(page.extract_text() or "" for page in PdfReader(str(path)).pages)
    if path.suffix == ".docx":
        lines = []
        with zipfile.ZipFile(path) as package:
            for name in package.namelist():
                if re.match(r"word/(document|header\d*|footer\d*)\.xml$", name):
                    xml = package.read(name).decode("utf-8")
                    for paragraph in _W_P.findall(xml):
                        lines.append("".join(_W_T.findall(paragraph)))
        return "\n".join(lines).replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", '"').replace("&apos;", "'")
    return path.read_text(encoding="utf-8")


def squash(text: str) -> str:
    return re.sub(r"\s+", "", text).lower()
