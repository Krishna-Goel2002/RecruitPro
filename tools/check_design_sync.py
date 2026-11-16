#!/usr/bin/env python3
"""Fail if frontend/src/design and hr-frontend/src/design (and the other shared portal files) differ.

Shared byte for byte: src/design/, public/theme-init.js, and the serving setup (nginx.conf with the CSP and
security headers, Dockerfile, .dockerignore), so a header or cache fix cannot land in one portal only.

The design system is copied byte for byte between the two portals (DESIGN_SYSTEM.md §12). Workflow: change
src/design in one portal, copy the folder to the other, run this check, then rebuild and snapshot both portals.

PER_PORTAL lists paths (relative to src/design) that are allowed to differ. It is empty on purpose: portal-specific
code (navigation, pages, API) lives outside src/design.
"""
import filecmp
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
PAIRS = [
    ("frontend/src/design", "hr-frontend/src/design"),
    ("frontend/public/theme-init.js", "hr-frontend/public/theme-init.js"),
    ("frontend/nginx.conf", "hr-frontend/nginx.conf"),
    ("frontend/Dockerfile", "hr-frontend/Dockerfile"),
    ("frontend/.dockerignore", "hr-frontend/.dockerignore"),
]
PER_PORTAL: set[str] = set()
IGNORED_PARTS = {"__pycache__", ".DS_Store"}


def walk(base: pathlib.Path) -> set[str]:
    return {
        p.relative_to(base).as_posix()
        for p in base.rglob("*")
        if p.is_file() and not (IGNORED_PARTS & set(p.parts))
    }


def main() -> int:
    problems: list[str] = []
    for a, b in PAIRS:
        pa, pb = ROOT / a, ROOT / b
        if not (pa.is_dir() or pb.is_dir()):  # single file (a name without a suffix, e.g. Dockerfile, too)
            if not (pa.is_file() and pb.is_file()):
                problems.append(f"missing: {a if not pa.is_file() else b}")
            elif not filecmp.cmp(pa, pb, shallow=False):
                problems.append(f"differs: {a} <> {b}")
            continue
        if not pa.is_dir() or not pb.is_dir():
            problems.append(f"missing folder: {a if not pa.is_dir() else b}")
            continue
        fa, fb = walk(pa) - PER_PORTAL, walk(pb) - PER_PORTAL
        problems += [f"only in {a}: {f}" for f in sorted(fa - fb)]
        problems += [f"only in {b}: {f}" for f in sorted(fb - fa)]
        problems += [f"differs: {f}" for f in sorted(fa & fb) if not filecmp.cmp(pa / f, pb / f, shallow=False)]
    print("\n".join(problems) or "design folders in sync")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
