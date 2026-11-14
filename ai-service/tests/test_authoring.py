from app.compile.requirements import compile_rules
from app.generate.interview_kits import generate_rules as interview_rules
from app.generate.work_samples import generate_rules as work_sample_rules
from app.models.authoring import CompileRequest, InterviewKitRequest, WorkSampleRequest
from tests.helpers import requirement

JOB = """We are hiring a rockstar backend engineer for our fast-paced team. He will own payment services.
About us:
- Founded in 2015, we love Python
Responsibilities:
- Build REST APIs in Java
Requirements:
- 3+ years of experience with Java and Spring Boot
- Experience with AWS or Azure
- Must have strong PostgreSQL skills
- Bachelor's degree in Computer Science or related field
- Excellent communication skills
- Native English speaker
Nice to have:
- Kubernetes
- AWS Certified Developer certification
- Digital natives and recent graduates welcome
- 12+ years in distributed systems
"""


def compiled(description=JOB, level="MID"):
    return compile_rules(CompileRequest(title="Backend Engineer", description=description, experienceLevel=level))


class TestCompiler:
    def test_requirements_types_priorities_and_alternatives(self):
        result = compiled()
        assert result.engine == "rules"
        rows = {(r.type, r.description): r for r in result.requirements}
        experience = rows[("EXPERIENCE", "Java and Spring Boot")]
        assert experience.mandatory and experience.minimumYears == 3
        cloud = rows[("SKILL", "AWS")]
        assert cloud.alternatives == ["Azure"] and cloud.mandatory and not cloud.knockout
        postgres = rows[("SKILL", "PostgreSQL")]
        assert postgres.knockout and postgres.proficiency == "ADVANCED"
        assert rows[("EDUCATION", "Bachelor's degree in Computer Science or related field")].mandatory
        assert not rows[("SKILL", "Kubernetes")].mandatory
        assert rows[("CERTIFICATION", "AWS Certified Developer certification")].mandatory is False
        assert rows[("EXPERIENCE", "distributed systems")].minimumYears == 12
        assert ("OTHER", "Excellent communication skills") in rows
        assert all(r.rationale for r in result.requirements)
        assert not any("Python" in r.description or "REST" in r.description for r in result.requirements)  # about/duties skipped

    def test_wording_warnings_cover_every_kind(self):
        warnings = {(w.kind, w.phrase.lower()) for w in compiled().warnings}
        assert {("GENDER_CODED", "rockstar"), ("GENDER_CODED", "he will"), ("VAGUE", "fast-paced"),
                ("VAGUE", "excellent communication skills"), ("EXCLUSIONARY", "native english speaker"),
                ("AGE_CODED", "digital natives"), ("AGE_CODED", "recent graduates"),
                ("DEGREE_INFLATION", "bachelor's degree in computer science or related field"),
                ("EXCESSIVE_EXPERIENCE", "12+ years")} <= warnings
        assert all(w.suggestion for w in compiled().warnings)

    def test_excessive_experience_depends_on_level_and_age_limits_are_flagged(self):
        senior = {w.kind for w in compiled(level="SENIOR").warnings}
        assert "EXCESSIVE_EXPERIENCE" not in senior
        limits = compile_rules(CompileRequest(title="Clerk", description="Candidates must be under 30 years of age."))
        assert ("AGE_CODED", "under 30 years of age") in {(w.kind, w.phrase) for w in limits.warnings}

    def test_unstructured_description(self):
        result = compile_rules(CompileRequest(title="Accountant", description=(
            "We need an accountant with 2+ years of experience in Tally. GST filing knowledge is required. "
            "Our office is in Bhubaneswar. MBA preferred.")))
        kinds = {(r.type, r.mandatory) for r in result.requirements}
        assert ("EXPERIENCE", True) in kinds and ("SKILL", True) in kinds and ("EDUCATION", False) in kinds
        assert not any("Bhubaneswar" in r.description for r in result.requirements)


class TestWorkSamples:
    def test_tasks_time_and_anchored_rubric(self):
        reqs = [requirement(i, "SKILL", name) for i, name in enumerate(["Java", "Spring Boot", "PostgreSQL", "Docker", "AWS"], start=1)]
        reqs.append(requirement(9, "SKILL", "Kubernetes", mandatory=False))
        sample = work_sample_rules(WorkSampleRequest(jobTitle="Backend Engineer", requirements=reqs))
        assert sample.engine == "rules" and sample.timeLimitMinutes == 30
        assert sample.instructions.count("Task ") == 2
        assert [c.requirementId for c in sample.rubric] == [1, 2, 3, 4, 5, None]
        for criterion in sample.rubric:
            assert set(criterion.anchors) == {"1", "2", "3", "4"} and all(criterion.anchors.values())

    def test_bounds_and_fallbacks(self):
        many = [requirement(i, "SKILL", f"Skill {i}") for i in range(1, 15)]
        assert work_sample_rules(WorkSampleRequest(jobTitle="Analyst", requirements=many)).timeLimitMinutes == 45
        nice_only = work_sample_rules(WorkSampleRequest(jobTitle="Analyst", requirements=[requirement(1, "SKILL", "SQL", mandatory=False)]))
        assert nice_only.rubric[0].requirementId == 1 and nice_only.timeLimitMinutes == 15
        empty = work_sample_rules(WorkSampleRequest(jobTitle="Teacher", requirements=[]))
        assert empty.timeLimitMinutes == 15 and [c.requirementId for c in empty.rubric] == [None]


class TestInterviewKits:
    def test_focus_requirements_come_first_with_anchors(self):
        reqs = [requirement(1, "SKILL", "SQL"), requirement(2, "SKILL", "Tableau", mandatory=False),
                requirement(3, "EXPERIENCE", "Stakeholder reporting", years=2), requirement(4, "OTHER", "Willing to travel")]
        kit = interview_rules(InterviewKitRequest(jobTitle="Analyst", requirements=reqs, focusRequirementIds=[3]))
        order = [q.requirementId for q in kit.questions]
        assert order[:2] == [3, 3] and order.index(1) < order.index(2)
        assert order.count(1) == 2 and order.count(2) == 1 and order.count(4) == 1
        assert [q.id for q in kit.questions] == [f"q{i}" for i in range(1, len(kit.questions) + 1)]
        assert all(set(q.anchors) == {"1", "2", "3", "4"} and q.followUps for q in kit.questions)

    def test_question_cap_and_empty_requirements(self):
        reqs = [requirement(i, "SKILL", f"Skill {i}") for i in range(1, 30)]
        assert len(interview_rules(InterviewKitRequest(jobTitle="Engineer", requirements=reqs)).questions) == 20
        assert interview_rules(InterviewKitRequest(jobTitle="Engineer", requirements=[])).questions[0].requirementId is None
