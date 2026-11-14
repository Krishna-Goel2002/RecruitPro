import asyncio

import pytest
from fastapi.testclient import TestClient

from app.main import app
from eval.render import make_pdf
from tests.helpers import TOKEN

PARSE = "/api/v1/resume/parse"


def asgi_call(scope_headers: list[tuple[bytes, bytes]], body_chunks: list[bytes] | None = None, path: str = PARSE):
    """Drive the ASGI app directly; returns (status, received_count). Fails if the body is read when it must not be."""
    sent: list[dict] = []
    reads = 0

    async def receive():
        nonlocal reads
        if body_chunks is None:
            raise AssertionError("the request body must not be read")
        if reads < len(body_chunks):
            reads += 1
            return {"type": "http.request", "body": body_chunks[reads - 1], "more_body": reads < len(body_chunks)}
        return {"type": "http.disconnect"}

    async def send(message):
        sent.append(message)

    scope = {"type": "http", "asgi": {"version": "3.0"}, "http_version": "1.1", "method": "POST", "scheme": "http",
             "path": path, "raw_path": path.encode(), "query_string": b"", "root_path": "", "server": ("test", 80),
             "client": ("test", 1), "headers": scope_headers}
    asyncio.run(app(scope, receive, send))
    start = next(message for message in sent if message["type"] == "http.response.start")
    return start["status"], reads


class TestAuthentication:
    def test_health_is_public(self, token):
        body = TestClient(app).get("/health").json()
        assert body == {"status": "ok", "version": "2.0.0", "llmProvider": "none", "ocrAvailable": False}

    def test_fails_closed_without_configured_token(self, monkeypatch):
        monkeypatch.delenv("AI_SERVICE_TOKEN", raising=False)
        assert TestClient(app).post("/api/v1/match/batch", json={"requirements": [], "candidates": []}).status_code == 503

    def test_rejects_wrong_token_and_accepts_the_internal_token(self, token):
        client = TestClient(app)
        payload = {"requirements": [], "candidates": []}
        assert client.post("/api/v1/match/batch", json=payload, headers={"X-Internal-Service-Token": "wrong"}).status_code == 401
        allowed = client.post("/api/v1/match/batch", json=payload, headers={"X-Internal-Service-Token": token})
        assert allowed.status_code == 200 and allowed.json() == {"candidates": []}

    def test_unauthenticated_large_multipart_is_rejected_before_the_body_is_read(self, token):
        headers = [(b"content-type", b"multipart/form-data; boundary=x"), (b"content-length", str(50 * 1024 * 1024).encode())]
        assert asgi_call(headers) == (401, 0)
        assert asgi_call(headers + [(b"x-internal-service-token", b"nope")]) == (401, 0)

    def test_unauthenticated_upload_never_reaches_the_multipart_parser(self, token, monkeypatch):
        import starlette.formparsers

        def explode(*args, **kwargs):
            raise AssertionError("multipart parsing must not start")

        monkeypatch.setattr(starlette.formparsers.MultiPartParser, "parse", explode)
        response = TestClient(app).post(PARSE, files={"file": ("big.pdf", b"%PDF-" + b"x" * (12 * 1024 * 1024), "application/pdf")})
        assert response.status_code == 401

    def test_declared_oversized_body_is_refused_without_reading(self, token, monkeypatch):
        monkeypatch.setenv("MAX_UPLOAD_BYTES", "1000")
        headers = [(b"x-internal-service-token", TOKEN.encode()), (b"content-type", b"multipart/form-data; boundary=x"),
                   (b"content-length", str(10 * 1024 * 1024).encode())]
        assert asgi_call(headers) == (413, 0)

    def test_streamed_oversized_body_is_cut_off(self, token, monkeypatch):
        monkeypatch.setenv("MAX_UPLOAD_BYTES", "1000")
        headers = [(b"x-internal-service-token", TOKEN.encode()), (b"content-type", b"multipart/form-data; boundary=x")]
        body = (b'--x\r\nContent-Disposition: form-data; name="file"; filename="cv.pdf"\r\nContent-Type: application/pdf\r\n\r\n'
                + b"%PDF-" + b"x" * 200_000 + b"\r\n--x--\r\n")
        status, reads = asgi_call(headers, [body[i:i + 40_000] for i in range(0, len(body), 40_000)])
        assert status == 413 and reads <= 3


class TestEndpoints:
    def headers(self, token):
        return {"X-Internal-Service-Token": token}

    def test_parse_runs_in_the_process_pool(self, token):
        pdf = make_pdf(["Jane Doe", "Skills", "Java, SQL", "Experience", "Engineer, Acme Corp", "Jan 2020 - Present"])
        with TestClient(app) as client:
            response = client.post(PARSE, files={"file": ("cv.pdf", pdf, "application/pdf")}, headers=self.headers(token))
        body = response.json()
        assert response.status_code == 200 and body["status"] == "COMPLETED"
        assert {fact["value"] for fact in body["facts"] if fact["type"] == "SKILL"} == {"Java", "SQL"}
        assert body["timeline"]["roles"][0]["current"] is True and body["flags"] == []
        assert {"spanStart", "spanEnd", "attributes", "sourceReference"} <= set(body["facts"][0])

    def test_parse_limits_and_timeouts(self, token, monkeypatch):
        monkeypatch.setenv("MAX_UPLOAD_BYTES", "1000")
        client = TestClient(app)
        too_big = client.post(PARSE, files={"file": ("cv.pdf", b"%PDF-" + b"x" * 2000, "application/pdf")}, headers=self.headers(token))
        assert too_big.json()["errorCode"] == "FILE_TOO_LARGE"
        monkeypatch.setenv("MAX_UPLOAD_BYTES", str(10 * 1024 * 1024))
        monkeypatch.setenv("PARSE_TIMEOUT_SECONDS", "0.0001")
        slow = client.post(PARSE, files={"file": ("cv.pdf", make_pdf(["Skills", "Java"]), "application/pdf")}, headers=self.headers(token))
        assert (slow.json()["status"], slow.json()["errorCode"]) == ("FAILED", "PARSE_TIMEOUT")

    def test_match_and_batch(self, token):
        requirements = [{"id": 1, "type": "SKILL", "description": "AWS or Azure", "mandatory": True, "knockout": True}]
        facts = [{"id": "fact:1", "type": "SKILL", "value": "AWS", "tier": "RESUME_STATED"}]
        client = TestClient(app)
        single = client.post("/api/v1/match", json={"requirements": requirements, "facts": facts, "timeline": None},
                             headers=self.headers(token)).json()
        assert single["algorithmVersion"] == "evidence-ladder-3" and single["verifiedCoverage"] == 100.0
        assert single["knockoutsVerified"] is True and single["requirements"][0]["factIds"] == ["fact:1"]
        batch = client.post("/api/v1/match/batch", json={"requirements": requirements, "candidates": [
            {"applicationId": 5, "facts": facts}, {"applicationId": 6, "facts": []}]}, headers=self.headers(token)).json()
        assert [(c["applicationId"], c["verifiedCoverage"], c["unverifiedKnockouts"]) for c in batch["candidates"]] == [
            (5, 100.0, []), (6, 0.0, [1])]

    def test_invalid_payload_is_rejected(self, token):
        response = TestClient(app).post("/api/v1/match", json={"requirements": [{"id": 1, "type": "AGE", "description": "x",
                                                                                 "mandatory": True}], "facts": []},
                                        headers=self.headers(token))
        assert response.status_code == 422

    def test_authoring_endpoints(self, token):
        client = TestClient(app)
        compiled = client.post("/api/v1/requirements/compile", json={
            "title": "Accountant", "description": "Requirements:\n- 2+ years of experience in Tally and GST\n- B.Com required"},
            headers=self.headers(token)).json()
        assert compiled["engine"] == "rules" and {r["type"] for r in compiled["requirements"]} == {"EXPERIENCE", "EDUCATION"}
        requirement = {"id": 3, "type": "SKILL", "description": "Tally", "mandatory": True}
        sample = client.post("/api/v1/work-samples/generate", json={"jobTitle": "Accountant", "requirements": [requirement]},
                             headers=self.headers(token)).json()
        assert sample["engine"] == "rules" and sample["rubric"][0]["requirementId"] == 3
        kit = client.post("/api/v1/interview-kits/generate", json={"jobTitle": "Accountant", "requirements": [requirement],
                                                                   "focusRequirementIds": [3]}, headers=self.headers(token)).json()
        assert kit["engine"] == "rules" and set(kit["questions"][0]["anchors"]) == {"1", "2", "3", "4"}


@pytest.mark.parametrize("path", ["/api/v1/requirements/compile", "/api/v1/work-samples/generate", "/api/v1/interview-kits/generate"])
def test_authoring_endpoints_are_protected(token, path):
    assert TestClient(app).post(path, json={}).status_code == 401
