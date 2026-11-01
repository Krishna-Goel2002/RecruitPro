import io
import re
import zipfile
from dataclasses import dataclass
from html import unescape

import pdfplumber
from docx import Document
from pydantic import ValidationError

from app.models.intelligence import ExtractedFact, ResumeParseResponse


PARSER_VERSION = "rule-parser-1"
MAX_FILE_BYTES = 10 * 1024 * 1024
MAX_UNCOMPRESSED_DOCX_BYTES = 100 * 1024 * 1024
MAX_DOCX_ENTRIES = 1000
MAX_PDF_PAGES = 100
MAX_TEXT_CHARACTERS = 1_000_000
MAX_FACTS = 250


@dataclass(frozen=True)
class SkillDefinition:
    canonical: str
    aliases: tuple[str, ...] = ()
    domain: str = "GENERAL"
    parents: tuple[str, ...] = ()
    distinctions: tuple[str, ...] = ()

    def __iter__(self):
        """Allows backwards compatibility with tuple unpacking: canonical, aliases = skill"""
        yield self.canonical
        yield self.aliases

    def __getitem__(self, index: int):
        """Allows backwards compatibility with tuple indexing: skill[0], skill[1]"""
        if index == 0:
            return self.canonical
        if index == 1:
            return self.aliases
        raise IndexError(f"SkillDefinition index out of range: {index}")


SKILL_DOMAINS = {
    "LANGUAGES": "Programming and scripting languages",
    "FRAMEWORKS": "Application frameworks and runtimes",
    "DATABASES": "Relational and NoSQL database management systems",
    "APIS_AND_MESSAGING": "API protocols, message brokers, and RPC",
    "CLOUD_AND_DEVOPS": "Cloud infrastructure, containers, and deployment automation",
    "AI_ML": "Machine learning, deep learning, and AI subfields",
    "MOBILE": "Mobile application development platforms and ecosystems",
}

SKILL_TAXONOMY: dict[str, SkillDefinition] = {
    # --- Existing 18 Skills (Preserved aliases) ---
    "java": SkillDefinition(canonical="Java", aliases=(), domain="LANGUAGES", distinctions=("JavaScript",)),
    "spring boot": SkillDefinition(canonical="Spring Boot", aliases=("spring-boot",), domain="FRAMEWORKS", parents=("Spring",)),
    "spring": SkillDefinition(canonical="Spring", aliases=(), domain="FRAMEWORKS"),
    "python": SkillDefinition(canonical="Python", aliases=(), domain="LANGUAGES", distinctions=("PyTorch",)),
    "sql": SkillDefinition(canonical="SQL", aliases=(), domain="DATABASES"),
    "mysql": SkillDefinition(canonical="MySQL", aliases=(), domain="DATABASES", parents=("SQL",)),
    "postgresql": SkillDefinition(canonical="PostgreSQL", aliases=("postgres",), domain="DATABASES", parents=("SQL",)),
    "javascript": SkillDefinition(canonical="JavaScript", aliases=("javascript", "java script", "java-script"), domain="LANGUAGES", distinctions=("Java",)),
    "typescript": SkillDefinition(canonical="TypeScript", aliases=(), domain="LANGUAGES"),
    "react": SkillDefinition(canonical="React", aliases=("react.js", "reactjs"), domain="FRAMEWORKS", distinctions=("React Native",)),
    "node.js": SkillDefinition(canonical="Node.js", aliases=("nodejs",), domain="FRAMEWORKS"),
    "rest api": SkillDefinition(canonical="REST API", aliases=("restful api", "rest apis"), domain="APIS_AND_MESSAGING"),
    "aws": SkillDefinition(canonical="AWS", aliases=("amazon web services",), domain="CLOUD_AND_DEVOPS", distinctions=("Azure",)),
    "azure": SkillDefinition(canonical="Azure", aliases=("microsoft azure",), domain="CLOUD_AND_DEVOPS", distinctions=("AWS",)),
    "docker": SkillDefinition(canonical="Docker", aliases=(), domain="CLOUD_AND_DEVOPS", distinctions=("Kubernetes",)),
    "kubernetes": SkillDefinition(canonical="Kubernetes", aliases=("k8s",), domain="CLOUD_AND_DEVOPS", distinctions=("Docker",)),
    "git": SkillDefinition(canonical="Git", aliases=(), domain="CLOUD_AND_DEVOPS"),
    "machine learning": SkillDefinition(canonical="Machine Learning", aliases=("ml",), domain="AI_ML"),

    # --- Languages (9) ---
    "c": SkillDefinition(canonical="C", aliases=(), domain="LANGUAGES", distinctions=("C++", "C#")),
    "c++": SkillDefinition(canonical="C++", aliases=("cpp",), domain="LANGUAGES", distinctions=("C", "C#")),
    "c#": SkillDefinition(canonical="C#", aliases=("csharp", "c sharp"), domain="LANGUAGES", distinctions=("C", "C++")),
    "go": SkillDefinition(canonical="Go", aliases=("golang",), domain="LANGUAGES"),
    "rust": SkillDefinition(canonical="Rust", aliases=(), domain="LANGUAGES"),
    "kotlin": SkillDefinition(canonical="Kotlin", aliases=(), domain="LANGUAGES"),
    "swift": SkillDefinition(canonical="Swift", aliases=(), domain="LANGUAGES"),
    "php": SkillDefinition(canonical="PHP", aliases=(), domain="LANGUAGES"),
    "ruby": SkillDefinition(canonical="Ruby", aliases=(), domain="LANGUAGES"),

    # --- Databases (5) ---
    "mongodb": SkillDefinition(canonical="MongoDB", aliases=("mongo",), domain="DATABASES"),
    "redis": SkillDefinition(canonical="Redis", aliases=(), domain="DATABASES"),
    "cassandra": SkillDefinition(canonical="Cassandra", aliases=(), domain="DATABASES"),
    "oracle": SkillDefinition(canonical="Oracle", aliases=(), domain="DATABASES"),
    "sqlite": SkillDefinition(canonical="SQLite", aliases=(), domain="DATABASES", parents=("SQL",)),

    # --- APIs and messaging (4) ---
    "graphql": SkillDefinition(canonical="GraphQL", aliases=(), domain="APIS_AND_MESSAGING"),
    "grpc": SkillDefinition(canonical="gRPC", aliases=(), domain="APIS_AND_MESSAGING"),
    "kafka": SkillDefinition(canonical="Kafka", aliases=(), domain="APIS_AND_MESSAGING"),
    "rabbitmq": SkillDefinition(canonical="RabbitMQ", aliases=(), domain="APIS_AND_MESSAGING"),

    # --- Cloud and DevOps (5) ---
    "ci/cd": SkillDefinition(canonical="CI/CD", aliases=("cicd", "ci cd"), domain="CLOUD_AND_DEVOPS"),
    "terraform": SkillDefinition(canonical="Terraform", aliases=(), domain="CLOUD_AND_DEVOPS"),
    "linux": SkillDefinition(canonical="Linux", aliases=(), domain="CLOUD_AND_DEVOPS"),
    "github actions": SkillDefinition(canonical="GitHub Actions", aliases=(), domain="CLOUD_AND_DEVOPS"),
    "gcp": SkillDefinition(canonical="GCP", aliases=("google cloud", "google cloud platform"), domain="CLOUD_AND_DEVOPS"),

    # --- AI/ML (5) ---
    "deep learning": SkillDefinition(canonical="Deep Learning", aliases=("dl",), domain="AI_ML", parents=("Machine Learning",)),
    "pytorch": SkillDefinition(canonical="PyTorch", aliases=(), domain="AI_ML", distinctions=("Python",)),
    "tensorflow": SkillDefinition(canonical="TensorFlow", aliases=(), domain="AI_ML"),
    "nlp": SkillDefinition(canonical="NLP", aliases=("natural language processing",), domain="AI_ML"),
    "computer vision": SkillDefinition(canonical="Computer Vision", aliases=("cv",), domain="AI_ML"),

    # --- Mobile (4) ---
    "react native": SkillDefinition(canonical="React Native", aliases=("react-native",), domain="MOBILE", distinctions=("React",)),
    "flutter": SkillDefinition(canonical="Flutter", aliases=(), domain="MOBILE"),
    "android": SkillDefinition(canonical="Android", aliases=(), domain="MOBILE"),
    "ios": SkillDefinition(canonical="iOS", aliases=(), domain="MOBILE"),
}

SKILLS = SKILL_TAXONOMY


def get_skill(name: str) -> SkillDefinition | None:
    """Lookup a skill definition by canonical name or alias (case-insensitive)."""
    norm = name.strip().lower()
    if norm in SKILL_TAXONOMY:
        return SKILL_TAXONOMY[norm]
    for skill in SKILL_TAXONOMY.values():
        if skill.canonical.lower() == norm:
            return skill
        if any(alias.lower() == norm for alias in skill.aliases):
            return skill
    return None


def get_canonical(name_or_alias: str) -> str | None:
    """Return the canonical skill name for a skill name or alias, or None if unknown."""
    skill = get_skill(name_or_alias)
    return skill.canonical if skill else None


def get_parents(skill_name: str) -> tuple[str, ...]:
    """Return the direct parents of a skill (e.g. MySQL -> ('SQL',))."""
    skill = get_skill(skill_name)
    return skill.parents if skill else ()


def get_children(parent_name: str) -> tuple[str, ...]:
    """Return the direct children of a parent skill (e.g. SQL -> ('MySQL', 'PostgreSQL', 'SQLite'))."""
    parent_canon = get_canonical(parent_name)
    if not parent_canon:
        return ()
    children = [s.canonical for s in SKILL_TAXONOMY.values() if parent_canon in s.parents]
    return tuple(sorted(children))


def are_distinct(skill_a: str, skill_b: str) -> bool:
    """Return True if skill_a and skill_b are explicitly defined as distinct technologies."""
    sa = get_skill(skill_a)
    sb = get_skill(skill_b)
    if not sa or not sb:
        return False
    return sb.canonical in sa.distinctions or sa.canonical in sb.distinctions


def is_parent_of(parent: str, child: str) -> bool:
    """Return True if parent is an ancestor of child in directional subsumption."""
    parent_canon = get_canonical(parent)
    child_skill = get_skill(child)
    if not parent_canon or not child_skill:
        return False
    queue = list(child_skill.parents)
    visited = set()
    while queue:
        p = queue.pop(0)
        if p == parent_canon:
            return True
        if p not in visited:
            visited.add(p)
            ps = get_skill(p)
            if ps:
                queue.extend(ps.parents)
    return False


def validate_taxonomy() -> list[str]:
    """Validate taxonomy consistency: no collision, valid parents, valid distinctions."""
    errors = []
    seen_canonicals = set()
    seen_aliases: dict[str, str] = {}
    for key, skill in SKILL_TAXONOMY.items():
        if skill.canonical.lower() in seen_canonicals:
            errors.append(f"Duplicate canonical skill: {skill.canonical}")
        seen_canonicals.add(skill.canonical.lower())
        for alias in skill.aliases:
            alias_lower = alias.lower()
            if alias_lower in seen_aliases:
                errors.append(f"Alias collision: '{alias}' used by both {seen_aliases[alias_lower]} and {skill.canonical}")
            seen_aliases[alias_lower] = skill.canonical
        for parent in skill.parents:
            if not get_skill(parent):
                errors.append(f"Invalid parent '{parent}' for skill '{skill.canonical}'")
        for dist in skill.distinctions:
            if not get_skill(dist):
                errors.append(f"Invalid distinction '{dist}' for skill '{skill.canonical}'")
    return errors


def _build_search_phrases() -> list[tuple[str, str, str]]:
    """Build a deterministic list of (phrase, canonical, key) sorted longest-first."""
    phrases = []
    seen = set()
    for key, skill in SKILL_TAXONOMY.items():
        candidates = [key, skill.canonical.lower()]
        for alias in skill.aliases:
            candidates.append(alias.lower())
        for cand in candidates:
            c = cand.strip().lower()
            if c and c not in seen:
                seen.add(c)
                phrases.append((c, skill.canonical, key))
    # Sort: longest length descending, then alphabetically ascending
    phrases.sort(key=lambda item: (len(item[0]), item[0]), reverse=True)
    return phrases


_SEARCH_PHRASES = _build_search_phrases()


def extract_skill_spans(text: str) -> list[tuple[str, str, int, int, str]]:
    """
    Extract skills from text using deterministic longest-match-first phrase recognition.
    Returns list of (canonical, matched_text, start_index, end_index, skill_key)
    sorted by start_index. Overlapping sub-phrases are discarded.
    """
    occupied_intervals: list[tuple[int, int]] = []
    matches: list[tuple[str, str, int, int, str]] = []

    for phrase, canonical, key in _SEARCH_PHRASES:
        escaped = re.escape(phrase)
        pattern = rf"(?<![a-z0-9+#.\-]){escaped}(?![a-z0-9+#.\-])"
        for m in re.finditer(pattern, text, re.I):
            start, end = m.span()
            overlaps = any(not (end <= occ_start or start >= occ_end) for occ_start, occ_end in occupied_intervals)
            if not overlaps:
                occupied_intervals.append((start, end))
                matches.append((canonical, text[start:end], start, end, key))

    matches.sort(key=lambda item: item[2])
    return matches


def extract_skills(text: str) -> set[str]:
    """Return the set of canonical skill names observed in text."""
    return {canonical for canonical, _, _, _, _ in extract_skill_spans(text)}


SECTION_HEADINGS = {
    "SKILL": re.compile(r"^(technical\s+)?skills?|core competencies|technologies|tools(?: and technologies)?$", re.I),
    "EDUCATION": re.compile(r"^(education|academic background|qualifications?)$", re.I),
    "EXPERIENCE": re.compile(r"^(work experience|professional experience|employment history|experience|career history)$", re.I),
    "CERTIFICATION": re.compile(r"^(certifications?|licenses?|professional credentials)$", re.I),
}
EMAIL_RE = re.compile(r"\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b", re.I)
PHONE_RE = re.compile(r"(?<!\w)(?:\+?\d[\d ()-]{6,}\d)(?!\w)")


@dataclass
class DocumentText:
    paragraphs: list[tuple[str, str | None]]
    has_images: bool = False


class DocumentParseError(Exception):
    def __init__(self, code: str, status: str = "FAILED"):
        self.code = code
        self.status = status
        super().__init__(code)


def parse_resume(filename: str, content: bytes) -> ResumeParseResponse:
    extension = filename.rsplit(".", 1)[-1].lower() if "." in filename else ""
    if extension not in {"pdf", "docx"}:
        raise DocumentParseError("UNSUPPORTED_FORMAT")
    if not content:
        raise DocumentParseError("EMPTY_DOCUMENT")
    if len(content) > MAX_FILE_BYTES:
        raise DocumentParseError("FILE_TOO_LARGE")

    if extension == "pdf":
        document = _read_pdf(content)
    else:
        document = _read_docx(content)

    text_length = sum(len(text.strip()) for text, _ in document.paragraphs)
    if text_length == 0:
        if document.has_images:
            return ResumeParseResponse(status="NEEDS_OCR", errorCode=f"SCANNED_{extension.upper()}", parserVersion=PARSER_VERSION)
        raise DocumentParseError("EMPTY_DOCUMENT")
    if text_length < 80 and document.has_images:
        return ResumeParseResponse(status="NEEDS_OCR", errorCode=f"SCANNED_{extension.upper()}", parserVersion=PARSER_VERSION)

    facts = _extract_facts(document.paragraphs)
    return ResumeParseResponse(
        status="COMPLETED" if facts else "PARTIAL",
        errorCode=None if facts else "NO_STRUCTURED_FACTS",
        parserVersion=PARSER_VERSION,
        facts=facts,
    )


def _read_pdf(content: bytes) -> DocumentText:
    if not content.startswith(b"%PDF-"):
        raise DocumentParseError("MALFORMED_PDF")
    output: list[tuple[str, str | None]] = []
    has_images = False
    extracted_characters = 0
    try:
        with pdfplumber.open(io.BytesIO(content)) as pdf:
            if not pdf.pages:
                raise DocumentParseError("EMPTY_DOCUMENT")
            if len(pdf.pages) > MAX_PDF_PAGES:
                raise DocumentParseError("FILE_TOO_COMPLEX")
            for index, page in enumerate(pdf.pages, start=1):
                has_images = has_images or bool(page.images)
                text = page.extract_text(layout=False) or ""
                if text:
                    output.extend((line, f"page:{index}") for line in text.splitlines())
                    extracted_characters += len(text)
                if extracted_characters > MAX_TEXT_CHARACTERS:
                    raise DocumentParseError("TEXT_LIMIT_EXCEEDED")
    except DocumentParseError:
        raise
    except Exception as exc:
        name = type(exc).__name__.lower()
        if "password" in name or "encrypt" in str(exc).lower():
            raise DocumentParseError("ENCRYPTED_PDF") from None
        raise DocumentParseError("MALFORMED_PDF") from None
    return DocumentText(output, has_images)


def _read_docx(content: bytes) -> DocumentText:
    if not content.startswith(b"PK"):
        raise DocumentParseError("MALFORMED_DOCX")
    try:
        with zipfile.ZipFile(io.BytesIO(content)) as archive:
            entries = archive.infolist()
            if len(entries) > MAX_DOCX_ENTRIES or sum(entry.file_size for entry in entries) > MAX_UNCOMPRESSED_DOCX_BYTES:
                raise DocumentParseError("FILE_TOO_COMPLEX")
            names = set(archive.namelist())
            if "[Content_Types].xml" not in names or "word/document.xml" not in names:
                raise DocumentParseError("MALFORMED_DOCX")
            has_images = any(name.startswith("word/media/") and not name.endswith("/") for name in names)
        document = Document(io.BytesIO(content))
    except DocumentParseError:
        raise
    except Exception:
        raise DocumentParseError("MALFORMED_DOCX") from None

    lines: list[str] = []
    for paragraph in document.paragraphs:
        if paragraph.text.strip():
            lines.append(paragraph.text)
    for table in document.tables:
        for row in table.rows:
            for cell in row.cells:
                if cell.text.strip():
                    lines.extend(cell.text.splitlines())
    if sum(map(len, lines)) > MAX_TEXT_CHARACTERS:
        raise DocumentParseError("TEXT_LIMIT_EXCEEDED")
    return DocumentText([(line, None) for line in lines], has_images)


def _extract_facts(paragraphs: list[tuple[str, str | None]]) -> list[ExtractedFact]:
    facts: list[ExtractedFact] = []
    section = "GENERAL"
    section_lines: list[tuple[str, str | None, str]] = []
    all_lines: list[tuple[str, str | None, str]] = []
    for raw, reference in paragraphs:
        line = " ".join(unescape(raw).replace("\x00", " ").split())
        if not line:
            continue
        heading = _heading_type(line)
        if heading:
            section = heading
            continue
        row = (line[:1000], reference, section)
        section_lines.append(row)
        all_lines.append(row)

    seen_skills: set[str] = set()
    for line, reference, current_section in all_lines:
        for canonical, matched in _skills_in(line):
            if canonical in seen_skills:
                continue
            seen_skills.add(canonical)
            facts.append(_fact("SKILL", canonical, canonical.lower(), line, current_section, reference))
            if len(facts) >= MAX_FACTS:
                return facts

    seen_lines: set[tuple[str, str]] = set()
    for kind in ("EDUCATION", "EXPERIENCE", "CERTIFICATION"):
        for line, reference, current_section in section_lines:
            if current_section != kind:
                continue
            normalized = _normalize(line)
            key = (kind, normalized)
            if key in seen_lines:
                continue
            seen_lines.add(key)
            facts.append(_fact(kind, line, normalized, line, current_section.lower(), reference))
            if len(facts) >= MAX_FACTS:
                return facts

    full_text = "\n".join(line for line, _, _ in all_lines)
    seen_contacts: set[tuple[str, str]] = set()
    for match in EMAIL_RE.finditer(full_text):
        value = match.group(0)
        _append_contact(facts, seen_contacts, "EMAIL", value, value.lower())
    for match in PHONE_RE.finditer(full_text):
        value = match.group(0).strip()
        if _is_date_or_year_range(value):
            continue
        digits = re.sub(r"\D", "", value)
        if 7 <= len(digits) <= 15:
            _append_contact(facts, seen_contacts, "PHONE", value, digits)
    return facts[:MAX_FACTS]


def _append_contact(facts: list[ExtractedFact], seen: set[tuple[str, str]], kind: str, value: str, normalized: str) -> None:
    key = (kind, normalized)
    if key in seen or len(facts) >= MAX_FACTS:
        return
    seen.add(key)
    facts.append(_fact(kind, value, normalized, value, "contact", None))


def _fact(kind: str, value: str, normalized: str, evidence: str, section: str, reference: str | None) -> ExtractedFact:
    if kind not in {"EMAIL", "PHONE"}:
        evidence = EMAIL_RE.sub("[redacted contact]", evidence)
        evidence = PHONE_RE.sub(lambda match: match.group(0) if _is_date_or_year_range(match.group(0)) else "[redacted contact]", evidence)
    return ExtractedFact(
        type=kind,
        value=value[:1000],
        normalizedValue=normalized[:1000],
        evidence=evidence[:1000],
        sourceSection=section[:80],
        sourceReference=reference,
    )


def _heading_type(line: str) -> str | None:
    heading = re.sub(r"[:|]+$", "", line).strip()
    for kind, pattern in SECTION_HEADINGS.items():
        if pattern.fullmatch(heading):
            return kind
    return None


def _skills_in(line: str) -> list[tuple[str, str]]:
    """Extract distinct (canonical, key) pairs from a line using longest match first."""
    spans = extract_skill_spans(line)
    seen: dict[str, str] = {}
    for canonical, _, _, _, key in spans:
        seen.setdefault(canonical, key)
    return sorted(seen.items())


def _contains_phrase(text: str, phrase: str) -> bool:
    return re.search(r"(?<![a-z0-9+#.\-])" + re.escape(phrase) + r"(?![a-z0-9+#.\-])", text, re.I) is not None


def _normalize(value: str) -> str:
    return re.sub(r"\s+", " ", re.sub(r"[^a-z0-9+#. ]", " ", value.lower())).strip()


def _is_date_or_year_range(value: str) -> bool:
    compact = re.sub(r"\s+", "", value)
    return bool(re.fullmatch(r"(?:(?:19|20)\d{2}[-/](?:19|20)\d{2}|\d{1,2}[-/]\d{1,2}[-/]\d{2,4})", compact))
