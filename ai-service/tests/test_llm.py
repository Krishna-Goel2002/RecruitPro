"""LLM provider tests. httpx is mocked with MockTransport; nothing touches the network."""
import json
from dataclasses import replace

import httpx
import pytest
from fastapi.testclient import TestClient

from app.compile.requirements import compile_requirements
from app.core.config import ConfigError, load_settings, validate_settings
from app.generate.interview_kits import generate_interview_kit
from app.generate.work_samples import generate_work_sample
from app.llm.provider import AnthropicProvider, LLMError, OpenAICompatibleProvider, find_quote, get_provider, parse_json_object
from app.main import app
from app.models.authoring import CompileRequest, InterviewKitRequest, WorkSampleRequest
from app.pipeline import extract
from eval.render import make_pdf
from tests.helpers import TODAY, requirement

ANTHROPIC = replace(load_settings({}), llm_provider="anthropic", llm_model="test-model", anthropic_api_key="test-key",
                    soffice_path=None)
OPENAI = replace(load_settings({}), llm_provider="openai_compatible", llm_model="test-model",
                 openai_base_url="http://llm.internal/v1", openai_api_key="k", soffice_path=None)
JOB = CompileRequest(title="Accountant", description="Requirements:\n- 2+ years of experience in Tally\n- GST filing is required\n"
                                                     "Contact hr@example.test")


def anthropic_reply(payload, captured=None, status=200):
    def handler(request: httpx.Request) -> httpx.Response:
        if captured is not None:
            captured.append(request)
        text = payload if isinstance(payload, str) else json.dumps(payload)
        return httpx.Response(status, json={"content": [{"type": "text", "text": text}]})
    return AnthropicProvider(ANTHROPIC, transport=httpx.MockTransport(handler))


def openai_reply(payload, captured=None):
    def handler(request: httpx.Request) -> httpx.Response:
        if captured is not None:
            captured.append(request)
        return httpx.Response(200, json={"choices": [{"message": {"content": json.dumps(payload)}}]})
    return OpenAICompatibleProvider(OPENAI, transport=httpx.MockTransport(handler))


class TestProviderBasics:
    def test_configuration_requires_a_model(self):
        with pytest.raises(ConfigError):
            validate_settings(replace(ANTHROPIC, llm_model=None))
        with pytest.raises(ConfigError):
            validate_settings(replace(OPENAI, openai_base_url=None))
        assert get_provider(replace(ANTHROPIC, llm_model=None)) is None
        assert get_provider(load_settings({})) is None
        assert isinstance(get_provider(ANTHROPIC), AnthropicProvider)

    def test_service_refuses_to_start_when_misconfigured(self, monkeypatch):
        monkeypatch.setenv("LLM_PROVIDER", "anthropic")
        monkeypatch.delenv("LLM_MODEL", raising=False)
        with pytest.raises(ConfigError):
            with TestClient(app):
                pass

    def test_health_reports_the_provider(self, monkeypatch):
        for key, value in {"LLM_PROVIDER": "anthropic", "LLM_MODEL": "test-model", "ANTHROPIC_API_KEY": "k"}.items():
            monkeypatch.setenv(key, value)
        assert TestClient(app).get("/health").json()["llmProvider"] == "anthropic"

    def test_strict_json_and_verbatim_quotes(self):
        assert parse_json_object('```json\n{"a": 1}\n```') == {"a": 1}
        for bad in ("[1, 2]", "Sure! {\"a\": 1}", "not json"):
            with pytest.raises(LLMError):
                parse_json_object(bad)
        assert find_quote("Tally  ERP\n9", "Books kept in Tally ERP 9 daily") == (14, 25)
        assert find_quote("Tally Prime", "Books kept in Tally ERP 9 daily") is None

    def test_request_shapes(self):
        captured: list[httpx.Request] = []
        anthropic_reply({"ok": True}, captured).complete_json("system text", "user text")
        sent = captured[0]
        assert sent.url == "https://api.anthropic.com/v1/messages" and sent.headers["x-api-key"] == "test-key"
        assert sent.headers["anthropic-version"] == "2023-06-01"
        body = json.loads(sent.content)
        assert body["model"] == "test-model" and body["system"] == "system text" and body["temperature"] == 0
        openai_reply({"ok": True}, captured).complete_json("s", "u")
        assert str(captured[1].url) == "http://llm.internal/v1/chat/completions"
        assert json.loads(captured[1].content)["response_format"] == {"type": "json_object"}


class TestCompileWithLlm:
    def test_grounded_requirements_are_used_and_ungrounded_ones_dropped(self):
        provider = anthropic_reply({"requirements": [
            {"type": "SKILL", "description": "Tally", "mandatory": True, "knockout": True, "minimumYears": None,
             "alternatives": [], "proficiency": "INTERMEDIATE", "rationale": "Named in the requirements", "quote": "years of experience in Tally"},
            {"type": "SKILL", "description": "SAP", "mandatory": True, "quote": "SAP S/4HANA expert"},
            {"type": "AGE", "description": "Under 30", "quote": "Tally"},
        ]})
        result = compile_requirements(JOB, ANTHROPIC, provider)
        assert result.engine == "llm" and [r.description for r in result.requirements] == ["Tally"]
        assert result.requirements[0].knockout and "source:" in result.requirements[0].rationale

    def test_contacts_are_scrubbed_before_sending(self):
        captured: list[httpx.Request] = []
        compile_requirements(JOB, ANTHROPIC, anthropic_reply({"requirements": []}, captured))
        assert b"hr@example.test" not in captured[0].content and b"[email]" in captured[0].content

    @pytest.mark.parametrize("reply,status", [("not json at all", 200), ({"requirements": "nope"}, 200),
                                              ({"requirements": []}, 200), ({"error": "boom"}, 500)])
    def test_any_failure_falls_back_to_rules(self, reply, status):
        result = compile_requirements(JOB, ANTHROPIC, anthropic_reply(reply, status=status))
        assert result.engine == "rules" and result.requirements

    def test_timeouts_fall_back_to_rules(self):
        def timeout(request):
            raise httpx.ReadTimeout("slow", request=request)
        provider = AnthropicProvider(ANTHROPIC, transport=httpx.MockTransport(timeout))
        assert compile_requirements(JOB, ANTHROPIC, provider).engine == "rules"


class TestGeneratorsWithLlm:
    REQS = [requirement(1, "SKILL", "SQL"), requirement(2, "SKILL", "Tableau", mandatory=False)]
    ANCHORS = {"1": "poor", "2": "fair", "3": "good", "4": "excellent"}

    def test_interview_kit_from_an_openai_compatible_server(self):
        provider = openai_reply({"questions": [
            {"requirementId": 1, "question": "Walk me through a slow SQL query you fixed.", "followUps": ["What changed?"], "anchors": self.ANCHORS},
            {"requirementId": 99, "question": "Unknown requirement", "followUps": [], "anchors": self.ANCHORS},
            {"requirementId": 2, "question": "Missing anchors", "followUps": [], "anchors": {"1": "x"}},
        ]})
        kit = generate_interview_kit(InterviewKitRequest(jobTitle="Analyst", requirements=self.REQS), OPENAI, provider)
        assert kit.engine == "llm" and [(q.id, q.requirementId) for q in kit.questions] == [("q1", 1)]

    def test_invalid_work_sample_falls_back(self):
        provider = anthropic_reply({"title": "x", "instructions": "Do it", "timeLimitMinutes": 90,
                                    "rubric": [{"requirementId": 1, "criterion": "SQL", "anchors": self.ANCHORS}]})
        sample = generate_work_sample(WorkSampleRequest(jobTitle="Analyst", requirements=self.REQS), ANTHROPIC, provider)
        assert sample.engine == "rules"
        valid = anthropic_reply({"title": "Analyst task", "instructions": "Answer two questions", "timeLimitMinutes": 30,
                                 "rubric": [{"requirementId": 1, "criterion": "SQL accuracy", "anchors": self.ANCHORS}]})
        assert generate_work_sample(WorkSampleRequest(jobTitle="Analyst", requirements=self.REQS), ANTHROPIC, valid).engine == "llm"


class TestExtractionWithLlm:
    PDF = make_pdf(["Priya Sharma", "priya.sharma@example.test | +91 98765 43210", "Summary",
                    "Priya keeps the books in Busy accounting software and files returns.", "Skills", "Tally, GST"])

    def test_only_de_identified_text_is_sent_and_only_quoted_skills_are_added(self, monkeypatch):
        captured: list[httpx.Request] = []
        provider = anthropic_reply({"skills": [
            {"name": "Busy Accounting", "quote": "books in Busy accounting software"},
            {"name": "GST", "quote": "Tally, GST"},
            {"name": "SAP FICO", "quote": "Configured SAP FICO for clients"},
            {"name": "Leadership", "quote": "files returns"},
        ]}, captured)
        monkeypatch.setattr(extract, "get_provider", lambda settings: provider)
        result = extract.parse_resume("cv.pdf", self.PDF, settings=ANTHROPIC, today=TODAY)
        sent = captured[0].content.decode()
        for secret in ("Priya", "Sharma", "priya.sharma@example.test", "98765"):
            assert secret not in sent
        assert result.parserVersion == "rule-parser-2+llm"
        skills = {f.value: f for f in result.facts if f.type == "SKILL"}
        assert set(skills) == {"Tally", "GST", "Accounting", "Busy Accounting"}
        added = skills["Busy Accounting"]
        assert added.evidence == "books in Busy accounting software" and added.sourceSection == "summary"
        assert result.facts and added.spanStart is not None

    def test_llm_failure_keeps_the_rules_result(self, monkeypatch):
        monkeypatch.setattr(extract, "get_provider", lambda settings: anthropic_reply("garbage"))
        result = extract.parse_resume("cv.pdf", self.PDF, settings=ANTHROPIC, today=TODAY)
        assert result.parserVersion == "rule-parser-2" and {f.value for f in result.facts if f.type == "SKILL"} == {"Tally", "GST", "Accounting"}
