"""PDF rendering with reportlab (selectable text, embedded TrueType subsets), one layout per template."""
from __future__ import annotations

import io
import random
from xml.sax.saxutils import escape

from reportlab import rl_config
from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER, TA_LEFT, TA_RIGHT
from reportlab.lib.pagesizes import A4, LETTER
from reportlab.lib.styles import ParagraphStyle
from reportlab.pdfgen import canvas as rl_canvas
from reportlab.platypus import (
    BaseDocTemplate, CondPageBreak, Flowable, Frame, FrameBreak, HRFlowable, Image, KeepTogether, NextPageTemplate, PageTemplate,
    Paragraph, Spacer, Table, TableStyle,
)

from resumelab.compose import Bullets, Document, EduEntry, KeyValues, Para, RoleEntry, Section, SkillGroups, Table as DocTable
from resumelab.render.fonts import family, has_glyph
from resumelab.render.photo import placeholder_png

rl_config.invariant = 1  # deterministic bytes (no timestamps / random ids)

ICONS = {"email": "✉", "phone": "☎", "city": "⌂", "linkedin": "in", "github": "⌘", "portfolio": "☍"}


def _rgb(color) -> colors.Color:
    return colors.Color(*color)


def _lighter(color, amount=0.85):
    return colors.Color(*[c + (1 - c) * amount for c in color])


class NumberedCanvas(rl_canvas.Canvas):
    """Canvas that knows the page count, for "Page X of Y" footers."""

    def __init__(self, *args, footer=None, **kwargs):
        super().__init__(*args, **kwargs)
        self._saved = []
        self._footer = footer

    def showPage(self):
        self._saved.append(dict(self.__dict__))
        self._startPage()

    def save(self):
        total = len(self._saved)
        for state in self._saved:
            self.__dict__.update(state)
            if self._footer:
                self._footer(self, total)
            super().showPage()
        super().save()


class RatingBar(Flowable):
    """A skill name with dots, drawn as text so the PDF text layer reads e.g. 'Python ●●●●○'."""

    def __init__(self, text: str, rating: int, font: str, size: float, color, dot_font: str, width: float, text_color=colors.black):
        super().__init__()
        self.text, self.rating, self.font, self.size, self.color, self.dot_font, self.width_avail = text, rating, font, size, color, dot_font, width
        self.text_color = text_color

    def wrap(self, availWidth, availHeight):
        return min(self.width_avail, availWidth), self.size * 1.7

    def draw(self):
        c = self.canv
        c.setFont(self.font, self.size)
        c.setFillColor(self.text_color)
        c.drawString(0, 2, self.text)
        c.setFont(self.dot_font, self.size)
        c.setFillColor(self.color)
        dots = "●" * self.rating + "○" * (5 - self.rating)
        c.drawRightString(min(self.width_avail, 1000) - 2, 2, dots)


class PdfRenderer:
    def __init__(self, doc: Document, seed: str) -> None:
        self.doc = doc
        self.rng = random.Random(f"pdf:{seed}")
        self.t = doc.template
        st = doc.style
        self.accent = st["accent"]
        fonts = {"ats_classic": self.rng.choice(("LibSans", "Helvetica", "LibSans")), "modern_sidebar": "Poppins",
                 "classic_serif": self.rng.choice(("Caladea", "LibSerif")), "compact_dense": "LibSans", "creative_color": "Poppins",
                 "table_grid": self.rng.choice(("LibSans", "Caladea")), "academic_cv": self.rng.choice(("LibSerif", "Times-Roman")),
                 "fresher_indian": self.rng.choice(("Times-Roman", "LibSerif", "LibSans")), "indian_traditional": self.rng.choice(("Times-Roman", "LibSerif")),
                 "docx_textbox": "LibSans", "plain_text": "LibSans"}
        self.font, self.bold, self.italic = family(fonts.get(self.t, "LibSans"))
        self.symbol_font = family("DejaVu")[0]
        base = {"compact_dense": 8.4, "modern_sidebar": 9.0, "creative_color": 9.0, "academic_cv": 10.5, "fresher_indian": 10.5,
                "indian_traditional": 11.0, "classic_serif": 10.5}.get(self.t, 9.8)
        self.size = base + self.rng.choice((-0.3, 0, 0, 0.3))
        self.pagesize = A4 if self.rng.random() < 0.75 else LETTER
        self.bullet = self._bullet_glyph(st["bullet"])
        self.styles = self._styles()

    def _bullet_glyph(self, glyph: str) -> str:
        if glyph == "numbered":
            return glyph
        return glyph if has_glyph(self.font, glyph) else "•"

    def _styles(self) -> dict[str, ParagraphStyle]:
        s, f, b, i = self.size, self.font, self.bold, self.italic
        # ~15% of real PDFs carry bullets from a font without a usable Unicode map (they extract as "(cid:127)" or a
        # private-use glyph); keep that artefact for parsers to cope with.
        bullet_font = "Helvetica" if self.rng.random() < 0.15 else f
        lead = s * (1.22 if self.t == "compact_dense" else 1.35)
        side_color = colors.white if self.t == "modern_sidebar" else colors.black
        return {
            "body": ParagraphStyle("body", fontName=f, fontSize=s, leading=lead, spaceAfter=2, bulletFontName=f),
            "bullet": ParagraphStyle("bullet", fontName=f, fontSize=s, leading=lead, leftIndent=12, bulletIndent=2, spaceAfter=1.5,
                                     bulletFontName=bullet_font),
            "meta": ParagraphStyle("meta", fontName=i, fontSize=s - 0.6, leading=lead, textColor=colors.Color(0.25, 0.25, 0.25)),
            "small": ParagraphStyle("small", fontName=f, fontSize=s - 1, leading=lead - 1),
            "role": ParagraphStyle("role", fontName=b, fontSize=s + 0.6, leading=lead + 1),
            "right": ParagraphStyle("right", fontName=i, fontSize=s - 0.4, leading=lead, alignment=TA_RIGHT),
            "heading": ParagraphStyle("heading", fontName=b, fontSize=s + 2.2, leading=s + 5, spaceBefore=7, spaceAfter=3,
                                      textColor=_rgb(self.accent) if self.t in {"creative_color", "modern_sidebar", "compact_dense"} else colors.black),
            "name": ParagraphStyle("name", fontName=b, fontSize=s + 12, leading=s + 15),
            "headline": ParagraphStyle("headline", fontName=f, fontSize=s + 2, leading=s + 5, textColor=colors.Color(0.3, 0.3, 0.3)),
            "contact": ParagraphStyle("contact", fontName=f, fontSize=s - 0.8, leading=s + 3),
            "side": ParagraphStyle("side", fontName=f, fontSize=s - 0.6, leading=s + 3, textColor=side_color, bulletFontName=f),
            "side_head": ParagraphStyle("side_head", fontName=b, fontSize=s + 1, leading=s + 4, spaceBefore=9, spaceAfter=3, textColor=side_color),
            "center": ParagraphStyle("center", fontName=f, fontSize=s - 0.5, leading=s + 3, alignment=TA_CENTER),
        }

    # ----------------------------------------------------------------------------------------- helpers
    def P(self, text: str, style: str = "body", **kw) -> Paragraph:
        return Paragraph(escape(text), self.styles[style], **kw)

    def heading_text(self, text: str) -> str:
        case = self.doc.style["heading_case"]
        if case == "upper" or self.t in {"modern_sidebar", "compact_dense"}:
            return text.upper()
        return text

    def heading(self, text: str, side: bool = False) -> list:
        text = self.heading_text(text)
        if side:
            return [Paragraph(escape(text.upper()), self.styles["side_head"]),
                    HRFlowable(width="100%", thickness=0.6, color=colors.Color(1, 1, 1, 0.6), spaceAfter=3)]
        if self.t == "creative_color":
            return [Paragraph(f'<font color="#{_hex(self.accent)}">▍</font> {escape(text)}' if has_glyph(self.bold, "▍") else escape(text),
                              self.styles["heading"])]
        if self.t in {"fresher_indian", "indian_traditional"}:
            tbl = Table([[Paragraph(f"<b>{escape(text)}</b>", ParagraphStyle("h", fontName=self.bold, fontSize=self.size + 1, leading=self.size + 4))]],
                        colWidths=["100%"])
            tbl.setStyle(TableStyle([("BACKGROUND", (0, 0), (-1, -1), colors.Color(0.85, 0.85, 0.85)), ("TOPPADDING", (0, 0), (-1, -1), 2),
                                     ("BOTTOMPADDING", (0, 0), (-1, -1), 3), ("LEFTPADDING", (0, 0), (-1, -1), 4)]))
            return [Spacer(1, 6), tbl, Spacer(1, 4)]
        if self.t == "compact_dense":
            return [Paragraph(escape(text), self.styles["heading"]), HRFlowable(width="100%", thickness=1.2, color=_rgb(self.accent), spaceAfter=2)]
        if self.t == "classic_serif":
            style = ParagraphStyle("hc", parent=self.styles["heading"], alignment=TA_CENTER, fontSize=self.size + 1.5)
            return [HRFlowable(width="100%", thickness=0.4, color=colors.grey, spaceBefore=6, spaceAfter=2), Paragraph(escape(text.upper()), style),
                    HRFlowable(width="100%", thickness=0.4, color=colors.grey, spaceAfter=4)]
        if self.t == "academic_cv":
            return [Paragraph(escape(text), self.styles["heading"]), HRFlowable(width="100%", thickness=0.3, color=colors.black, spaceAfter=3)]
        return [Paragraph(escape(text), self.styles["heading"]), HRFlowable(width="100%", thickness=0.8, color=colors.Color(0.2, 0.2, 0.2), spaceAfter=4)]

    def bullet_items(self, lines, numbered=False, style="bullet") -> list:
        out = []
        for index, line in enumerate(lines, start=1):
            glyph = f"{index}." if (numbered or self.bullet == "numbered") else self.bullet
            if self.bullet == "numbered" and not numbered:
                glyph = "•"
            out.append(Paragraph(escape(line.text), self.styles[style], bulletText=glyph))
        return out

    def two_col(self, left, right, widths=(0.72, 0.28), avail=None) -> Table:
        avail = avail or self.frame_width
        tbl = Table([[left, right]], colWidths=[avail * widths[0], avail * widths[1]])
        tbl.setStyle(TableStyle([("VALIGN", (0, 0), (-1, -1), "TOP"), ("LEFTPADDING", (0, 0), (-1, -1), 0), ("RIGHTPADDING", (0, 0), (-1, -1), 0),
                                 ("TOPPADDING", (0, 0), (-1, -1), 0), ("BOTTOMPADDING", (0, 0), (-1, -1), 1)]))
        return tbl

    # ----------------------------------------------------------------------------------------- blocks
    def role(self, entry: RoleEntry, avail: float) -> list:
        out = []
        loc = f", {entry.location}" if entry.location else ""
        layout = entry.layout
        if self.t == "compact_dense":
            layout = "inline"
        if layout == "keyvalue":
            rows = [("Organisation", f"{entry.employer}{loc}"), ("Designation", entry.title), ("Duration", entry.dates)]
            for key, value in rows:
                out.append(Paragraph(f"<b>{escape(key)}</b> : {escape(value)}", self.styles["body"]))
        elif layout == "inline":
            left = Paragraph(f"<b>{escape(entry.title)}</b>, {escape(entry.employer)}{escape(loc)}", self.styles["body"])
            out.append(self.two_col(left, Paragraph(escape(entry.dates), self.styles["right"]), avail=avail))
        elif layout == "date_first":
            out.append(Paragraph(escape(entry.dates), self.styles["meta"]))
            out.append(Paragraph(f"<b>{escape(entry.title)}</b> | {escape(entry.employer)}{escape(loc)}", self.styles["body"]))
        else:  # stacked
            if self.t == "classic_serif" or self.rng.random() < 0.5:
                out.append(self.two_col(Paragraph(f"<b>{escape(entry.employer)}</b>", self.styles["body"]),
                                        Paragraph(escape(entry.location or ""), self.styles["right"]), avail=avail))
                out.append(self.two_col(Paragraph(f"<i>{escape(entry.title)}</i>", self.styles["body"]), Paragraph(escape(entry.dates), self.styles["right"]),
                                        avail=avail))
            else:
                out.append(self.two_col(Paragraph(f"<b>{escape(entry.title)}</b>", self.styles["role"]), Paragraph(escape(entry.dates), self.styles["right"]),
                                        avail=avail))
                out.append(Paragraph(f"{escape(entry.employer)}{escape(loc)}", self.styles["meta"]))
        if layout == "keyvalue" and (entry.bullets or entry.projects):
            out.append(Paragraph("<b>Roles &amp; Responsibilities:</b>" if entry.bullets else "", self.styles["body"]))
        out += self.bullet_items(entry.bullets)
        for kvs, bullets, env in entry.projects:
            out.append(Spacer(1, 2))
            for key, value in kvs:
                out.append(Paragraph(f"<b>{escape(key)}</b>: {escape(value)}", self.styles["small"]))
            if bullets:
                out.append(Paragraph("<b>Responsibilities:</b>", self.styles["small"]))
                out += self.bullet_items(bullets)
            if env:
                out.append(Paragraph(escape(env.text), self.styles["meta"]))
        if entry.env:
            out.append(Paragraph(escape(entry.env.text), self.styles["meta"]))
        out.append(Spacer(1, 5))
        return out

    def edu(self, entry: EduEntry, avail: float, side: bool = False) -> list:
        if side:
            st = self.styles["side"]
            out = [Paragraph(f"<b>{escape(entry.degree_line)}</b>", st), Paragraph(escape(entry.institution), st),
                   Paragraph(escape(f"{entry.dates}  {entry.grade}".strip()), st)]
            return out + [Paragraph(escape(x.text), st) for x in entry.extra] + [Spacer(1, 4)]
        left = Paragraph(f"<b>{escape(entry.degree_line)}</b><br/>{escape(entry.institution)}" + (f"<br/>{escape(entry.grade)}" if entry.grade else ""),
                         self.styles["body"])
        out = [self.two_col(left, Paragraph(escape(entry.dates), self.styles["right"]), avail=avail)]
        out += [Paragraph(escape(x.text), self.styles["small"]) for x in entry.extra]
        return out + [Spacer(1, 3)]

    def skills(self, block: SkillGroups, avail: float, side: bool = False) -> list:
        st = self.styles["side" if side else "body"]
        out = []
        if block.style == "bars":
            text_color = colors.white if side else colors.black
            dot_color = colors.white if side else _rgb(self.accent)
            for _, items in block.groups:
                for line in items:
                    name = line.skills[0] if line.skills else line.text
                    out.append(RatingBar(line.text, block.ratings.get(name, 4), self.font, self.size - 0.5, dot_color, self.symbol_font, avail,
                                         text_color))
            return out
        if block.style == "table" or (block.style == "tags" and not side):
            items = [line.text for _, lines in block.groups for line in lines]
            cols = 3 if avail > 300 else 2
            rows = [items[i:i + cols] for i in range(0, len(items), cols)]
            rows[-1] += [""] * (cols - len(rows[-1]))
            data = [[Paragraph(f"{self.bullet if self.bullet != 'numbered' else '•'} {escape(c)}" if c else "", st) for c in row] for row in rows]
            tbl = Table(data, colWidths=[avail / cols] * cols)
            tbl.setStyle(TableStyle([("LEFTPADDING", (0, 0), (-1, -1), 0), ("TOPPADDING", (0, 0), (-1, -1), 0.5), ("BOTTOMPADDING", (0, 0), (-1, -1), 0.5)]))
            return [tbl]
        if block.style == "bullets" or side:
            for _, items in block.groups:
                out += [Paragraph(escape(line.text), st, bulletText=self.bullet if self.bullet != "numbered" else "•") for line in items]
            return out
        for label, items in block.groups:
            text = ", ".join(line.text for line in items)
            out.append(Paragraph((f"<b>{escape(label)}:</b> " if label else "") + escape(text), st))
        return out

    def keyvalues(self, block: KeyValues, avail: float, side: bool = False) -> list:
        st = self.styles["side" if side else "body"]
        if side:
            return [Paragraph(f"<b>{escape(k)}</b>: {escape(v.text)}", st) for k, v in block.rows]
        data = [[Paragraph(escape(k), st), Paragraph(":", st), Paragraph(escape(v.text), st)] for k, v in block.rows]
        tbl = Table(data, colWidths=[avail * 0.28, 10, avail * 0.72 - 10])
        tbl.setStyle(TableStyle([("LEFTPADDING", (0, 0), (-1, -1), 0), ("TOPPADDING", (0, 0), (-1, -1), 0.5), ("BOTTOMPADDING", (0, 0), (-1, -1), 0.5)]))
        return [tbl]

    def table(self, block: DocTable, avail: float) -> list:
        st = ParagraphStyle("cell", parent=self.styles["small"], fontSize=self.size - 1)
        head = ParagraphStyle("cellh", parent=st, fontName=self.bold)
        data = [[Paragraph(escape(h), head) for h in block.header]] + [[Paragraph(escape(c.text), st) for c in row] for row in block.rows]
        widths = [0.24, 0.2, 0.3, 0.12, 0.14]
        tbl = Table(data, colWidths=[avail * w for w in widths], repeatRows=1)
        tbl.setStyle(TableStyle([("GRID", (0, 0), (-1, -1), 0.5, colors.Color(0.4, 0.4, 0.4)), ("BACKGROUND", (0, 0), (-1, 0), colors.Color(0.9, 0.9, 0.9)),
                                 ("VALIGN", (0, 0), (-1, -1), "MIDDLE"), ("TOPPADDING", (0, 0), (-1, -1), 2), ("BOTTOMPADDING", (0, 0), (-1, -1), 2)]))
        return [tbl, Spacer(1, 4)]

    def section(self, section: Section, avail: float, side: bool = False) -> list:
        out = list(self.heading(section.heading, side))
        if section.kind == "DECLARATION" and not side:
            for block in section.blocks:
                if isinstance(block, Para):
                    out.append(self.P(block.line.text))
                elif isinstance(block, KeyValues):
                    rows = dict((k, v.text) for k, v in block.rows)
                    out.append(Spacer(1, 10))
                    out.append(self.two_col(Paragraph(f"Place: {escape(rows.get('Place', ''))}<br/>Date: {escape(rows.get('Date', ''))}", self.styles["body"]),
                                            Paragraph(escape(rows.get("Signature", "")), ParagraphStyle("sig", parent=self.styles["body"], alignment=TA_RIGHT)),
                                            (0.6, 0.4), avail))
            return out
        for block in section.blocks:
            if isinstance(block, Para):
                style = "side" if side else ("meta" if block.line.kind == "meta" else "body")
                if section.kind == "PROJECTS" and block.line.kind == "meta" and not side:
                    out.append(Paragraph(f"<b>{escape(block.line.text)}</b>", self.styles["body"]))
                else:
                    out.append(self.P(block.line.text, style))
            elif isinstance(block, Bullets):
                out += self.bullet_items(block.items, block.numbered, "side" if side else "bullet")
            elif isinstance(block, RoleEntry):
                flow = self.role(block, avail)
                out.append(KeepTogether(flow[:3]))
                out += flow[3:]
            elif isinstance(block, EduEntry):
                out += self.edu(block, avail, side)
            elif isinstance(block, SkillGroups):
                out += self.skills(block, avail, side)
            elif isinstance(block, KeyValues):
                out += self.keyvalues(block, avail, side)
            elif isinstance(block, DocTable):
                out += self.table(block, avail) if not side else [Paragraph(escape(" | ".join(c.text for c in row)), self.styles["side"]) for row in block.rows]
        return out

    # ----------------------------------------------------------------------------------------- page furniture
    def contact_text(self, sep=" | ", icons=False) -> str:
        parts = []
        for kind, text in self.doc.header.contacts:
            if icons and kind in ICONS:
                icon = ICONS[kind]
                parts.append(f'<font name="{self.symbol_font}">{escape(icon)}</font> {escape(text)}' if len(icon) == 1 else f"<b>{icon}</b> {escape(text)}")
            else:
                parts.append(escape(text))
        return sep.join(parts)

    def header_flowables(self, avail: float) -> list:
        hd = self.doc.header
        out = []
        if hd.title_line:
            out.append(Paragraph(f"<u><b>{escape(hd.title_line)}</b></u>", ParagraphStyle("tl", fontName=self.bold, fontSize=self.size + 4,
                                                                                         leading=self.size + 8, alignment=TA_CENTER, spaceAfter=8)))
        center = self.t in {"classic_serif", "academic_cv"} or (self.t == "ats_classic" and self.rng.random() < 0.4)
        name_style = ParagraphStyle("nm", parent=self.styles["name"], alignment=TA_CENTER if center else TA_LEFT)
        contact_style = ParagraphStyle("ct", parent=self.styles["contact"], alignment=TA_CENTER if center else TA_LEFT)
        block = [Paragraph(escape(hd.name), name_style)]
        if hd.headline:
            block.append(Paragraph(escape(hd.headline), ParagraphStyle("hl", parent=self.styles["headline"], alignment=TA_CENTER if center else TA_LEFT)))
        if self.t in {"indian_traditional", "fresher_indian"}:
            block += [Paragraph(f"{escape(label)}: {escape(text)}", self.styles["contact"]) for label, text in
                      ((("Email" if k == "email" else "Mobile" if k == "phone" else "Address" if k == "city" else k.title()), v)
                       for k, v in hd.contacts)]
        else:
            block.append(Spacer(1, 2))
            block.append(Paragraph(self.contact_text(sep="  |  " if not center else "  •  "), contact_style))
        if hd.photo:
            img = Image(io.BytesIO(placeholder_png(self.rng)), width=72, height=86)
            out.append(self.two_col(block, img, (0.82, 0.18), avail))
        else:
            out += block
        out.append(Spacer(1, 6))
        return out

    def adversarial(self, c, width, height):
        if self.doc.hidden_text:
            c.saveState()
            c.setFillColorRGB(1, 1, 1)
            c.setFont(self.font, 4)
            text = self.doc.hidden_text
            y = 14
            for start in range(0, len(text), 220):
                c.drawString(30, y, text[start:start + 220])
                y += 5
            c.restoreState()
        if self.doc.tiny_text:
            c.saveState()
            c.setFillColorRGB(0.82, 0.82, 0.82)
            c.setFont(self.font, 1.6)
            c.drawString(40, 8, self.doc.tiny_text)
            c.restoreState()

    def footer(self, c, total):
        page = c.getPageNumber()
        width, height = self.pagesize
        c.saveState()
        c.setFont(self.font, 7.5)
        c.setFillColor(colors.Color(0.4, 0.4, 0.4))
        if self.doc.footer == "page":
            c.drawCentredString(width / 2, 20, f"Page {page} of {total}")
        elif self.doc.footer:
            c.drawString(40, 20, self.doc.footer)
            c.drawRightString(width - 40, 20, f"{page}/{total}")
        if self.t == "academic_cv" and page > 1:
            c.drawRightString(width - 50, height - 30, f"{self.doc.header.name} - Curriculum Vitae")
        c.restoreState()

    # ----------------------------------------------------------------------------------------- build
    def render(self) -> bytes:
        width, height = self.pagesize
        buf = io.BytesIO()
        margin = {"compact_dense": 28, "modern_sidebar": 0, "creative_color": 36}.get(self.t, 46)
        story: list = []
        templates = []

        def first_page(c, d):
            self.decorate(c, d, first=True)
            self.adversarial(c, width, height)

        def later_page(c, d):
            self.decorate(c, d, first=False)

        if self.t == "modern_sidebar":
            side_w = 178
            self.frame_width = width - side_w - 36 - 22
            side = Frame(14, 24, side_w - 28, height - 48, id="side", showBoundary=0)
            main = Frame(side_w + 22, 30, self.frame_width, height - 60, id="main")
            later = Frame(side_w + 22, 30, self.frame_width, height - 60, id="later")
            templates = [PageTemplate("first", [side, main], onPage=first_page), PageTemplate("later", [later], onPage=later_page)]
            story += self.sidebar_story(side_w - 28)
            story.append(FrameBreak())
            story.append(NextPageTemplate("later"))
            story += [Paragraph(escape(self.doc.header.name), ParagraphStyle("nm2", parent=self.styles["name"], fontSize=self.size + 14,
                                                                             leading=self.size + 18, textColor=_rgb(self.accent)))]
            if self.doc.header.headline:
                story.append(Paragraph(escape(self.doc.header.headline), self.styles["headline"]))
            story.append(Spacer(1, 8))
            for section in self.doc.sections:
                if section.column != "side":
                    story += self.section(section, self.frame_width)
        else:
            top = 150 if self.t == "creative_color" else margin
            self.frame_width = width - 2 * margin
            frame1 = Frame(margin, 36, self.frame_width, height - top - 36 - (0 if self.t == "creative_color" else 0), id="f1")
            frame2 = Frame(margin, 36, self.frame_width, height - margin - 46, id="f2")
            templates = [PageTemplate("first", [frame1], onPage=first_page), PageTemplate("later", [frame2], onPage=later_page)]
            story.append(NextPageTemplate("later"))
            if self.t == "creative_color":
                story += self.creative_body()
            elif self.t == "table_grid":
                story += self.header_flowables(self.frame_width)
                story += self.grid_body()
            else:
                story += self.header_flowables(self.frame_width)
                for section in self.doc.sections:
                    story.append(CondPageBreak(60))
                    story += self.section(section, self.frame_width)
        doc = BaseDocTemplate(buf, pagesize=self.pagesize, pageTemplates=templates, title=f"{self.doc.header.name} Resume",
                              author=self.doc.header.name, creator="Microsoft Word" if self.rng.random() < 0.3 else "")
        footer = self.footer if self.doc.footer or self.t == "academic_cv" else None
        doc.build(story, canvasmaker=lambda *a, **k: NumberedCanvas(*a, footer=footer, **k))
        return buf.getvalue()

    def decorate(self, c, d, first: bool):
        width, height = self.pagesize
        if self.t == "modern_sidebar":
            c.saveState()
            c.setFillColor(_rgb(self.accent))
            c.rect(0, 0, 178, height, stroke=0, fill=1)
            c.restoreState()
        if self.t == "creative_color" and first:
            c.saveState()
            c.setFillColor(_rgb(self.accent))
            c.rect(0, height - 120, width, 120, stroke=0, fill=1)
            c.setFillColor(colors.white)
            c.setFont(self.bold, self.size + 15)
            c.drawString(36, height - 58, self.doc.header.name)
            if self.doc.header.headline:
                c.setFont(self.font, self.size + 2.5)
                c.drawString(36, height - 80, self.doc.header.headline)
            c.setFont(self.font, self.size - 0.8)
            x = 36
            for kind, text in self.doc.header.contacts[:4]:
                icon = ICONS.get(kind, "")
                if len(icon) == 1:
                    c.setFont(self.symbol_font, self.size - 0.5)
                    c.drawString(x, height - 104, icon)
                    x += 12
                c.setFont(self.font, self.size - 0.8)
                c.drawString(x, height - 104, text)
                x += c.stringWidth(text, self.font, self.size - 0.8) + 16
            if self.doc.header.photo:
                from reportlab.lib.utils import ImageReader
                c.drawImage(ImageReader(io.BytesIO(placeholder_png(self.rng))), width - 110, height - 112, 72, 96)
            c.restoreState()

    def sidebar_story(self, w: float) -> list:
        out = []
        hd = self.doc.header
        if hd.photo:
            out.append(Image(io.BytesIO(placeholder_png(self.rng, circle=True)), width=90, height=90))
            out.append(Spacer(1, 6))
        out.append(Paragraph("CONTACT", self.styles["side_head"]))
        out.append(HRFlowable(width="100%", thickness=0.6, color=colors.white, spaceAfter=3))
        for kind, text in hd.contacts:
            icon = ICONS.get(kind, "")
            prefix = f'<font name="{self.symbol_font}">{escape(icon)}</font> ' if len(icon) == 1 else ""
            out.append(Paragraph(prefix + escape(text), self.styles["side"]))
        for section in self.doc.sections:
            if section.column == "side":
                out += self.section(section, w, side=True)
        return out

    def creative_body(self) -> list:
        out = []
        main = [s for s in self.doc.sections if s.kind not in {"EDUCATION", "LANGUAGES", "CERTIFICATIONS", "INTERESTS"}]
        bottom = [s for s in self.doc.sections if s.kind in {"EDUCATION", "LANGUAGES", "CERTIFICATIONS", "INTERESTS"}]
        for section in main:
            out.append(CondPageBreak(60))
            out += self.section(section, self.frame_width)
        if bottom:
            half = self.frame_width / 2 - 8
            left = [f for s in bottom if s.kind in {"EDUCATION", "CERTIFICATIONS"} for f in self.section(s, half)]
            right = [f for s in bottom if s.kind not in {"EDUCATION", "CERTIFICATIONS"} for f in self.section(s, half)]
            tbl = Table([[left or "", right or ""]], colWidths=[half + 8, half + 8])
            tbl.setStyle(TableStyle([("VALIGN", (0, 0), (-1, -1), "TOP"), ("LEFTPADDING", (0, 0), (-1, -1), 0)]))
            out += [CondPageBreak(160), tbl]
        return out

    def grid_body(self) -> list:
        label_w = 110
        content_w = self.frame_width - label_w - 8
        rows = []
        starts = []
        for section in self.doc.sections:
            flowables = []
            for f in self.section(section, content_w):
                flowables += list(f._content) if isinstance(f, KeepTogether) else [f]  # KeepTogether cannot live in a table cell
            body = [f for f in flowables if not isinstance(f, (HRFlowable, Spacer, CondPageBreak))][1:]  # drop the heading paragraph
            label = Paragraph(f"<b>{escape(section.heading)}</b>", ParagraphStyle("gl", parent=self.styles["body"], textColor=_rgb(self.accent)))
            starts.append(len(rows))
            for position, flowable in enumerate(body or [Spacer(1, 1)]):
                rows.append([label if position == 0 else "", flowable])
        if not rows:
            return []
        tbl = Table(rows, colWidths=[label_w, content_w + 8], splitByRow=1)
        commands = [("VALIGN", (0, 0), (-1, -1), "TOP"), ("TOPPADDING", (0, 0), (-1, -1), 1), ("BOTTOMPADDING", (0, 0), (-1, -1), 1)]
        for start in starts[1:]:
            commands.append(("LINEABOVE", (0, start), (-1, start), 0.4, colors.Color(0.75, 0.75, 0.75)))
            commands.append(("TOPPADDING", (0, start), (-1, start), 6))
        tbl.setStyle(TableStyle(commands))
        return [tbl]


def _hex(color) -> str:
    return "".join(f"{int(c * 255):02x}" for c in color)


def render_pdf(doc: Document, seed: str) -> bytes:
    return PdfRenderer(doc, seed).render()
