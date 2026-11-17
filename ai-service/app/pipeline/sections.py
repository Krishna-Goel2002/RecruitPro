"""Resume section segmentation (finding H4).

Headings are recognised from a lexicon of ~250 variants, then by a head-noun classifier
(``classify_heading``: "Professional Journey" -> EXPERIENCE, "Career Goal" -> SUMMARY, "Internship &
Training" -> EXPERIENCE), table rows labelled in their first cell and headings wrapped over two
lines. A heading-like line that is not recognised starts an ``OTHER`` section (promoted to EXPERIENCE
when it holds dated role headers), so its content never inherits the previous section
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
from app.pipeline.education import detect_degree
from app.pipeline.text import Line
from app.pipeline.timeline import has_employer_word, has_title_word
from app.taxonomy.taxonomy import get_taxonomy

_LEXICON: dict[str, tuple[str, ...]] = {
    "SKILLS": (
        "skills", "technical skills", "key skills", "core skills", "skill set", "skillset", "skills summary",
        "skills and tools", "tools", "tools and technologies", "technologies", "technical proficiency",
        "technical proficiencies", "core competencies", "competencies", "areas of expertise", "expertise",
        "it skills", "computer skills", "software skills", "software", "tech stack", "technical expertise",
        "professional skills", "functional skills", "programming languages", "frameworks", "skills and abilities",
        "key competencies", "technical summary", "skills and competencies", "technical competencies",
        "skills and expertise", "technical skills and tools", "toolkit", "relevant skills", "core strengths",
        "skills and interests",
    ),
    "EXPERIENCE": (
        "experience", "work experience", "professional experience", "employment", "employment history",
        "work history", "career history", "professional background", "relevant experience", "industry experience",
        "experience summary", "internships", "internship", "internship experience", "positions held",
        "professional history", "work", "career", "freelance", "freelance experience", "employment details",
        "work experience and internships", "professional experience and internships", "teaching experience",
        "clinical experience", "sales experience", "legal experience", "research experience", "career progression",
        "professional journey", "career journey", "work journey", "my journey", "journey", "organisational experience",
        "organizational experience", "experience details", "work profile", "job history", "employment record",
        "career path", "career timeline", "where i have worked", "internships and training", "internship and training",
        "industrial training", "industrial exposure", "summer internship", "summer internships", "summer training",
        "in-plant training", "inplant training", "vocational training", "articleship", "apprenticeship",
        "apprenticeships", "teaching", "consulting experience", "assignments handled", "professional engagements",
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
        "publications and presentations", "research and publications", "conference presentations",
        "conferences and presentations", "presentations", "talks", "selected publications", "research publications",
    ),
    "SUMMARY": (
        "summary", "profile", "professional summary", "career objective", "objective", "about me", "about",
        "profile summary", "executive summary", "career summary", "personal statement", "overview",
        "introduction", "professional profile", "career profile", "summary of qualifications", "objectives",
        "career goal", "career goals", "goal", "goals", "career aim", "aim", "professional objective",
        # Strengths lists are mostly traits ("Quick learner"): taxonomy skills in them still count, but the
        # lines are not read as a skills list (no unknown-skill facts for "Punctual").
        "strengths", "key strengths", "personal strengths", "my strengths", "strengths and weaknesses",
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
        "personal details", "personal information", "personal profile", "personal data",
        "contact", "contact information", "contact details", "personal particulars", "personal dossier",
        "bio data", "bio-data", "biodata", "personal vitae",
    ),
    # A signed statement ("I hereby declare ..."): never evidence of anything.
    "DECLARATION": ("declaration", "undertaking", "self declaration", "self-declaration"),
}
# Heading classifier (beyond the exact lexicon). A heading names its section with a *head noun*,
# usually its last word ("Professional Journey", "Career Goal", "Conference Presentations"); words
# such as "details", "summary" or "background" only say "a section about ...", so their modifier
# decides ("Employment Details", "Academic Background"). Coordinated headings ("Internship & Training",
# "Courses & Certifications") take the strongest kind among their parts.
_HEAD_NOUNS: dict[str, str] = {
    **dict.fromkeys(("experience", "experiences", "employment", "employments", "journey", "internship", "internships",
                     "positions", "assignments", "engagements", "tenure", "tenures", "career", "articleship",
                     "apprenticeship", "apprenticeships"), "EXPERIENCE"),
    **dict.fromkeys(("skills", "skill", "skillset", "competencies", "competency", "technologies", "tools", "toolkit",
                     "stack", "expertise", "proficiency", "proficiencies", "abilities", "capabilities", "software",
                     "frameworks"), "SKILLS"),
    **dict.fromkeys(("objective", "objectives", "goal", "goals", "aim", "aims", "statement", "introduction", "about",
                     "synopsis", "snapshot", "strengths", "attributes", "traits", "qualities", "profile"), "SUMMARY"),
    **dict.fromkeys(("education", "academics", "qualification", "qualifications", "schooling", "studies"), "EDUCATION"),
    **dict.fromkeys(("certifications", "certification", "certificates", "certificate", "licenses", "licences", "license",
                     "licence", "accreditations", "credentials"), "CERTIFICATIONS"),
    **dict.fromkeys(("courses", "course", "coursework", "trainings", "training", "workshops", "workshop", "seminars",
                     "webinars", "moocs"), "COURSES"),
    **dict.fromkeys(("projects", "project", "portfolio"), "PROJECTS"),
    **dict.fromkeys(("achievements", "achievement", "accomplishments", "awards", "award", "honours", "honors",
                     "recognition", "recognitions", "scholarships", "distinctions", "accolades", "highlights"),
                    "ACHIEVEMENTS"),
    **dict.fromkeys(("publications", "publication", "papers", "patents", "presentations", "talks", "conferences",
                     "research"), "PUBLICATIONS"),
    **dict.fromkeys(("languages", "language"), "LANGUAGES"),
    **dict.fromkeys(("interests", "hobbies", "activities", "pastimes", "passions", "extracurriculars"), "INTERESTS"),
    **dict.fromkeys(("volunteering", "volunteer"), "VOLUNTEERING"),
    **dict.fromkeys(("references", "reference", "referees", "referee"), "REFERENCES"),
    **dict.fromkeys(("particulars", "dossier", "biodata", "contact", "contacts"), "PERSONAL"),
    **dict.fromkeys(("declaration", "undertaking", "affirmation"), "DECLARATION"),
}
# Uninformative heads: the modifier names the section ("Employment Details", "Skills Summary").
_GENERIC_HEADS = frozenset({"details", "detail", "information", "info", "summary", "background", "history", "record",
                            "records", "overview", "data", "section", "list", "profile", "highlights", "exposure",
                            "particulars", "path", "timeline", "progression", "snapshot"})
_MODIFIERS: tuple[tuple[str, str], ...] = (
    ("intern", "EXPERIENCE"), ("experience", "EXPERIENCE"), ("employment", "EXPERIENCE"), ("career", "EXPERIENCE"),
    ("work", "EXPERIENCE"), ("job", "EXPERIENCE"), ("professional", "EXPERIENCE"), ("industr", "EXPERIENCE"),
    ("project", "PROJECTS"), ("skill", "SKILLS"), ("technical", "SKILLS"), ("academ", "EDUCATION"),
    ("educat", "EDUCATION"), ("scholastic", "EDUCATION"), ("qualification", "EDUCATION"), ("certif", "CERTIFICATIONS"),
    ("personal", "PERSONAL"), ("contact", "PERSONAL"), ("family", "PERSONAL"), ("language", "LANGUAGES"),
    ("publication", "PUBLICATIONS"), ("award", "ACHIEVEMENTS"), ("achievement", "ACHIEVEMENTS"),
)
# Training that is work, not a course: Indian engineering and commerce degrees require it.
_WORK_TRAINING_RE = re.compile(r"\b(?:industrial|summer|in-?plant|vocational|on-the-job|on the job|practical)\s+training\b")
# When a heading coordinates two kinds, the one that carries more evidence wins.
_KIND_PRIORITY = ("EXPERIENCE", "EDUCATION", "CERTIFICATIONS", "PROJECTS", "SKILLS", "PUBLICATIONS", "COURSES",
                  "ACHIEVEMENTS", "LANGUAGES", "VOLUNTEERING", "SUMMARY", "INTERESTS", "REFERENCES", "PERSONAL",
                  "DECLARATION")


# Every word of a classifier-only heading must be heading vocabulary: "Paywise Technologies" (an employer in
# bold) or "Sales Presentations" is not a section, "Key Technologies" or "Industrial Training" is.
_HEADING_VOCABULARY = frozenset(
    {word for phrases in _LEXICON.values() for phrase in phrases for word in phrase.replace("-", " ").split()}
    | set(_HEAD_NOUNS) | _GENERIC_HEADS | {
        "my", "other", "additional", "relevant", "selected", "key", "major", "notable", "recent", "previous", "current",
        "industrial", "summer", "social", "extra", "curricular", "co", "core", "main", "technical", "professional",
        "academic", "personal", "work", "job", "career", "family", "contact", "the", "of", "and", "or", "in", "for",
        "to", "on", "a", "an", "my", "all", "list", "areas", "area", "functional", "domain", "it", "computer", "digital",
        "soft", "hard", "transferable", "interpersonal", "leadership", "management", "managerial", "clinical", "teaching",
        "research", "sales", "legal", "field", "site", "plant", "project", "projects", "company", "companies",
        "organisational", "organizational", "corporate", "internship", "internships", "in-plant", "inplant", "vocational",
        "practical", "on-the-job", "achieved", "honours", "honors", "online", "certificate", "short", "term", "value",
        "added", "add-on", "spoken", "known", "written", "language", "linguistic", "conference", "journal", "seminar",
        "trainings", "programmes", "programs", "program", "programme", "attended", "undertaken", "handled", "done",
        "completed", "qualification", "educational", "scholastic", "school", "college", "university",
    })


def _part_kind(words: list[str]) -> str | None:
    if not words:
        return None
    head = words[-1]
    if head in _GENERIC_HEADS:
        modifiers = " ".join(words[:-1])
        for keyword, kind in _MODIFIERS:
            if keyword in modifiers:
                return kind
        return "SUMMARY" if head in {"summary", "profile", "overview", "snapshot"} else _HEAD_NOUNS.get(head)
    return _HEAD_NOUNS.get(head)  # an unknown head ("Beyond Work", "Key Skills used") names no known section
_INDEX = {phrase: kind for kind, phrases in _LEXICON.items() for phrase in phrases}
_INLINE_RE = re.compile(r"^(?P<label>[A-Za-z][A-Za-z &/\-]{2,40}?)\s*[:–\-]\s+(?P<content>\S.*)$")
# A table row whose first cell is a section label: "TECHNICAL SKILLS | Java, SQL" (table layouts) or
# "Languages Known | : English, Hindi" (personal-details tables).
_ROW_LABEL_RE = re.compile(r"^(?P<label>[A-Za-z][A-Za-z &/'’\-]{2,40}?)\s*\|\s*(?::\s*)?(?P<content>[^|\s].*)$")
# Sections whose lines are lists, so ambiguous skill names may be read as items.
LIST_SECTIONS = frozenset({"SKILLS", "LANGUAGES"})
# Sections an inline "Label: content" line may temporarily override.
_OVERRIDABLE = frozenset({"HEADER", "SUMMARY", "OTHER", "PERSONAL", "INTERESTS", "ACHIEVEMENTS"})
# Inline labels that override any section ("Education: MBA, 2015" at the end of a work history).
# ("Languages: Java, Python" inside a role lists programming languages, so LANGUAGES is not one of them.)
_ALWAYS_INLINE = frozenset({"EDUCATION", "CERTIFICATIONS"})
# Lexicon headings that are also ordinary one-word list items ("Training", "Research", "Software"
# in a skills list). In a list section, or as a bullet, they start a section only when styled as a
# heading (heading style/font, capitals or a trailing colon).
_ITEM_LIKE_HEADINGS = frozenset({
    "training", "trainings", "research", "software", "tools", "technologies", "frameworks", "strengths",
    "expertise", "competencies", "activities", "portfolio", "recognition", "overview", "introduction", "coursework",
    "workshops", "contact", "reference", "volunteering", "social work", "community service", "toolkit",
    "programming languages",
})
_LIST_LIKE = frozenset({"SKILLS", "LANGUAGES", "INTERESTS", "COURSES", "CERTIFICATIONS"})
# Inside a work history, "Roles & Responsibilities:", "Achievements:" or "Projects handled" are
# sub-headings of a role when the next role (a date range) follows before the next main heading.
_ROLE_SUBHEADING_KINDS = frozenset({"ACHIEVEMENTS", "OTHER", "PROJECTS", "COURSES", "SUMMARY"})
_MAIN_KINDS = frozenset({"EDUCATION", "SKILLS", "CERTIFICATIONS", "LANGUAGES", "INTERESTS", "REFERENCES", "PERSONAL",
                         "PUBLICATIONS", "VOLUNTEERING", "EXPERIENCE"})


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


def classify_heading(text: str) -> str | None:
    """Section kind of a heading line: the exact lexicon first, then the head-noun classifier.

    Only for lines already known to be heading-like (styled, capitals, a trailing colon); it does
    not decide *whether* a line is a heading."""
    exact = lexicon_kind(text)
    if exact is not None:
        return exact
    key = heading_key(text)
    words = key.split()
    if not key or len(words) > 6:
        return None
    if any(word not in _HEADING_VOCABULARY for word in key.replace("-", " ").split()):
        return None
    if _WORK_TRAINING_RE.search(key):
        return "EXPERIENCE"
    parts = [part.split() for part in re.split(r"\s+(?:and|or)\s+|\s*/\s*|,\s*", key) if part.strip()]
    if len(parts) > 1:
        # "Skills & Tools": a modifier-less first part ("Skills") still names its own kind.
        kinds = {kind for part in parts if (kind := _part_kind(part))}
        return next((kind for kind in _KIND_PRIORITY if kind in kinds), None)
    return _part_kind(words)


def _keyword_kind(text: str) -> str | None:
    return classify_heading(text)


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
        if detect_degree(text) or has_employer_word(text):
            return False  # a degree ("Bachelor of Science in Nursing") or an employer line, styled in bold
        neighbours = [lines[i].index for i in (position - 1, position + 1) if 0 <= i < len(lines)]
        if any(index in has_dates for index in neighbours):
            return False
        letters = re.sub(r"[^A-Za-z]", "", text)
        all_caps = len(letters) >= 4 and letters.isupper()
        colon_label = text.endswith(":") and all(word[:1].isupper() or word in {"and", "of", "&"} for word in words)
        styled = line.style_key is not None and line.style_key in signatures
        return all_caps or colon_label or styled

    def styled_as_heading(line: Line) -> bool:
        letters = re.sub(r"[^A-Za-z]", "", line.text)
        return line.heading_hint or line.text.rstrip().endswith(":") or (len(letters) >= 4 and letters.isupper()) or (
            line.style_key is not None and line.style_key in signatures)

    def signature(line: Line) -> tuple[object, ...]:
        letters = re.sub(r"[^A-Za-z]", "", line.text)
        return (line.style_key, line.heading_hint, line.text.rstrip().endswith(":"), len(letters) >= 4 and letters.isupper())

    # For every position: is there a dated line before the next main heading? (one backward pass, O(n))
    dated_ahead = [False] * (len(lines) + 1)
    for position in range(len(lines) - 1, -1, -1):
        later = lines[position]
        if exact[later.index] in _MAIN_KINDS and not later.is_bullet:
            dated_ahead[position] = False
        else:
            dated_ahead[position] = later.index in has_dates or dated_ahead[position + 1]

    def _opens_work_history(position: int, line: Line) -> bool:
        """"WHERE I MADE AN IMPACT" + "Data Analyst, Acme | Jan 2021 - Dec 2022": a heading styled line (capitals,
        heading font or style) with no job title or date of its own, directly above a dated role header."""
        text = line.text.strip()
        if line.is_bullet or not 2 <= len(text.rstrip(":").split()) <= 6 or re.search(r"[\d,;|]", text):
            return False
        letters = re.sub(r"[^A-Za-z]", "", text)
        styled = (len(letters) >= 4 and letters.isupper()) or line.heading_hint or (
            line.style_key is not None and line.style_key in signatures)
        if not styled or has_title_word(text) or has_employer_word(text) or detect_degree(text) or taxonomy.find_mentions(text):
            return False
        following = lines[position + 1] if position + 1 < len(lines) else None
        return following is not None and not following.is_bullet and following.index in has_dates and (
            has_title_word(following.text) or has_employer_word(following.text))

    def dated_role_follows(position: int) -> bool:
        return dated_ahead[position + 1]

    sections: list[Section] = []
    kind, heading, bucket = "HEADER", None, []
    heading_line: Line | None = None
    seen_heading = False

    def flush() -> None:
        if bucket or heading is not None:
            sections.append(Section(kind, heading, tuple(bucket)))

    consumed: set[int] = set()  # second lines of headings wrapped over two lines
    for position, line in enumerate(lines):
        if position in consumed:
            continue
        following = lines[position + 1] if position + 1 < len(lines) else None
        wrapped = _wrapped_heading(line.text, following)
        if wrapped and not line.is_bullet and (styled_as_heading(line) or seen_heading):
            flush()  # "CERTIFICATIONS &" + "TRAININGS"
            kind, heading, bucket, seen_heading, heading_line = wrapped, f"{line.text} {following.text}", [], True, line
            consumed.add(position + 1)
            continue
        new_kind = exact[line.index]
        if new_kind is not None and heading_key(line.text) in _ITEM_LIKE_HEADINGS and (
                line.is_bullet or (kind in _LIST_LIKE and not styled_as_heading(line))):
            new_kind = None  # a list item that happens to be a heading word
        if new_kind is None and not line.is_bullet and (seen_heading or line.heading_hint):
            keyword = _keyword_kind(line.text) if _heading_shaped(line.text) else None
            signal = line.heading_hint or line.text.rstrip().endswith(":") or (
                line.style_key is not None and line.style_key in signatures)
            if keyword and signal and len(line.text.split()) <= 6 and not re.search(r"\d", line.text):
                new_kind = keyword
            elif seen_heading and heading_like(position, line):
                new_kind = keyword or "OTHER"
        if new_kind is None and seen_heading and kind != "EXPERIENCE" and _opens_work_history(position, line):
            new_kind = "EXPERIENCE"  # an unrecognised heading right above a dated role header
        if new_kind in _ROLE_SUBHEADING_KINDS and kind == "EXPERIENCE" and heading_line is not None \
                and signature(line) != signature(heading_line) and dated_role_follows(position):
            new_kind = None  # a role's own sub-heading; the work history continues
        if new_kind is not None:
            flush()
            kind, heading, bucket, seen_heading = new_kind, line.text.rstrip(": "), [], True
            heading_line = line
            continue
        row = _ROW_LABEL_RE.match(line.text) if not line.is_bullet else None
        row_kind = _row_label_kind(row.group("label")) if row else None
        if row and row_kind is None and following is not None:
            row_kind = _wrapped_heading(row.group("label"), following)  # "Certifications & | ..." + "Trainings"
            if row_kind:
                consumed.add(position + 1)
        if row and row_kind:
            content = row.group("content")
            content_line = replace(line, text=content, start=line.start + row.start("content"))
            flush()
            side_by_side = classify_heading(content) is not None
            if kind == "PERSONAL" and not side_by_side:
                # "Languages Known | : English, Hindi" inside a personal-details table: one row only.
                sections.append(Section(row_kind, row.group("label").strip(), (content_line,)))
                heading, bucket = None, []
                continue
            # A table layout's label cell ("TECHNICAL SKILLS | Java, SQL" with more lines below) starts a
            # section; side-by-side headings ("EDUCATION | HOBBIES") keep the first column's.
            kind, heading, seen_heading, heading_line = row_kind, row.group("label").strip(), True, line
            bucket = [] if side_by_side else [content_line]
            continue
        inline = _INLINE_RE.match(line.text)
        inline_kind = None
        if inline:
            inline_kind = lexicon_kind(inline.group("label"))
            separator = line.text[inline.end("label"):inline.start("content")].strip()
            if inline_kind is None and separator == ":" and not line.is_bullet:
                inline_kind = classify_heading(inline.group("label"))  # "Languages Known: English, Hindi"
        if inline and inline_kind and inline_kind != kind and (kind in _OVERRIDABLE or inline_kind in _ALWAYS_INLINE):
            content_start = line.start + inline.start("content")
            flush()
            sections.append(Section(inline_kind, inline.group("label"),
                                    (replace(line, text=inline.group("content"), start=content_start),)))
            heading, bucket = None, []
            continue
        bucket.append(line)
    flush()
    return [_promote_work_history(section, today) for section in sections]


def _heading_shaped(text: str) -> bool:
    """A line that may be a heading at all: no list punctuation, no "Label: content", no degree or job title
    ("Bachelor of Technology," or "Lead Engineer - Projects" in bold are content, not headings)."""
    stripped = text.strip()
    if re.search(r"[,;|]", stripped) or re.search(r":\s*\S", stripped):
        return False
    if lexicon_kind(stripped):
        return True
    if detect_degree(stripped) or has_title_word(stripped) or has_employer_word(stripped):
        return False
    # "Sales Presentations" or "Customer Journey Mapping" is a skill, not a "Presentations" section.
    last_word = re.search(r"([A-Za-z][\w\-]*)\W*$", stripped)
    return not (last_word and any(mention.start <= last_word.start(1) < mention.end
                                  for mention in get_taxonomy().find_mentions(stripped, list_context=True)))


def _wrapped_heading(text: str, following: Line | None) -> str | None:
    """Kind of a heading wrapped over two lines ("CERTIFICATIONS &" + "TRAININGS"), else None."""
    if following is None or following.is_bullet or not re.search(r"(?:&|\band|/)\s*$", text.strip(), re.I):
        return None
    tail = following.text.strip()
    if not 1 <= len(tail.split()) <= 3 or re.search(r"[\d,;|:]", tail):
        return None
    joined = f"{text.strip()} {tail}"
    return classify_heading(joined) if _heading_shaped(joined) else None


def _row_label_kind(label: str) -> str | None:
    label = label.strip()
    kind = lexicon_kind(label)
    if kind is not None:
        return kind
    letters = re.sub(r"[^A-Za-z]", "", label)
    if len(label.split()) <= 4 and _heading_shaped(label) and (letters.isupper() or label.istitle()):
        return classify_heading(label)
    return None


def _promote_work_history(section: Section, today: date) -> Section:
    """An unrecognised heading followed by dated role headers is a work history ("Where I've Made an Impact").

    Needs two dated lines that name a job title or employer (on the line or right above it), or one
    such line followed by at least two bullets, and no degree lines."""
    if section.kind != "OTHER" or section.heading is None or not section.lines:
        return section
    lines = section.lines
    if any(detect_degree(line.text) for line in lines):
        return section
    anchors = 0
    for position, line in enumerate(lines):
        if line.is_bullet or not find_date_ranges(line.text, today):
            continue
        context = " ".join(lines[i].text for i in (position - 1, position, position + 1) if 0 <= i < len(lines)
                           and not lines[i].is_bullet)
        if has_title_word(context) or has_employer_word(context):
            anchors += 1
    bullets = sum(1 for line in lines if line.is_bullet)
    if anchors >= 2 or (anchors == 1 and bullets >= 2):
        return Section("EXPERIENCE", section.heading, section.lines)
    return section
