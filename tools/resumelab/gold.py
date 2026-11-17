"""Gold labels read back from a composed Document (what a careful human reader can establish)."""
from __future__ import annotations

from resumelab.compose import Document, EduEntry, RoleEntry, Section, Table, block_lines
from resumelab.jobs import Evidence
from resumelab.model import Candidate, union_months, ym
from resumelab.taxonomy import taxonomy_id

# Sections whose text is not a professional skill claim (the parser ignores them too).
NON_SKILL_SECTIONS = frozenset({"INTERESTS", "REFERENCES", "DECLARATION"})
_PRIORITY = {"EXPERIENCE": 0, "INTERNSHIPS": 0, "SKILLS": 1, "PROJECTS": 2, "CERTIFICATIONS": 3, "SUMMARY": 4, "LANGUAGES": 1}


def rendered_roles(doc: Document) -> list[RoleEntry]:
    seen: dict[int, RoleEntry] = {}
    for section in doc.sections:
        for block in section.blocks:
            if isinstance(block, RoleEntry) and id(block.role) not in seen:
                seen[id(block.role)] = block
    return list(seen.values())


def rendered_education(doc: Document) -> list:
    records = []
    for section in doc.sections:
        for block in section.blocks:
            if isinstance(block, EduEntry):
                records.append(block.record)
            elif isinstance(block, Table) and block.records:
                records.extend(block.records)
    return records


def build_gold(doc: Document, cand: Candidate, as_of: int) -> tuple[dict, Evidence]:
    entries = rendered_roles(doc)
    roles = [entry.role for entry in entries]
    evidence_lines: dict[str, tuple[int, str, str, str]] = {}  # name -> (priority, text, section, surface)
    for name, shown in doc.header.skills.items():  # the headline under the name ("Customer Care Executive")
        evidence_lines[name] = (6, doc.header.headline or shown, "HEADER", shown)
    for section in doc.sections:
        if section.kind in NON_SKILL_SECTIONS:
            continue
        for block in section.blocks:
            for line in block_lines(block):
                for name in line.skills:
                    priority = _PRIORITY.get(section.kind, 5)
                    current = evidence_lines.get(name)
                    if current is None or priority < current[0]:
                        evidence_lines[name] = (priority, line.text, section.kind, line.surfaces.get(name, name))
    skills = []
    months_map: dict[str, int | None] = {}
    for name, (_, text, section_kind, shown) in sorted(evidence_lines.items()):
        intervals = [(role.start, role.end_or(as_of)) for role in roles if name in role.mentioned()]
        months = union_months(intervals) if intervals else None
        months_map[name] = months
        skills.append({"name": name, "taxonomyId": taxonomy_id(name), "months": months, "evidence": text, "section": section_kind, "surface": shown})
    gold_roles = []
    for entry in sorted(entries, key=lambda e: (-(e.role.end_or(as_of)), -e.role.start)):
        role = entry.role
        gold_roles.append({"title": role.title, "employer": role.employer, "start": ym(role.start), "end": None if role.end is None else ym(role.end),
                           "kind": role.kind, "datePrecision": entry.date_precision})
    total = union_months([(role.start, role.end_or(as_of)) for role in roles])
    education = []
    degrees = []
    for record in rendered_education(doc):
        education.append({"degree": record.label, "field": record.field, "institution": record.institution, "year": record.end_year,
                          "grade": record.grade, "level": record.level, "pursuing": record.pursuing})
        if not record.pursuing:
            degrees.append(record)
    cert_keys: set[str] = set()
    cert_names = []
    if any(section.kind == "CERTIFICATIONS" for section in doc.sections):
        for cert in cand.certs:
            cert_keys.add(cert.key)
            cert_names.append(cert.name)
    gold = {"skills": skills, "roles": gold_roles, "totalMonths": total, "education": education, "certifications": cert_names}
    evidence = Evidence(skills=months_map, role_intervals=[(r.start, r.end_or(as_of), r.profession) for r in roles], degrees=degrees, certs=cert_keys)
    return gold, evidence


def section_kinds(doc: Document) -> list[str]:
    return [section.kind for section in doc.sections]


def find_section(doc: Document, kind: str) -> Section | None:
    return next((section for section in doc.sections if section.kind == kind), None)
