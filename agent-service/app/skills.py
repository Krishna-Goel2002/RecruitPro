"""Map languages, dependencies, topics and free text to taxonomy skills (vendored ai-service taxonomy).

Order of lookup for a dependency name: the alias table below (exact name, then group/prefix), then the taxonomy
name/alias lookup of the full name, of its last path segment (``@scope/pkg`` -> ``pkg``, ``group:artifact`` ->
``artifact``, ``github.com/org/repo`` -> ``repo``) and of the hyphen-to-space form. One-character names and
generic package words never map. A claim value is resolved by exact taxonomy lookup, then by mention detection.
"""
from __future__ import annotations

import re
from dataclasses import dataclass

from app.taxonomy.taxonomy import Skill, get_taxonomy

# Dependency or topic name -> taxonomy id, for packages the taxonomy has no alias for.
DEPENDENCY_ALIASES: dict[str, str] = {
    "react-dom": "react", "react-native": "react-native", "@types/react": "react", "react-scripts": "react",
    "next": "next-js", "vue": "vue-js", "nuxt": "vue-js", "@angular/core": "angular", "svelte": "svelte",
    "@sveltejs/kit": "svelte", "express": "express", "@nestjs/core": "nestjs", "typescript": "typescript",
    "redux": "redux", "@reduxjs/toolkit": "redux", "tailwindcss": "tailwind-css", "sass": "sass", "node-sass": "sass",
    "jest": "jest", "webpack": "webpack", "vite": "vite", "jquery": "jquery", "bootstrap": "bootstrap",
    "graphql": "graphql", "@apollo/client": "graphql", "apollo-server": "graphql",
    "torch": "pytorch", "torchvision": "pytorch", "tensorflow": "tensorflow", "keras": "keras",
    "scikit-learn": "scikit-learn", "sklearn": "scikit-learn", "numpy": "numpy", "pandas": "pandas",
    "matplotlib": "matplotlib", "opencv-python": "opencv", "opencv-python-headless": "opencv", "django": "django",
    "djangorestframework": "django", "flask": "flask", "fastapi": "fastapi", "sqlalchemy": "sqlalchemy",
    "celery": "celery", "pytest": "pytest", "langchain": "langchain", "transformers": "hugging-face",
    "psycopg2": "postgresql", "psycopg2-binary": "postgresql", "psycopg": "postgresql", "pg": "postgresql",
    "mysql2": "mysql", "mysqlclient": "mysql", "pymysql": "mysql", "mongoose": "mongodb", "pymongo": "mongodb",
    "mongodb": "mongodb", "redis": "redis", "ioredis": "redis", "kafka-python": "kafka", "kafkajs": "kafka",
    "elasticsearch": "elasticsearch", "@elastic/elasticsearch": "elasticsearch", "selenium": "selenium",
    "pyspark": "spark", "boto3": "aws", "aws-sdk": "aws", "@aws-sdk/client-s3": "aws",
    "springframework": "spring", "org.springframework": "spring", "org.springframework.boot": "spring-boot",
    "spring-boot-starter": "spring-boot", "spring-boot-starter-web": "spring-boot", "org.hibernate": "hibernate",
    "hibernate-core": "hibernate", "junit": "junit", "org.junit.jupiter": "junit", "org.apache.kafka": "kafka",
    "org.apache.spark": "spark", "rails": "ruby-on-rails", "laravel/framework": "laravel", "symfony/symfony": "symfony",
    "symfony/framework-bundle": "symfony", "flutter": "flutter", "microsoft.aspnetcore": "asp-net",
    "microsoft.aspnetcore.app": "asp-net", "microsoft.entityframeworkcore": "dotnet",
    "terraform": "terraform", "docker": "docker", "kubernetes": "kubernetes", "k8s": "kubernetes",
    "nodejs": "node-js", "node": "node-js", "machine-learning": "machine-learning", "deep-learning": "machine-learning",
}
# Prefixes of grouped dependency names (Maven groupIds, Go module paths, NuGet namespaces).
PREFIX_ALIASES: tuple[tuple[str, str], ...] = (
    ("org.springframework.boot", "spring-boot"), ("org.springframework", "spring"), ("org.hibernate", "hibernate"),
    ("org.junit", "junit"), ("org.apache.kafka", "kafka"), ("org.apache.spark", "spark"),
    ("microsoft.aspnetcore", "asp-net"), ("github.com/gin-gonic/gin", "go"), ("github.com/labstack/echo", "go"),
    ("com.amazonaws", "aws"), ("software.amazon.awssdk", "aws"), ("@angular/", "angular"), ("@nestjs/", "nestjs"),
)
_GENERIC = {"core", "common", "utils", "util", "test", "tests", "lib", "api", "client", "server", "app", "main",
            "types", "plugin", "cli", "config", "tools", "build", "dev", "web", "data", "http", "json", "log", "go",
            "c", "r", "d", "v"}
# Languages with no taxonomy entry, mapped to the closest skill.
LANGUAGE_ALIASES: dict[str, str] = {"shell": "bash", "jupyter notebook": "jupyter", "dockerfile": "docker",
                                    "vue": "vue-js", "scss": "sass", "hcl": "terraform", "tsx": "typescript"}


@dataclass(frozen=True)
class SkillRef:
    id: str
    name: str


def _ref(skill: Skill | None) -> SkillRef | None:
    return SkillRef(skill.id, skill.name) if skill else None


def _by_id(skill_id: str) -> SkillRef | None:
    return _ref(get_taxonomy().by_id(skill_id))


def skill_for_language(language: str) -> SkillRef | None:
    key = language.strip().lower()
    if key in LANGUAGE_ALIASES:
        return _by_id(LANGUAGE_ALIASES[key])
    return _ref(get_taxonomy().lookup(language))


def skill_for_dependency(name: str) -> SkillRef | None:
    key = name.strip().lower()
    if len(key) < 2 or key in _GENERIC:
        return None
    if key in DEPENDENCY_ALIASES:
        return _by_id(DEPENDENCY_ALIASES[key])
    for prefix, skill_id in PREFIX_ALIASES:
        if key == prefix or key.startswith(prefix + ".") or key.startswith(prefix + ":") or key.startswith(prefix + "/") \
                or (prefix.endswith("/") and key.startswith(prefix)):
            return _by_id(skill_id)
    taxonomy = get_taxonomy()
    candidates = [key]
    last = re.split(r"[/:]", key)[-1]
    if last != key:
        candidates.append(last)
    candidates += [candidate.replace("-", " ") for candidate in list(candidates) if "-" in candidate]
    for candidate in candidates:
        if len(candidate) < 2 or candidate in _GENERIC:
            continue
        if candidate in DEPENDENCY_ALIASES:
            return _by_id(DEPENDENCY_ALIASES[candidate])
        skill = taxonomy.lookup(candidate)
        if skill is not None:
            return _ref(skill)
    return None


def skill_for_topic(topic: str) -> SkillRef | None:
    return skill_for_dependency(topic) or _ref(get_taxonomy().lookup(topic.replace("-", " ")))


def resolve_claim_skill(value: str) -> SkillRef | None:
    """The taxonomy skill a claim value names: exact lookup first, then the single mention found in it."""
    taxonomy = get_taxonomy()
    exact = taxonomy.lookup(value)
    if exact is not None:
        return _ref(exact)
    mentions = taxonomy.find_mentions(value, list_context=True)
    ids = {mention.skill.id for mention in mentions}
    if len(ids) == 1:
        return _ref(mentions[0].skill)
    return None


def covers(claim_skill_id: str, shown_skill_id: str) -> bool:
    """A shown skill supports a claimed skill when it is the same skill or a specialisation of it (MySQL -> SQL)."""
    return claim_skill_id == shown_skill_id or get_taxonomy().is_ancestor(claim_skill_id, shown_skill_id)


def skills_in_text(text: str) -> set[str]:
    return get_taxonomy().extract(text)
