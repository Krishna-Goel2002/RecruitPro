"""Seeded synthetic resumes with known gold labels, rendered to real DOCX/PDF files.

Each base resume has a counterfactual twin that differs only in the candidate's first name,
gender, pronouns, honorific and contact details, so any difference in outcome is attributable to
identity. Formats: DOCX, single-column PDF and two-column PDF; some documents carry hidden
keyword stuffing or a hidden prompt injection. The gold skill set is the union of the skills
declared by every rendered string (see ``eval.domains``).
"""
from __future__ import annotations

import random
from dataclasses import dataclass, field
from datetime import date

from app.taxonomy.taxonomy import get_taxonomy
from eval.domains import (
    DISTRACTORS, DOMAINS, FEMALE_NAMES, INJECTION, INSTITUTIONS, INTERESTS, MALE_NAMES, STUFFING, SURNAMES, Domain,
)
from eval.render import DocxRun, PdfText, make_docx, make_pdf

EVAL_TODAY = date(2026, 11, 1)
_MONTHS = ("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
_FULL_MONTHS = ("January", "February", "March", "April", "May", "June", "July", "August", "September", "October",
                "November", "December")
_DATE_FORMATS = ("mon", "mon", "full", "num", "iso", "apos", "year")
_PRESENT = ("Present", "Current", "Till date", "Now")
_SEPARATORS = (" - ", " – ", " to ", " — ")
_HEADINGS = {
    "SUMMARY": ("Summary", "Profile", "Professional Summary"),
    "SKILLS": ("Skills", "Technical Skills", "Key Skills", "Core Competencies"),
    "EXPERIENCE": ("Experience", "Work Experience", "Professional Experience", "Employment History"),
    "EDUCATION": ("Education", "Academic Qualifications"),
    "CERTIFICATIONS": ("Certifications", "Licenses & Certifications"),
    "ACHIEVEMENTS": ("Achievements", "Awards"),
    "LANGUAGES": ("Languages", "Languages Known"),
    "INTERESTS": ("Hobbies", "Interests"),
    "PERSONAL": ("Personal Details",),
}
_LANGUAGE_SETS = (("English", "Hindi"), ("English", "Hindi", "Odia"), ("English", "Tamil"), ("English", "Bengali", "Hindi"))
_PLAIN_ACHIEVEMENTS = ("Employee of the Quarter, Q3 2023", "Top performer award for two consecutive years",
                       "Led the office charity drive that raised funds for a local school")


@dataclass(frozen=True)
class GoldRole:
    title: str
    employer: str
    start: int  # month ordinal (year * 12 + month - 1)
    end: int
    current: bool
    skills: frozenset[str]  # skills named in the role block
    implied: frozenset[str]  # skills the role title implies (relevance only)
    expected_start: str  # ISO date at the rendered precision
    expected_end: str | None


@dataclass
class SyntheticResume:
    id: str
    domain: Domain
    gender: str
    first_name: str
    surname: str
    format: str  # docx | pdf | pdf2
    twin_of: str | None = None
    blocks: list[tuple[str, str]] = field(default_factory=list)  # (kind, text); kinds: name contact heading text bullet
    sidebar: list[tuple[str, str]] = field(default_factory=list)  # two-column PDFs: left column blocks
    hidden: str | None = None
    gold_skills: set[str] = field(default_factory=set)
    roles: list[GoldRole] = field(default_factory=list)
    degree: tuple[str, tuple[str, ...]] = ("BACHELOR", ())
    certifications: set[str] = field(default_factory=set)
    injection: bool = False

    @property
    def filename(self) -> str:
        return f"{self.id}.{'docx' if self.format == 'docx' else 'pdf'}"


def _ordinal(year: int, month: int) -> int:
    return year * 12 + month - 1


def _format_date(ordinal: int, style: str) -> tuple[str, str]:
    year, month = divmod(ordinal, 12)
    month += 1
    rendered = {
        "mon": f"{_MONTHS[month - 1]} {year}", "full": f"{_FULL_MONTHS[month - 1]} {year}", "num": f"{month:02d}/{year}",
        "iso": f"{year}-{month:02d}", "apos": f"{_MONTHS[month - 1]} '{year % 100:02d}", "year": f"{year}",
    }[style]
    return rendered, (f"{year}" if style == "year" else f"{year}-{month:02d}")


class _Builder:
    """Builds one base resume (and, from the same random draws, its twin)."""

    def __init__(self, domain: Domain, index: int, seed: int) -> None:
        self.domain = domain
        self.rng = random.Random(f"{seed}:{domain.key}:{index}")
        self.index = index
        self.taxonomy = get_taxonomy()

    def surface(self, name: str, *, in_list: bool) -> str:
        aliases = self.domain.aliases.get(name, ())
        skill = self.taxonomy.lookup(name)
        if skill and skill.ambiguous and not in_list:
            return aliases[0] if aliases else ""
        return self.rng.choice(aliases) if aliases and self.rng.random() < 0.3 else name

    def build(self) -> tuple[SyntheticResume, SyntheticResume]:
        rng, domain = self.rng, self.domain
        gender = rng.choice("FM")
        name_index = rng.randrange(len(FEMALE_NAMES))
        surname = rng.choice(SURNAMES)
        fmt = rng.choices(("docx", "pdf", "pdf2"), weights=(4, 3.5, 2.5))[0]
        base_id = f"{domain.key}-{self.index:03d}"
        draws = self._draw()
        resumes = []
        for twin, this_gender in ((False, gender), (True, "M" if gender == "F" else "F")):
            first = (FEMALE_NAMES if this_gender == "F" else MALE_NAMES)[name_index]
            resume = SyntheticResume(f"{base_id}{'-twin' if twin else ''}", domain, this_gender, first, surname, fmt,
                                     twin_of=base_id if twin else None)
            self._render(resume, draws)
            resumes.append(resume)
        return resumes[0], resumes[1]

    # ----------------------------------------------------------------- random draws (shared by twins)
    def _draw(self) -> dict:
        rng, domain = self.rng, self.domain
        title, title_skills = rng.choice(domain.titles)
        listed = rng.sample(domain.skills, rng.randint(5, 9))
        draws: dict = {
            "title": title, "title_skills": title_skills, "listed": listed,
            "honorific": rng.random() < 0.15, "personal": rng.random() < 0.2, "languages": rng.random() < 0.4,
            "achievements": rng.random() < 0.6, "interests": rng.random() < 0.5, "other_section": rng.random() < 0.15,
            "school_line": rng.random() < 0.3, "date_style": rng.choice(_DATE_FORMATS), "separator": rng.choice(_SEPARATORS),
            "present": rng.choice(_PRESENT), "layout": rng.choice("abcd"), "labelled_skills": rng.random() < 0.3,
            "headings": {key: rng.choice(values) for key, values in _HEADINGS.items()},
            "phone": f"+91 9{rng.randint(100000000, 999999999)}", "dob": f"{rng.randint(1, 28):02d}/{rng.randint(1, 12):02d}/{rng.randint(1985, 2001)}",
            "degree": rng.choice(domain.degrees), "institution": rng.choice(INSTITUTIONS),
            "languages_set": rng.choice(_LANGUAGE_SETS), "interest": rng.choice(INTERESTS),
            "distractor": rng.choice(DISTRACTORS), "plain_achievement": rng.choice(_PLAIN_ACHIEVEMENTS),
            "summary_skills": [(name, self.surface(name, in_list=False) or name) for name in rng.sample(listed, 2)],
            "listed_surfaces": [self.surface(name, in_list=True) for name in listed],
            "hidden": rng.random() < 0.12, "injection": rng.random() < 0.06, "stuffing": rng.choice(STUFFING),
        }
        cert_keys = list(domain.certifications)
        roll = rng.random()
        draws["certs"] = rng.sample(cert_keys, 2 if roll < 0.15 and len(cert_keys) > 1 else 1) if roll < 0.55 else []
        # Roles, most recent first, with occasional gaps and one occasional overlap.
        today = _ordinal(EVAL_TODAY.year, EVAL_TODAY.month)
        roles = []
        end = today if rng.random() < 0.8 else today - rng.randint(1, 6)
        current = end == today
        for position in range(rng.choice((1, 2, 2, 3, 3, 4))):
            months = rng.randint(6, 40)
            start = end - months + 1
            role_title, title_mentions, title_implies = self._title(title if position == 0 else None)
            roles.append({"title": role_title, "mentions": title_mentions, "implies": title_implies,
                          "employer": rng.choice(domain.employers), "start": start, "end": end,
                          "current": current and position == 0, "bullets": self._bullets(listed)})
            overlap = rng.random() < 0.1
            end = start + (rng.randint(1, 3) if overlap else -rng.randint(1, 5))
            if end - 6 < _ordinal(2008, 1):
                break
        draws["roles"] = roles
        return draws

    def _title(self, fixed: str | None) -> tuple[str, tuple[str, ...], tuple[str, ...]]:
        title, mentions = next((item for item in self.domain.titles if item[0] == fixed), None) or self.rng.choice(self.domain.titles)
        implied = tuple(name for name in self.domain.skills if self._implies(title, name))
        return title, mentions, implied

    def _implies(self, title: str, skill_name: str) -> bool:
        """Ground truth for 'this job title practises this skill' (Recruiter -> Recruitment, Key Account Manager ->
        Account Management, Mathematics Teacher -> Teaching, Medical Coder -> Medical Coding)."""
        practitioner = {"Recruitment": ("Recruiter",), "Account Management": ("Key Account Manager",),
                        "Teaching": ("Teacher",), "Medical Coding": ("Medical Coder",)}
        return any(title.endswith(suffix) for suffix in practitioner.get(skill_name, ()))

    def _bullets(self, listed: list[str]) -> list[tuple[str, tuple[str, ...]]]:
        rng, domain = self.rng, self.domain
        bullets = []
        for _ in range(rng.randint(1, 3)):
            ambiguous = [name for name in domain.native if rng.random() < 0.25]
            if ambiguous and not any(text == domain.native[ambiguous[0]][0] for text, _ in bullets):
                bullets.append(domain.native[ambiguous[0]])
                continue
            pool = [name for name in listed if self.surface(name, in_list=False)] if rng.random() < 0.7 else []
            pool = pool if len(pool) >= 2 else [name for name in domain.skills if self.surface(name, in_list=False)]
            first, second = rng.sample(pool, 2)
            template = rng.choice(domain.templates)
            bullets.append((template.format(a=self.surface(first, in_list=False), b=self.surface(second, in_list=False)),
                            (first, second)))
        return bullets

    # ---------------------------------------------------------------------------------- rendering
    def _render(self, resume: SyntheticResume, draws: dict) -> None:
        """Pure function of the draws and the identity: twins differ only in name, gender and contact details."""
        domain = self.domain
        female = resume.gender == "F"
        headings = draws["headings"]
        main, side = resume.blocks, (resume.sidebar if resume.format == "pdf2" else resume.blocks)
        honorific = ("Ms. " if female else "Mr. ") if draws["honorific"] else ""
        main.append(("name", f"{honorific}{resume.first_name} {resume.surname}"))
        handle = f"{resume.first_name.lower()}.{resume.surname.lower()}"
        main.append(("contact", f"{handle}@example.test | {draws['phone']} | linkedin.com/in/{handle.replace('.', '-')}"))

        main.append(("heading", headings["SUMMARY"]))
        (a, a_text), (b, b_text) = draws["summary_skills"]
        summary = domain.summary.format(title=draws["title"], years=max(1, sum(r["end"] - r["start"] + 1 for r in draws["roles"]) // 12),
                                        a=a_text, b=b_text, pronoun="She" if female else "He")
        main.append(("text", summary))
        resume.gold_skills.update((a, b))

        side.append(("heading", headings["SKILLS"]))
        listed = draws["listed"]
        rendered = draws["listed_surfaces"]
        resume.gold_skills.update(listed)
        if resume.format == "pdf2":
            side.extend(("text", item) for item in rendered)
        elif draws["labelled_skills"]:
            half = len(rendered) // 2
            main.append(("text", "Tools: " + ", ".join(rendered[:half])))
            main.append(("text", "Other: " + ", ".join(rendered[half:])))
        else:
            main.append(("text", ", ".join(rendered)))

        main.append(("heading", headings["EXPERIENCE"]))
        for role in draws["roles"]:
            start_text, start_iso = _format_date(role["start"], draws["date_style"])
            end_text, end_iso = _format_date(role["end"], draws["date_style"])
            if role["current"]:
                end_text, end_iso = draws["present"], None
            dates = f"{start_text}{draws['separator']}{end_text}"
            title, employer = role["title"], role["employer"]
            layout = draws["layout"]
            if layout == "a":
                main += [("text", f"{title}, {employer}"), ("text", dates)]
            elif layout == "b":
                main.append(("text", f"{title} | {employer} | {dates}"))
            elif layout == "c":
                main.append(("text", f"{employer} — {title} ({dates})"))
            else:
                main += [("text", dates), ("text", f"{title}, {employer}")]
            mentioned = set(role["mentions"])
            for text, skills in role["bullets"]:
                main.append(("bullet", text))
                mentioned.update(skills)
            resume.gold_skills.update(mentioned)
            resume.roles.append(GoldRole(title, employer, role["start"], role["end"], role["current"], frozenset(mentioned),
                                         frozenset(role["implies"]), start_iso, end_iso))

        degree_text, level, families = draws["degree"]
        side.append(("heading", headings["EDUCATION"]))
        side.append(("text", f"{degree_text}, {draws['institution']}, {resume.roles[-1].start // 12 - 1}"))
        if draws["school_line"]:
            side.append(("text", "Class XII (CBSE), 2009"))
        resume.degree = (level, families)

        if draws["certs"]:
            side.append(("heading", headings["CERTIFICATIONS"]))
            for key in draws["certs"]:
                text, skills = domain.certifications[key]
                side.append(("text", text))
                resume.certifications.add(key)
                resume.gold_skills.update(skills)
        if draws["languages"]:
            side.append(("heading", headings["LANGUAGES"]))
            side.append(("text", ", ".join(draws["languages_set"])))
            resume.gold_skills.update(draws["languages_set"])
        if draws["achievements"]:
            main.append(("heading", headings["ACHIEVEMENTS"]))
            main += [("bullet", draws["plain_achievement"]), ("bullet", draws["distractor"])]
        if draws["other_section"]:
            main += [("heading", "BEYOND WORK"), ("text", "Weekend football coach for an under-12 team")]
        if draws["interests"]:
            main += [("heading", headings["INTERESTS"]), ("text", draws["interest"])]
        if draws["personal"]:
            main += [("heading", headings["PERSONAL"]), ("text", f"Gender: {'Female' if female else 'Male'}"),
                     ("text", f"Date of Birth: {draws['dob']}"), ("text", "Marital Status: Single")]
        if draws["hidden"]:
            resume.hidden = draws["stuffing"]
        if draws["injection"]:
            resume.hidden = f"{resume.hidden or ''} {INJECTION}".strip()
            resume.injection = True


def generate(seed: int, per_domain: int) -> list[SyntheticResume]:
    """Base resumes and their twins, interleaved (base, twin, base, twin, ...)."""
    resumes: list[SyntheticResume] = []
    for domain in DOMAINS:
        for index in range(per_domain):
            resumes.extend(_Builder(domain, index, seed).build())
    return resumes


def _wrap(text: str, width: int) -> list[str]:
    lines, current = [], ""
    for word in text.split():
        if current and len(current) + 1 + len(word) > width:
            lines.append(current)
            current = word
        else:
            current = f"{current} {word}".strip()
    return lines + ([current] if current else [])


def render(resume: SyntheticResume) -> tuple[str, bytes]:
    if resume.format == "docx":
        paragraphs: list[tuple[str | list[DocxRun], str]] = []
        for kind, text in resume.blocks:
            style = {"name": "Title", "heading": "Heading 1", "bullet": "List Bullet"}.get(kind, "Normal")
            paragraphs.append((text, style))
        if resume.hidden:
            paragraphs.append(([DocxRun(resume.hidden, color="FFFFFF", size=2)], "Normal"))
        return resume.filename, make_docx(paragraphs)
    items: list[PdfText] = []
    two_column = resume.format == "pdf2"
    x_main, width = (230, 62) if two_column else (60, 88)
    size = 9 if two_column else 10
    y = 760.0
    for kind, text in resume.blocks:
        if kind == "name":
            items.append(PdfText(text, x=60 if not two_column else 200, y=y, size=18, bold=True))
            y -= 22
            continue
        if kind == "contact":
            items.append(PdfText(text, x=60, y=y, size=9))
            y -= 26
            continue
        if kind == "heading":
            y -= 4
            items.append(PdfText(text.upper() if two_column else text, x=x_main, y=y, size=12, bold=True))
            y -= 16
            continue
        prefix = "• " if kind == "bullet" else ""
        for number, line in enumerate(_wrap(text, width)):
            items.append(PdfText((prefix if number == 0 else "  ") + line, x=x_main, y=y, size=size))
            y -= size * 1.45
    side_y = 690.0
    for kind, text in resume.sidebar:
        if kind == "heading":
            side_y -= 4
            items.append(PdfText(text.upper(), x=36, y=side_y, size=12, bold=True))
            side_y -= 16
            continue
        for line in _wrap(text, 30):
            items.append(PdfText(line, x=36, y=side_y, size=9))
            side_y -= 13
    pages = _paginate(items)
    if resume.hidden:
        pages[-1].append(PdfText(resume.hidden, x=60, y=30, size=8, color=(1, 1, 1)))
    return resume.filename, make_pdf(pages)


def _paginate(items: list[PdfText]) -> list[list[PdfText]]:
    """Move items that fell below the bottom margin onto continuation pages."""
    pages: list[list[PdfText]] = [[]]
    offset = 0.0
    for item in sorted(items, key=lambda entry: -(entry.y or 0)):
        y = (item.y or 0) + offset
        if y < 50:
            pages.append([])
            offset += 700
            y = (item.y or 0) + offset
        pages[-1].append(PdfText(item.text, x=item.x, y=y, size=item.size, color=item.color, bold=item.bold))
    return pages
