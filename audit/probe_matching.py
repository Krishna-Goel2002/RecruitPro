"""
RecruitPro matching-engine probes (audit, 2026-11-08; adapted to the v3 contract by WP-PY).

Reproduces the matching findings in the co-founder audit. Each probe prints what the pipeline now
does so the behaviour can be compared with the audit. The same scenarios are asserted as
regression tests in ai-service/tests/test_audit_probes.py.

Contract changes since the audit: facts carry origin-tagged string ids and an evidence tier; a
candidate's edit is no longer folded into the resume fact but sent as a separate SELF_CLAIMED
"claim:*" fact; the response reports verifiedCoverage (score) and claimedCoverage separately.

Run from the repository root, with the ai-service requirements installed:
    python audit/probe_matching.py
"""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "ai-service"))

from app.matching.engine import match_requirements  # noqa: E402
from app.models.matching import MatchFact, MatchRequest, MatchRequirement  # noqa: E402
from app.pipeline.extract import analyse_text, timeline_model  # noqa: E402
from app.taxonomy.taxonomy import get_taxonomy  # noqa: E402


def parse(lines):
    """Run the real pipeline over plain resume lines; returns (facts, timeline model)."""
    analysis = analyse_text(lines)
    return analysis.facts, timeline_model(analysis.timeline)


def to_match(facts, claims=()):
    """Mirror the backend: resume facts as "fact:N" (RESUME_STATED), candidate edits as "claim:N" (SELF_CLAIMED)."""
    out = [MatchFact(id=f"fact:{i}", type=f.type, value=f.value, evidence=f.evidence, sourceSection=f.sourceSection,
                     tier="RESUME_STATED", attributes=f.attributes)
           for i, f in enumerate(facts) if f.type not in ("EMAIL", "PHONE", "LINK")]
    out += [MatchFact(id=f"claim:{i}", type=kind, value=value, evidence=value, sourceSection="candidate claim",
                      tier="SELF_CLAIMED") for i, (kind, value) in enumerate(claims)]
    return out


def run(title, reqs, mfacts, timeline=None):
    request = MatchRequest(
        requirements=[MatchRequirement(id=i, type=t, description=d, mandatory=m, knockout=m, minimumYears=y)
                      for i, (t, d, m, y) in enumerate(reqs)],
        facts=mfacts, timeline=timeline,
    )
    result = match_requirements(request)
    print(f"\n### {title}\n  verified = {result.verifiedCoverage}  claimed = {result.claimedCoverage}  "
          f"knockoutsVerified = {result.knockoutsVerified}")
    for r in result.requirements:
        print(f"  req{r.requirementId}: {r.status:12} tier={r.tier} provenance={r.provenance} facts={r.factIds} | {r.explanation[:110]}")
    return result


if __name__ == "__main__":
    taxonomy = get_taxonomy()
    print("=== P1: skills invented from common words and ambiguous aliases (expect none)")
    for sentence in [
        "Please find my CV attached.",
        "Led the team to go live in Spring 2023 semester.",
        "Worked at Oracle Financial Services on SWIFT payments.",
        "Trained staff to react quickly to incidents.",
        "Grade C in mathematics; ML (millilitre) dosing calculations in pharmacy lab",
        "Managed the DL (distribution list) for HR",
    ]:
        print(f"  {sentence!r:80} -> {sorted(taxonomy.extract(sentence))}")

    print("\n=== P2: unrecognised headings start their own section (expect no certification from hobbies/projects)")
    for f in parse(["Jane Doe", "Certifications", "AWS Certified Cloud Practitioner",
                    "Hobbies", "Playing guitar and chess", "Projects", "Built a todo app"])[0]:
        print(f"  {f.type:14} {f.sourceSection:15} {f.value}")

    base = ["Skills", "Java, Spring Boot, MySQL",
            "Experience", "Software Engineer, Acme Corp", "Jan 2019 - Mar 2021",
            "Backend Developer, Beta Ltd", "Apr 2021 - Present",
            "Education", "B.Tech in Computer Science and Engineering, KIIT, 2018"]
    facts, timeline = parse(base)
    print("\n=== P3: month-name ranges parsed, years summed across roles, 'Present' = today")
    for role in timeline.roles:
        print(f"  {role.title} @ {role.employer}: {role.startDate} -> {role.endDate or 'present'} ({role.months} months)")
    print(f"  totalMonths = {timeline.totalMonths}")
    reqs = [("SKILL", "Java", True, None), ("SKILL", "AWS or Azure", True, None),
            ("EXPERIENCE", "Backend development with Java", True, 4),
            ("EDUCATION", "Bachelor's degree in Computer Science", True, None)]
    honest = run("P4a: honest two-job candidate", reqs, to_match(facts), timeline)
    edited = run("P4b: same candidate after 3 self-edits (now SELF_CLAIMED claims: claimed coverage rises, verified does not)",
                 reqs, to_match(facts, [("SKILL", "Java AWS Azure"),
                                        ("EXPERIENCE", "10 years backend development with Java"),
                                        ("EDUCATION", "Bachelor's degree in Computer Science")]), timeline)
    assert honest.verifiedCoverage == edited.verifiedCoverage < edited.claimedCoverage

    facts, timeline = parse(["Skills", "Salesforce CRM, Microsoft Excel, negotiation, cold calling",
                             "Experience", "Account Executive, 6 years, B2B SaaS sales"])
    run("P5: account-executive resume vs a sales job (non-tech skills)",
        [("SKILL", "Salesforce", True, None), ("SKILL", "Excel", True, None), ("EXPERIENCE", "B2B sales", True, 5)],
        to_match(facts), timeline)
    facts, timeline = parse(["Skills", "Java, Figma, Photoshop"])
    run("P6: 'Figma' when the line also holds another skill (cites the Figma fact)", [("SKILL", "Figma", True, None)],
        to_match(facts), timeline)
    facts, timeline = parse(["Skills", "Figma, Photoshop, Illustrator"])
    run("P6b: 'Figma' when the line holds no other dictionary skill", [("SKILL", "Figma", True, None)], to_match(facts), timeline)
    facts, timeline = parse(["Skills", "Java, Zeplinx, Photoshop"])
    run("P6c: lexical fallback cites the fact that contains the term", [("SKILL", "Zeplinx", True, None)], to_match(facts), timeline)
    facts, timeline = parse(["Skills", "AWS, Docker"])
    run("P7: 'AWS or Azure' for a candidate who has AWS", [("SKILL", "AWS or Azure", True, None)], to_match(facts), timeline)
    facts, timeline = parse(["Experience", "Python developer, A, 2 years",
                             "Python developer, B, 2 years", "Python developer, C, 2 years"])
    run("P8: three 2-year Python roles vs a 5-year requirement", [("EXPERIENCE", "Python development", True, 5)],
        to_match(facts), timeline)
