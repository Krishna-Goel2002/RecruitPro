"""Regression tests for the ResumeLab findings (docs/audit/RESUMELAB_PARSER_FINDINGS.md, "Fix pass").

Each test uses a small inline document; none reads the ResumeLab corpus. All names are synthetic.
"""
from __future__ import annotations

from dataclasses import replace

from app.compile.requirements import compile_rules
from app.core.config import load_settings
from app.matching.engine import match_requirements, skill_cnf
from app.matching.occupations import normalise_title, practice
from app.matching.preferences import split_preference
from app.models.authoring import CompileRequest
from app.models.matching import MatchFact, MatchRequest
from app.pipeline.education import detect_degree, is_school_line
from app.pipeline.extract import analyse_text, parse_resume, timeline_model
from app.pipeline.sections import classify_heading
from app.pipeline.text import RawLine, strip_page_furniture
from eval.render import PdfText, make_pdf
from tests.helpers import TODAY, fact, request, requirement

OFFLINE = replace(load_settings(), llm_provider="none", soffice_path=None, ocr_enabled=False)


def analyse(lines):
    return analyse_text(lines, today=TODAY)


def kinds(lines):
    return [(section.kind, [line.text for line in section.lines]) for section in analyse(lines).sections]


def roles(lines):
    timeline = analyse(lines).timeline
    return [(role.title, role.employer, role.start_date, role.end_date) for role in (timeline.roles if timeline else [])]


def skills(lines):
    return {str(f.attributes.get("skillId")) for f in analyse(lines).facts if f.type == "SKILL" and "skillId" in f.attributes}


def facts_of(lines, kind):
    return [f for f in analyse(lines).facts if f.type == kind]


def match_resume(lines, requirements):
    analysis = analyse(lines)
    facts = [MatchFact(id=f"fact:{i}", type=f.type, value=f.value, evidence=f.evidence, sourceSection=f.sourceSection,
                       tier="RESUME_STATED", attributes=f.attributes)
             for i, f in enumerate(analysis.facts) if f.type not in {"EMAIL", "PHONE", "LINK"}]
    return match_requirements(MatchRequest(requirements=requirements, facts=facts,
                                           timeline=timeline_model(analysis.timeline)), today=TODAY)


# ----------------------------------------------------------------------------------- W1 headings
class TestHeadings:
    def test_professional_journey_keeps_its_roles(self):
        lines = ["Asha Verma", "Profile", "Sales professional.", "PROFESSIONAL JOURNEY",
                 "Key Account Manager, Bharat Freight Lines | Aug 2024 - Present", "- Managed 12 key accounts",
                 "Inside Sales Representative, Bharat Freight Lines | Jul 2023 - Jul 2024", "Skills", "Negotiation"]
        assert roles(lines) == [("Key Account Manager", "Bharat Freight Lines", "2024-08", None),
                                ("Inside Sales Representative", "Bharat Freight Lines", "2023-07", "2024-07")]

    def test_classifier_reads_unseen_headings_by_head_noun(self):
        assert classify_heading("Professional Journey") == "EXPERIENCE"
        assert classify_heading("Internship & Training") == "EXPERIENCE"
        assert classify_heading("Industrial Training") == "EXPERIENCE"
        assert classify_heading("Career Goal") == "SUMMARY"
        assert classify_heading("Key Strengths") == "SUMMARY"
        assert classify_heading("Conference Presentations") == "PUBLICATIONS"
        assert classify_heading("Courses & Certifications") == "CERTIFICATIONS"
        assert classify_heading("Employment Details") == "EXPERIENCE"
        assert classify_heading("Academic Background") == "EDUCATION"
        assert classify_heading("Declaration") == "DECLARATION"

    def test_bold_employers_skills_and_degrees_are_not_headings(self):
        assert classify_heading("Paywise Technologies") is None  # an employer in bold, not "Technologies"
        assert classify_heading("Beyond Work") is None
        sections = kinds(["Jane Roe", "Skills", "Python", "Experience", "Paywise Technologies",
                          "ML Engineer | 10/2020 to 04/2021", "- Built a ticket router", "Education",
                          "Bachelor of Science in Nursing", "Riverbend University (2023)"])
        assert [kind for kind, _ in sections] == ["HEADER", "SKILLS", "EXPERIENCE", "EDUCATION"]

    def test_declaration_yields_no_facts(self):
        lines = ["Jane Roe", "Declaration", "I hereby declare that I know Python and SQL.", "Skills", "Excel"]
        assert ("DECLARATION", ["I hereby declare that I know Python and SQL."]) in kinds(lines)
        assert skills(lines) == {"excel"}

    def test_unknown_heading_over_dated_role_headers_is_a_work_history(self):
        lines = ["Jane Roe", "Skills", "SQL", "WHERE I MADE AN IMPACT", "Data Analyst, Acme Analytics | Jan 2021 - Dec 2022",
                 "- Built dashboards", "Junior Analyst, Beta Retail | Jun 2019 - Dec 2020", "- Cleaned data"]
        assert [role[0] for role in roles(lines)] == ["Data Analyst", "Junior Analyst"]

    def test_table_row_label_starts_a_section_and_wrapped_headings_join(self):
        lines = ["Hala Haddad", "TECHNICAL SKILLS | dialysis, IV therapy, wound care", "EDUCATION", "B.Sc Nursing (2023)",
                 "COURSES & CERTIFICATIONS | Registered Nurse and Registered Midwife (RNRM), State Nursing Council, 2023"]
        assert {"dialysis", "iv-therapy", "wound-care"} <= skills(lines)
        assert [f.value for f in facts_of(lines, "CERTIFICATION")] == [
            "Registered Nurse and Registered Midwife (RNRM), State Nursing Council, 2023"]
        wrapped = ["Jane Roe", "Experience", "Analyst, Acme | 2020 - 2022", "CERTIFICATIONS &", "TRAININGS",
                   "- Microsoft Certified: Power BI", "Data Analyst Associate (PL-300)"]
        assert [f.value for f in facts_of(wrapped, "CERTIFICATION")] == [
            "Microsoft Certified: Power BI Data Analyst Associate (PL-300)"]


# --------------------------------------------------------------------------------- roles (W1/W3/W4)
class TestRoleSegmentation:
    def test_key_value_role_blocks(self):
        lines = ["CURRICULUM VITAE", "Work Experience", "Organisation : Zentrix SaaS Pvt. Ltd., Remote",
                 "Designation : Territory Sales Manager", "Duration : May 2025 - Aug 2026", "Roles & Responsibilities:",
                 "- Closed INR 1.2 Cr in new ARR"]
        assert roles(lines) == [("Territory Sales Manager", "Zentrix SaaS Pvt. Ltd.", "2025-05", "2026-08")]

    def test_title_on_the_line_after_employer_and_dates(self):
        lines = ["Jane Roe", "Experience", "Northgate Software GmbH, Kozhikode Dec 2025 - Present", "Senior Java Developer",
                 "- Built REST APIs", "Northgate Software GmbH Jul 2023 - Nov 2025", "Backend Developer", "- Tuned MySQL"]
        assert [(r[0], r[1]) for r in roles(lines)] == [("Senior Java Developer", "Northgate Software GmbH"),
                                                        ("Backend Developer", "Northgate Software GmbH")]

    def test_date_first_layout_never_borrows_the_previous_roles_tools_line(self):
        lines = ["Jane Roe", "Employment History", "Jul 2024 to till date", "SDE-2 | Cloudmint Software",
                 "Tools & Technologies: Spring Boot, Hibernate", "May 2023 to Jun 2024",
                 "Associate Software Engineer | Cloudmint Software"]
        assert [r[0] for r in roles(lines)] == ["SDE-2", "Associate Software Engineer"]

    def test_employer_line_above_a_title_only_anchor(self):
        lines = ["Jane Roe", "Experience", "Little Acorns Montessori | Pune", "Vice Principal | Jan'26 - Ongoing",
                 "- Led 40 teachers"]
        assert roles(lines) == [("Vice Principal", "Little Acorns Montessori", "2026-01", None)]

    def test_registered_nurse_is_a_title_not_a_verb(self):
        lines = ["Jane Roe", "Experience", "Ward Nurse - Riverbend Medical Centre", "2025-02 - Till date", "- Triage",
                 "Registered Nurse - Harbourview General Hospital, Doha", "2024-04 - 2024-11", "- ICU care"]
        assert [r[0] for r in roles(lines)] == ["Ward Nurse", "Registered Nurse"]

    def test_career_breaks_and_project_blocks_are_not_roles(self):
        lines = ["Jane Roe", "Experience", "Account Executive, Cloudmint | Apr 2024 - Jun 2025",
                 "Career break - family business (Jul 2025 - Apr 2026)", "Senior Java Developer | Trident Ltd | Nov 2024 - Present",
                 "Project : Merchant Signup", "Client : A European bank", "Duration : Dec 2024 - Present",
                 "Role : Senior Java Developer"]
        assert [(r[0], r[2]) for r in roles(lines)] == [("Account Executive", "2024-04"), ("Senior Java Developer", "2024-11")]

    def test_internships_are_experience_with_a_flag(self):
        lines = ["Jane Roe", "Internships", "Business Development Intern, Gulmohar Beverages | Dec 2023 - Jan 2024",
                 "Work Experience", "Account Executive, Cloudmint | Apr 2024 - Jun 2025"]
        experience = facts_of(lines, "EXPERIENCE")
        assert [f.attributes.get("internship") for f in experience] == [True, None]
        assert analyse(lines).timeline.total_months == 2 + 15

    def test_rank_and_function_titles_stay_whole(self):
        lines = ["Jane Roe", "Experience", "VP - Product | Ledgerline Financial | Mar 2024 - Present"]
        assert roles(lines)[0][:2] == ("VP - Product", "Ledgerline Financial")


# ------------------------------------------------------------------------------- identity (W3/W10)
class TestIdentityLeaks:
    def test_a_name_outside_the_header_block_is_never_a_skill(self):
        lines = ["CONTACT", "neha_tiwari99@example.com", "SKILLS", "Cold Calling", "HubSpot", "Neha Tiwari",
                 "Key Account Manager", "PROFILE SUMMARY", "Sales executive."]
        analysis = analyse(lines)
        text = " ".join(f"{f.value} {f.evidence}" for f in analysis.facts if f.type not in {"EMAIL", "PHONE", "LINK"})
        assert "Neha" not in text and "Tiwari" not in text

    def test_a_name_wrapped_one_word_per_line_is_redacted(self):
        lines = ["Experience", "Associate Product Manager | Jul 2026 - Aug 2026", "Acme Fintech Pvt. Ltd.",
                 "- Owned the roadmap", "Khushi", "Irani", "khushi.irani@example.com"]
        evidence = " ".join(f.evidence for f in analyse(lines).facts if f.type == "EXPERIENCE")
        assert "Khushi" not in evidence and "Irani" not in evidence

    def test_running_footers_and_page_numbers_are_dropped(self):
        raw = [RawLine("Meghana Prasad", "page:1"), RawLine("Certifications", "page:1"), RawLine("AWS Certified Developer", "page:1"),
               RawLine("Meghana Prasad - Resume | 1/2", "page:1"), RawLine("Projects", "page:2"), RawLine("Page 2 of 2", "page:2")]
        assert [line.text for line in strip_page_furniture(raw)] == [
            "Meghana Prasad", "Certifications", "AWS Certified Developer", "Projects"]

    def test_sidebar_pdf_reads_the_main_column_first(self):
        sidebar = [PdfText("CONTACT", x=20, y=740, size=10, bold=True), PdfText("tsidhu@example.test", x=20, y=724, size=8),
                   PdfText("SKILL SET", x=20, y=690, size=10, bold=True)] + [
            PdfText(item, x=20, y=674 - 12 * i, size=8) for i, item in enumerate(["Python", "SQL", "Spark", "Git", "TensorFlow", "NLP"])] + [
            PdfText("EDUCATION", x=20, y=580, size=10, bold=True), PdfText("B.Tech, Computer Science", x=20, y=566, size=8)]
        main = [PdfText("Tejinder Sidhu", x=220, y=740, size=20, bold=True), PdfText("Research Intern", x=220, y=716, size=11),
                PdfText("EXPERIENCE", x=220, y=690, size=11, bold=True),
                PdfText("Research Intern, Basketful Retail Pvt. Ltd. | Aug 2026 - Nov 2026", x=220, y=672, size=9),
                PdfText("- Developed an NLP pipeline for support-ticket routing with Python", x=220, y=658, size=9),
                PdfText("- Engineered 300 features from transaction data in SQL and Spark", x=220, y=644, size=9),
                PdfText("Research Intern, Ladder Learning Pvt. Ltd. | Dec 2025 - Jan 2026", x=220, y=622, size=9),
                PdfText("- Ran A/B experiments for the pricing team and measured the uplift", x=220, y=608, size=9)]
        result = parse_resume("sidebar.pdf", make_pdf(sidebar + main), settings=OFFLINE, today=TODAY)
        assert "TWO_COLUMN_LAYOUT" in {flag.code for flag in result.flags}
        assert [(r.title, r.startDate) for r in result.timeline.roles] == [("Research Intern", "2026-08"), ("Research Intern", "2025-12")]
        assert all("Sidhu" not in f.value + f.evidence for f in result.facts if f.type not in {"EMAIL", "PHONE", "LINK"})

    def test_label_value_table_and_right_aligned_dates_are_not_columns(self):
        rows = [("Father's Name", "Mr. Rajendra Ahmed"), ("Date of Birth", "17.01.1994"), ("Gender", "Male"),
                ("Marital Status", "Married"), ("Nationality", "Indian"), ("Languages Known", "English, Hindi, Urdu")]
        page = [PdfText("PERSONAL INFORMATION", x=236, y=700, size=11, bold=True)]
        for i, (label, value) in enumerate(rows):
            page += [PdfText(label, x=46, y=680 - 15 * i, size=9), PdfText(":", x=192, y=680 - 15 * i, size=9),
                     PdfText(value, x=202, y=680 - 15 * i, size=9)]
        page += [PdfText(f"Built and shipped feature number {i} with Java and Spring Boot for retail clients", x=46, y=560 - 14 * i, size=9)
                 for i in range(4)]
        result = parse_resume("table.pdf", make_pdf(page), settings=OFFLINE, today=TODAY)
        assert "TWO_COLUMN_LAYOUT" not in {flag.code for flag in result.flags}
        assert {"english", "hindi", "urdu"} <= {str(f.attributes.get("skillId")) for f in result.facts if f.type == "SKILL"}
        dated = [PdfText("EXPERIENCE", x=72, y=720, size=12, bold=True)]
        for i, (title, dates) in enumerate([("Lead Product Manager, Gyanpath Edtech", "Dec-2025 - Till date"),
                                            ("Product Manager, Gyanpath Edtech", "Aug-2023 - Nov-2025"),
                                            ("Product Manager, Ledgerline Financial", "Sep-2015 - Jul-2023"),
                                            ("Associate Product Manager, Kitecraft", "Jun-2014 - Aug-2015")]):
            y = 700 - 60 * i
            dated += [PdfText(title, x=36, y=y, size=9), PdfText(dates, x=480, y=y, size=8.6)] + [
                PdfText(f"- Wrote PRDs and user stories for revenue line {j} with six stakeholders", x=46, y=y - 14 * (j + 1), size=9)
                for j in range(2)]
        result = parse_resume("dates.pdf", make_pdf(dated), settings=OFFLINE, today=TODAY)
        assert [r.startDate for r in result.timeline.roles] == ["2025-12", "2023-08", "2015-09", "2014-06"]

    def test_text_running_past_the_page_edge_is_not_hidden_text(self):
        page = [PdfText("Priya Sharma", x=72, y=740, size=18), PdfText("Skills", x=72, y=700, size=12, bold=True),
                PdfText("Python, SQL", x=72, y=684),
                PdfText("github.com/a-very-long-profile-handle-that-runs-past-the-right-edge-of-the-page", x=330, y=660, size=11)]
        result = parse_resume("overflow.pdf", make_pdf(page), settings=OFFLINE, today=TODAY)
        assert "HIDDEN_TEXT" not in {flag.code for flag in result.flags}


# ------------------------------------------------------------------------------- skills (W5/W8/W9)
class TestSkillExtraction:
    def test_languages_known_inside_personal_details(self):
        lines = ["Jane Roe", "Skills", "Excel", "Personal Details", "Father's Name : R. Roe", "Languages Known : English, Hindi",
                 "Marital Status : Single"]
        assert skills(lines) == {"excel", "english", "hindi"}

    def test_rating_glyphs_end_list_items(self):
        assert {"english", "hindi", "bengali"} <= skills(["Jane Roe", "Languages", "English ●●●●○", "Hindi ●●●○○",
                                                          "Bengali ●●○○○"])
        assert {"english"} <= skills(["Jane Roe", "Education", "B.E. - IT | 2020", "English | ●●●○○"])

    def test_coordination_ellipsis(self):
        assert {"field-sales", "inside-sales"} <= skills(["Jane Roe", "Summary", "3+ years in field and inside sales."])
        assert [[skill.id for skill in group] for group in skill_cnf("B2B or SaaS sales")] == [["b2b-sales", "saas-sales"]]
        assert [[skill.id for skill in group] for group in skill_cnf("Java and Spring Boot")] == [["java"], ["spring-boot"]]

    def test_verb_and_phrase_aliases(self):
        found = skills(["Jane Roe", "Experience", "Teacher, Delhi School | 2020 - 2023",
                        "- Delivered online classes during school closures", "- Designed and analysed an A/B test for pricing"])
        assert {"online-teaching", "a-b-testing"} <= found

    def test_labelled_lists_inside_roles_accept_ambiguous_names(self):
        lines = ["Jane Roe", "Experience", "Operations Executive, Acme | 2020 - 2023",
                 "Areas handled: Fleet Management, Oracle, Excel, SAP MM"]
        assert {"oracle", "excel", "sap-mm"} <= skills(lines)

    def test_employer_issuer_and_contact_labels_are_not_skills(self):
        lines = ["Jane Roe", "Github: github.com/janeroe", "Experience",
                 "Lead Product Manager, Gyanpath Edtech, Mysuru | Dec 2025 - Present",
                 "Account Executive, Northstar Logistics Pvt. Ltd. | 2023 - 2025", "Certifications",
                 "Certified Kubernetes Administrator (CKA) - The Linux Foundation (2023)"]
        found = skills(lines)
        assert not found & {"educational-technology", "logistics", "linux", "github"}
        assert "kubernetes" in found

    def test_name_tokens_are_not_unknown_skills(self):
        lines = ["CONTACT", "tsidhu@example.test", "SKILL SET", "Python", "Tejinder Sidhu", "Research Intern"]
        values = {f.value for f in analyse(lines).facts if f.type == "SKILL"}
        assert "Tejinder Sidhu" not in values


# ------------------------------------------------------------------------------------- education (W7)
class TestQualifications:
    def test_professional_qualifications(self):
        assert detect_degree("CA (ICAI) | 2019")[:2] == ("MASTER", "commerce")
        assert detect_degree("Chartered Accountant, ICAI, 2018")[0] == "MASTER"
        assert detect_degree("Company Secretary (ICSI)")[:2] == ("MASTER", "law")
        assert detect_degree("Cost and Management Accountancy")[0] == "MASTER"
        assert detect_degree("ICWA")[0] == "MASTER"
        assert detect_degree("CFA Charterholder")[0] == "MASTER"
        for partial in ("CA Inter (cleared)", "CS Executive", "CFA Level II candidate", "San Jose, CA | 2019"):
            assert detect_degree(partial) is None, partial
        assert detect_degree("B.Tech CS")[0] == "BACHELOR"

    def test_more_indian_degrees_and_school_lines(self):
        assert detect_degree("BHM (Hotel Management) | 2026")[:2] == ("BACHELOR", "hospitality")
        assert detect_degree("M.Stat in Statistics")[:2] == ("MASTER", "mathematics")
        assert detect_degree("B.E., Information Technology")[0] == "BACHELOR"
        assert detect_degree("Master of Social Work")[:2] == ("MASTER", "humanities")
        for school in ("10+2 (PCM), CBSE", "ISC | Sunrise School | 2022", "SSLC, Karnataka Board", "PUC, 2018"):
            assert is_school_line(school), school

    def test_a_degree_in_progress_does_not_meet_a_degree_requirement(self):
        lines = ["Jane Roe", "Education", "B.Tech in Computer Science (2024 - 2028) expected"]
        edu = facts_of(lines, "EDUCATION")[0]
        assert edu.attributes["inProgress"] is True
        result = match_resume(lines, [requirement(1, "EDUCATION", "Bachelor's degree in Computer Science")])
        assert result.requirements[0].status == "UNCLEAR" and "in progress" in result.requirements[0].explanation


# --------------------------------------------------------------------------------- preferences (W6)
class TestPreferences:
    def test_split_preference(self):
        assert split_preference("Bachelor's degree; MBA preferred") == ("Bachelor's degree", ["MBA"])
        assert split_preference("B.Tech, preferably in Computer Science") == ("B.Tech", ["Computer Science"])
        assert split_preference("Bachelor's degree (MBA is a plus)") == ("Bachelor's degree", ["MBA"])
        assert split_preference("MBA preferred") == ("MBA preferred", [])
        assert split_preference("B.Sc. Nursing or GNM") == ("B.Sc. Nursing or GNM", [])

    def test_matcher_does_not_require_the_preferred_part(self):
        facts = [fact("fact:1", "EDUCATION", "B.A. English, Delhi University, 2015", section="education",
                      attributes={"degreeLevel": "BACHELOR", "field": "english"})]
        result = match_requirements(request([requirement(1, "EDUCATION", "Bachelor's degree; MBA in Operations preferred")], facts),
                                    today=TODAY)
        assert result.requirements[0].status == "MATCHED"

    def test_compiler_splits_required_and_preferred(self):
        compiled = compile_rules(CompileRequest(title="PM", description="Requirements:\n- Bachelor's degree, MBA preferred"))
        assert [(r.type, r.description, r.mandatory) for r in compiled.requirements] == [
            ("EDUCATION", "Bachelor's degree", True), ("EDUCATION", "MBA", False)]


# ------------------------------------------------------------------------------- experience (W2)
class TestExperienceRelevance:
    def test_title_normalisation(self):
        assert normalise_title("Sr. Software Engineer II") == "software engineer"
        assert normalise_title("SDE-2") == "sde"

    def test_titles_imply_practices_with_corroboration(self):
        assert "backend-development" in practice("SDE-2", frozenset({"java"})).skills
        assert "backend-development" not in practice("Software Engineer", frozenset()).skills
        assert "b2b-sales" in practice("Key Account Manager").skills
        assert "b2b-sales" not in practice("Inside Sales Executive").skills  # B2C in much of retail and edtech
        assert "b2b-sales" in practice("Inside Sales Executive", frozenset({"hubspot"})).skills
        assert {"nursing-care", "patient-care"} <= practice("Staff Nurse").skills
        assert "teaching" in practice("PGT Mathematics").skills
        assert "internal-audit" not in practice("Audit Associate").skills  # statutory or internal: the block says

    def test_backend_experience_from_sde_titles(self):
        lines = ["Jane Roe", "Experience", "Senior Software Engineer, Northgate | Mar 2026 - Present",
                 "- Built Spring Boot services in Java", "SDE-2, Northgate | Apr 2023 - Feb 2026", "- Tuned MySQL queries"]
        result = match_resume(lines, [requirement(1, "EXPERIENCE", "3+ years: professional backend development experience",
                                                  years=3)])
        assert result.requirements[0].status == "MATCHED"

    def test_b2b_sales_from_account_titles_and_clinical_nursing_from_nurse_titles(self):
        sales = ["Jane Roe", "Experience", "Key Account Manager, Bharat Freight | Aug 2024 - Present",
                 "Account Executive, Skyline Telecom | Jul 2023 - Jul 2024"]
        assert match_resume(sales, [requirement(1, "EXPERIENCE", "1+ years: B2B sales experience", years=1)]
                            ).requirements[0].status == "MATCHED"
        nurse = ["Jane Roe", "Experience", "Staff Nurse, Apollo Hospital | Jan 2022 - Present", "- Ward care"]
        assert match_resume(nurse, [requirement(1, "EXPERIENCE", "2+ years: clinical nursing experience", years=2)]
                            ).requirements[0].status == "MATCHED"

    def test_a_title_never_becomes_skill_evidence(self):
        lines = ["Jane Roe", "Experience", "Key Account Manager, Bharat Freight | Aug 2020 - Present", "- Managed accounts"]
        assert match_resume(lines, [requirement(1, "SKILL", "B2B Sales")]).requirements[0].status != "MATCHED"

    def test_skill_less_alternatives_need_a_title_not_a_bullet(self):
        requirement_ = requirement(1, "EXPERIENCE", "3+ years: supply chain or operations experience", years=3)
        ops = ["Jane Roe", "Experience", "Operations Executive, Acme Retail | Jan 2020 - Present", "- Ran dispatch"]
        assert match_resume(ops, [requirement_]).requirements[0].status == "MATCHED"
        pm = ["Jane Roe", "Experience", "Product Manager, Acme | Jan 2015 - Present",
              "- Led the launch of UPI autopay with legal, risk and operations teams"]
        assert match_resume(pm, [requirement_]).requirements[0].status != "MATCHED"

    def test_words_scattered_over_bullets_are_not_the_practice(self):
        requirement_ = requirement(1, "EXPERIENCE", "3+ years: product or UX design experience", years=3)
        pm = ["Jane Roe", "Experience", "Product Manager, Acme | Jan 2018 - Present",
              "- Designed the product onboarding flow with the design team"]
        assert match_resume(pm, [requirement_]).requirements[0].status != "MATCHED"
        ux = ["Jane Roe", "Experience", "UX Designer, Acme | Jan 2021 - Present", "- Ran usability tests"]
        assert match_resume(ux, [requirement_]).requirements[0].status == "MATCHED"
