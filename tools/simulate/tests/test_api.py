import httpx
import pytest

from simulate.api import (ApiError, ApiSession, ApiStats, RateGate, RetryPolicy, parse_retry_after, totp)
from simulate.clock import FakeClock


def make_session(handler, clock=None, pacing=True, **kw):
    clock = clock or FakeClock()
    stats = ApiStats()
    client = httpx.Client(base_url="http://test/api/v1/", transport=httpx.MockTransport(handler))
    gate = RateGate(clock, enabled=pacing, stats=stats)
    return ApiSession(client, portal="candidate", gate=gate, clock=clock, stats=stats,
                      retry=RetryPolicy(max_attempts=4), **kw), clock, stats


def envelope(data, status=200):
    return httpx.Response(status, json={"success": True, "message": "ok", "data": data})


def test_rate_gate_paces_register_to_backend_limit():
    clock = FakeClock()
    gate = RateGate(clock)
    start = clock.now()
    for _ in range(5):
        gate.acquire("auth/register")
    assert clock.now() == start  # first 5 within the window are free
    gate.acquire("auth/register")
    assert clock.now() - start >= 60  # sixth waits for the 1-minute window
    disabled = RateGate(clock, enabled=False)
    t = clock.now()
    for _ in range(50):
        disabled.acquire("auth/register")
    assert clock.now() == t


def test_rate_gate_route_keys():
    assert RateGate.key_for("POST", "/auth/login") == "auth/login"
    assert RateGate.key_for("POST", "auth/verify-email") == "auth/*"
    assert RateGate.key_for("DELETE", "candidates/me") == "DELETE candidates/me"
    assert RateGate.key_for("PUT", "candidates/me") is None
    assert RateGate.key_for("GET", "jobs") is None


def test_parse_retry_after_seconds_and_date():
    clock = FakeClock()
    assert parse_retry_after("17", clock) == 17
    assert parse_retry_after(None, clock) is None
    assert parse_retry_after("garbage", clock) is None
    import email.utils
    date = email.utils.formatdate(clock.wall() + 30, usegmt=True)
    assert 28 <= parse_retry_after(date, clock) <= 31


def test_429_honours_retry_after_then_succeeds():
    calls = []

    def handler(request):
        calls.append(request)
        if len(calls) < 3:
            return httpx.Response(429, json={"success": False, "message": "Too many"}, headers={"Retry-After": "7"})
        return envelope({"accessToken": "t"})

    session, clock, stats = make_session(handler, pacing=False)
    t0 = clock.now()
    data = session.request("POST", "auth/login", json={}, auth=False)
    assert data == {"accessToken": "t"}
    assert stats.retries_429 == 2 and clock.now() - t0 >= 14
    assert calls[0].headers["X-RecruitPro-Portal"] == "candidate"


def test_429_on_unpaced_route_sleeps_too():
    n = {"i": 0}

    def handler(request):
        n["i"] += 1
        if n["i"] == 1:
            return httpx.Response(429, headers={"Retry-After": "3"})
        return envelope([])

    session, clock, stats = make_session(handler)
    t0 = clock.now()
    session.request("GET", "jobs")
    assert clock.now() - t0 >= 3


def test_5xx_and_transport_errors_retry_then_give_up():
    def flaky(request):
        raise httpx.ConnectError("refused", request=request)

    session, _, stats = make_session(flaky)
    with pytest.raises(ApiError) as exc:
        session.request("GET", "jobs")
    assert exc.value.status is None and stats.retries_transport == 3

    def down(request):
        return httpx.Response(503, text="down")

    session, _, stats = make_session(down)
    with pytest.raises(ApiError) as exc:
        session.request("GET", "jobs")
    assert exc.value.status == 503 and stats.retries_5xx == 3


def test_401_signs_in_again_once():
    state = {"token": "old", "logins": 0}

    def handler(request):
        if request.url.path.endswith("/auth/login"):
            state["logins"] += 1
            state["token"] = f"new{state['logins']}"
            return envelope({"accessToken": state["token"]})
        if request.headers.get("Authorization") != f"Bearer {state['token']}":
            return httpx.Response(401, json={"success": False, "message": "expired"})
        return envelope({"ok": True})

    session, _, stats = make_session(handler, email="a@b.test", password="Secret12345")
    session.token = "stale"
    assert session.request("GET", "candidates/me") == {"ok": True}
    assert stats.reauth == 1 and state["logins"] == 1


def test_error_message_includes_field_errors():
    def handler(request):
        return httpx.Response(400, json={"success": False, "message": "Validation failed",
                                         "errors": [{"field": "email", "message": "must be valid"}]})

    session, _, _ = make_session(handler)
    with pytest.raises(ApiError) as exc:
        session.request("POST", "auth/register", json={}, auth=False)
    assert exc.value.status == 400 and "email: must be valid" in exc.value.message


def test_mfa_login_with_totp_secret():
    secret = "JBSWY3DPEHPK3PXP"
    clock = FakeClock()

    def handler(request):
        import json
        body = json.loads(request.read() or b"{}")
        if request.url.path.endswith("/auth/login"):
            return envelope({"mfaRequired": True, "mfaEnrollmentRequired": False, "mfaToken": "m1"})
        if request.url.path.endswith("/auth/mfa/verify"):
            assert body == {"mfaToken": "m1", "code": totp(secret, clock.wall())}
            return envelope({"accessToken": "staff-token"})
        return envelope(None)

    session, _, _ = make_session(handler, clock=clock, email="hr@acme.test", password="ChangeMe-123!",
                                 totp_secret=secret)
    session.login()
    assert session.token == "staff-token"

    no_secret, _, _ = make_session(handler, email="hr@acme.test", password="ChangeMe-123!")
    with pytest.raises(ApiError, match="hr-totp-secret"):
        no_secret.login()


def test_totp_rfc6238_vector():
    # RFC 6238 SHA-1 test secret "12345678901234567890" at T=59 -> 94287082 (8 digits)
    import base64
    secret = base64.b32encode(b"12345678901234567890").decode()
    assert totp(secret, 59, digits=8) == "94287082"
