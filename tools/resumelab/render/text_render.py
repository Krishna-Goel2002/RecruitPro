"""Plain-text rendering (a resume pasted into a portal text box or saved as .txt)."""
from __future__ import annotations

import random
import textwrap

from resumelab.compose import Bullets, Document, EduEntry, KeyValues, Para, RoleEntry, SkillGroups, Table


def render_txt(doc: Document, seed: str) -> bytes:
    rng = random.Random(f"txt:{seed}")
    width = rng.choice((78, 90, 100, 1000))  # 1000 = no wrapping
    underline = rng.choice(("=", "-", "", ""))
    glyph = rng.choice(("-", "*", "•", "o"))
    out: list[str] = []

    def wrap(text: str, indent: str = "") -> None:
        if width >= 1000:
            out.append(indent + text)
        else:
            out.extend(textwrap.wrap(text, width, initial_indent=indent, subsequent_indent=" " * len(indent)) or [indent])

    hd = doc.header
    out.append(hd.name)
    if hd.headline:
        out.append(hd.headline)
    out.append(" | ".join(text for _, text in hd.contacts))
    for section in doc.sections:
        out.append("")
        title = section.heading.upper() if rng.random() < 0.7 else section.heading
        out.append(title)
        if underline:
            out.append(underline * len(title))
        for block in section.blocks:
            if isinstance(block, Para):
                wrap(block.line.text)
            elif isinstance(block, Bullets):
                for index, line in enumerate(block.items, start=1):
                    wrap(line.text, f"{index}. " if block.numbered else f"{glyph} ")
            elif isinstance(block, RoleEntry):
                loc = f", {block.location}" if block.location else ""
                if block.layout == "keyvalue":
                    out += [f"Organisation: {block.employer}{loc}", f"Designation: {block.title}", f"Duration: {block.dates}"]
                elif block.layout == "date_first":
                    out += [block.dates, f"{block.title} | {block.employer}{loc}"]
                else:
                    out.append(f"{block.title} - {block.employer}{loc} ({block.dates})")
                for line in block.bullets:
                    wrap(line.text, f"{glyph} ")
                for kvs, bullets, env in block.projects:
                    out += [f"{k}: {v}" for k, v in kvs]
                    for line in bullets:
                        wrap(line.text, f"{glyph} ")
                    if env:
                        wrap(env.text)
                if block.env:
                    wrap(block.env.text)
                out.append("")
            elif isinstance(block, EduEntry):
                out.append(f"{block.degree_line}, {block.institution} ({block.dates}) {block.grade}".strip())
                for line in block.extra:
                    wrap(line.text)
            elif isinstance(block, SkillGroups):
                for label, lines in block.groups:
                    wrap((f"{label}: " if label else "") + ", ".join(line.text for line in lines))
            elif isinstance(block, KeyValues):
                out += [f"{k}: {v.text}" for k, v in block.rows]
            elif isinstance(block, Table):
                out.append(" | ".join(block.header))
                out += [" | ".join(cell.text for cell in row) for row in block.rows]
    if doc.tiny_text:
        out += ["", doc.tiny_text]
    text = "\n".join(out).rstrip() + "\n"
    if rng.random() < 0.3:
        text = text.replace("\n", "\r\n")
    return text.encode("utf-8")
