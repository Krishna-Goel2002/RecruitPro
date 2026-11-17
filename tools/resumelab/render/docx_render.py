"""DOCX rendering with python-docx: styles, tables, headers/footers with page fields, VML text boxes, hidden runs."""
from __future__ import annotations

import io
import random
import zipfile
from datetime import datetime

from docx import Document as DocxDocument
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH, WD_TAB_ALIGNMENT
from docx.oxml import parse_xml
from docx.oxml.ns import nsdecls, qn
from docx.shared import Inches, Pt, RGBColor

from resumelab.compose import Bullets, Document, EduEntry, KeyValues, Para, RoleEntry, Section, SkillGroups, Table as DocTable
from resumelab.render.photo import placeholder_png

FONTS = {"ats_classic": ("Calibri", "Arial"), "classic_serif": ("Cambria", "Georgia", "Times New Roman"), "table_grid": ("Calibri", "Verdana"),
         "academic_cv": ("Times New Roman", "Garamond"), "fresher_indian": ("Times New Roman", "Arial", "Calibri"),
         "indian_traditional": ("Times New Roman", "Book Antiqua", "Arial"), "docx_textbox": ("Calibri", "Segoe UI")}


def _shade(cell, hex_fill: str) -> None:
    cell._tc.get_or_add_tcPr().append(parse_xml(f'<w:shd {nsdecls("w")} w:val="clear" w:color="auto" w:fill="{hex_fill}"/>'))


def _borders(table, size=4, color="808080") -> None:
    tbl_pr = table._tbl.tblPr
    xml = f'<w:tblBorders {nsdecls("w")}>' + "".join(
        f'<w:{edge} w:val="single" w:sz="{size}" w:space="0" w:color="{color}"/>' for edge in ("top", "left", "bottom", "right", "insideH", "insideV")
    ) + "</w:tblBorders>"
    tbl_pr.append(parse_xml(xml))


def _no_borders(table) -> None:
    xml = f'<w:tblBorders {nsdecls("w")}>' + "".join(f'<w:{edge} w:val="nil"/>' for edge in ("top", "left", "bottom", "right", "insideH", "insideV")) \
        + "</w:tblBorders>"
    table._tbl.tblPr.append(parse_xml(xml))


def _page_field(paragraph, instr: str) -> None:
    run = paragraph.add_run()
    run._r.append(parse_xml(f'<w:fldChar {nsdecls("w")} w:fldCharType="begin"/>'))
    run2 = paragraph.add_run()
    run2._r.append(parse_xml(f'<w:instrText {nsdecls("w")} xml:space="preserve"> {instr} </w:instrText>'))
    run3 = paragraph.add_run()
    run3._r.append(parse_xml(f'<w:fldChar {nsdecls("w")} w:fldCharType="separate"/>'))
    paragraph.add_run("1")
    run4 = paragraph.add_run()
    run4._r.append(parse_xml(f'<w:fldChar {nsdecls("w")} w:fldCharType="end"/>'))


def _bottom_rule(paragraph, color="404040") -> None:
    p_pr = paragraph._p.get_or_add_pPr()
    p_pr.append(parse_xml(f'<w:pBdr {nsdecls("w")}><w:bottom w:val="single" w:sz="6" w:space="1" w:color="{color}"/></w:pBdr>'))


def _textbox_paragraph(doc, lines: list[str], font: str, size: float) -> None:
    """A floating VML text box (how many Word templates hold the contact block)."""
    paras = "".join(
        f'<w:p><w:pPr><w:jc w:val="right"/></w:pPr><w:r><w:rPr><w:rFonts w:ascii="{font}" w:hAnsi="{font}"/><w:sz w:val="{int(size * 2)}"/></w:rPr>'
        f'<w:t xml:space="preserve">{_xml(line)}</w:t></w:r></w:p>' for line in lines)
    xml = (f'<w:r {nsdecls("w")} xmlns:v="urn:schemas-microsoft-com:vml" xmlns:o="urn:schemas-microsoft-com:office:office">'
           '<w:pict><v:shapetype id="_x0000_t202" coordsize="21600,21600" o:spt="202" path="m,l,21600r21600,l21600,xe">'
           '<v:stroke joinstyle="miter"/><v:path gradientshapeok="t" o:connecttype="rect"/></v:shapetype>'
           '<v:shape id="ContactBox" o:spid="_x0000_s1026" type="#_x0000_t202" '
           'style="position:absolute;margin-left:290pt;margin-top:-6pt;width:190pt;height:78pt;z-index:251659264;'
           'mso-position-horizontal-relative:margin;mso-position-vertical-relative:text" stroked="f">'
           f'<v:textbox><w:txbxContent>{paras}</w:txbxContent></v:textbox></v:shape></w:pict></w:r>')
    paragraph = doc.add_paragraph()
    paragraph._p.append(parse_xml(xml))


def _xml(text: str) -> str:
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;")


class DocxRenderer:
    def __init__(self, doc: Document, seed: str) -> None:
        self.doc = doc
        self.rng = random.Random(f"docx:{seed}")
        self.t = doc.template if doc.template in FONTS else "ats_classic"
        self.font = self.rng.choice(FONTS[self.t])
        self.size = {"academic_cv": 11, "indian_traditional": 11.5, "fresher_indian": 11}.get(self.t, 10.5)
        self.accent = "".join(f"{int(c * 255):02X}" for c in doc.style["accent"])
        self.d = DocxDocument()
        self.bullet_style = "List Number" if doc.style["bullet"] == "numbered" else "List Bullet"

    # ----------------------------------------------------------------------------------------- basics
    def setup(self) -> None:
        normal = self.d.styles["Normal"]
        normal.font.name = self.font
        normal.font.size = Pt(self.size)
        normal.element.rPr.rFonts.set(qn("w:eastAsia"), self.font)
        normal.paragraph_format.space_after = Pt(2)
        for name in ("Heading 1", "Heading 2", "Title"):
            style = self.d.styles[name]
            style.font.name = self.font
            style.font.color.rgb = RGBColor.from_string(self.accent if self.t in {"docx_textbox", "table_grid"} else "000000")
        section = self.d.sections[0]
        margin = Inches(0.6 if self.t != "academic_cv" else 0.9)
        section.left_margin = section.right_margin = margin
        section.top_margin = section.bottom_margin = Inches(0.6)
        props = self.d.core_properties
        props.author = self.doc.header.name
        props.title = f"{self.doc.header.name} Resume"
        props.created = props.modified = datetime(2024, 1, 1)
        props.last_modified_by = self.doc.header.name
        props.revision = 1
        props.comments = ""

    def para(self, text: str = "", bold=False, italic=False, size=None, align=None, style=None, color=None):
        paragraph = self.d.add_paragraph(style=style) if style else self.d.add_paragraph()
        if text:
            run = paragraph.add_run(text)
            run.bold, run.italic = bold or None, italic or None
            if size:
                run.font.size = Pt(size)
            if color:
                run.font.color.rgb = RGBColor.from_string(color)
        if align:
            paragraph.alignment = align
        return paragraph

    def heading(self, text: str) -> None:
        case = self.doc.style["heading_case"]
        shown = text.upper() if case == "upper" else text
        if self.t in {"fresher_indian", "indian_traditional"}:
            table = self.d.add_table(rows=1, cols=1)
            cell = table.cell(0, 0)
            _shade(cell, "D9D9D9")
            run = cell.paragraphs[0].add_run(shown)
            run.bold = True
            run.font.size = Pt(self.size + 1)
            self.d.add_paragraph().paragraph_format.space_after = Pt(0)
            return
        if self.rng.random() < 0.5 or self.t == "academic_cv":
            paragraph = self.d.add_paragraph(style="Heading 1")
            paragraph.add_run(shown)
        else:
            paragraph = self.para(shown, bold=True, size=self.size + 2, color=self.accent if self.t == "docx_textbox" else None)
            _bottom_rule(paragraph)
        paragraph.paragraph_format.space_before = Pt(8)

    def bullets(self, lines, numbered=False) -> None:
        for line in lines:
            if self.doc.style["bullet"] in {"-", "–", "»"} and not numbered:
                self.para(f"{self.doc.style['bullet']} {line.text}")
            else:
                self.d.add_paragraph(line.text, style="List Number" if numbered else self.bullet_style)

    # ----------------------------------------------------------------------------------------- blocks
    def role(self, entry: RoleEntry) -> None:
        loc = f", {entry.location}" if entry.location else ""
        if entry.layout == "keyvalue":
            for key, value in (("Organisation", f"{entry.employer}{loc}"), ("Designation", entry.title), ("Duration", entry.dates)):
                paragraph = self.d.add_paragraph()
                paragraph.add_run(f"{key}: ").bold = True
                paragraph.add_run(value)
            if entry.bullets:
                self.para("Roles & Responsibilities:", bold=True)
        else:
            paragraph = self.d.add_paragraph()
            width = self.d.sections[0].page_width - self.d.sections[0].left_margin - self.d.sections[0].right_margin
            paragraph.paragraph_format.tab_stops.add_tab_stop(width, WD_TAB_ALIGNMENT.RIGHT)
            if entry.layout == "date_first":
                paragraph.add_run(entry.dates).italic = True
                second = self.d.add_paragraph()
                second.add_run(entry.title).bold = True
                second.add_run(f" | {entry.employer}{loc}")
            elif entry.layout == "inline":
                paragraph.add_run(entry.title).bold = True
                paragraph.add_run(f", {entry.employer}{loc}\t")
                paragraph.add_run(entry.dates).italic = True
            else:
                paragraph.add_run(entry.employer).bold = True
                paragraph.add_run(f"{loc}\t")
                paragraph.add_run(entry.dates)
                self.para(entry.title, italic=True)
        self.bullets(entry.bullets)
        for kvs, bullets, env in entry.projects:
            for key, value in kvs:
                paragraph = self.d.add_paragraph()
                paragraph.add_run(f"{key}: ").bold = True
                paragraph.add_run(value)
            if bullets:
                self.para("Responsibilities:", bold=True)
                self.bullets(bullets)
            if env:
                self.para(env.text, italic=True)
        if entry.env:
            self.para(entry.env.text, italic=True)

    def skills(self, block: SkillGroups) -> None:
        if block.style in {"table", "tags"} or (block.style == "bars" and self.t != "plain"):
            items = [line for _, lines in block.groups for line in lines]
            cols = 3
            rows = (len(items) + cols - 1) // cols
            table = self.d.add_table(rows=rows, cols=cols)
            if self.t == "table_grid" or self.rng.random() < 0.4:
                table.style = "Table Grid"
            for index, line in enumerate(items):
                text = line.text
                if block.style == "bars":
                    name = line.skills[0] if line.skills else line.text
                    rating = block.ratings.get(name, 4)
                    text = f"{line.text}  {'★' * rating}{'☆' * (5 - rating)}"
                table.cell(index // cols, index % cols).text = text
            return
        if block.style == "bullets":
            self.bullets([line for _, lines in block.groups for line in lines])
            return
        for label, lines in block.groups:
            paragraph = self.d.add_paragraph()
            if label:
                paragraph.add_run(f"{label}: ").bold = True
            paragraph.add_run(", ".join(line.text for line in lines))

    def keyvalues(self, block: KeyValues) -> None:
        table = self.d.add_table(rows=len(block.rows), cols=3)
        _no_borders(table)
        for index, (key, value) in enumerate(block.rows):
            table.cell(index, 0).text = key
            table.cell(index, 1).text = ":"
            table.cell(index, 2).text = value.text
        for row in table.rows:
            row.cells[0].width, row.cells[1].width, row.cells[2].width = Inches(1.8), Inches(0.2), Inches(4.5)

    def table(self, block: DocTable) -> None:
        table = self.d.add_table(rows=1 + len(block.rows), cols=len(block.header))
        table.style = "Table Grid"
        table.alignment = WD_TABLE_ALIGNMENT.CENTER
        for col, text in enumerate(block.header):
            cell = table.cell(0, col)
            cell.text = text
            cell.paragraphs[0].runs[0].bold = True
            _shade(cell, "EDEDED")
        for row_index, row in enumerate(block.rows, start=1):
            for col, line in enumerate(row):
                table.cell(row_index, col).text = line.text

    def edu(self, entry: EduEntry) -> None:
        paragraph = self.d.add_paragraph()
        paragraph.add_run(entry.degree_line).bold = True
        paragraph.add_run(f"  ({entry.dates})")
        self.para(f"{entry.institution}" + (f" | {entry.grade}" if entry.grade else ""))
        for line in entry.extra:
            self.para(line.text, italic=True)

    def section(self, section: Section) -> None:
        self.heading(section.heading)
        for block in section.blocks:
            if isinstance(block, Para):
                self.para(block.line.text, bold=section.kind == "PROJECTS" and block.line.kind == "meta",
                          italic=block.line.kind == "meta" and section.kind != "PROJECTS")
            elif isinstance(block, Bullets):
                self.bullets(block.items, block.numbered)
            elif isinstance(block, RoleEntry):
                self.role(block)
            elif isinstance(block, EduEntry):
                self.edu(block)
            elif isinstance(block, SkillGroups):
                self.skills(block)
            elif isinstance(block, KeyValues):
                if section.kind == "DECLARATION":
                    rows = {k: v.text for k, v in block.rows}
                    self.para("")
                    paragraph = self.d.add_paragraph()
                    paragraph.paragraph_format.tab_stops.add_tab_stop(Inches(6.8), WD_TAB_ALIGNMENT.RIGHT)
                    paragraph.add_run(f"Place: {rows.get('Place', '')}\t{rows.get('Signature', '')}")
                    self.para(f"Date: {rows.get('Date', '')}")
                else:
                    self.keyvalues(block)
            elif isinstance(block, DocTable):
                self.table(block)

    # ----------------------------------------------------------------------------------------- header + whole
    def header(self) -> None:
        hd = self.doc.header
        if hd.title_line:
            paragraph = self.para(hd.title_line, bold=True, size=self.size + 4, align=WD_ALIGN_PARAGRAPH.CENTER)
            paragraph.runs[0].underline = True
        contacts = [text for _, text in hd.contacts]
        if self.t == "docx_textbox":
            _textbox_paragraph(self.d, contacts, self.font, self.size - 1)
            self.para(hd.name, bold=True, size=24, color=self.accent)
            if hd.headline:
                self.para(hd.headline, size=13, color="595959")
            return
        center = self.t in {"classic_serif", "academic_cv"}
        if hd.photo:
            table = self.d.add_table(rows=1, cols=2)
            _no_borders(table)
            left, right = table.cell(0, 0), table.cell(0, 1)
            left.width, right.width = Inches(5.4), Inches(1.3)
            run = left.paragraphs[0].add_run(hd.name)
            run.bold, run.font.size = True, Pt(20)
            if hd.headline:
                left.add_paragraph(hd.headline)
            for text in contacts:
                left.add_paragraph(text)
            right.paragraphs[0].add_run().add_picture(io.BytesIO(placeholder_png(self.rng)), width=Inches(1.05))
            return
        self.para(hd.name, bold=True, size=22 if self.t != "indian_traditional" else 16,
                  align=WD_ALIGN_PARAGRAPH.CENTER if center else None, style="Title" if self.rng.random() < 0.3 and not center else None)
        if hd.headline:
            self.para(hd.headline, size=self.size + 1.5, align=WD_ALIGN_PARAGRAPH.CENTER if center else None, color="404040")
        if self.t in {"indian_traditional", "fresher_indian"}:
            labels = {"email": "Email", "phone": "Mobile", "city": "Address"}
            for kind, text in hd.contacts:
                self.para(f"{labels.get(kind, kind.title())}: {text}")
        else:
            self.para("  |  ".join(contacts), size=self.size - 1, align=WD_ALIGN_PARAGRAPH.CENTER if center else None)

    def footer(self) -> None:
        if not self.doc.footer and self.t != "academic_cv":
            return
        section = self.d.sections[0]
        paragraph = section.footer.paragraphs[0]
        paragraph.alignment = WD_ALIGN_PARAGRAPH.CENTER
        if self.doc.footer and self.doc.footer != "page":
            paragraph.add_run(f"{self.doc.footer}    ")
        paragraph.add_run("Page ")
        _page_field(paragraph, "PAGE")
        paragraph.add_run(" of ")
        _page_field(paragraph, "NUMPAGES")
        if self.t == "academic_cv":
            section.header.paragraphs[0].text = f"{self.doc.header.name} - Curriculum Vitae"
            section.header.paragraphs[0].alignment = WD_ALIGN_PARAGRAPH.RIGHT

    def adversarial(self) -> None:
        if self.doc.hidden_text:
            paragraph = self.d.add_paragraph()
            run = paragraph.add_run(self.doc.hidden_text)
            if self.rng.random() < 0.5:
                run.font.color.rgb = RGBColor(0xFF, 0xFF, 0xFF)
                run.font.size = Pt(1)
            else:
                run.font.hidden = True
        if self.doc.tiny_text:
            paragraph = self.d.add_paragraph()
            run = paragraph.add_run(self.doc.tiny_text)
            run.font.size = Pt(1)
            run.font.color.rgb = RGBColor(0xD0, 0xD0, 0xD0)

    def render(self) -> bytes:
        self.setup()
        self.header()
        if self.t == "table_grid":
            self.grid()
        else:
            for section in self.doc.sections:
                self.section(section)
        self.footer()
        self.adversarial()
        out = io.BytesIO()
        self.d.save(out)
        return normalize_zip(out.getvalue())

    def grid(self) -> None:
        """Two-column table resume: section label on the left, content on the right (one row per section)."""
        table = self.d.add_table(rows=0, cols=2)
        _no_borders(table)
        for section in self.doc.sections:
            row = table.add_row()
            label, body = row.cells
            label.width, body.width = Inches(1.6), Inches(5.3)
            run = label.paragraphs[0].add_run(section.heading.upper())
            run.bold = True
            run.font.color.rgb = RGBColor.from_string(self.accent)
            _shade(label, "F2F2F2")
            first = True

            def add(text, bold=False, italic=False, style=None):
                nonlocal first
                paragraph = body.paragraphs[0] if first else body.add_paragraph(style=style)
                first = False
                if style and paragraph.style.name != style:
                    paragraph.style = self.d.styles[style]
                r = paragraph.add_run(text)
                r.bold, r.italic = bold or None, italic or None

            for block in section.blocks:
                if isinstance(block, Para):
                    add(block.line.text, italic=block.line.kind == "meta")
                elif isinstance(block, Bullets):
                    for line in block.items:
                        add(line.text, style="List Bullet")
                elif isinstance(block, RoleEntry):
                    loc = f", {block.location}" if block.location else ""
                    add(f"{block.title} - {block.employer}{loc}", bold=True)
                    add(block.dates, italic=True)
                    for line in block.bullets:
                        add(line.text, style="List Bullet")
                    for kvs, bullets, env in block.projects:
                        for key, value in kvs:
                            add(f"{key}: {value}")
                        for line in bullets:
                            add(line.text, style="List Bullet")
                        if env:
                            add(env.text, italic=True)
                    if block.env:
                        add(block.env.text, italic=True)
                elif isinstance(block, EduEntry):
                    add(block.degree_line, bold=True)
                    add(f"{block.institution} ({block.dates}) {block.grade}".strip())
                    for line in block.extra:
                        add(line.text, italic=True)
                elif isinstance(block, SkillGroups):
                    for group_label, lines in block.groups:
                        add((f"{group_label}: " if group_label else "") + ", ".join(line.text for line in lines))
                elif isinstance(block, KeyValues):
                    for key, value in block.rows:
                        add(f"{key}: {value.text}")
                elif isinstance(block, DocTable):
                    for line_row in block.rows:
                        add(" | ".join(cell.text for cell in line_row))


def normalize_zip(data: bytes) -> bytes:
    """Rewrite the package with fixed entry timestamps so the same seed gives byte-identical files."""
    source = zipfile.ZipFile(io.BytesIO(data))
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as target:
        for info in source.infolist():
            fixed = zipfile.ZipInfo(info.filename, date_time=(2024, 1, 1, 0, 0, 0))
            fixed.compress_type = zipfile.ZIP_DEFLATED
            fixed.external_attr = info.external_attr
            target.writestr(fixed, source.read(info.filename))
    return out.getvalue()


def render_docx(doc: Document, seed: str) -> bytes:
    return DocxRenderer(doc, seed).render()
