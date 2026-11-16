#!/usr/bin/env python3
"""Create or complete the local `.env` with freshly generated secrets.

RecruitPro's docker-compose.yml no longer carries default secrets (security incident of 17 Nov 2026: the old
development defaults are public in the Git history and the backend now refuses them). This script writes random
values for the four secrets the stack needs, using the operating system's cryptographic random generator:

* JWT_SECRET          - 48 random bytes, Base64 (access-token signing key)
* AI_SERVICE_TOKEN    - 48 random bytes, URL-safe (backend <-> AI service)
* AGENT_SERVICE_TOKEN - 48 random bytes, URL-safe (backend <-> agent service)
* APP_ENCRYPTION_KEY  - exactly 32 random bytes, Base64 (AES-256-GCM key for stored tokens and MFA secrets)

Rules: `.env` is created from `.env.example` when it does not exist; a secret that already has a value is kept
(use --force to replace all four); secrets are never printed. `.env` is git-ignored - never commit it.

Usage (repository root):  python tools/generate_env.py [--force] [--env-file PATH]
"""
from __future__ import annotations

import argparse
import base64
import os
import re
import secrets
import shutil
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
GENERATORS = {
    "JWT_SECRET": lambda: base64.b64encode(secrets.token_bytes(48)).decode("ascii"),
    "AI_SERVICE_TOKEN": lambda: secrets.token_urlsafe(48),
    "AGENT_SERVICE_TOKEN": lambda: secrets.token_urlsafe(48),
    "APP_ENCRYPTION_KEY": lambda: base64.b64encode(secrets.token_bytes(32)).decode("ascii"),
}
LINE_RE = re.compile(r"^(\s*)([A-Z0-9_]+)=(.*)$")


def complete(lines: list[str], force: bool) -> tuple[list[str], list[str]]:
    """Fills empty (or, with force, all) managed secrets; appends any that are missing."""
    seen: set[str] = set()
    changed: list[str] = []
    out: list[str] = []
    for line in lines:
        match = LINE_RE.match(line.rstrip("\r\n"))
        if match and match.group(2) in GENERATORS:
            name = match.group(2)
            seen.add(name)
            if force or not match.group(3).strip():
                out.append(f"{match.group(1)}{name}={GENERATORS[name]()}\n")
                changed.append(name)
                continue
        out.append(line if line.endswith("\n") else line + "\n")
    for name, make in GENERATORS.items():
        if name not in seen:
            out.append(f"{name}={make()}\n")
            changed.append(name)
    return out, changed


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--force", action="store_true", help="replace all four secrets even when they have values")
    parser.add_argument("--env-file", default=os.path.join(ROOT, ".env"), help="path of the .env file (default: repo root)")
    args = parser.parse_args(argv)

    env_path = os.path.abspath(args.env_file)
    if not os.path.exists(env_path):
        example = os.path.join(ROOT, ".env.example")
        if os.path.exists(example):
            shutil.copyfile(example, env_path)
        else:
            open(env_path, "w", encoding="utf-8").close()
        print(f"Created {env_path}")
    with open(env_path, encoding="utf-8") as handle:
        lines = handle.readlines()
    updated, changed = complete(lines, args.force)
    with open(env_path, "w", encoding="utf-8", newline="\n") as handle:
        handle.writelines(updated)
    try:
        os.chmod(env_path, 0o600)
    except OSError:
        pass  # Windows: ACLs, not POSIX modes
    if changed:
        print("Generated new values for: " + ", ".join(changed) + " (not shown). Never commit .env.")
    else:
        print("All secrets already set; nothing changed (use --force to rotate them).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
