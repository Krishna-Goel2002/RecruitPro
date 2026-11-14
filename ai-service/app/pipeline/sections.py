"""Resume section segmentation (finding H4).

Headings are recognised from a lexicon of ~200 variants. A heading-like line that is not in
the lexicon starts an ``OTHER`` section, so its content never inherits the previous section
(e.g. "Hobbies" lines are never mistaken for certifications). A line is heading-like when it
is styled like the document's recognised headings (same DOCX style / PDF font signature),
written in capitals, or a short Title Case label ending with a colon - and does not look like
a role header (dates nearby, job-title words, separators, skills).
"""
from __future__ import annotations

import re
from collections import Counter
from dataclasses import dataclass, replace
from datetime import date

from app.pipeline.dates import find_date_ranges
from app.pipeline.text import Line
from app.pipeline.timeline import has_title_word
from app.taxonomy.taxonomy import get_taxonomy

_LEXICON: dict[str, tuple[str, ...]] = {
    "SKILLS": (
        "skills", "technical skills", "key skills", "core skills", "skill set", "skillset", "skills summary",
        "skills and tools", "tools", "tools and technologies", "technologies", "technical proficiency",
        "technical proficiencies", "core competencies", "competencies", "areas of expertise", "expertise",
        "it skills", "computer skills", "software skills", "software", "tech stack", "technical expertise",
        "professional skills", "functional skills", "programming languages", "frameworks", "skills and abilities",
        "key competencies", "technical summary", "strengths", "skills and competencies", "technical competencies",
        "core strengths", "skills and expertise", "technical skills and tools", "toolkit", "relevant skills",
        "skills and interests",
    ),
    "EXPERIENCE": (
        "experience", "work experience", "professional experience", "employment", "employment history",
        "work history", "career history", "professional background", "relevant experience", "industry experience",
        "experience summary", "internships", "internship", "internship experience", "positions held",
        "professional history", "work", "career", "freelance", "freelance experience", "employment details",
        "work experience and internships", "professional experience and internships", "teaching experience",
        "clinical experience", "sales experience", "legal experience", "research experience", "career progression",
    ),
    "PROJECTS": (
        "projects", "academic projects", "personal projects", "key projects", "project experience", "major projects",
        "selected projects", "project work", "portfolio", "projects undertaken", "project details", "side projects",
    ),
    "EDUCATION": (
        "education", "academics", "academic background", "academic qualifications", "educational qualifications",
        "educational qualification", "educational background", "qualifications", "academic details",
        "education and training", "academic profile", "scholastic record", "academic record", "education details",
        "academic qualification", "qualification",
    ),
    "CERTIFICATIONS": (
        "certifications", "certification", "certificates", "licenses", "licences", "licenses and certifications",
        "licences and certifications", "certifications and licenses", "certifications and licences",
        "professional certifications", "credentials", "accreditations", "training and certifications",
        "certifications and training", "certificate courses",
    ),
    "COURSES": (
        "courses", "coursework", "relevant coursework", "trainings", "training", "online courses",
        "professional development", "continuing education", "workshops", "trainings attended",
        "workshops and trainings",
    ),
    "ACHIEVEMENTS": (
        "achievements", "accomplishments", "key achievements", "highlights", "awards", "honors", "honours",
        "awards and achievements", "honors and awards", "honours and awards", "recognition", "scholarships",
        "awards and recognition", "achievements and awards", "career highlights",
    ),
    "PUBLICATIONS": (
        "publications", "research", "research papers", "papers", "patents", "conference papers",
        "publications and presentations", "research and publications",
    ),
    "SUMMARY": (
        "summary", "profile", "professional summary", "career objective", "objective", "about me", "about",
        "profile summary", "executive summary", "career summary", "personal statement", "overview",
        "introduction", "professional profile", "career profile", "summary of qualifications", "objectives",
    ),
    "LANGUAGES": (
        "languages", "languages known", "language proficiency", "spoken languages", "linguistic proficiency",
        "language skills", "languages spoken",
    ),
    "INTERESTS": (
        "interests", "hobbies", "hobbies and interests", "interests and hobbies", "extracurricular activities",
        "extra curricular activities", "extracurriculars", "activities", "personal interests",
        "co curricular activities", "co-curricular activities", "extra-curricular activities", "leisure activities",
    ),
    "VOLUNTEERING": (
        "volunteering", "volunteer experience", "volunteer work", "community service", "social work",
        "leadership and volunteering", "positions of responsibility", "volunteer activities",
    ),
    "REFERENCES": ("references", "referees", "references available upon request", "reference"),
    "PERSONAL": (
        "personal details", "personal information", "personal profile", "personal data", "declaration",
        "contact", "contact information", "contact details", "personal particulars",
    ),
}
_KEYWORDS: tuple[tuple[str, str], ...] = (
    ("experience", "EXPERIENCE"), ("employment", "EXPERIENCE"), ("work history", "EXPERIENCE"),
    ("internship", "EXPERIENCE"), ("skill", "SKILLS"), ("competenc", "SKILLS"), ("technolog", "SKILLS"),
    ("tools", "SKILLS"), ("education", "EDUCATION"), ("academic", "EDUCATION"), ("qualification", "EDUCATION"),
    ("certif", "CERTIFICATIONS"), ("licen", "CERTIFICATIONS"), ("project", "PROJECTS"), ("award", "ACHIEVEMENTS"),
    ("achievement", "ACHIEVEMENTS"), ("honor", "ACHIEVEMENTS"), ("honour", "ACHIEVEMENTS"),
    ("accomplishment", "ACHIEVEMENTS"), ("publication", "PUBLICATIONS"), ("summary", "SUMMARY"),
    ("objective", "SUMMARY"), ("profile", "SUMMARY"), ("language", "LANGUAGES"), ("interest", "INTERESTS"),
    ("hobb", "INTERESTS"), ("extracurricular", "INTERESTS"), ("extra-curricular", "INTERESTS"),
    ("volunteer", "VOLUNTEERING"), ("reference", "REFERENCES"), ("course", "COURSES"), ("training", "COURSES"),
    ("personal", "PERSONAL"), ("contact", "PERSONAL"), ("declaration", "PERSONAL"),
)
_INDEX = {phrase: kind for kind, phrases in _LEXICON.items() for phrase in phrases}
_INLINE_RE = re.compile(r"^(?P<label>[A-Za-z][A-Za-z &/\-]{2,40}?)\s*[:–\-]\s+(?P<content>\S.*)$")
# Sections whose lines are lists, so ambiguous skill names may be read as items.
LIST_SECTIONS = frozenset({"SKILLS", "LANGUAGES"})
# Sections an inline "Label: content" line may temporarily override.
_OVERRIDABLE = frozenset({"HEADER", "SUMMARY", "OTHER", "PERSONAL", "INTERESTS", "ACHIEVEMENTS"})


@dataclass(frozen=True)
class Section:
    kind: str
    heading: str | None
    lines: tuple[Line, ...]

    @property
    def label(self) -> str:
        """The ``sourceSection`` value reported on facts."""
        return self.kind.lower()


def heading_key(text: str) -> str:
    text = re.sub(r"^(?:\d{1,2}|[ivx]{1,4})[.)]\s*", "", text.strip(), flags=re.I)
    text = text.rstrip(":-|–— .").lower().replace("&", " and ")
    return " ".join(re.sub(r"[^a-z\- ]", " ", text).split())


def lexicon_kind(text: str) -> str | None:
    """Section kind of an exact lexicon heading, else None."""
    key = heading_key(text)
    if not key or len(key.split()) > 6:
        return None
    return _INDEX.get(key)


def _keyword_kind(text: str) -> str | None:
    key = heading_key(text)
    return next((kind for keyword, kind in _KEYWORDS if keyword in key), None)


def split_sections(lines: list[Line], today: date) -> list[Section]:
    """Group lines into sections. Heading lines are not part of any section's lines."""
    taxonomy = get_taxonomy()
    exact = {line.index: lexicon_kind(line.text) for line in lines}
    styles = Counter(line.style_key for line in lines if line.style_key)
    body_style = styles.most_common(1)[0][0] if styles else None
    # Styles shared by recognised headings, unless that is simply the body style (plain documents).
    signatures = {line.style_key for line in lines if exact[line.index] and line.style_key} - {body_style}
    has_dates = {line.index for line in lines if find_date_ranges(line.text, today)}

    def heading_like(position: int, line: Line) -> bool:
        text = line.text
        words = text.rstrip(":").split()
        if line.is_bullet or not 1 <= len(words) <= 5 or len(text) > 50 or re.search(r"\d|[,;|]| at ", text):
            return False
        if not re.search(r"[A-Za-z]", text) or has_title_word(text) or taxonomy.find_mentions(text):
            return False
        neighbours = [lines[i].index for i in (position - 1, position + 1) if 0 <= i < len(lines)]
        if any(index in has_dates for index in neighbours):
            return False
        letters = re.sub(r"[^A-Za-z]", "", text)
        all_caps = len(letters) >= 4 and letters.isupper()
        colon_label = text.endswith(":") and all(word[:1].isupper() or word in {"and", "of", "&"} for word in words)
        styled = line.style_key is not None and line.style_key in signatures
        return all_caps or colon_label or styled

    sections: list[Section] = []
    kind, heading, bucket = "HEADER", None, []
    seen_heading = False

    def flush() -> None:
        if bucket or heading is not None:
            sections.append(Section(kind, heading, tuple(bucket)))

    for position, line in enumerate(lines):
        new_kind = exact[line.index]
        if new_kind is None and not line.is_bullet and (seen_heading or line.heading_hint):
            keyword = _keyword_kind(line.text)
            signal = line.heading_hint or line.text.rstrip().endswith(":") or (
                line.style_key is not None and line.style_key in signatures)
            if keyword and signal and len(line.text.split()) <= 6 and not re.search(r"\d", line.text):
                new_kind = keyword
            elif seen_heading and heading_like(position, line):
                new_kind = keyword or "OTHER"
        if new_kind is not None:
            flush()
            kind, heading, bucket, seen_heading = new_kind, line.text.rstrip(": "), [], True
            continue
        inline = _INLINE_RE.match(line.text)
        inline_kind = lexicon_kind(inline.group("label")) if inline else None
        if inline and inline_kind and inline_kind != kind and kind in _OVERRIDABLE:
            content_start = line.start + inline.start("content")
            flush()
            sections.append(Section(inline_kind, inline.group("label"),
                                    (replace(line, text=inline.group("content"), start=content_start),)))
            heading, bucket = None, []
            continue
        bucket.append(line)
    flush()
    return sections
