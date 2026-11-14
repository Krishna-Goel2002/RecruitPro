import json
from collections import Counter

import pytest

from app.matching.engine import match_requirements, skill_cnf
from app.taxonomy import import_esco
from app.taxonomy.taxonomy import Taxonomy, load_skills, get_taxonomy
from tests.helpers import fact, request, requirement

TAXONOMY = get_taxonomy()


def names(text: str, **kwargs) -> set[str]:
    return TAXONOMY.extract(text, **kwargs)


def status(required: str, held: str) -> str:
    result = match_requirements(request([requirement(1, "SKILL", required)], [fact("fact:1", "SKILL", held)]))
    return result.requirements[0].status


class TestTaxonomyContent:
    def test_is_large_multi_domain_and_valid(self):
        assert TAXONOMY.validate() == []
        assert len(TAXONOMY.skills) >= 600
        domains = Counter(skill.domain for skill in TAXONOMY.skills)
        for domain in ("SOFTWARE", "DATA", "CLOUD", "DESIGN", "PRODUCT", "SALES", "MARKETING", "FINANCE", "HR",
                       "OPERATIONS", "HEALTHCARE", "EDUCATION", "LEGAL", "LANGUAGE"):
            assert domains[domain] >= 10, domain

    def test_required_non_tech_skills_are_present(self):
        for name in ("Tally", "GST", "Ind AS", "TDS", "Bank Reconciliation", "Salesforce", "B2B Sales", "Recruitment",
                     "Payroll", "Supply Chain Management", "Patient Care", "Lesson Planning", "Contract Drafting", "Hindi", "Odia"):
            assert TAXONOMY.lookup(name) is not None, name

    def test_every_ambiguous_skill_has_context_words(self):
        ambiguous = [skill for skill in TAXONOMY.skills if skill.ambiguous]
        assert {"go", "c", "r", "spring", "react", "swift", "oracle", "excel", "tally"} <= {skill.id for skill in ambiguous}
        assert all(skill.context_words for skill in ambiguous)

    def test_lookup_is_case_insensitive_and_covers_aliases(self):
        assert TAXONOMY.lookup("JAVA").name == "Java"
        assert TAXONOMY.lookup("postgres").name == "PostgreSQL"
        assert TAXONOMY.lookup("k8s").name == "Kubernetes"
        assert TAXONOMY.lookup("Tally ERP 9").name == "Tally"
        assert TAXONOMY.lookup("Goods and Services Tax").name == "GST"
        assert TAXONOMY.lookup("Indian Accounting Standards").name == "Ind AS"
        assert TAXONOMY.lookup("unknown_nonexistent_skill") is None

    @pytest.mark.parametrize("alias", ["node", "ml", "dl", "cv", "rest"])
    def test_overly_ambiguous_aliases_are_not_listed(self, alias):
        assert TAXONOMY.lookup(alias) is None

    def test_validation_reports_collisions_and_bad_references(self):
        java = TAXONOMY.lookup("Java")
        clash = type(java)(id="other", name="Other", aliases=("Core Java",), domain="SOFTWARE", parents=("missing",),
                           distinctions=(), ambiguous=True, context_words=())
        errors = Taxonomy([java, clash]).validate()
        assert any("collision" in error for error in errors)
        assert any("missing" in error for error in errors)
        assert any("context words" in error for error in errors)


class TestHierarchyAndDistinctions:
    @pytest.mark.parametrize("child,parent", [("MySQL", "SQL"), ("PostgreSQL", "SQL"), ("SQLite", "SQL"),
                                              ("Spring Boot", "Spring"), ("Deep Learning", "Machine Learning"),
                                              ("GST", "Taxation"), ("Salesforce", "CRM"), ("Moodle", "Learning Management Systems")])
    def test_directional_parents(self, child, parent):
        child_id, parent_id = TAXONOMY.lookup(child).id, TAXONOMY.lookup(parent).id
        assert TAXONOMY.is_ancestor(parent_id, child_id)
        assert not TAXONOMY.is_ancestor(child_id, parent_id)
        assert child_id in TAXONOMY.children(parent_id) or TAXONOMY.is_ancestor(parent_id, child_id)

    @pytest.mark.parametrize("first,second", [("Java", "JavaScript"), ("React", "React Native"), ("AWS", "Azure"),
                                              ("Docker", "Kubernetes"), ("Python", "PyTorch"), ("GCP", "Good Clinical Practice")])
    def test_distinctions_are_symmetric(self, first, second):
        a, b = TAXONOMY.lookup(first).id, TAXONOMY.lookup(second).id
        assert TAXONOMY.are_distinct(a, b) and TAXONOMY.are_distinct(b, a)
        assert not TAXONOMY.is_ancestor(a, b) and not TAXONOMY.is_ancestor(b, a)


class TestPhraseRecognition:
    def test_java_vs_javascript(self):
        assert names("Java") == {"Java"}
        assert names("Java and JavaScript") == {"Java", "JavaScript"}
        assert names("Java script developer") == {"JavaScript"}
        assert names("Java-script developer") == {"JavaScript"}
        assert status("Java", "JavaScript") == "NOT_OBSERVED"
        assert status("JavaScript", "Java") == "NOT_OBSERVED"
        assert status("Java", "Java") == "MATCHED"

    def test_react_vs_react_native(self):
        assert names("React and React Native", list_context=True) == {"React", "React Native"}
        assert names("Building React Native mobile apps") == {"React Native"}
        assert status("React", "React Native") == "NOT_OBSERVED"
        assert status("React Native", "React") == "NOT_OBSERVED"

    def test_spring_and_spring_boot(self):
        assert names("Spring Boot microservices") == {"Spring Boot", "Microservices"}
        assert names("Spring and Spring Boot", list_context=True) == {"Spring", "Spring Boot"}
        assert status("Spring", "Spring Boot") == "MATCHED"  # a specialisation satisfies its parent
        assert status("Spring Boot", "Spring") == "UNCLEAR"  # the broader skill is partial evidence only

    def test_c_family(self):
        assert names("C, C++, and C#") == {"C", "C++", "C#"}
        assert names("Objective-C developer") == {"Objective-C"}
        assert names("C++ developer") == {"C++"}
        assert names("C", list_context=True) == {"C"}
        assert names("C") == set()  # a bare letter in prose is not a skill
        assert status("C", "C++") == "NOT_OBSERVED"
        assert status("C#", "C") == "NOT_OBSERVED"

    def test_sql_and_mysql(self):
        assert names("MySQL database server") == {"MySQL"}
        assert status("SQL", "MySQL") == "MATCHED"
        assert status("MySQL", "SQL") == "UNCLEAR"

    def test_case_punctuation_and_multi_word(self):
        assert names("rEaCt nAtIvE") == {"React Native"}
        assert names("(Java)") == {"Java"}
        assert names("Python/Django") == {"Python", "Django"}
        assert names("CI/CD pipeline") == {"CI/CD"}
        assert names("Node.js runtime") == {"Node.js"}
        assert names("I know Java.") == {"Java"}
        assert names("ASP.NET Core") == {"ASP.NET"}
        assert names("Something. Net revenue grew") == set()
        assert names("Amazon Web Services and Natural Language Processing") == {"AWS", "NLP"}
        assert names("Deep Learning networks") == {"Deep Learning"}

    def test_non_tech_domains(self):
        assert names("Maintained books in Tally ERP 9; filed GST returns and TDS; Ind AS transition") == {"Tally", "GST", "TDS", "Ind AS"}
        assert names("Salesforce CRM, Microsoft Excel, negotiation, cold calling", list_context=True) == {
            "Salesforce", "Excel", "Negotiation", "Cold Calling"}
        assert names("Fluent in English and Hindi") == {"English", "Hindi"}
        assert names("BA English") == set()

    def test_cnf_detects_alternatives(self):
        def groups(text):
            return [[skill.name for skill in group] for group in skill_cnf(text)]

        assert groups("AWS or Azure") == [["AWS", "Azure"]]
        assert groups("AWS/Azure") == [["AWS", "Azure"]]
        assert groups("Java, Spring Boot and SQL") == [["Java"], ["Spring Boot"], ["SQL"]]
        assert groups("a cloud platform such as AWS, Azure or GCP") == [["AWS", "Azure", "GCP"]]
        assert groups("React or Angular and Node.js") == [["React", "Angular"], ["Node.js"]]


def test_esco_import_skips_curated_collisions(tmp_path):
    skills_csv = tmp_path / "skills_en.csv"
    skills_csv.write_text(
        "conceptUri,preferredLabel,altLabels\n"
        "http://esco/1,Java,\n"
        "http://esco/2,beekeeping,\"apiculture\nbee keeping\"\n"
        "http://esco/3,manage a large team of software developers in agile settings,\n"
        "http://esco/4,hive inspection,\n", encoding="utf-8")
    relations = tmp_path / "relations.csv"
    relations.write_text("conceptUri,broaderUri\nhttp://esco/4,http://esco/2\n", encoding="utf-8")
    out = tmp_path / "esco.json"
    assert import_esco.main(["--skills", str(skills_csv), "--relations", str(relations), "--out", str(out)]) == 0
    imported = {skill["name"]: skill for skill in json.loads(out.read_text(encoding="utf-8"))["skills"]}
    assert set(imported) == {"beekeeping", "hive inspection"}
    assert imported["beekeeping"]["aliases"] == ["apiculture", "bee keeping"]
    assert imported["hive inspection"]["parents"] == [imported["beekeeping"]["id"]]
    extended = Taxonomy(load_skills(import_esco.SKILLS_PATH) + load_skills(out))
    assert extended.validate() == []
    assert extended.extract("Experienced in apiculture") == {"beekeeping"}
