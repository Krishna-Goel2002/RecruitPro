import os
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient

from app.main import app


class InternalServiceAuthenticationTests(unittest.TestCase):
    def setUp(self):
        self.client = TestClient(app)
        self.payload = {"requirements": [], "candidates": []}

    @patch.dict(os.environ, {}, clear=False)
    def test_matching_fails_closed_when_token_is_not_configured(self):
        os.environ.pop("AI_SERVICE_TOKEN", None)
        response = self.client.post("/api/v1/match/batch", json=self.payload)
        self.assertEqual(response.status_code, 503)

    @patch.dict(os.environ, {"AI_SERVICE_TOKEN": "test-only-secret"})
    def test_matching_rejects_wrong_token_and_accepts_internal_token(self):
        denied = self.client.post("/api/v1/match/batch", json=self.payload, headers={"X-Internal-Service-Token": "wrong"})
        allowed = self.client.post("/api/v1/match/batch", json=self.payload, headers={"X-Internal-Service-Token": "test-only-secret"})
        self.assertEqual(denied.status_code, 401)
        self.assertEqual(allowed.status_code, 200)
        self.assertEqual(allowed.json(), {"candidates": []})


if __name__ == "__main__":
    unittest.main()
