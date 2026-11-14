"""Regression tests for every probe in audit/probe_matching.py (findings H1-H5, M14, C1, L12)."""
import pytest

from app.matching.engine import match_requirements
from app.models.matching import MatchFact, MatchRequest, MatchRequirement
from app.pipeline.extract import analyse_text, timeline_model
from app.taxonomy.taxonomy import get_taxonomy
from tests.helpers import TODAY

BASE = ["Skills", "Java, Spring Boot, MySQL", "Experience", "Software Engineer, Acme Corp", "Jan 2019 - Mar 2021",
        "Backend Developer, Beta Ltd", "Apr 2021 - Present", "Education", "B.Tech in Computer Science and Engineering, KIIT, 2018"]


def parse(lines):
    analysis = analyse_text(lines, today=TODAY)
    return analysis.facts, timeline_model(analysis.timeline)


def to_match(facts, claims=()):
    items = [MatchFact(id=f"fact:{i}", type=f.type, value=f.value, evidence=f.evidence, sourceSection=f.sourceSection,
                       tier="RESUME_STATED", attributes=f.attributes)
             for i, f in enumerate(facts) if f.type not in ("EMAIL", "PHONE", "LINK")]
    return items + [MatchFact(id=f"claim:{i}", type=kind, value=value, evidence=value, sourceSection="candidate claim",
                              tier="SELF_CLAIMED") for i, (kind, value) in enumerate(claims)]


def match(reqs, facts, timeline=None):
    request = MatchRequest(requirements=[MatchRequirement(id=i, type=t, description=d, mandatory=True, knockout=True, minimumYears=y)
                                         for i, (t, d, y) in enumerate(reqs)], facts=facts, timeline=timeline)
    return match_requirements(request, today=TODAY)


@pytest.mark.parametrize("sentence", [
    "Please find my CV attached.",
    "Led the team to go live in Spring 2023 semester.",
    "Worked at Oracle Financial Services on SWIFT payments.",
    "Trained staff to react quickly to incidents.",
    "Grade C in mathematics; ML (millilitre) dosing calculations in pharmacy lab",
    "Managed the DL (distribution list) for HR",
])
def test_p1_no_skills_invented_from_common_words(sentence):
    assert get_taxonomy().extract(sentence) == set()
    assert not [f for f in analyse_text(["Summary", sentence], today=TODAY).facts if f.type == "SKILL"]


def test_p2_unknown_headings_do_not_inherit_sections():
    facts, _ = parse(["Jane Doe", "Certifications", "AWS Certified Cloud Practitioner", "Hobbies", "Playing guitar and chess",
                      "Projects", "Built a todo app"])
    assert [(f.type, f.value) for f in facts if f.type != "SKILL"] == [("CERTIFICATION", "AWS Certified Cloud Practitioner")]


def test_p3_month_ranges_summed_and_present_is_today():
    _, timeline = parse(BASE)
    assert [(r.startDate, r.endDate, r.current, r.months) for r in timeline.roles] == [
        ("2019-01", "2021-03", False, 27), ("2021-04", None, True, 68)]
    assert timeline.totalMonths == 95


def test_p4_self_edits_become_claims_that_never_raise_verified_coverage():
    reqs = [("SKILL", "Java", None), ("SKILL", "AWS or Azure", None), ("EXPERIENCE", "Backend development with Java", 4),
            ("EDUCATION", "Bachelor's degree in Computer Science", None)]
    facts, timeline = parse(BASE)
    honest = match(reqs, to_match(facts), timeline)
    edited = match(reqs, to_match(facts, [("SKILL", "Java AWS Azure"), ("EXPERIENCE", "10 years backend development with Java"),
                                          ("EDUCATION", "Bachelor's degree in Computer Science")]), timeline)
    assert [r.status for r in honest.requirements] == ["MATCHED", "NOT_OBSERVED", "UNCLEAR", "MATCHED"]
    assert honest.verifiedCoverage == edited.verifiedCoverage == 50.0
    assert edited.claimedCoverage == 100.0 and honest.claimedCoverage == 50.0
    assert [r.tier for r in edited.requirements] == ["RESUME_STATED", "SELF_CLAIMED", "SELF_CLAIMED", "RESUME_STATED"]
    assert edited.unverifiedKnockouts == [1, 2]


def test_p5_non_tech_skills_and_stated_durations():
    facts, timeline = parse(["Skills", "Salesforce CRM, Microsoft Excel, negotiation, cold calling", "Experience",
                             "Account Executive, 6 years, B2B SaaS sales"])
    result = match([("SKILL", "Salesforce", None), ("SKILL", "Excel", None), ("EXPERIENCE", "B2B sales", 5)], to_match(facts), timeline)
    assert [r.status for r in result.requirements] == ["MATCHED"] * 3 and result.verifiedCoverage == 100.0


@pytest.mark.parametrize("line", ["Java, Figma, Photoshop", "Figma, Photoshop, Illustrator"])
def test_p6_cites_the_fact_that_contains_the_skill(line):
    facts, timeline = parse(["Skills", line])
    result = match([("SKILL", "Figma", None)], to_match(facts), timeline).requirements[0]
    figma_index = next(i for i, f in enumerate(facts) if f.value == "Figma")
    assert result.status == "MATCHED" and result.factIds == [f"fact:{figma_index}"]


def test_p6c_lexical_fallback_cites_the_right_fact():
    facts, timeline = parse(["Skills", "Java, Zeplinx, Photoshop"])
    result = match([("SKILL", "Zeplinx", None)], to_match(facts), timeline).requirements[0]
    assert result.status == "MATCHED" and facts[int(result.factIds[0].split(":")[1])].value == "Zeplinx"


def test_p7_or_is_an_alternative():
    facts, timeline = parse(["Skills", "AWS, Docker"])
    assert match([("SKILL", "AWS or Azure", None)], to_match(facts), timeline).requirements[0].status == "MATCHED"


def test_p8_years_are_summed_across_roles():
    facts, timeline = parse(["Experience", "Python developer, A, 2 years", "Python developer, B, 2 years", "Python developer, C, 2 years"])
    result = match([("EXPERIENCE", "Python development", 5)], to_match(facts), timeline).requirements[0]
    assert result.status == "MATCHED" and len(result.factIds) == 3
