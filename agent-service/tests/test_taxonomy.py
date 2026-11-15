"""Vendored taxonomy must not drift from ai-service; alias tables must point at real skills."""
from __future__ import annotations

import importlib.util
from pathlib import Path

import pytest

from app.skills import DEPENDENCY_ALIASES, LANGUAGE_ALIASES, PREFIX_ALIASES, skill_for_dependency
from app.taxonomy.taxonomy import get_taxonomy

REPO_ROOT = Path(__file__).resolve().parents[2]
SYNC_TOOL = REPO_ROOT / "tools" / "sync_taxonomy.py"


@pytest.mark.skipif(not (REPO_ROOT / "ai-service" / "app" / "taxonomy").exists() or not SYNC_TOOL.exists(),
                    reason="ai-service is not part of this checkout (standalone image build)")
def test_vendored_taxonomy_matches_ai_service():
    spec = importlib.util.spec_from_file_location("sync_taxonomy", SYNC_TOOL)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    assert module.drifted() == [], "run: python tools/sync_taxonomy.py"


def test_alias_targets_exist():
    taxonomy = get_taxonomy()
    targets = list(DEPENDENCY_ALIASES.values()) + [target for _, target in PREFIX_ALIASES] + list(LANGUAGE_ALIASES.values())
    assert [target for target in targets if taxonomy.by_id(target) is None] == []
    assert taxonomy.validate() == []


@pytest.mark.parametrize("name,skill", [("react-dom", "react"), ("springframework", "spring"),
                                        ("org.springframework.boot:spring-boot-starter", "spring-boot"),
                                        ("@angular/core", "angular"), ("torch", "pytorch"), ("express", "express"),
                                        ("lodash", None), ("core", None), ("c", None)])
def test_dependency_mapping(name, skill):
    found = skill_for_dependency(name)
    assert (found.id if found else None) == skill
