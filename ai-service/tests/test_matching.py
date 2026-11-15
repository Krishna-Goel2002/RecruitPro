from app.matching.engine import ALGORITHM_VERSION, match_batch, match_requirements
from app.models.matching import BatchCandidate, BatchMatchRequest
from app.models.resume import Timeline, TimelineRole
from tests.helpers import TODAY, fact, request, requirement


def run(requirements, facts, timeline=None):
    return match_requirements(request(requirements, facts, timeline), today=TODAY)


def one(kind, description, facts, *, years=None, timeline=None, alternatives=None):
    return run([requirement(1, kind, description, years=years, alternatives=alternatives)], facts, timeline).requirements[0]


class TestCoverageAndContract:
    def test_weighted_verified_coverage_is_deterministic_and_ignores_contacts(self):
        reqs = [requirement(1, "SKILL", "Java and Spring Boot"), requirement(2, "SKILL", "AWS", mandatory=False)]
        facts = [fact("fact:1", "SKILL", "Java"), fact("fact:2", "SKILL", "Spring Boot"), fact("fact:3", "EMAIL", "a@example.test")]
        first, second = run(reqs, facts), run(reqs, facts)
        assert first == second
        assert first.algorithmVersion == ALGORITHM_VERSION == "evidence-ladder-3"
        assert first.verifiedCoverage == first.score == first.claimedCoverage == 66.7
        assert [r.status for r in first.requirements] == ["MATCHED", "NOT_OBSERVED"]
        assert first.requirements[0].factIds == ["fact:1", "fact:2"]
        assert "Java" in first.requirements[0].evidence and "Spring Boot" in first.requirements[0].evidence
        assert "not mean the candidate lacks" in first.requirements[1].explanation
        assert "not a probability" in first.scoreLabel

    def test_other_requirements_need_human_review_and_leave_the_denominator(self):
        result = run([requirement(1, "SKILL", "Python"), requirement(2, "OTHER", "age 30")],
                     [fact("fact:1", "SKILL", "Java"), fact("fact:2", "EXPERIENCE", "age 30")])
        assert [r.status for r in result.requirements] == ["NOT_OBSERVED", "NOT_OBSERVED"]
        assert "human review" in result.requirements[1].explanation
        assert result.requirements[1].verificationHint
        assert result.verifiedCoverage == 0.0
        only_other = run([requirement(1, "OTHER", "Willing to travel")], [])
        assert only_other.verifiedCoverage is None and only_other.claimedCoverage is None

    def test_every_unverified_requirement_gets_a_hint_and_verified_ones_do_not(self):
        result = run([requirement(1, "SKILL", "Java"), requirement(2, "SKILL", "Kafka"), requirement(3, "EXPERIENCE", "Java", years=3),
                      requirement(4, "EDUCATION", "Master's degree"), requirement(5, "CERTIFICATION", "PMP")],
                     [fact("fact:1", "SKILL", "Java")])
        assert result.requirements[0].verificationHint is None
        assert all(item.verificationHint for item in result.requirements[1:])

    def test_batch_matches_each_candidate_independently(self):
        reqs = [requirement(1, "SKILL", "Java")]
        response = match_batch(BatchMatchRequest(requirements=reqs, candidates=[
            BatchCandidate(applicationId=7, facts=[fact("fact:1", "SKILL", "Java")]),
            BatchCandidate(applicationId=8, facts=[fact("fact:2", "SKILL", "Go")]),
        ]), today=TODAY)
        assert [(c.applicationId, c.verifiedCoverage) for c in response.candidates] == [(7, 100.0), (8, 0.0)]
        assert response.candidates[0].algorithmVersion == "evidence-ladder-3"


class TestEvidenceLadder:
    def test_candidate_edit_is_a_claim_counted_only_in_claimed_coverage(self):
        reqs = [requirement(1, "SKILL", "Python", knockout=True)]
        resume_only = run(reqs, [fact("fact:1", "SKILL", "Java")])
        with_claim = run(reqs, [fact("fact:1", "SKILL", "Java"), fact("claim:4", "SKILL", "Python", tier="SELF_CLAIMED")])
        assert resume_only.verifiedCoverage == with_claim.verifiedCoverage == 0.0
        assert with_claim.claimedCoverage == 100.0
        item = with_claim.requirements[0]
        assert (item.status, item.tier, item.provenance) == ("MATCHED", "SELF_CLAIMED", "CANDIDATE_CLAIM")
        assert item.evidence.startswith("Candidate claim: ")
        assert "Only the candidate's own claim" in item.verificationHint
        assert with_claim.knockoutsVerified is False and with_claim.unverifiedKnockouts == [1]

    def test_best_tier_is_reported_and_weakest_link_decides_conjunctions(self):
        facts = [fact("fact:1", "SKILL", "Java"), fact("fact:2", "SKILL", "Java", tier="CORROBORATED"),
                 fact("claim:1", "SKILL", "Kafka", tier="SELF_CLAIMED")]
        result = run([requirement(1, "SKILL", "Java"), requirement(2, "SKILL", "Java and Kafka")], facts)
        assert (result.requirements[0].tier, result.requirements[0].provenance) == ("CORROBORATED", "CORROBORATED")
        assert result.requirements[0].factIds == ["fact:2"]
        assert (result.requirements[1].status, result.requirements[1].tier) == ("MATCHED", "SELF_CLAIMED")

    def test_work_sample_and_interview_evidence_is_linked_to_its_requirement(self):
        facts = [fact("ws:4:r1", "SKILL", "Rubric: SQL query design", tier="DEMONSTRATED"),
                 fact("iv:7:r2", "OTHER", "Stakeholder question rated 4", tier="INTERVIEW_VERIFIED")]
        result = run([requirement(1, "SKILL", "SQL"), requirement(2, "SKILL", "Stakeholder Management"),
                      requirement(3, "SKILL", "SQL")], facts)
        assert (result.requirements[0].tier, result.requirements[0].provenance) == ("DEMONSTRATED", "WORK_SAMPLE")
        assert (result.requirements[1].tier, result.requirements[1].provenance) == ("INTERVIEW_VERIFIED", "INTERVIEW")
        assert result.requirements[2].status == "NOT_OBSERVED"  # linked evidence counts only for its own requirement

    def test_recruiter_confirmed_verification_facts_are_corroborated_general_facts(self):
        # WP-VBE: the backend sends a confirmed verdict as "vf:<verdictId>" at tier CORROBORATED (plan v2 3.5).
        facts = [fact("fact:1", "SKILL", "React"), fact("vf:42", "SKILL", "React", tier="CORROBORATED",
                                                          evidence="Verified by code; confirmed by a recruiter",
                                                          section="verification")]
        result = run([requirement(1, "SKILL", "React"), requirement(2, "SKILL", "Go")], facts)
        assert (result.requirements[0].tier, result.requirements[0].provenance) == ("CORROBORATED", "CORROBORATED")
        assert result.requirements[0].factIds == ["vf:42"]
        assert result.requirements[1].status == "NOT_OBSERVED"  # not linked to a requirement: a general fact

    def test_knockouts_are_gates_not_rejections(self):
        reqs = [requirement(1, "SKILL", "Java", knockout=True), requirement(2, "OTHER", "Night shifts", knockout=True)]
        result = run(reqs, [fact("fact:1", "SKILL", "Java")])
        assert result.knockoutsVerified is False and result.unverifiedKnockouts == [2]
        assert result.verifiedCoverage == 100.0


class TestSkills:
    def test_alternatives_explicit_and_detected(self):
        aws = [fact("fact:1", "SKILL", "AWS")]
        assert one("SKILL", "AWS or Azure", aws).status == "MATCHED"
        assert one("SKILL", "AWS/Azure", aws).status == "MATCHED"
        explicit = one("SKILL", "GCP", aws, alternatives=["Azure", "AWS"])
        assert explicit.status == "MATCHED" and "Accepted alternative 'AWS'" in explicit.explanation

    def test_hierarchy_and_partial_evidence(self):
        assert one("SKILL", "SQL", [fact("fact:1", "SKILL", "PostgreSQL")]).status == "MATCHED"
        assert one("SKILL", "CRM", [fact("fact:1", "SKILL", "Salesforce")]).status == "MATCHED"
        partial = one("SKILL", "GraphQL and Kafka", [fact("fact:1", "EXPERIENCE", "Developed GraphQL APIs", section="experience")])
        assert partial.status == "UNCLEAR" and "Kafka" in partial.verificationHint

    def test_skill_found_in_experience_text_and_skill_fact_preferred(self):
        experience = fact("fact:10", "EXPERIENCE", "Developed GraphQL APIs for four years", section="experience")
        assert one("SKILL", "GraphQL", [experience]).status == "MATCHED"
        both = one("SKILL", "GraphQL", [experience, fact("fact:11", "SKILL", "GraphQL", evidence="Proficient in GraphQL")])
        assert both.sourceSection == "skills" and both.factIds == ["fact:11"]
        assert one("SKILL", "Kubernetes", [fact("fact:1", "EXPERIENCE", "3 years of Python backend development")]).status == "NOT_OBSERVED"
        assert one("SKILL", "Java", [fact("fact:1", "EXPERIENCE", "Developed JavaScript SPA applications")]).status == "NOT_OBSERVED"

    def test_lexical_fallback_stays_within_type_and_cites_the_right_fact(self):
        facts = [fact("fact:1", "SKILL", "Java", evidence="Java, Zeplinx, Photoshop"), fact("fact:2", "SKILL", "Zeplinx"),
                 fact("fact:3", "EXPERIENCE", "Worked with Quarkly every day", section="experience")]
        matched = one("SKILL", "Zeplinx", facts)
        assert matched.status == "MATCHED" and matched.factIds == ["fact:2"]
        evidence_only = one("SKILL", "Zeplinx", facts[:1])
        assert evidence_only.status == "UNCLEAR" and evidence_only.factIds == ["fact:1"]
        assert one("SKILL", "Quarkly", facts).status == "NOT_OBSERVED"  # an EXPERIENCE fact never feeds SKILL lexical matching


class TestExperience:
    def facts(self, text, tier="RESUME_STATED", ident="fact:1"):
        return [fact(ident, "EXPERIENCE", text, tier=tier, section="experience")]

    def test_relevance_and_duration_from_statements(self):
        react = one("EXPERIENCE", "Java experience", self.facts("3 years of frontend React experience"), years=3)
        assert react.status == "UNCLEAR" and "relevance to 'Java experience' was not observed" in react.explanation
        assert one("EXPERIENCE", "Java experience", self.facts("3 years of Java experience"), years=3).status == "MATCHED"
        general = one("EXPERIENCE", "enterprise Java services", self.facts("3 years of Java experience"), years=3)
        assert general.status == "UNCLEAR" and "partially established" in general.explanation
        full = one("EXPERIENCE", "enterprise Java services", self.facts("4 years building enterprise Java services"), years=3)
        assert full.status == "MATCHED"
        assert "explicitly states a duration (4 years) meeting the minimum requirement (3 years)" in full.explanation
        undated = one("EXPERIENCE", "enterprise Java services", self.facts("Software engineer building enterprise Java services"), years=3)
        assert undated.status == "UNCLEAR" and "could not be verified from an explicit year count" in undated.explanation
        short = one("EXPERIENCE", "enterprise Java services", self.facts("1 year building enterprise Java services"), years=3)
        assert short.status == "NOT_OBSERVED" and "shorter duration (1 year)" in short.explanation

    def test_subsumption_keeps_duration_requirements(self):
        assert one("EXPERIENCE", "3 years SQL experience", self.facts("3 years of MySQL"), years=3).status == "MATCHED"
        assert one("EXPERIENCE", "5 years SQL experience", self.facts("2 years of MySQL"), years=5).status == "NOT_OBSERVED"
        assert one("EXPERIENCE", "3 years SQL experience", self.facts("MySQL experience"), years=3).status == "UNCLEAR"

    def test_claimed_duration_is_self_claimed(self):
        result = one("EXPERIENCE", "Backend development with Java", self.facts("10 years backend development with Java",
                                                                              tier="SELF_CLAIMED", ident="claim:2"), years=4)
        assert (result.status, result.tier, result.provenance) == ("MATCHED", "SELF_CLAIMED", "CANDIDATE_CLAIM")

    def test_timeline_months_are_merged_across_roles(self):
        roles = [
            fact("fact:1", "EXPERIENCE", "Engineer, Acme", evidence="Engineer, Acme · Built Python services", section="experience",
                 attributes={"title": "Engineer", "startDate": "2019-01", "endDate": "2020-12", "months": 24}),
            fact("fact:2", "EXPERIENCE", "Engineer, Beta", evidence="Engineer, Beta · Python and Django", section="experience",
                 attributes={"title": "Engineer", "startDate": "2020-07", "endDate": "2022-06", "months": 24}),
        ]
        merged = one("EXPERIENCE", "Python", roles, years=3)  # 2019-01..2022-06 = 42 months, not 48
        assert merged.status == "MATCHED" and "3.5 years" in merged.explanation
        assert one("EXPERIENCE", "Python", roles, years=4).status == "NOT_OBSERVED"

    def test_timeline_only_evidence_and_practitioner_titles(self):
        timeline = Timeline(totalMonths=40, skillMonths={}, roles=[
            TimelineRole(title="Mathematics Teacher", employer="Sunrise School", startDate="2023-01", months=40, current=True)])
        result = one("EXPERIENCE", "Teaching", [], years=3, timeline=timeline)
        assert (result.status, result.provenance, result.tier) == ("MATCHED", "RESUME", "RESUME_STATED")
        assert result.evidence.startswith("Resume timeline:")

    def test_listed_skill_not_tied_to_roles_is_unclear(self):
        roles = [fact("fact:1", "EXPERIENCE", "Backend Developer, Beta Ltd", section="experience",
                      attributes={"title": "Backend Developer", "startDate": "2021-04", "months": 68, "current": True})]
        result = one("EXPERIENCE", "Backend development with Java", roles + [fact("fact:2", "SKILL", "Java")], years=4)
        assert result.status == "UNCLEAR" and "does not tie Java" in result.explanation


class TestEducationAndCertifications:
    def test_education_levels_and_fields(self):
        btech = [fact("fact:1", "EDUCATION", "B.Tech in Computer Science and Engineering", section="education",
                      attributes={"degreeLevel": "BACHELOR", "field": "computer science"})]
        assert one("EDUCATION", "Bachelor's degree in Computer Science", btech).status == "MATCHED"
        assert one("EDUCATION", "Bachelor's degree in IT or related field", btech).status == "MATCHED"
        assert one("EDUCATION", "Master's degree in Computer Science", btech).status == "UNCLEAR"
        assert one("EDUCATION", "Bachelor's degree in Commerce", btech).status == "UNCLEAR"
        assert one("EDUCATION", "B.Sc. Nursing or GNM", [fact("fact:1", "EDUCATION", "GNM, City School of Nursing")]).status == "MATCHED"
        assert one("EDUCATION", "Bachelor's degree", []).status == "NOT_OBSERVED"

    def test_certification_overlap(self):
        aws = [fact("fact:1", "CERTIFICATION", "AWS", section="certifications")]
        assert one("CERTIFICATION", "AWS Certified Developer", aws).status == "UNCLEAR"
        full = [fact("fact:1", "CERTIFICATION", "AWS Certified Developer - Associate", section="certifications")]
        assert one("CERTIFICATION", "AWS Developer certification", full).status == "MATCHED"
        pmp = [fact("fact:1", "CERTIFICATION", "Project Management Professional (PMP), PMI", section="certifications")]
        assert one("CERTIFICATION", "PMP", pmp).status == "MATCHED"
        assert one("CERTIFICATION", "PMP or PRINCE2", [fact("fact:1", "CERTIFICATION", "PRINCE2 Practitioner")]).status == "MATCHED"
        assert one("CERTIFICATION", "CISSP", pmp).status == "NOT_OBSERVED"
