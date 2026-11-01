import unittest

from app.services.resume_parser import (
    SKILLS,
    SKILL_DOMAINS,
    SKILL_TAXONOMY,
    SkillDefinition,
    are_distinct,
    extract_skill_spans,
    extract_skills,
    get_canonical,
    get_children,
    get_parents,
    get_skill,
    is_parent_of,
    validate_taxonomy,
)
from app.models.intelligence import MatchFact, MatchRequest, MatchRequirement, RequirementMatch
from app.services.matching_engine import match_requirements


class SkillTaxonomyFoundationTests(unittest.TestCase):
    def test_existing_18_skills_remain_available_and_backward_compatible(self):
        original_18 = {
            "java": "Java",
            "spring boot": "Spring Boot",
            "spring": "Spring",
            "python": "Python",
            "sql": "SQL",
            "mysql": "MySQL",
            "postgresql": "PostgreSQL",
            "javascript": "JavaScript",
            "typescript": "TypeScript",
            "react": "React",
            "node.js": "Node.js",
            "rest api": "REST API",
            "aws": "AWS",
            "azure": "Azure",
            "docker": "Docker",
            "kubernetes": "Kubernetes",
            "git": "Git",
            "machine learning": "Machine Learning",
        }
        for key, expected_canonical in original_18.items():
            self.assertIn(key, SKILLS, f"Original skill key '{key}' must remain in SKILLS")
            skill = SKILLS[key]
            self.assertEqual(skill.canonical, expected_canonical)
            # Verify backward-compatible unpacking: canonical, aliases = skill
            canonical, aliases = skill
            self.assertEqual(canonical, expected_canonical)
            self.assertIsInstance(aliases, tuple)
            # Verify backward-compatible indexing: skill[0], skill[1]
            self.assertEqual(skill[0], expected_canonical)
            self.assertEqual(skill[1], aliases)

        # Verify existing aliases are preserved
        self.assertIn("postgres", SKILLS["postgresql"].aliases)
        self.assertIn("javascript", SKILLS["javascript"].aliases)
        self.assertIn("nodejs", SKILLS["node.js"].aliases)
        self.assertIn("restful api", SKILLS["rest api"].aliases)
        self.assertIn("rest apis", SKILLS["rest api"].aliases)
        self.assertIn("amazon web services", SKILLS["aws"].aliases)
        self.assertIn("microsoft azure", SKILLS["azure"].aliases)
        self.assertIn("k8s", SKILLS["kubernetes"].aliases)
        self.assertIn("ml", SKILLS["machine learning"].aliases)

    def test_all_required_added_skills_are_present_with_correct_domains(self):
        # Languages
        languages = ["C", "C++", "C#", "Go", "Rust", "Kotlin", "Swift", "PHP", "Ruby"]
        for lang in languages:
            skill = get_skill(lang)
            self.assertIsNotNone(skill, f"Language '{lang}' must be present in taxonomy")
            self.assertEqual(skill.domain, "LANGUAGES")

        # Databases
        databases = ["MongoDB", "Redis", "Cassandra", "Oracle", "SQLite"]
        for db in databases:
            skill = get_skill(db)
            self.assertIsNotNone(skill, f"Database '{db}' must be present in taxonomy")
            self.assertEqual(skill.domain, "DATABASES")

        # APIs and messaging
        apis = ["GraphQL", "gRPC", "Kafka", "RabbitMQ"]
        for api in apis:
            skill = get_skill(api)
            self.assertIsNotNone(skill, f"API/Messaging '{api}' must be present in taxonomy")
            self.assertEqual(skill.domain, "APIS_AND_MESSAGING")

        # Cloud and DevOps
        cloud = ["CI/CD", "Terraform", "Linux", "GitHub Actions", "GCP"]
        for tool in cloud:
            skill = get_skill(tool)
            self.assertIsNotNone(skill, f"Cloud/DevOps tool '{tool}' must be present in taxonomy")
            self.assertEqual(skill.domain, "CLOUD_AND_DEVOPS")

        # AI/ML
        aiml = ["Deep Learning", "PyTorch", "TensorFlow", "NLP", "Computer Vision"]
        for ai in aiml:
            skill = get_skill(ai)
            self.assertIsNotNone(skill, f"AI/ML skill '{ai}' must be present in taxonomy")
            self.assertEqual(skill.domain, "AI_ML")

        # Mobile
        mobile = ["React Native", "Flutter", "Android", "iOS"]
        for m in mobile:
            skill = get_skill(m)
            self.assertIsNotNone(skill, f"Mobile skill '{m}' must be present in taxonomy")
            self.assertEqual(skill.domain, "MOBILE")

        # Total taxonomy count check: 18 existing + 32 added = 50 canonical skills
        self.assertEqual(len(SKILL_TAXONOMY), 50)

    def test_canonical_skill_lookup_case_insensitivity(self):
        self.assertEqual(get_canonical("java"), "Java")
        self.assertEqual(get_canonical("JAVA"), "Java")
        self.assertEqual(get_canonical("Java"), "Java")
        self.assertEqual(get_canonical("python"), "Python")
        self.assertEqual(get_canonical("PYTHON"), "Python")
        self.assertEqual(get_canonical("deep learning"), "Deep Learning")
        self.assertEqual(get_canonical("DEEP LEARNING"), "Deep Learning")
        self.assertEqual(get_canonical("graphql"), "GraphQL")
        self.assertEqual(get_canonical("c++"), "C++")
        self.assertEqual(get_canonical("c#"), "C#")
        self.assertIsNone(get_canonical("unknown_nonexistent_skill"))

    def test_alias_lookup(self):
        # Database aliases
        self.assertEqual(get_canonical("postgres"), "PostgreSQL")
        self.assertEqual(get_canonical("POSTGRES"), "PostgreSQL")
        self.assertEqual(get_canonical("mongo"), "MongoDB")

        # Cloud aliases
        self.assertEqual(get_canonical("k8s"), "Kubernetes")
        self.assertEqual(get_canonical("amazon web services"), "AWS")
        self.assertEqual(get_canonical("microsoft azure"), "Azure")
        self.assertEqual(get_canonical("google cloud"), "GCP")
        self.assertEqual(get_canonical("google cloud platform"), "GCP")
        self.assertEqual(get_canonical("cicd"), "CI/CD")
        self.assertEqual(get_canonical("ci cd"), "CI/CD")

        # Language aliases
        self.assertEqual(get_canonical("cpp"), "C++")
        self.assertEqual(get_canonical("csharp"), "C#")
        self.assertEqual(get_canonical("c sharp"), "C#")
        self.assertEqual(get_canonical("golang"), "Go")

        # AI/ML aliases
        self.assertEqual(get_canonical("ml"), "Machine Learning")
        self.assertEqual(get_canonical("dl"), "Deep Learning")
        self.assertEqual(get_canonical("natural language processing"), "NLP")
        self.assertEqual(get_canonical("cv"), "Computer Vision")

        # API aliases
        self.assertEqual(get_canonical("restful api"), "REST API")
        self.assertEqual(get_canonical("rest apis"), "REST API")

    def test_conservative_node_alias_handling(self):
        # Explicit requirement: "Do not treat ambiguous aliases as unconditional synonyms.
        # In particular, Node must be handled conservatively."
        self.assertIsNone(get_canonical("node"))
        self.assertIsNone(get_canonical("Node"))
        self.assertIsNone(get_skill("node"))
        # Specific, unambiguous aliases must work
        self.assertEqual(get_canonical("nodejs"), "Node.js")
        self.assertEqual(get_canonical("node.js"), "Node.js")

    def test_no_duplicate_aliases_and_collision_detection(self):
        errors = validate_taxonomy()
        self.assertEqual(errors, [], f"Taxonomy validation must produce zero errors. Found: {errors}")

    def test_directional_relationships(self):
        # MySQL -> SQL
        self.assertTrue(is_parent_of("SQL", "MySQL"))
        self.assertFalse(is_parent_of("MySQL", "SQL"), "Parent-child relationship must be directional, not symmetric")
        self.assertEqual(get_parents("MySQL"), ("SQL",))
        self.assertIn("MySQL", get_children("SQL"))

        # PostgreSQL -> SQL
        self.assertTrue(is_parent_of("SQL", "PostgreSQL"))
        self.assertFalse(is_parent_of("PostgreSQL", "SQL"))
        self.assertEqual(get_parents("PostgreSQL"), ("SQL",))
        self.assertIn("PostgreSQL", get_children("SQL"))

        # SQLite -> SQL
        self.assertTrue(is_parent_of("SQL", "SQLite"))
        self.assertFalse(is_parent_of("SQLite", "SQL"))
        self.assertEqual(get_parents("SQLite"), ("SQL",))
        self.assertIn("SQLite", get_children("SQL"))

        # Spring Boot -> Spring
        self.assertTrue(is_parent_of("Spring", "Spring Boot"))
        self.assertFalse(is_parent_of("Spring Boot", "Spring"))
        self.assertEqual(get_parents("Spring Boot"), ("Spring",))
        self.assertIn("Spring Boot", get_children("Spring"))

        # Deep Learning -> Machine Learning
        self.assertTrue(is_parent_of("Machine Learning", "Deep Learning"))
        self.assertFalse(is_parent_of("Deep Learning", "Machine Learning"))
        self.assertEqual(get_parents("Deep Learning"), ("Machine Learning",))
        self.assertIn("Deep Learning", get_children("Machine Learning"))

        # Unrelated skills have no directional relationship
        self.assertFalse(is_parent_of("Java", "Python"))
        self.assertFalse(is_parent_of("Docker", "Kubernetes"))

    def test_distinction_java_vs_javascript(self):
        self.assertTrue(are_distinct("Java", "JavaScript"))
        self.assertTrue(are_distinct("JavaScript", "Java"))
        java = get_skill("Java")
        js = get_skill("JavaScript")
        self.assertIn("JavaScript", java.distinctions)
        self.assertIn("Java", js.distinctions)
        self.assertFalse(is_parent_of("Java", "JavaScript"))
        self.assertFalse(is_parent_of("JavaScript", "Java"))

    def test_distinction_react_vs_react_native(self):
        self.assertTrue(are_distinct("React", "React Native"))
        self.assertTrue(are_distinct("React Native", "React"))
        react = get_skill("React")
        rn = get_skill("React Native")
        self.assertIn("React Native", react.distinctions)
        self.assertIn("React", rn.distinctions)
        self.assertEqual(react.domain, "FRAMEWORKS")
        self.assertEqual(rn.domain, "MOBILE")
        self.assertFalse(is_parent_of("React", "React Native"))
        self.assertFalse(is_parent_of("React Native", "React"))

    def test_distinction_spring_vs_spring_boot(self):
        # Spring Boot is a child of Spring, not an unrelated distinction
        self.assertTrue(is_parent_of("Spring", "Spring Boot"))
        self.assertFalse(is_parent_of("Spring Boot", "Spring"))
        self.assertEqual(get_parents("Spring Boot"), ("Spring",))
        self.assertIn("Spring Boot", get_children("Spring"))

    def test_distinction_sql_vs_mysql(self):
        # MySQL is a child of SQL, not an unrelated distinction
        self.assertTrue(is_parent_of("SQL", "MySQL"))
        self.assertFalse(is_parent_of("MySQL", "SQL"))
        self.assertEqual(get_parents("MySQL"), ("SQL",))
        self.assertIn("MySQL", get_children("SQL"))

    def test_distinction_deep_learning_vs_machine_learning(self):
        # Deep Learning is a child of Machine Learning
        self.assertTrue(is_parent_of("Machine Learning", "Deep Learning"))
        self.assertFalse(is_parent_of("Deep Learning", "Machine Learning"))
        self.assertEqual(get_parents("Deep Learning"), ("Machine Learning",))
        self.assertIn("Deep Learning", get_children("Machine Learning"))

    def test_distinction_aws_vs_azure(self):
        self.assertTrue(are_distinct("AWS", "Azure"))
        self.assertTrue(are_distinct("Azure", "AWS"))
        self.assertIn("Azure", get_skill("AWS").distinctions)
        self.assertIn("AWS", get_skill("Azure").distinctions)

    def test_distinction_docker_vs_kubernetes(self):
        self.assertTrue(are_distinct("Docker", "Kubernetes"))
        self.assertTrue(are_distinct("Kubernetes", "Docker"))
        self.assertIn("Kubernetes", get_skill("Docker").distinctions)
        self.assertIn("Docker", get_skill("Kubernetes").distinctions)

    def test_distinction_python_vs_pytorch(self):
        self.assertTrue(are_distinct("Python", "PyTorch"))
        self.assertTrue(are_distinct("PyTorch", "Python"))
        self.assertIn("PyTorch", get_skill("Python").distinctions)
        self.assertIn("Python", get_skill("PyTorch").distinctions)
        self.assertEqual(get_skill("Python").domain, "LANGUAGES")
        self.assertEqual(get_skill("PyTorch").domain, "AI_ML")


class SkillPhraseRecognitionRegressionTests(unittest.TestCase):
    def match(self, req_desc: str, fact_val: str, req_type: str = "SKILL"):
        req = MatchRequirement(id=1, type=req_type, description=req_desc, mandatory=True)
        fact = MatchFact(id=1, type=req_type, value=fact_val, evidence=f"Worked with {fact_val}", sourceSection="skills", reviewStatus="UNREVIEWED")
        return match_requirements(MatchRequest(requirements=[req], facts=[fact])).requirements[0]

    def test_java_vs_javascript_both_directions(self):
        # Phrase recognition
        self.assertEqual(extract_skills("Java"), {"Java"})
        self.assertEqual(extract_skills("JavaScript"), {"JavaScript"})
        self.assertEqual(extract_skills("Java and JavaScript"), {"Java", "JavaScript"})
        self.assertEqual(extract_skills("Java script developer"), {"JavaScript"})
        self.assertEqual(extract_skills("Java-script developer"), {"JavaScript"})
        self.assertNotIn("Java", extract_skills("Java script developer"))

        # Matching engine
        self.assertEqual(self.match("Java", "JavaScript").status, "NOT_OBSERVED")
        self.assertEqual(self.match("JavaScript", "Java").status, "NOT_OBSERVED")
        self.assertEqual(self.match("Java", "Java script").status, "NOT_OBSERVED")
        self.assertEqual(self.match("Java", "Java-script").status, "NOT_OBSERVED")
        self.assertEqual(self.match("Java", "Java").status, "MATCHED")
        self.assertEqual(self.match("JavaScript", "JavaScript").status, "MATCHED")

    def test_react_vs_react_native_both_directions(self):
        # Phrase recognition
        self.assertEqual(extract_skills("React"), {"React"})
        self.assertEqual(extract_skills("React Native"), {"React Native"})
        self.assertEqual(extract_skills("React and React Native"), {"React", "React Native"})
        self.assertEqual(extract_skills("Building React Native mobile apps"), {"React Native"})
        self.assertNotIn("React", extract_skills("Building React Native mobile apps"))

        # Matching engine
        self.assertEqual(self.match("React", "React Native").status, "NOT_OBSERVED")
        self.assertEqual(self.match("React Native", "React").status, "NOT_OBSERVED")
        self.assertEqual(self.match("React", "React").status, "MATCHED")
        self.assertEqual(self.match("React Native", "React Native").status, "MATCHED")

    def test_spring_vs_spring_boot_both_directions(self):
        # Phrase recognition
        self.assertEqual(extract_skills("Spring"), {"Spring"})
        self.assertEqual(extract_skills("Spring Boot"), {"Spring Boot"})
        self.assertEqual(extract_skills("Spring and Spring Boot"), {"Spring", "Spring Boot"})
        self.assertEqual(extract_skills("Spring Boot microservices"), {"Spring Boot"})
        self.assertNotIn("Spring", extract_skills("Spring Boot microservices"))

        # Matching engine
        self.assertEqual(self.match("Spring Boot", "Spring").status, "UNCLEAR")
        self.assertEqual(self.match("Spring Boot", "Spring Boot").status, "MATCHED")
        self.assertEqual(self.match("Spring", "Spring").status, "MATCHED")

    def test_c_cpp_csharp_independent_recognition(self):
        # Phrase recognition
        self.assertEqual(extract_skills("C"), {"C"})
        self.assertEqual(extract_skills("C++"), {"C++"})
        self.assertEqual(extract_skills("C#"), {"C#"})
        self.assertEqual(extract_skills("C, C++, and C#"), {"C", "C++", "C#"})
        self.assertEqual(extract_skills("Objective-C developer"), set())

        # Sub-skill non-duplication
        self.assertEqual(extract_skills("C++ developer"), {"C++"})
        self.assertNotIn("C", extract_skills("C++ developer"))
        self.assertEqual(extract_skills("C# developer"), {"C#"})
        self.assertNotIn("C", extract_skills("C# developer"))

        # Matching engine
        self.assertEqual(self.match("C", "C++").status, "NOT_OBSERVED")
        self.assertEqual(self.match("C++", "C").status, "NOT_OBSERVED")
        self.assertEqual(self.match("C", "C#").status, "NOT_OBSERVED")
        self.assertEqual(self.match("C#", "C").status, "NOT_OBSERVED")
        self.assertEqual(self.match("C", "C").status, "MATCHED")
        self.assertEqual(self.match("C++", "C++").status, "MATCHED")
        self.assertEqual(self.match("C#", "C#").status, "MATCHED")

    def test_sql_vs_mysql_both_directions(self):
        # Phrase recognition
        self.assertEqual(extract_skills("SQL"), {"SQL"})
        self.assertEqual(extract_skills("MySQL"), {"MySQL"})
        self.assertEqual(extract_skills("SQL and MySQL"), {"SQL", "MySQL"})
        self.assertEqual(extract_skills("MySQL database server"), {"MySQL"})
        self.assertNotIn("SQL", extract_skills("MySQL database server"))

        # Matching engine (subsumption implemented in Phase 5C.3)
        # MySQL (child) satisfies SQL (parent) requirement; the reverse must not hold.
        self.assertEqual(self.match("SQL", "MySQL").status, "MATCHED")
        self.assertEqual(self.match("MySQL", "SQL").status, "NOT_OBSERVED")
        self.assertEqual(self.match("SQL", "SQL").status, "MATCHED")
        self.assertEqual(self.match("MySQL", "MySQL").status, "MATCHED")

    def test_python_vs_pytorch_both_directions(self):
        # Phrase recognition
        self.assertEqual(extract_skills("Python"), {"Python"})
        self.assertEqual(extract_skills("PyTorch"), {"PyTorch"})
        self.assertEqual(extract_skills("Python and PyTorch"), {"Python", "PyTorch"})

        # Matching engine
        self.assertEqual(self.match("Python", "PyTorch").status, "NOT_OBSERVED")
        self.assertEqual(self.match("PyTorch", "Python").status, "NOT_OBSERVED")
        self.assertEqual(self.match("Python", "Python").status, "MATCHED")
        self.assertEqual(self.match("PyTorch", "PyTorch").status, "MATCHED")

    def test_case_insensitive_matching(self):
        self.assertEqual(extract_skills("rEaCt nAtIvE"), {"React Native"})
        self.assertEqual(extract_skills("jAvAsCrIpT"), {"JavaScript"})
        self.assertEqual(extract_skills("pYtHoN"), {"Python"})
        self.assertEqual(extract_skills("kUbErNeTeS"), {"Kubernetes"})
        self.assertEqual(extract_skills("sPrInG bOoT"), {"Spring Boot"})

    def test_punctuation_boundaries(self):
        self.assertEqual(extract_skills("(Java)"), {"Java"})
        self.assertEqual(extract_skills("[React]"), {"React"})
        self.assertEqual(extract_skills("Python/Django"), {"Python"})
        self.assertEqual(extract_skills("CI/CD pipeline"), {"CI/CD"})
        self.assertEqual(extract_skills("Node.js runtime"), {"Node.js"})

    def test_multi_word_aliases(self):
        self.assertEqual(extract_skills("Amazon Web Services"), {"AWS"})
        self.assertEqual(extract_skills("Microsoft Azure"), {"Azure"})
        self.assertEqual(extract_skills("Google Cloud Platform"), {"GCP"})
        self.assertEqual(extract_skills("Natural Language Processing"), {"NLP"})
        self.assertEqual(extract_skills("Computer Vision engineer"), {"Computer Vision"})

    def test_overlapping_skill_names_and_duplicate_prevention(self):
        self.assertEqual(extract_skills("Deep Learning networks"), {"Deep Learning"})
        self.assertNotIn("Machine Learning", extract_skills("Deep Learning networks"))
        self.assertEqual(extract_skills("PostgreSQL database"), {"PostgreSQL"})
        self.assertNotIn("SQL", extract_skills("PostgreSQL database"))
        self.assertEqual(extract_skills("Machine Learning and Deep Learning"), {"Machine Learning", "Deep Learning"})


class SkillSubsumptionRegressionTests(unittest.TestCase):
    """Phase 5C.3 — directional parent-child subsumption in the matching engine."""

    # ---------------------------------------------------------------------------
    # Helpers
    # ---------------------------------------------------------------------------
    def _skill_match(self, req_desc: str, fact_val: str, review_status: str = "UNREVIEWED") -> RequirementMatch:
        req = MatchRequirement(id=1, type="SKILL", description=req_desc, mandatory=True)
        fact = MatchFact(
            id=1, type="SKILL", value=fact_val,
            evidence=f"Proficient in {fact_val}",
            sourceSection="skills",
            reviewStatus=review_status,
        )
        return match_requirements(MatchRequest(requirements=[req], facts=[fact])).requirements[0]

    def _exp_match(self, req_desc: str, fact_val: str, min_years: int | None = None,
                   review_status: str = "UNREVIEWED") -> RequirementMatch:
        req = MatchRequirement(id=1, type="EXPERIENCE", description=req_desc,
                               mandatory=True, minimumYears=min_years)
        fact = MatchFact(
            id=1, type="EXPERIENCE", value=fact_val,
            evidence=fact_val,
            sourceSection="experience",
            reviewStatus=review_status,
        )
        return match_requirements(MatchRequest(requirements=[req], facts=[fact])).requirements[0]

    # ---------------------------------------------------------------------------
    # SKILL subsumption — forward direction (child satisfies parent)
    # ---------------------------------------------------------------------------
    def test_mysql_satisfies_sql_requirement_matched(self):
        result = self._skill_match("SQL", "MySQL")
        self.assertEqual(result.status, "MATCHED",
                         f"MySQL candidate should satisfy SQL requirement via subsumption. Got: {result.status} — {result.explanation}")

    def test_postgresql_satisfies_sql_requirement_matched(self):
        result = self._skill_match("SQL", "PostgreSQL")
        self.assertEqual(result.status, "MATCHED",
                         f"PostgreSQL candidate should satisfy SQL requirement via subsumption. Got: {result.status} — {result.explanation}")

    def test_sqlite_satisfies_sql_requirement_matched(self):
        result = self._skill_match("SQL", "SQLite")
        self.assertEqual(result.status, "MATCHED",
                         f"SQLite candidate should satisfy SQL requirement via subsumption. Got: {result.status} — {result.explanation}")

    def test_spring_boot_satisfies_spring_requirement_matched(self):
        result = self._skill_match("Spring", "Spring Boot")
        self.assertEqual(result.status, "MATCHED",
                         f"Spring Boot candidate should satisfy Spring requirement via subsumption. Got: {result.status} — {result.explanation}")

    def test_deep_learning_satisfies_machine_learning_requirement_matched(self):
        result = self._skill_match("Machine Learning", "Deep Learning")
        self.assertEqual(result.status, "MATCHED",
                         f"Deep Learning candidate should satisfy Machine Learning requirement via subsumption. Got: {result.status} — {result.explanation}")

    # ---------------------------------------------------------------------------
    # SKILL subsumption — reverse direction (parent does NOT satisfy child)
    # ---------------------------------------------------------------------------
    def test_sql_does_not_satisfy_mysql_requirement_reverse(self):
        result = self._skill_match("MySQL", "SQL")
        self.assertIn(result.status, {"UNCLEAR", "NOT_OBSERVED"},
                      f"SQL candidate must NOT automatically satisfy MySQL requirement. Got: {result.status} — {result.explanation}")

    def test_spring_does_not_satisfy_spring_boot_requirement_reverse(self):
        result = self._skill_match("Spring Boot", "Spring")
        self.assertIn(result.status, {"UNCLEAR", "NOT_OBSERVED"},
                      f"Spring candidate must NOT automatically satisfy Spring Boot requirement. Got: {result.status} — {result.explanation}")

    def test_machine_learning_does_not_satisfy_deep_learning_requirement_reverse(self):
        result = self._skill_match("Deep Learning", "Machine Learning")
        self.assertIn(result.status, {"UNCLEAR", "NOT_OBSERVED"},
                      f"Machine Learning candidate must NOT automatically satisfy Deep Learning requirement. Got: {result.status} — {result.explanation}")

    # ---------------------------------------------------------------------------
    # Provenance: candidate-confirmed child skill must carry CANDIDATE_REVIEW
    # ---------------------------------------------------------------------------
    def test_candidate_confirmed_child_skill_preserves_candidate_review_provenance(self):
        result = self._skill_match("SQL", "MySQL", review_status="CONFIRMED")
        self.assertEqual(result.status, "MATCHED")
        self.assertEqual(result.provenance, "CANDIDATE_REVIEW",
                         "A CONFIRMED child-skill fact that subsumes a parent requirement must carry CANDIDATE_REVIEW provenance.")

    def test_candidate_corrected_child_skill_preserves_candidate_review_provenance(self):
        result = self._skill_match("SQL", "MySQL", review_status="CORRECTED")
        self.assertEqual(result.status, "MATCHED")
        self.assertEqual(result.provenance, "CANDIDATE_REVIEW",
                         "A CORRECTED child-skill fact that subsumes a parent requirement must carry CANDIDATE_REVIEW provenance.")

    # ---------------------------------------------------------------------------
    # Unrelated technology receives no subsumption credit
    # ---------------------------------------------------------------------------
    def test_unrelated_technology_does_not_receive_subsumption_credit(self):
        # Docker has no parent-child relationship to Kubernetes
        result = self._skill_match("Kubernetes", "Docker")
        self.assertIn(result.status, {"UNCLEAR", "NOT_OBSERVED"},
                      f"Docker must not satisfy Kubernetes via subsumption — they are unrelated distinctions. Got: {result.status}")

        # Java has no parent-child relationship with SQL
        result2 = self._skill_match("SQL", "Java")
        self.assertIn(result2.status, {"UNCLEAR", "NOT_OBSERVED"},
                      f"Java must not satisfy SQL via subsumption. Got: {result2.status}")

    # ---------------------------------------------------------------------------
    # EXPERIENCE subsumption — duration is still required independently
    # ---------------------------------------------------------------------------
    def test_experience_child_skill_subsumption_requires_duration(self):
        # MySQL candidate with sufficient years should satisfy "3 years SQL experience"
        result = self._exp_match("3 years SQL experience", "3 years of MySQL", min_years=3)
        self.assertEqual(result.status, "MATCHED",
                         f"3 years MySQL should satisfy 3 years SQL via subsumption. Got: {result.status} — {result.explanation}")

    def test_experience_child_skill_insufficient_duration_not_observed(self):
        # MySQL candidate with insufficient years must NOT satisfy "5 years SQL"
        result = self._exp_match("5 years SQL experience", "2 years of MySQL", min_years=5)
        self.assertEqual(result.status, "NOT_OBSERVED",
                         f"2 years MySQL must not satisfy 5 years SQL requirement. Got: {result.status} — {result.explanation}")

    def test_experience_child_skill_missing_duration_unclear(self):
        # MySQL candidate with no extractable year count → UNCLEAR (not MATCHED or NOT_OBSERVED)
        result = self._exp_match("3 years SQL experience", "MySQL experience", min_years=3)
        self.assertEqual(result.status, "UNCLEAR",
                         f"MySQL experience without explicit duration must be UNCLEAR for a min_years requirement. Got: {result.status} — {result.explanation}")

    # ---------------------------------------------------------------------------
    # Distinctions are unaffected by subsumption
    # ---------------------------------------------------------------------------
    def test_java_javascript_distinction_unaffected_by_subsumption(self):
        # Java and JavaScript are explicitly distinct — subsumption must not bridge them
        result = self._skill_match("Java", "JavaScript")
        self.assertEqual(result.status, "NOT_OBSERVED",
                         f"JavaScript must not satisfy Java (distinction pair). Got: {result.status}")
        result2 = self._skill_match("JavaScript", "Java")
        self.assertEqual(result2.status, "NOT_OBSERVED",
                         f"Java must not satisfy JavaScript (distinction pair). Got: {result2.status}")

    def test_react_react_native_distinction_unaffected_by_subsumption(self):
        # React and React Native are explicitly distinct — subsumption must not bridge them
        result = self._skill_match("React", "React Native")
        self.assertEqual(result.status, "NOT_OBSERVED",
                         f"React Native must not satisfy React (distinction pair). Got: {result.status}")
        result2 = self._skill_match("React Native", "React")
        self.assertEqual(result2.status, "NOT_OBSERVED",
                         f"React must not satisfy React Native (distinction pair). Got: {result2.status}")



class CrossCategorySkillFallbackTests(unittest.TestCase):
    """Phase 5C.4 — cross-category evidence fallback for SKILL requirements."""

    # ---------------------------------------------------------------------------
    # Helpers
    # ---------------------------------------------------------------------------
    def _match_skill_with_exp(
        self,
        req_desc: str,
        exp_value: str,
        skill_value: str | None = None,
        exp_review_status: str = "UNREVIEWED",
        skill_review_status: str = "UNREVIEWED",
    ) -> RequirementMatch:
        """Build a match request with an EXPERIENCE fact and an optional SKILL fact."""
        req = MatchRequirement(id=1, type="SKILL", description=req_desc, mandatory=True)
        facts: list[MatchFact] = [
            MatchFact(
                id=10, type="EXPERIENCE", value=exp_value,
                evidence=exp_value,
                sourceSection="experience",
                reviewStatus=exp_review_status,
            )
        ]
        if skill_value is not None:
            facts.append(MatchFact(
                id=11, type="SKILL", value=skill_value,
                evidence=f"Proficient in {skill_value}",
                sourceSection="skills",
                reviewStatus=skill_review_status,
            ))
        return match_requirements(MatchRequest(requirements=[req], facts=facts)).requirements[0]

    def _match_two_skill_reqs(
        self,
        req1_desc: str, req2_desc: str,
        exp_value: str,
    ) -> tuple[RequirementMatch, RequirementMatch]:
        """Two SKILL requirements matched against one EXPERIENCE fact — to test scoring."""
        reqs = [
            MatchRequirement(id=1, type="SKILL", description=req1_desc, mandatory=True),
            MatchRequirement(id=2, type="SKILL", description=req2_desc, mandatory=True),
        ]
        fact = MatchFact(
            id=10, type="EXPERIENCE", value=exp_value,
            evidence=exp_value, sourceSection="experience", reviewStatus="UNREVIEWED",
        )
        resp = match_requirements(MatchRequest(requirements=reqs, facts=[fact]))
        return resp.requirements[0], resp.requirements[1]

    # ---------------------------------------------------------------------------
    # Mandatory tests 1–3: named technologies found in EXPERIENCE facts
    # ---------------------------------------------------------------------------
    def test_graphql_requirement_satisfied_by_experience_fact(self):
        result = self._match_skill_with_exp("GraphQL", "Developed GraphQL APIs for four years")
        self.assertEqual(result.status, "MATCHED",
                         f"GraphQL skill req should be satisfied by experience evidence. Got: {result.status} — {result.explanation}")
        self.assertIsNotNone(result.evidence)

    def test_kafka_requirement_satisfied_by_experience_fact(self):
        result = self._match_skill_with_exp("Kafka", "Built event-driven pipelines using Kafka and RabbitMQ")
        self.assertEqual(result.status, "MATCHED",
                         f"Kafka skill req should be satisfied by experience evidence. Got: {result.status} — {result.explanation}")

    def test_redis_requirement_satisfied_by_experience_fact(self):
        result = self._match_skill_with_exp("Redis", "Used Redis for distributed caching in production")
        self.assertEqual(result.status, "MATCHED",
                         f"Redis skill req should be satisfied by experience evidence. Got: {result.status} — {result.explanation}")

    # ---------------------------------------------------------------------------
    # Mandatory test 4: unrelated technology does NOT satisfy the skill requirement
    # ---------------------------------------------------------------------------
    def test_unrelated_experience_technology_does_not_satisfy_skill_requirement(self):
        # Kubernetes requirement, but experience only mentions Python
        result = self._match_skill_with_exp("Kubernetes", "3 years of Python backend development")
        self.assertIn(result.status, {"NOT_OBSERVED", "UNCLEAR"},
                      f"Python experience must not satisfy Kubernetes skill requirement. Got: {result.status} — {result.explanation}")

    # ---------------------------------------------------------------------------
    # Mandatory test 5: candidate-reviewed EXPERIENCE fact → CANDIDATE_REVIEW provenance
    # ---------------------------------------------------------------------------
    def test_candidate_reviewed_experience_fact_preserves_candidate_review_provenance(self):
        result = self._match_skill_with_exp(
            "GraphQL", "Developed GraphQL APIs for four years",
            exp_review_status="CONFIRMED",
        )
        self.assertEqual(result.status, "MATCHED")
        self.assertEqual(result.provenance, "CANDIDATE_REVIEW",
                         "A CONFIRMED experience fact used as cross-category evidence must carry CANDIDATE_REVIEW provenance.")

    # ---------------------------------------------------------------------------
    # Mandatory test 6: unreviewed EXPERIENCE fact → RESUME provenance
    # ---------------------------------------------------------------------------
    def test_unreviewed_experience_fact_preserves_resume_provenance(self):
        result = self._match_skill_with_exp(
            "GraphQL", "Developed GraphQL APIs for four years",
            exp_review_status="UNREVIEWED",
        )
        self.assertEqual(result.status, "MATCHED")
        self.assertEqual(result.provenance, "RESUME",
                         "An unreviewed experience fact used as cross-category evidence must carry RESUME provenance.")

    # ---------------------------------------------------------------------------
    # Mandatory test 7: direct SKILL fact takes precedence over EXPERIENCE fact
    # ---------------------------------------------------------------------------
    def test_direct_skill_evidence_takes_precedence_over_experience_evidence(self):
        # Both SKILL and EXPERIENCE mention GraphQL; primary SKILL path fires, fallback not needed
        result = self._match_skill_with_exp(
            "GraphQL",
            "Developed GraphQL APIs for four years",  # EXPERIENCE fact
            skill_value="GraphQL",                    # SKILL fact — should be the evidence source
        )
        self.assertEqual(result.status, "MATCHED",
                         f"GraphQL skill must be MATCHED when direct SKILL fact is present. Got: {result.status}")
        # Evidence must come from the SKILL fact (sourceSection="skills"), not EXPERIENCE
        self.assertIsNotNone(result.sourceSection)
        self.assertEqual(result.sourceSection, "skills",
                         "When a direct SKILL fact matches, its sourceSection must be used, not the EXPERIENCE fact's.")

    # ---------------------------------------------------------------------------
    # Mandatory test 8: duplicate evidence does not inflate the weighted score
    # ---------------------------------------------------------------------------
    def test_duplicate_cross_category_evidence_does_not_inflate_score(self):
        # Two distinct SKILL requirements against one EXPERIENCE fact mentioning only GraphQL.
        # GraphQL req: MATCHED (found in experience); Kafka req: NOT_OBSERVED.
        # Score must reflect 1 matched / 2 required = 50%, not inflated.
        r1, r2 = self._match_two_skill_reqs("GraphQL", "Kafka", "Developed GraphQL APIs")
        self.assertEqual(r1.status, "MATCHED")
        self.assertIn(r2.status, {"NOT_OBSERVED", "UNCLEAR"})

        # Verify score
        req1 = MatchRequirement(id=1, type="SKILL", description="GraphQL", mandatory=True)
        req2 = MatchRequirement(id=2, type="SKILL", description="Kafka", mandatory=True)
        fact = MatchFact(id=10, type="EXPERIENCE", value="Developed GraphQL APIs",
                         evidence="Developed GraphQL APIs", sourceSection="experience",
                         reviewStatus="UNREVIEWED")
        resp = match_requirements(MatchRequest(requirements=[req1, req2], facts=[fact]))
        # Each mandatory requirement has weight 2; only GraphQL matched → score = 2/4 = 50.0
        self.assertEqual(resp.score, 50.0,
                         f"Score must reflect only matched requirements; got {resp.score}")

    # ---------------------------------------------------------------------------
    # Mandatory test 9: partial experience evidence → UNCLEAR
    # ---------------------------------------------------------------------------
    def test_partial_experience_evidence_produces_unclear(self):
        # Requirement: GraphQL AND Kafka. Experience only has GraphQL.
        req = MatchRequirement(id=1, type="SKILL", description="GraphQL and Kafka", mandatory=True)
        fact = MatchFact(
            id=10, type="EXPERIENCE", value="Developed GraphQL APIs",
            evidence="Developed GraphQL APIs", sourceSection="experience",
            reviewStatus="UNREVIEWED",
        )
        result = match_requirements(MatchRequest(requirements=[req], facts=[fact])).requirements[0]
        self.assertEqual(result.status, "UNCLEAR",
                         f"Partial cross-category skill evidence must produce UNCLEAR. Got: {result.status} — {result.explanation}")

    # ---------------------------------------------------------------------------
    # Mandatory test 10: missing evidence produces NOT_OBSERVED
    # ---------------------------------------------------------------------------
    def test_missing_evidence_produces_not_observed(self):
        # Requirement: GraphQL. No facts contain GraphQL.
        result = self._match_skill_with_exp("GraphQL", "3 years of Java backend development")
        self.assertIn(result.status, {"NOT_OBSERVED", "UNCLEAR"},
                      f"Absent cross-category evidence must not produce MATCHED. Got: {result.status}")

    # ---------------------------------------------------------------------------
    # Mandatory test 11: Java/JavaScript and React/React Native distinctions intact
    # ---------------------------------------------------------------------------
    def test_java_javascript_distinction_intact_in_cross_category(self):
        # Requirement: Java. EXPERIENCE fact only mentions JavaScript.
        result = self._match_skill_with_exp("Java", "Developed JavaScript SPA applications")
        self.assertIn(result.status, {"NOT_OBSERVED", "UNCLEAR"},
                      f"JavaScript experience must not satisfy Java skill requirement. Got: {result.status} — {result.explanation}")

        # Requirement: JavaScript. EXPERIENCE fact only mentions Java.
        result2 = self._match_skill_with_exp("JavaScript", "Built Java microservices")
        self.assertIn(result2.status, {"NOT_OBSERVED", "UNCLEAR"},
                      f"Java experience must not satisfy JavaScript skill requirement. Got: {result2.status} — {result2.explanation}")

    def test_react_react_native_distinction_intact_in_cross_category(self):
        # Requirement: React. EXPERIENCE fact only mentions React Native.
        result = self._match_skill_with_exp("React", "Built React Native mobile apps")
        self.assertIn(result.status, {"NOT_OBSERVED", "UNCLEAR"},
                      f"React Native experience must not satisfy React skill requirement. Got: {result.status} — {result.explanation}")

        # Requirement: React Native. EXPERIENCE fact only mentions React.
        result2 = self._match_skill_with_exp("React Native", "Built React web dashboards")
        self.assertIn(result2.status, {"NOT_OBSERVED", "UNCLEAR"},
                      f"React experience must not satisfy React Native skill requirement. Got: {result2.status} — {result2.explanation}")

    # ---------------------------------------------------------------------------
    # Mandatory test 12: directional subsumption remains correct in cross-category
    # ---------------------------------------------------------------------------
    def test_subsumption_works_through_cross_category_fallback(self):
        # Requirement: SQL. EXPERIENCE fact only mentions MySQL (a child of SQL).
        result = self._match_skill_with_exp("SQL", "4 years of MySQL database administration")
        self.assertEqual(result.status, "MATCHED",
                         f"MySQL in experience should satisfy SQL skill req via subsumption+cross-category. Got: {result.status} — {result.explanation}")

    def test_subsumption_reverse_not_applied_in_cross_category(self):
        # Requirement: MySQL. EXPERIENCE fact only mentions SQL (the general parent).
        result = self._match_skill_with_exp("MySQL", "Worked with SQL databases")
        self.assertIn(result.status, {"NOT_OBSERVED", "UNCLEAR"},
                      f"SQL (parent) must not satisfy MySQL (child) requirement via cross-category subsumption. Got: {result.status}")


class AlgorithmVersioningRegressionTests(unittest.TestCase):
    """Phase 5C.5 — algorithm versioning (evidence-coverage-2) and regression checks."""

    def test_algorithm_version_is_evidence_coverage_2(self):
        from app.services.matching_engine import ALGORITHM_VERSION
        self.assertEqual(ALGORITHM_VERSION, "evidence-coverage-2")

    def test_match_response_reflects_evidence_coverage_2(self):
        req = MatchRequirement(id=1, type="SKILL", description="Java", mandatory=True)
        fact = MatchFact(id=1, type="SKILL", value="Java", evidence="Java", sourceSection="skills", reviewStatus="UNREVIEWED")
        response = match_requirements(MatchRequest(requirements=[req], facts=[fact]))
        self.assertEqual(response.algorithmVersion, "evidence-coverage-2")

    def test_batch_match_reflects_evidence_coverage_2(self):
        from app.models.intelligence import BatchMatchRequest, MatchCandidate, CandidateMatch
        req = MatchRequirement(id=1, type="SKILL", description="Java", mandatory=True)
        fact = MatchFact(id=1, type="SKILL", value="Java", evidence="Java", sourceSection="skills", reviewStatus="UNREVIEWED")
        batch_req = BatchMatchRequest(requirements=[req], candidates=[MatchCandidate(applicationId=101, facts=[fact])])
        match_res = match_requirements(MatchRequest(requirements=batch_req.requirements, facts=batch_req.candidates[0].facts))
        cand_match = CandidateMatch(applicationId=101, **match_res.model_dump())
        self.assertEqual(cand_match.algorithmVersion, "evidence-coverage-2")


if __name__ == "__main__":
    unittest.main()
