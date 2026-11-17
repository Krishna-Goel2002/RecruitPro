"""Optional link to the RecruitPro skills taxonomy (ai-service/app/taxonomy/skills.json).

ResumeLab works without it (``taxonomyId`` is then null). With it, gold skills carry the taxonomy id
and the test suite can check that template text never mentions a taxonomy skill it did not
declare. The mention finder here is deliberately simple and conservative (exact names and aliases
on word boundaries; ambiguous names only with exact casing) - it is a lint for our own templates,
not a re-implementation of the parser under test.
"""
from __future__ import annotations

import json
import os
import re
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

_DEFAULT = Path(__file__).resolve().parents[2] / "ai-service" / "app" / "taxonomy" / "skills.json"


@dataclass(frozen=True)
class TaxSkill:
    id: str
    name: str
    aliases: tuple[str, ...]
    ambiguous: bool
    parents: tuple[str, ...]


class Taxonomy:
    def __init__(self, skills: list[TaxSkill]) -> None:
        self.skills = {skill.id: skill for skill in skills}
        self._by_surface: dict[str, TaxSkill] = {}
        for skill in skills:
            for surface in (skill.name,) + skill.aliases:
                self._by_surface.setdefault(surface.lower(), skill)
        patterns = []
        for skill in skills:
            for surface in sorted({skill.name, *skill.aliases}, key=len, reverse=True):
                patterns.append((surface, skill))
        patterns.sort(key=lambda item: -len(item[0]))
        self._patterns = patterns
        alternation = "|".join(re.escape(surface) for surface, _ in patterns)
        self._regex = re.compile(rf"(?<![\w+#.])(?:{alternation})(?![\w+#])", re.I)

    def resolve(self, name: str) -> TaxSkill | None:
        return self._by_surface.get(name.lower())

    def ancestors(self, skill_id: str) -> set[str]:
        seen: set[str] = set()
        stack = list(self.skills[skill_id].parents) if skill_id in self.skills else []
        while stack:
            current = stack.pop()
            if current not in seen:
                seen.add(current)
                stack.extend(self.skills[current].parents if current in self.skills else ())
        return seen

    def mentions(self, text: str) -> list[tuple[TaxSkill, str]]:
        """Taxonomy skills named in ``text`` (conservative: ambiguous names need exact casing)."""
        found = []
        for match in self._regex.finditer(text):
            surface = match.group(0)
            skill = self._by_surface.get(surface.lower())
            if skill is None:
                continue
            if skill.ambiguous and surface == surface.lower() and surface.lower() == skill.name.lower():
                continue  # "go", "express", "spring" in lower case prose are ordinary words
            if skill.ambiguous and surface.lower() == skill.name.lower() and surface != skill.name:
                continue
            if skill.ambiguous and len(surface) <= 2 and surface.lower() == skill.name.lower():
                continue  # "R", "C", "Go" - too ambiguous for a lint
            found.append((skill, surface))
        return found


@lru_cache(maxsize=1)
def load() -> Taxonomy | None:
    path = Path(os.environ.get("RESUMELAB_TAXONOMY", str(_DEFAULT)))
    if not path.is_file():
        return None
    data = json.loads(path.read_text(encoding="utf-8"))
    skills = [TaxSkill(item["id"], item["name"], tuple(item.get("aliases", ())), bool(item.get("ambiguous")), tuple(item.get("parents", ())))
              for item in data.get("skills", [])]
    return Taxonomy(skills)


def taxonomy_id(name: str) -> str | None:
    taxonomy = load()
    if taxonomy is None:
        return None
    skill = taxonomy.resolve(name)
    return skill.id if skill else None
