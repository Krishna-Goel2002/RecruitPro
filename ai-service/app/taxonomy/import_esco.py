"""Import ESCO skills into the RecruitPro taxonomy format (plan D10).

ESCO's bulk download is not reachable from the build environment, so this tool converts the
official CSV export when you have it::

    python -m app.taxonomy.import_esco --skills skills_en.csv \\
        [--relations broaderRelationsSkillPillar.csv] --domain GENERAL --out esco_skills.json

then point ``TAXONOMY_EXTRA_PATH`` at the output. Curated skills always win: an ESCO concept whose
id or labels collide with ``skills.json`` is skipped, so the import never changes existing matches.
Only ``preferredLabel`` and ``altLabels`` of up to five words are used as names/aliases; ESCO
labels are often long phrases ("manage a team of software developers") that would never match.
"""
from __future__ import annotations

import argparse
import csv
import json
import re
import sys
from pathlib import Path

from app.taxonomy.taxonomy import SKILLS_PATH, load_skills, phrase_key

MAX_LABEL_WORDS = 5


def _slug(label: str) -> str:
    return "esco-" + re.sub(r"[^a-z0-9]+", "-", label.lower()).strip("-")


def convert(skills_csv: Path, relations_csv: Path | None, domain: str) -> list[dict]:
    curated = load_skills(SKILLS_PATH)
    taken_keys = {phrase_key(label)[0] for skill in curated for label in (skill.name, *skill.aliases)}
    taken_ids = {skill.id for skill in curated}
    by_uri: dict[str, dict] = {}
    with skills_csv.open(encoding="utf-8", newline="") as handle:
        for row in csv.DictReader(handle):
            label = (row.get("preferredLabel") or "").strip()
            if not label or len(label.split()) > MAX_LABEL_WORDS or not phrase_key(label)[0]:
                continue
            key = phrase_key(label)[0]
            identifier = _slug(label)
            if key in taken_keys or identifier in taken_ids:
                continue
            aliases = []
            for alt in (row.get("altLabels") or "").splitlines():
                alt = alt.strip()
                alt_key = phrase_key(alt)[0] if alt else ()
                if alt and len(alt.split()) <= MAX_LABEL_WORDS and alt_key and alt_key not in taken_keys:
                    aliases.append(alt)
                    taken_keys.add(alt_key)
            taken_keys.add(key)
            taken_ids.add(identifier)
            by_uri[row.get("conceptUri", identifier)] = {
                "id": identifier, "name": label, "aliases": aliases, "domain": domain, "parents": [],
                "distinctions": [], "ambiguous": False, "contextWords": [], "escoUri": row.get("conceptUri"),
            }
    if relations_csv is not None:
        with relations_csv.open(encoding="utf-8", newline="") as handle:
            for row in csv.DictReader(handle):
                child, parent = by_uri.get(row.get("conceptUri", "")), by_uri.get(row.get("broaderUri", ""))
                if child and parent and parent["id"] not in child["parents"]:
                    child["parents"].append(parent["id"])
    return list(by_uri.values())


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Convert an ESCO skills CSV into a RecruitPro taxonomy extension")
    parser.add_argument("--skills", type=Path, required=True, help="ESCO skills_<lang>.csv")
    parser.add_argument("--relations", type=Path, help="ESCO broaderRelationsSkillPillar.csv (optional)")
    parser.add_argument("--domain", default="GENERAL", help="domain assigned to imported skills")
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args(argv)
    skills = convert(args.skills, args.relations, args.domain)
    args.out.write_text(json.dumps({"version": "esco-import", "skills": skills}, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"Wrote {len(skills)} ESCO skills to {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
