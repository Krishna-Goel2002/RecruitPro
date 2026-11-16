"""Degree levels and fields of study, shared by the parser and the matcher."""
from __future__ import annotations

import re
from dataclasses import dataclass

LEVELS = {"DIPLOMA": 1, "BACHELOR": 2, "MASTER": 3, "DOCTORATE": 4}

# (pattern, level, implied field family). Two-letter abbreviations (BE, ME, MS, MA, BA, BS) are
# case-sensitive and must be followed by "in", "(", a separator or a field word, so "MS Excel"
# or "BE able" never become degrees.
_FIELD_WORDS = (
    "computer|data|electronics|electrical|mechanical|civil|chemical|mathematics|maths|physics|chemistry|biology|"
    "biotechnology|economics|english|history|psychology|commerce|finance|accounting|marketing|management|"
    "information|software|statistics|political|sociology|journalism|mass|nursing|public|business|education"
)
_FOLLOW = rf"(?=\s*(?:\(|in\b|of\b|[-,–/]|$|(?i:{_FIELD_WORDS})))"
# "B.E", "M.S", "B.A" written without the final dot ("B.E Mechanical", "M.S in Data Science").
_DOTTED_FOLLOW = rf"(?=\s*(?:\(|in\b|of\b|[-,–/]|$|(?i:{_FIELD_WORDS})|[A-Z][a-z]))"
_DEGREES: tuple[tuple[re.Pattern[str], str, str | None], ...] = tuple(
    (re.compile(pattern, flags), level, field)
    for pattern, flags, level, field in (
        (r"\bPh\.?\s?D\b\.?|\bD\.?\s?Phil\b|\bdoctor(?:ate)?\s+of\s+philosophy\b|\bdoctorate\b", re.I, "DOCTORATE", None),
        (r"\bM\.?\s?Tech\b|\bmaster\s+of\s+(?:technology|engineering)\b", re.I, "MASTER", None),
        (r"\bM\.\s?E\.(?=\s|$)|\bM\.\s?E\b(?!\.)" + _DOTTED_FOLLOW + r"|\bME" + _FOLLOW, 0, "MASTER", None),
        (r"\bM\.?\s?Sc\b\.?|\bmaster\s+of\s+science\b", re.I, "MASTER", None),
        (r"\bM\.\s?S\.(?=\s|$)|\bM\.\s?S\b(?!\.)" + _DOTTED_FOLLOW + r"|\bMS" + _FOLLOW, 0, "MASTER", None),
        (r"\bM\.?\s?C\.?\s?A\b\.?|\bmaster\s+of\s+computer\s+applications?\b", re.I, "MASTER", "computer science"),
        (r"\bM\.?\s?B\.?\s?A\b\.?|\bmaster\s+of\s+business\s+administration\b|\bPGDM\b|\bPGDBA\b", re.I, "MASTER", "business"),
        (r"\bM\.?\s?Com\b\.?|\bmaster\s+of\s+commerce\b", re.I, "MASTER", "commerce"),
        (r"\bM\.?\s?Pharm\b\.?|\bmaster\s+of\s+pharmacy\b", re.I, "MASTER", "pharmacy"),
        (r"\bLL\.?\s?M\b\.?|\bmaster\s+of\s+laws\b", re.I, "MASTER", "law"),
        (r"\bM\.?\s?Ed\b\.?|\bmaster\s+of\s+education\b", re.I, "MASTER", "education"),
        (r"\bM\.?\s?Des\b\.?|\bmaster\s+of\s+design\b", re.I, "MASTER", "design"),
        (r"\bM\.?\s?Phil\b\.?", re.I, "MASTER", None),
        (r"\bmaster\s+of\s+arts\b", re.I, "MASTER", None),
        (r"\bM\.\s?A\.(?=\s|$)|\bM\.\s?A\b(?!\.)" + _DOTTED_FOLLOW + r"|\bMA" + _FOLLOW, 0, "MASTER", None),
        # A bare "Master" is a title or a certification ("Scrum Master", "Master Trainer"), not a degree.
        (r"\bmaster(?:'s|’s|s)\b(?:\s+degree)?|\bmaster\s+(?:degree|in|of)\b|\bpost[\s-]?graduat(?:e|ion)\b|"
         r"\bmaster(?=\s*(?:/|or\b|and\b)\s*bachelor)|(?<=bachelor/)master\b|(?<=bachelor or )master\b", re.I, "MASTER", None),
        (r"\bB\.?\s?Tech\b\.?|\bbachelor\s+of\s+(?:technology|engineering)\b", re.I, "BACHELOR", None),
        (r"\bB\.\s?E\.(?=\s|$)|\bB\.\s?E\b(?!\.)" + _DOTTED_FOLLOW + r"|\bBE" + _FOLLOW, 0, "BACHELOR", None),
        (r"\bB\.?\s?C\.?\s?A\b\.?|\bbachelor\s+of\s+computer\s+applications?\b", re.I, "BACHELOR", "computer science"),
        (r"\bB\.?\s?B\.?\s?A\b\.?|\bbachelor\s+of\s+business\s+administration\b|\bBMS\b", re.I, "BACHELOR", "business"),
        (r"\bB\.?\s?Com\b\.?|\bbachelor\s+of\s+commerce\b", re.I, "BACHELOR", "commerce"),
        (r"\bB\.?\s?Pharm\b\.?|\bbachelor\s+of\s+pharmacy\b", re.I, "BACHELOR", "pharmacy"),
        (r"\bLL\.?\s?B\b\.?|\bbachelor\s+of\s+laws\b|\bB\.?A\.?\s?LL\.?B\b", re.I, "BACHELOR", "law"),
        (r"\bB\.?\s?Ed\b\.?|\bB\.?\s?El\.?\s?Ed\b\.?|\bbachelor\s+of\s+(?:elementary\s+)?education\b", re.I, "BACHELOR", "education"),
        (r"\bB\.?\s?Des\b\.?|\bbachelor\s+of\s+design\b|\bB\.?\s?F\.?\s?A\b\.?|\bbachelor\s+of\s+fine\s+arts\b", re.I, "BACHELOR", "design"),
        (r"\bB\.?\s?Arch\b\.?|\bbachelor\s+of\s+architecture\b", re.I, "BACHELOR", "architecture"),
        (r"\bMBBS\b|\bBDS\b|\bbachelor\s+of\s+medicine\b", re.I, "BACHELOR", "medicine"),
        (r"\bB\.?\s?P\.?\s?T\b\.?|\bbachelor\s+of\s+physiotherapy\b", re.I, "BACHELOR", "medicine"),
        (r"\bB\.?\s?Sc\b\.?|\bbachelor\s+of\s+science\b", re.I, "BACHELOR", None),
        (r"\bB\.\s?S\.(?=\s|$)|\bB\.\s?S\b(?!\.)" + _DOTTED_FOLLOW + r"|\bBS" + _FOLLOW, 0, "BACHELOR", None),
        (r"\bbachelor\s+of\s+arts\b", re.I, "BACHELOR", None),
        (r"\bB\.\s?A\.(?=\s|$)|\bB\.\s?A\b(?!\.)" + _DOTTED_FOLLOW + r"|\bBA" + _FOLLOW, 0, "BACHELOR", None),
        (r"\bbachelor(?:'s|’s|s)\b(?:\s+degree)?|\bbachelor\s+(?:degree|in|of)\b|\bundergraduate\s+degree\b|"
         r"\bbachelor(?=\s*(?:/|or\b|and\b)\s*master)|(?<=master/)bachelor\b|(?<=master or )bachelor\b|"
         r"\bgraduat(?:e|ion)\s+(?:degree|in)\b|\bany\s+graduate\b", re.I, "BACHELOR", None),
        (r"\bdiploma\b|\bpolytechnic\b|\bassociate(?:'s)?\s+degree\b", re.I, "DIPLOMA", None),
        (r"\bD\.?\s?El\.?\s?Ed\b\.?|\bD\.\s?Ed\b\.?", re.I, "DIPLOMA", "education"),
        (r"\bD\.?\s?Pharm\b\.?", re.I, "DIPLOMA", "pharmacy"),
        (r"\bGNM\b|\bANM\b", 0, "DIPLOMA", "nursing"),
        (r"\bITI\b", 0, "DIPLOMA", None),
    )
)
_SCHOOL_RE = re.compile(r"\b(?:12th|10th|XII|HSC|SSC|higher\s+secondary|senior\s+secondary|secondary\s+school|matriculation|intermediate|class\s+(?:x|xii|10|12))\b", re.I)

FIELD_FAMILIES: dict[str, tuple[str, ...]] = {
    "computer science": ("computer science", "computer science and engineering", "computer engineering",
                         "information technology", "software engineering", "computer applications", "computing",
                         "informatics", "information science", "information systems", "cse", "comp sci"),
    "data science": ("data science", "data analytics", "artificial intelligence", "machine learning", "ai and ml",
                     "ai ml", "business analytics"),
    "electronics": ("electronics", "electronics and communication", "electronics and telecommunication",
                    "electrical and electronics", "instrumentation", "ece", "eee", "entc", "telecommunication"),
    "electrical": ("electrical engineering", "electrical"),
    "mechanical": ("mechanical", "mechanical engineering", "production engineering", "automobile engineering",
                   "industrial engineering", "manufacturing"),
    "civil": ("civil", "civil engineering", "structural engineering"),
    "chemical": ("chemical engineering",),
    "mathematics": ("mathematics", "maths", "applied mathematics", "statistics", "actuarial science"),
    "physics": ("physics",),
    "chemistry": ("chemistry",),
    "biology": ("biology", "biotechnology", "microbiology", "life sciences", "biochemistry", "zoology", "botany",
                "genetics", "bioinformatics"),
    "commerce": ("commerce", "accounting", "accountancy", "finance", "banking", "taxation", "financial management",
                 "accounting and finance", "banking and finance"),
    "business": ("business administration", "management", "business management", "marketing", "human resources",
                 "human resource management", "hr", "international business", "operations management",
                 "supply chain management", "logistics", "business"),
    "economics": ("economics", "econometrics"),
    "law": ("law", "legal studies", "laws"),
    "medicine": ("medicine", "surgery", "dental surgery", "physiotherapy", "medical"),
    "nursing": ("nursing", "general nursing and midwifery", "gnm", "anm", "midwifery"),
    "pharmacy": ("pharmacy", "pharmaceutical sciences", "pharmaceutics", "pharmacology"),
    "education": ("education", "elementary education", "early childhood education", "teaching"),
    "design": ("design", "fine arts", "visual arts", "graphic design", "communication design", "fashion design",
               "interior design", "product design", "industrial design", "visual communication"),
    "psychology": ("psychology", "applied psychology", "clinical psychology", "counselling psychology"),
    "english": ("english", "english literature", "literature", "linguistics"),
    "journalism": ("journalism", "mass communication", "media studies", "communication studies", "mass media"),
    "humanities": ("history", "political science", "sociology", "philosophy", "social work", "public policy"),
    "hospitality": ("hotel management", "hospitality", "tourism"),
    "architecture": ("architecture",),
    "public health": ("public health", "healthcare management", "hospital administration", "health administration"),
}
RELATED_FIELDS: dict[str, frozenset[str]] = {
    "computer science": frozenset({"data science", "electronics", "mathematics", "electrical"}),
    "data science": frozenset({"computer science", "mathematics", "economics"}),
    "electronics": frozenset({"electrical", "computer science"}),
    "electrical": frozenset({"electronics"}),
    "mathematics": frozenset({"computer science", "data science", "economics", "physics"}),
    "commerce": frozenset({"business", "economics"}),
    "business": frozenset({"commerce", "economics"}),
    "economics": frozenset({"commerce", "business", "mathematics"}),
    "medicine": frozenset({"nursing", "pharmacy", "public health", "biology"}),
    "nursing": frozenset({"medicine", "public health"}),
    "pharmacy": frozenset({"medicine", "biology", "chemistry"}),
    "biology": frozenset({"medicine", "pharmacy", "chemistry"}),
    "education": frozenset({"psychology", "english"}),
    "english": frozenset({"journalism", "education"}),
    "journalism": frozenset({"english"}),
    "design": frozenset({"architecture"}),
    "public health": frozenset({"medicine", "nursing"}),
}
_FIELD_PATTERNS = sorted(
    ((phrase, family) for family, phrases in FIELD_FAMILIES.items() for phrase in phrases),
    key=lambda item: len(item[0]), reverse=True,
)
_CASE_SENSITIVE_FIELDS = {"cse", "ece", "eee", "entc", "hr", "gnm", "anm"}


@dataclass(frozen=True)
class EducationInfo:
    level: str | None
    fields: frozenset[str]  # field families, explicit and implied by the degree
    field_label: str | None  # the explicitly stated field family, for display
    any_field: bool = False  # "any discipline" / "or related field" style wording handled by callers


def detect_degree(text: str) -> tuple[str, str | None, tuple[int, int]] | None:
    """The highest-level degree mentioned: (level, implied field family, span)."""
    best: tuple[str, str | None, tuple[int, int]] | None = None
    for pattern, level, implied in _DEGREES:
        match = pattern.search(text)
        if match and (best is None or LEVELS[level] > LEVELS[best[0]]):
            best = (level, implied, match.span())
    return best


def degree_levels(text: str) -> list[str]:
    """Every degree level mentioned, e.g. "Bachelor's or Master's" -> [MASTER, BACHELOR]."""
    return sorted({level for pattern, level, _ in _DEGREES if pattern.search(text)}, key=LEVELS.__getitem__, reverse=True)


def detect_fields(text: str) -> list[str]:
    """Field families named in ``text`` (longest phrase first, abbreviations case-sensitive)."""
    found: list[str] = []
    lowered = text.lower()
    taken: list[tuple[int, int]] = []
    for phrase, family in _FIELD_PATTERNS:
        source = text if phrase in _CASE_SENSITIVE_FIELDS else lowered
        needle = phrase.upper() if phrase in _CASE_SENSITIVE_FIELDS else phrase
        for match in re.finditer(r"(?<![A-Za-z])" + re.escape(needle) + r"(?![A-Za-z])", source):
            if any(match.start() < end and match.end() > start for start, end in taken):
                continue
            taken.append(match.span())
            if family not in found:
                found.append(family)
    if re.search(r"(?<![A-Za-z])(?:CS|IT)(?![A-Za-z])", text) and "computer science" not in found:
        found.append("computer science")
    return found


def is_school_line(text: str) -> bool:
    return bool(_SCHOOL_RE.search(text)) and detect_degree(text) is None


def parse_education(text: str) -> EducationInfo:
    degree = detect_degree(text)
    explicit = detect_fields(text)
    fields = set(explicit)
    if degree and degree[1]:
        fields.add(degree[1])
    any_field = bool(re.search(r"\bany\s+(?:discipline|stream|field|graduate)\b", text, re.I))
    return EducationInfo(degree[0] if degree else None, frozenset(fields), explicit[0] if explicit else
                         (degree[1] if degree else None), any_field)


def fields_related(required: frozenset[str], held: frozenset[str]) -> bool:
    return any(field in RELATED_FIELDS.get(required_field, frozenset()) for required_field in required for field in held)
