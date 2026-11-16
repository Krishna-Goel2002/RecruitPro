#!/usr/bin/env python3
"""Fail if the shared design system or the other shared web files differ between the portals and the landing site.

Source of truth: hr-frontend/ (the HR workstream owns src/design). Copies that must be byte-identical:

  hr-frontend/src/design          ->  frontend/src/design, site/src/design
  hr-frontend/public/theme-init.js -> frontend/public/theme-init.js, site/public/theme-init.js
  hr-frontend/{nginx.conf, Dockerfile, .dockerignore} -> frontend/ (the two portals share their serving setup, so a
  header or cache fix cannot land in one portal only; the site has its own stricter nginx.conf and Dockerfile
  because it calls no API)

Workflow (DESIGN_SYSTEM.md §12): change src/design in hr-frontend, copy the folder to frontend/ and site/, run this
check, then rebuild and snapshot all three apps.

PER_COPY lists paths (relative to src/design) that are allowed to differ. It is empty on purpose: app-specific code
(navigation, pages, API, landing sections) lives outside src/design.

Exit code 1 on any difference; each problem names the copy that drifted.
"""
import filecmp
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = "hr-frontend"
# (path relative to each app, copies that must match the source)
SHARED = [
    ("src/design", ["frontend", "site"]),
    ("public/theme-init.js", ["frontend", "site"]),
    ("nginx.conf", ["frontend"]),
    ("Dockerfile", ["frontend"]),
    (".dockerignore", ["frontend", "site"]),
]
PER_COPY: set[str] = set()
IGNORED_PARTS = {"__pycache__", ".DS_Store"}


def walk(base: pathlib.Path) -> set[str]:
    return {
        p.relative_to(base).as_posix()
        for p in base.rglob("*")
        if p.is_file() and not (IGNORED_PARTS & set(p.parts))
    }


def compare(rel: str, copy: str) -> list[str]:
    src, dst = ROOT / SOURCE / rel, ROOT / copy / rel
    a, b = f"{SOURCE}/{rel}", f"{copy}/{rel}"
    if not src.exists():
        return [f"missing (source of truth): {a}"]
    if src.is_file():
        if not dst.is_file():
            return [f"missing: {b}"]
        return [] if filecmp.cmp(src, dst, shallow=False) else [f"differs: {b} (from {a})"]
    if not dst.is_dir():
        return [f"missing folder: {b}"]
    fa, fb = walk(src) - PER_COPY, walk(dst) - PER_COPY
    problems = [f"only in {a}: {f}  (copy it to {copy}/)" for f in sorted(fa - fb)]
    problems += [f"only in {b}: {f}" for f in sorted(fb - fa)]
    problems += [f"differs: {b}/{f}" for f in sorted(fa & fb) if not filecmp.cmp(src / f, dst / f, shallow=False)]
    return problems


def main() -> int:
    problems: list[str] = []
    for rel, copies in SHARED:
        for copy in copies:
            problems += compare(rel, copy)
    print("\n".join(problems) or f"design folders in sync ({SOURCE} -> frontend, site)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
