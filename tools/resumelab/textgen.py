"""Template filling and writing-quality transforms.

A template mixes literal text, category slots (``{lang}``, ``{db2}``, ``{any}``), inline skill
declarations (``[[Skill|surface]]``) and metrics (``{pct}``). Literal text may be degraded (typos,
Indian-English phrasing, tense and capitalisation slips) according to the resume's quality; slot
and declaration surfaces are never touched, so the gold labels stay exact.
"""
from __future__ import annotations

import random
import re
from dataclasses import dataclass

from resumelab.data.professions import GENERIC, Profession, SkillSpec
from resumelab.data.writing import INDIAN_ENGLISH, TYPOS

TOKEN_RE = re.compile(r"(\{[a-z_]+\d?\}|\[\[[^\]]+\]\])")
METRICS = {"pct", "pct_small", "n", "n2", "team", "users", "money", "days", "hours", "mins", "ms", "count", "count2", "x", "fy",
           "pct_quota", "csat", "beds", "grade_lo", "grade_hi", "floors", "years", "title", "label", "year", "q", "range"}


def metric(name: str, rng: random.Random, indian: bool, as_of_year: int) -> str:
    if name == "pct":
        return str(rng.choice((18, 22, 25, 27, 30, 32, 35, 38, 40, 42, 45, 50, 55, 60, 65, 70)))
    if name == "pct_small":
        return str(rng.choice((3, 4, 5, 6, 7, 8, 9, 11, 12, 14, 15)))
    if name in {"n", "n2"}:
        return str(rng.randint(3, 12))
    if name == "team":
        return str(rng.randint(2, 8))
    if name == "users":
        return rng.choice(("50,000", "1.2 lakh", "2 lakh", "5 lakh", "10 lakh", "3 million", "200K", "40,000")) if indian else \
            rng.choice(("50,000", "200K", "1.5 million", "3 million", "80,000"))
    if name == "money":
        return rng.choice(("INR 1.2 Cr", "INR 85 lakh", "Rs. 40 lakh", "INR 3.5 Cr", "Rs. 12 lakh", "INR 6 Cr")) if indian else \
            rng.choice(("$450K", "$1.2M", "$2.8M", "$300K", "EUR 900K"))
    if name == "days":
        return str(rng.choice((5, 7, 10, 12, 14, 21, 30, 45)))
    if name == "hours":
        return str(rng.choice((4, 6, 8, 10, 12, 15, 20)))
    if name == "mins":
        return str(rng.choice((5, 8, 10, 12, 15, 20, 25, 40)))
    if name == "ms":
        return str(rng.choice((80, 120, 150, 200, 250, 300)))
    if name in {"count", "count2"}:
        base = rng.choice((40, 60, 75, 120, 150, 200, 250, 300, 450, 600, 800, 1200))
        return f"{base * (3 if name == 'count2' else 1):,}"
    if name == "x":
        return str(rng.choice((2, 3, 4, 5)))
    if name == "fy":
        return f"{(as_of_year - rng.randint(1, 3)) % 100:02d}"
    if name == "pct_quota":
        return str(rng.randint(104, 138))
    if name == "csat":
        return f"4.{rng.randint(3, 8)}"
    if name == "beds":
        return str(rng.choice((24, 30, 32, 40, 48)))
    if name == "grade_lo":
        return str(rng.choice((6, 8, 9)))
    if name == "grade_hi":
        return str(rng.choice((10, 12)))
    if name == "floors":
        return str(rng.choice((7, 10, 12, 14, 18, 22)))
    if name == "year":
        return str(as_of_year - rng.randint(1, 6))
    if name == "range":
        low = rng.randint(3, 8)
        return f"{low}-{low + rng.randint(2, 5)}"
    if name == "q":
        return str(rng.randint(1, 4))
    return ""


# Language binding of languages and the frameworks/test tools built on them. A bullet never pairs a language with a
# framework of another language ("Spring Boot services in C#").
BINDING = {
    "Java": "jvm", "Kotlin": "jvm", "Spring Boot": "jvm", "Hibernate": "jvm", "JUnit": "jvm", "Mockito": "jvm", "Maven": "jvm", "Gradle": "jvm",
    "TestNG": "jvm", "Python": "py", "Django": "py", "FastAPI": "py", "Flask": "py", "pytest": "py", "pandas": "py", "NumPy": "py",
    "scikit-learn": "py", "PySpark": "py", "JavaScript": "js", "TypeScript": "js", "Node.js": "js", "NestJS": "js", "Express": "js",
    "React": "js", "Next.js": "js", "Angular": "js", "Vue.js": "js", "Jest": "js", "Cypress": "js", "C#": "net", ".NET": "net",
    "Go": "go", "PHP": "php", "Laravel": "php", "Swift": "apple", "SwiftUI": "apple", "Objective-C": "apple", "iOS": "apple",
    "Dart": "dart", "Flutter": "dart", "React Native": "js", "Android": "jvm", "Jetpack Compose": "jvm", "R": "r",
}
_LANGS = {"Java", "Kotlin", "Python", "JavaScript", "TypeScript", "C#", "Go", "PHP", "Swift", "Dart", "Objective-C", "R", "Node.js"}


def compatible_with(name: str, used: list[str]) -> bool:
    binding = BINDING.get(name)
    if binding is None:
        return True
    for other in used:
        other_binding = BINDING.get(other)
        if other_binding and other_binding != binding and ((name in _LANGS) != (other in _LANGS)):
            return False
    return True


def spec_for(name: str, professions: list[Profession]) -> SkillSpec | None:
    for profession in professions:
        spec = profession.skill(name)
        if spec:
            return spec
    return next((spec for spec in GENERIC if spec.name == name), None)


def surface(rng: random.Random, spec: SkillSpec | None, name: str, quality: str) -> str:
    text = name
    if spec and spec.alt and rng.random() < 0.3:
        text = rng.choice(spec.alt)
    if quality == "poor" and rng.random() < 0.12 and len(text) > 3 and not any(ch.isdigit() for ch in text):
        text = text.lower()
    return text


@dataclass
class Filled:
    text: str
    skills: tuple[str, ...]
    surfaces: dict[str, str]


def degrade(literal: str, rng: random.Random, quality: str) -> str:
    """Typos, Indian-English swaps and casing slips on literal template text."""
    if quality == "polished" or not literal.strip():
        return literal
    typo_rate = 0.35 if quality == "poor" else 0.06
    if rng.random() < typo_rate:
        for word, typo in TYPOS.items():
            if re.search(rf"\b{word}\b", literal, re.I):
                literal = re.sub(rf"\b{word}\b", typo, literal, count=1, flags=re.I)
                break
    if quality == "poor" and rng.random() < 0.3:
        for plain, indian in INDIAN_ENGLISH:
            if literal.startswith(plain):
                literal = indian + literal[len(plain):]
                break
    return literal


def fill(template: str, rng: random.Random, *, pool: dict[str, list[str]], specs, quality: str, indian: bool, as_of_year: int,
         excluded: frozenset[str], context: dict[str, str] | None = None, prefer: set[str] | None = None) -> Filled | None:
    """Fill one template. ``pool``: category -> skill names available (``any`` = all). Returns None if a slot cannot be filled
    or an inline declaration names an excluded skill."""
    used: list[str] = []
    surfaces: dict[str, str] = {}
    out: list[str] = []
    for index, piece in enumerate(TOKEN_RE.split(template)):
        if not piece:
            continue
        if piece.startswith("[["):
            body = piece[2:-2]
            name, _, shown = body.partition("|")
            if name in excluded:
                return None
            out.append(shown or name)
            used.append(name)
            surfaces.setdefault(name, shown or name)
            continue
        if piece.startswith("{") and piece.endswith("}"):
            key = piece[1:-1]
            base = key.rstrip("0123456789")
            if context and key in context:
                out.append(context[key])
                continue
            if base in METRICS:
                out.append(metric(base, rng, indian, as_of_year))
                continue
            options = [name for name in pool.get(base, []) if name not in used and name not in excluded and compatible_with(name, used)]
            if not options:
                return None
            preferred = [name for name in options if prefer and name in prefer]
            name = rng.choice(preferred or options)
            used.append(name)
            shown_surface = surface(rng, specs(name), name, quality)
            surfaces.setdefault(name, shown_surface)
            out.append(shown_surface)
            continue
        out.append(degrade(piece, rng, quality) if index == 0 else (degrade(piece, rng, quality) if quality == "poor" else piece))
    text = "".join(out)
    text = re.sub(r"\s+", " ", text).strip()
    return Filled(text, tuple(dict.fromkeys(used)), surfaces)


_TENSE_SLIPS = {
    "Developed": ("Develop", "Developing"), "Designed": ("Design", "Designing"), "Built": ("Build", "Building"),
    "Managed": ("Manage", "Managing"), "Handled": ("Handle", "Handling"), "Implemented": ("Implement", "Implementing"),
    "Created": ("Create", "Creating"), "Prepared": ("Prepare", "Preparing"), "Coordinated": ("Coordinate", "Coordinating"),
    "Conducted": ("Conduct", "Conducting"), "Maintained": ("Maintain", "Maintaining"), "Led": ("Lead", "Leading"),
    "Wrote": ("Write", "Writing"), "Ran": ("Run", "Running"), "Worked": ("Work", "Working"), "Provided": ("Provide", "Providing"),
}


def finish_sentence(text: str, rng: random.Random, quality: str, tense_slip: bool = True) -> str:
    """Final punctuation/casing per quality. Polished: consistent; poor: random slips."""
    if quality == "polished":
        return text.rstrip(".")
    if quality == "average":
        return text if rng.random() < 0.5 else text.rstrip(".")
    if rng.random() < 0.25 and text[:1].isupper() and not text[:2].isupper():
        text = text[0].lower() + text[1:]
    if tense_slip and rng.random() < 0.2:
        first, _, rest = text.partition(" ")
        if first in _TENSE_SLIPS and rest:
            text = f"{rng.choice(_TENSE_SLIPS[first])} {rest}"
    if rng.random() < 0.3:
        text = text.rstrip(".") + rng.choice(("", ".", "..", " ."))
    return text
