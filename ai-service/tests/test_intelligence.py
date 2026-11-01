import io
import unittest
import zipfile

from docx import Document

from app.models.intelligence import MatchFact, MatchRequest, MatchRequirement
from app.services.matching_engine import match_requirements
from app.services.resume_parser import DocumentParseError, parse_resume


def make_pdf(lines: list[str], scanned: bool = False) -> bytes:
    content_lines = ["BT /F1 12 Tf 72 720 Td"]
    for index, line in enumerate(lines):
        escaped = line.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
        if index:
            content_lines.append("0 -18 Td")
        content_lines.append(f"({escaped}) Tj")
    content_lines.append("ET")
    if scanned:
        content_lines.extend(["q 10 0 0 10 10 10 cm /Im0 Do Q"])
    stream = "\n".join(content_lines).encode("ascii")
    resources = "<< /Font << /F1 5 0 R >>" + (" /XObject << /Im0 6 0 R >>" if scanned else "") + " >>"
    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        (f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources {resources} /Contents 4 0 R >>").encode(),
        f"<< /Length {len(stream)} >>\nstream\n".encode() + stream + b"\nendstream",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    ]
    if scanned:
        objects.append(b"<< /Type /XObject /Subtype /Image /Width 1 /Height 1 /ColorSpace /DeviceRGB /BitsPerComponent 8 /Length 3 >>\nstream\n\x00\x00\x00\nendstream")
    pdf = bytearray(b"%PDF-1.4\n")
    offsets = [0]
    for index, obj in enumerate(objects, 1):
        offsets.append(len(pdf))
        pdf.extend(f"{index} 0 obj\n".encode())
        pdf.extend(obj)
        pdf.extend(b"\nendobj\n")
    xref = len(pdf)
    pdf.extend(f"xref\n0 {len(objects) + 1}\n0000000000 65535 f \n".encode())
    for offset in offsets[1:]:
        pdf.extend(f"{offset:010d} 00000 n \n".encode())
    pdf.extend(f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode())
    return bytes(pdf)


def make_docx(lines: list[tuple[str, str]]) -> bytes:
    document = Document()
    for line, style in lines:
        document.add_paragraph(line, style=style)
    output = io.BytesIO()
    document.save(output)
    return output.getvalue()


def make_image_only_docx() -> bytes:
    source = make_docx([])
    output = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(source)) as original, zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as scanned:
        for entry in original.infolist():
            scanned.writestr(entry, original.read(entry.filename))
        scanned.writestr("word/media/scanned-page.png", b"image placeholder")
    return output.getvalue()


class ResumeParsingTests(unittest.TestCase):
    def test_pdf_extracts_structured_skills_and_evidence(self):
        pdf = make_pdf(["Skills", "Java, Spring Boot, SQL", "Education", "BSc Computer Science", "Experience", "4 years building Java services"])
        result = parse_resume("candidate.pdf", pdf)
        self.assertEqual(result.status, "COMPLETED")
        self.assertEqual({fact.value for fact in result.facts if fact.type == "SKILL"}, {"Java", "Spring Boot", "SQL"})
        java = next(fact for fact in result.facts if fact.value == "Java")
        self.assertEqual(java.sourceReference, "page:1")
        self.assertIn("Java", java.evidence)
        self.assertEqual({fact.type for fact in result.facts}, {"SKILL", "EDUCATION", "EXPERIENCE"})

    def test_docx_extracts_contact_details_but_redacts_them_from_other_evidence(self):
        docx = make_docx([
            ("Skills", "Heading 1"),
            ("Java and Python · alex@example.test · +1 555 123 4567", "Normal"),
            ("Certifications", "Heading 1"),
            ("AWS Certified Developer", "Normal"),
            ("Experience", "Heading 1"),
            ("2020-2024 Software Engineer", "Normal"),
        ])
        result = parse_resume("candidate.docx", docx)
        self.assertEqual(result.status, "COMPLETED")
        self.assertTrue(any(fact.type == "EMAIL" and fact.value == "alex@example.test" for fact in result.facts))
        self.assertTrue(any(fact.type == "PHONE" for fact in result.facts))
        skill = next(fact for fact in result.facts if fact.type == "SKILL")
        self.assertNotIn("alex@example.test", skill.evidence)
        self.assertNotIn("555 123 4567", skill.evidence)
        experience = next(fact for fact in result.facts if fact.type == "EXPERIENCE")
        self.assertIn("2020-2024", experience.evidence)
        self.assertFalse(any(fact.type == "PHONE" and fact.normalizedValue == "20202024" for fact in result.facts))

    def test_scanned_pdf_requires_ocr_and_is_not_given_invented_facts(self):
        result = parse_resume("scan.pdf", make_pdf([], scanned=True))
        self.assertEqual(result.status, "NEEDS_OCR")
        self.assertEqual(result.errorCode, "SCANNED_PDF")
        self.assertEqual(result.facts, [])

    def test_image_only_docx_requires_ocr_and_is_not_reported_as_empty(self):
        result = parse_resume("scan.docx", make_image_only_docx())
        self.assertEqual(result.status, "NEEDS_OCR")
        self.assertEqual(result.errorCode, "SCANNED_DOCX")
        self.assertEqual(result.facts, [])

    def test_empty_malformed_oversized_and_unsupported_documents_are_explicit(self):
        with self.assertRaisesRegex(DocumentParseError, "MALFORMED_PDF"):
            parse_resume("broken.pdf", b"%PDF-1.4 not a document %%EOF")
        with self.assertRaisesRegex(DocumentParseError, "EMPTY_DOCUMENT"):
            parse_resume("empty.docx", make_docx([]))
        with self.assertRaisesRegex(DocumentParseError, "FILE_TOO_LARGE"):
            parse_resume("large.pdf", b"%PDF-" + (b"x" * (10 * 1024 * 1024)))
        with self.assertRaisesRegex(DocumentParseError, "UNSUPPORTED_FORMAT"):
            parse_resume("legacy.doc", b"\xd0\xcf\x11\xe0")


class MatchingTests(unittest.TestCase):
    def fact(self, ident: int, kind: str, value: str) -> MatchFact:
        return MatchFact(id=ident, type=kind, value=value, evidence=value, sourceSection="skills", reviewStatus="UNREVIEWED")

    def test_deterministic_weighted_score_and_missing_requirement_evidence(self):
        request = MatchRequest(
            requirements=[
                MatchRequirement(id=1, type="SKILL", description="Java and Spring Boot", mandatory=True),
                MatchRequirement(id=2, type="SKILL", description="AWS", mandatory=False),
            ],
            facts=[self.fact(1, "SKILL", "Java"), self.fact(2, "SKILL", "Spring Boot"), self.fact(3, "EMAIL", "alex@example.test")],
        )
        first = match_requirements(request)
        second = match_requirements(request)
        self.assertEqual(first, second)
        self.assertEqual(first.score, 66.7)
        self.assertEqual([item.status for item in first.requirements], ["MATCHED", "NOT_OBSERVED"])
        self.assertIn("Java", first.requirements[0].evidence)
        self.assertIn("Spring Boot", first.requirements[0].evidence)
        self.assertIn("not mean the candidate lacks", first.requirements[1].explanation)

    def test_nonmatching_resume_scores_zero_and_protected_other_requirements_are_not_scored(self):
        request = MatchRequest(
            requirements=[
                MatchRequirement(id=1, type="SKILL", description="Python", mandatory=True),
                MatchRequirement(id=2, type="OTHER", description="age 30", mandatory=True),
            ],
            facts=[self.fact(1, "SKILL", "Java"), self.fact(2, "EXPERIENCE", "age 30")],
        )
        result = match_requirements(request)
        self.assertEqual(result.score, 0.0)
        self.assertEqual([item.status for item in result.requirements], ["NOT_OBSERVED", "NOT_OBSERVED"])
        self.assertIn("human review", result.requirements[1].explanation)

    def test_partial_term_overlap_is_unclear_not_missing_or_confirmed(self):
        request = MatchRequest(
            requirements=[MatchRequirement(id=1, type="CERTIFICATION", description="AWS Certified Developer", mandatory=True)],
            facts=[self.fact(1, "CERTIFICATION", "AWS")],
        )
        result = match_requirements(request)
        self.assertEqual(result.requirements[0].status, "UNCLEAR")
        self.assertIsNotNone(result.requirements[0].evidence)

    def test_corrected_candidate_fact_is_labeled_as_reviewed_not_resume_evidence(self):
        corrected = MatchFact(id=1, type="SKILL", value="Python 3", evidence="Python 3",
                              sourceSection="candidate review", reviewStatus="CORRECTED")
        result = match_requirements(MatchRequest(
            requirements=[MatchRequirement(id=1, type="SKILL", description="Python", mandatory=True)],
            facts=[corrected],
        ))
        match = result.requirements[0]
        self.assertEqual(match.status, "MATCHED")
        self.assertEqual(match.provenance, "CANDIDATE_REVIEW")
        self.assertIn("Candidate correction: Python 3", match.evidence)
        self.assertIn("Candidate-reviewed information", match.explanation)
        self.assertNotIn("Resume evidence", match.explanation)

    def test_experience_react_vs_java_is_unclear_not_matched(self):
        req = MatchRequirement(id=1, type="EXPERIENCE", description="Java experience", mandatory=True, minimumYears=3)
        # Candidate with React: 3 years duration exists, but relevance to Java cannot be established
        react_candidate = match_requirements(MatchRequest(
            requirements=[req],
            facts=[MatchFact(id=1, type="EXPERIENCE", value="3 years of frontend React experience",
                             evidence="3 years of frontend React experience", sourceSection="experience", reviewStatus="UNREVIEWED")]
        ))
        self.assertEqual(react_candidate.requirements[0].status, "UNCLEAR")
        self.assertEqual(react_candidate.score, 0.0)
        self.assertIn("relevance to 'Java experience' was not observed", react_candidate.requirements[0].explanation)

        # Candidate with Java: duration and relevance match
        java_candidate = match_requirements(MatchRequest(
            requirements=[req],
            facts=[MatchFact(id=2, type="EXPERIENCE", value="3 years of Java experience",
                             evidence="3 years of Java experience", sourceSection="experience", reviewStatus="UNREVIEWED")]
        ))
        self.assertEqual(java_candidate.requirements[0].status, "MATCHED")
        self.assertEqual(java_candidate.score, 100.0)

    def test_experience_general_java_vs_enterprise_java_services_is_unclear(self):
        req = MatchRequirement(id=1, type="EXPERIENCE", description="enterprise Java services", mandatory=True, minimumYears=3)
        # Candidate with general Java (missing "enterprise", "services" domain scope)
        general_java = match_requirements(MatchRequest(
            requirements=[req],
            facts=[MatchFact(id=1, type="EXPERIENCE", value="3 years of Java experience",
                             evidence="3 years of Java experience", sourceSection="experience", reviewStatus="UNREVIEWED")]
        ))
        self.assertEqual(general_java.requirements[0].status, "UNCLEAR")
        self.assertEqual(general_java.score, 0.0)
        self.assertIn("partially established", general_java.requirements[0].explanation)

        # Candidate with full enterprise Java services
        enterprise_java = match_requirements(MatchRequest(
            requirements=[req],
            facts=[MatchFact(id=2, type="EXPERIENCE", value="4 years building enterprise Java services",
                             evidence="4 years building enterprise Java services", sourceSection="experience", reviewStatus="UNREVIEWED")]
        ))
        self.assertEqual(enterprise_java.requirements[0].status, "MATCHED")
        self.assertEqual(enterprise_java.score, 100.0)

    def test_experience_relevant_without_stated_duration_is_unclear(self):
        req = MatchRequirement(id=1, type="EXPERIENCE", description="enterprise Java services", mandatory=True, minimumYears=3)
        result = match_requirements(MatchRequest(
            requirements=[req],
            facts=[MatchFact(id=1, type="EXPERIENCE", value="Software engineer building enterprise Java services",
                             evidence="Software engineer building enterprise Java services", sourceSection="experience", reviewStatus="UNREVIEWED")]
        ))
        self.assertEqual(result.requirements[0].status, "UNCLEAR")
        self.assertEqual(result.score, 0.0)
        self.assertIn("could not be verified from an explicit year count", result.requirements[0].explanation)

    def test_experience_relevant_with_insufficient_duration_is_not_observed(self):
        req = MatchRequirement(id=1, type="EXPERIENCE", description="enterprise Java services", mandatory=True, minimumYears=3)
        result = match_requirements(MatchRequest(
            requirements=[req],
            facts=[MatchFact(id=1, type="EXPERIENCE", value="1 year building enterprise Java services",
                             evidence="1 year building enterprise Java services", sourceSection="experience", reviewStatus="UNREVIEWED")]
        ))
        self.assertEqual(result.requirements[0].status, "NOT_OBSERVED")
        self.assertEqual(result.score, 0.0)
        self.assertIn("shorter duration (1 year)", result.requirements[0].explanation)

    def test_experience_relevant_with_sufficient_duration_is_matched(self):
        req = MatchRequirement(id=1, type="EXPERIENCE", description="enterprise Java services", mandatory=True, minimumYears=3)
        result = match_requirements(MatchRequest(
            requirements=[req],
            facts=[MatchFact(id=1, type="EXPERIENCE", value="4 years building enterprise Java services",
                             evidence="4 years building enterprise Java services", sourceSection="experience", reviewStatus="UNREVIEWED")]
        ))
        self.assertEqual(result.requirements[0].status, "MATCHED")
        self.assertEqual(result.score, 100.0)
        self.assertIn("explicitly states a duration (4 years) meeting the minimum requirement (3 years)", result.requirements[0].explanation)

    def test_experience_candidate_reviewed_preserves_provenance(self):
        req = MatchRequirement(id=1, type="EXPERIENCE", description="enterprise Java services", mandatory=True, minimumYears=3)
        result = match_requirements(MatchRequest(
            requirements=[req],
            facts=[MatchFact(id=1, type="EXPERIENCE", value="4 years building enterprise Java services",
                             evidence="4 years building enterprise Java services", sourceSection="candidate review", reviewStatus="CORRECTED")]
        ))
        match = result.requirements[0]
        self.assertEqual(match.status, "MATCHED")
        self.assertEqual(match.provenance, "CANDIDATE_REVIEW")
        self.assertIn("Candidate correction: 4 years building enterprise Java services", match.evidence)
        self.assertIn("Candidate-reviewed information explicitly states a duration", match.explanation)
        self.assertNotIn("Resume evidence", match.explanation)


if __name__ == "__main__":
    unittest.main()
