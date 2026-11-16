"""SSRF guard: address classes, URL rules, redirects, DNS rebinding (pinning), caps, robots, cookies."""
from __future__ import annotations

import httpx
import pytest

from app.core.budget import Budget
from app.core.config import load_settings
from app.fetch.client import PAGE_TYPES, SafeFetcher
from app.fetch.ssrf import BlockedUrlError, FetchError, check_url, is_blocked_address, parse_url

SETTINGS = load_settings({"AGENT_SERVICE_TOKEN": "x"})
PUBLIC = "93.184.216.34"


@pytest.mark.parametrize("address", [
    "127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254", "100.64.0.1", "0.0.0.0", "224.0.0.1",
    "240.0.0.1", "255.255.255.255", "192.0.2.10", "198.18.0.1", "::1", "fe80::1", "fc00::1", "fd00:ec2::254",
    "::ffff:127.0.0.1", "::ffff:10.0.0.1", "64:ff9b::a00:1", "2002:a00:1::1", "2001:db8::1", "ff02::1", "not-an-ip",
])
def test_blocked_addresses(address: str) -> None:
    assert is_blocked_address(address)


@pytest.mark.parametrize("address", ["93.184.216.34", "8.8.8.8", "2606:4700:4700::1111"])
def test_public_addresses_are_allowed(address: str) -> None:
    assert not is_blocked_address(address)


@pytest.mark.parametrize("url", [
    "ftp://example.com/", "file:///etc/passwd", "gopher://example.com", "http://example.com:8080/",
    "https://example.com:22/", "http://user:pass@example.com/", "http://localhost/", "http://foo.internal/",
    "http://printer.local/", "http:///nohost", "javascript:alert(1)",
])
def test_bad_urls_are_rejected(url: str) -> None:
    with pytest.raises(BlockedUrlError):
        parse_url(url)


def test_every_resolved_address_must_be_public() -> None:
    with pytest.raises(BlockedUrlError):
        check_url("https://mixed.example/", lambda host: [PUBLIC, "10.0.0.5"])
    with pytest.raises(BlockedUrlError):
        check_url("http://127.0.0.1/", lambda host: [PUBLIC])  # IP literal checked directly
    assert check_url("https://ok.example/", lambda host: [PUBLIC]).address == PUBLIC


def _fetcher(handler, resolver, budget: int = 50) -> SafeFetcher:
    return SafeFetcher(SETTINGS, Budget(budget), resolver=resolver, transport_factory=lambda: httpx.MockTransport(handler))


def test_request_is_pinned_to_the_checked_address_and_dns_rebinding_cannot_redirect_it() -> None:
    answers = iter([[PUBLIC], ["127.0.0.1"], ["127.0.0.1"]])
    resolutions = []

    def resolver(host: str) -> list[str]:
        resolutions.append(host)
        return next(answers)

    seen = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return httpx.Response(200, text="hello", headers={"content-type": "text/plain"})

    result = _fetcher(handler, resolver).fetch("https://rebind.example/page?q=1", respect_robots=False,
                                               accept=PAGE_TYPES)
    assert result.ok and result.text() == "hello"
    assert resolutions == ["rebind.example"]  # resolved once; the connection used that answer
    request = seen[0]
    assert request.url.host == PUBLIC and request.url.path == "/page" and request.url.query == b"q=1"
    assert request.headers["host"] == "rebind.example"
    assert request.extensions["sni_hostname"] == "rebind.example"
    assert request.headers["user-agent"] == "RecruitProVerifier/1.0 (+contact)"


def test_redirect_to_a_private_address_is_blocked_and_re_resolved_per_hop() -> None:
    hosts = {"start.example": [PUBLIC], "evil.example": ["169.254.169.254"]}

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(302, headers={"location": "http://evil.example/latest/meta-data"})

    with pytest.raises(FetchError) as error:
        _fetcher(handler, lambda host: hosts[host]).fetch("https://start.example/", respect_robots=False)
    assert error.value.code == "URL_BLOCKED"


def test_redirect_to_a_private_ip_literal_or_bad_scheme_is_blocked() -> None:
    for location in ("http://10.0.0.1/", "file:///etc/passwd", "http://example.com:8443/"):
        def handler(request: httpx.Request, location: str = location) -> httpx.Response:
            return httpx.Response(301, headers={"location": location})

        with pytest.raises(FetchError) as error:
            _fetcher(handler, lambda host: [PUBLIC]).fetch("https://start.example/", respect_robots=False)
        assert error.value.code == "URL_BLOCKED"


def test_at_most_five_redirects() -> None:
    count = {"n": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        count["n"] += 1
        return httpx.Response(302, headers={"location": f"/r{count['n']}"})

    with pytest.raises(FetchError) as error:
        _fetcher(handler, lambda host: [PUBLIC]).fetch("https://loop.example/", respect_robots=False)
    assert error.value.code == "TOO_MANY_REDIRECTS" and count["n"] == 6


def test_body_is_capped_and_content_types_are_allow_listed() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/big":
            return httpx.Response(200, content=b"a" * 50_000, headers={"content-type": "text/plain"})
        return httpx.Response(200, content=b"\x00\x01", headers={"content-type": "application/zip"})

    fetcher = _fetcher(handler, lambda host: [PUBLIC])
    result = fetcher.fetch("https://x.example/big", max_bytes=1000, respect_robots=False)
    assert result.truncated and len(result.body) == 1000
    with pytest.raises(FetchError) as error:
        fetcher.fetch("https://x.example/zip", respect_robots=False)
    assert error.value.code == "UNSUPPORTED_CONTENT"


def test_no_cookies_are_kept_between_requests() -> None:
    seen = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request.headers.get("cookie"))
        return httpx.Response(200, text="ok", headers={"content-type": "text/plain", "set-cookie": "sid=abc; Path=/"})

    fetcher = _fetcher(handler, lambda host: [PUBLIC])
    fetcher.fetch("https://c.example/1", respect_robots=False)
    fetcher.fetch("https://c.example/2", respect_robots=False)
    assert seen == [None, None]


def test_robots_txt_is_respected_and_cached() -> None:
    calls = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request.url.path)
        if request.url.path == "/robots.txt":
            return httpx.Response(200, text="User-agent: RecruitProVerifier\nDisallow: /private\n",
                                  headers={"content-type": "text/plain"})
        return httpx.Response(200, text="<p>ok</p>", headers={"content-type": "text/html"})

    fetcher = _fetcher(handler, lambda host: [PUBLIC])
    assert fetcher.fetch("https://r.example/public").ok
    with pytest.raises(FetchError) as error:
        fetcher.fetch("https://r.example/private/cv")
    assert error.value.code == "ROBOTS_DISALLOWED"
    assert calls.count("/robots.txt") == 1


def test_robots_server_error_means_disallow_and_404_means_allow() -> None:
    def handler_for(status: int):
        def handler(request: httpx.Request) -> httpx.Response:
            if request.url.path == "/robots.txt":
                return httpx.Response(status, text="", headers={"content-type": "text/plain"})
            return httpx.Response(200, text="ok", headers={"content-type": "text/plain"})
        return handler

    assert _fetcher(handler_for(404), lambda host: [PUBLIC]).fetch("https://a.example/x").ok
    with pytest.raises(FetchError):
        _fetcher(handler_for(503), lambda host: [PUBLIC]).fetch("https://a.example/x")


def test_budget_stops_requests() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, text="ok", headers={"content-type": "text/plain"})

    fetcher = _fetcher(handler, lambda host: [PUBLIC], budget=1)
    fetcher.fetch("https://b.example/1", respect_robots=False)
    with pytest.raises(FetchError) as error:
        fetcher.fetch("https://b.example/2", respect_robots=False)
    assert error.value.code == "BUDGET" and fetcher.budget.truncated


def test_ipv6_literal_target_uses_brackets() -> None:
    seen = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(str(request.url))
        return httpx.Response(200, text="ok", headers={"content-type": "text/plain"})

    _fetcher(handler, lambda host: ["2606:4700:4700::1111"]).fetch("https://v6.example/", respect_robots=False)
    assert seen[0].startswith("https://[2606:4700:4700::1111]/")


def test_web_endpoint_rejects_a_private_start_url(client, fake_net) -> None:
    from tests.conftest import HEADERS

    fake_net.hosts["intranet.example"] = ["10.0.0.8"]
    for url in ("http://169.254.169.254/latest/meta-data/", "https://intranet.example/", "http://localhost:80/"):
        response = client.post("/api/v1/verify/web", headers=HEADERS, json={"url": url, "claims": []})
        assert response.status_code == 422 and response.json()["detail"]["code"] == "URL_BLOCKED"


# ------------------------------------------------------------------------------------- bug-hunt regressions
def test_blocked_host_names_are_checked_after_idna_normalisation() -> None:
    for url in ("http://ｌｏｃａｌｈｏｓｔ/", "http://printer.ｉｎｔｅｒｎａｌ/", "http://METADATA.google.internal./"):
        with pytest.raises(BlockedUrlError) as error:
            parse_url(url)
        assert "internal host names" in str(error.value), url


def test_site_local_and_mapped_ipv6_and_short_ipv4_forms_are_blocked() -> None:
    import ipaddress
    import socket

    assert is_blocked_address("fec0::1") and is_blocked_address("::ffff:7f00:1")
    for literal in ("http://[::ffff:127.0.0.1]/", "http://[::ffff:a9fe:a9fe]/", "http://[fec0::1]/"):
        with pytest.raises(BlockedUrlError):
            check_url(literal, lambda host: [PUBLIC])

    def inet_aton_resolver(host: str) -> list[str]:  # what glibc does with decimal / octal / hex / short forms
        return [str(ipaddress.IPv4Address(socket.inet_aton(host)))]

    for url in ("http://2130706433/", "http://0177.0.0.1/", "http://127.1/", "http://0x7f.1/", "http://0/"):
        with pytest.raises(BlockedUrlError):
            check_url(url, inet_aton_resolver)


def test_control_characters_in_urls_are_blocked_not_a_crash() -> None:
    for url in ("https://x.example/a\x00b", "https://x.example/?q=\x01", "https://x.example/a\x7f"):
        with pytest.raises(BlockedUrlError):
            parse_url(url)


def test_a_link_with_a_control_character_is_skipped_by_the_crawl(client, fake_net) -> None:
    from app.demo.fakes import FakePage
    from tests.conftest import HEADERS

    fake_net.pages["https://site.example/robots.txt"] = FakePage("User-agent: *\nAllow: /\n", "text/plain")
    fake_net.pages["https://site.example/"] = FakePage("<p>Rust</p><a href='/cv\x01x'>cv</a>")  # a raw control character
    response = client.post("/api/v1/verify/web", headers=HEADERS, json={
        "url": "https://site.example/", "claims": [{"id": "c", "type": "SKILL", "value": "Rust"}]})
    assert response.status_code == 200, response.text
    assert "PAGE_SKIPPED:URL_BLOCKED" in response.json()["warnings"]


def test_a_trickling_body_hits_the_total_timeout(monkeypatch: pytest.MonkeyPatch) -> None:
    clock = {"t": 1000.0}

    def fake_now() -> float:
        clock["t"] += 4.0  # every check sees four more seconds
        return clock["t"]

    monkeypatch.setattr("app.fetch.client._now", fake_now)
    served = {"chunks": 0}

    def body():
        for _ in range(1000):
            served["chunks"] += 1
            yield b"x" * 10

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, headers={"content-type": "text/plain"}, content=body())

    with pytest.raises(FetchError) as error:
        _fetcher(handler, lambda host: [PUBLIC]).fetch("https://slow.example/", respect_robots=False)
    assert error.value.code == "TIMEOUT" and served["chunks"] < 10


def test_https_to_http_redirects_are_refused() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(302, headers={"location": "http://plain.example/did.json"})

    with pytest.raises(FetchError) as error:
        _fetcher(handler, lambda host: [PUBLIC]).fetch("https://secure.example/", respect_robots=False)
    assert error.value.code == "URL_BLOCKED"


def test_ipv6_literal_host_header_keeps_its_brackets() -> None:
    seen = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request.headers["host"])
        return httpx.Response(200, text="ok", headers={"content-type": "text/plain"})

    _fetcher(handler, lambda host: [PUBLIC]).fetch("https://[2606:4700:4700::1111]/", respect_robots=False)
    assert seen == ["[2606:4700:4700::1111]"]


def test_html_charset_is_sniffed_from_meta_when_the_header_has_none() -> None:
    from app.fetch.client import FetchResult

    body = '<html><head><meta charset="windows-1252"></head><body>Café Ltd</body></html>'.encode("cp1252")
    assert "Café Ltd" in FetchResult(url="https://x.example/", status=200, content_type="text/html", body=body).text()
    assert "Café" in FetchResult(url="https://x.example/", status=200, content_type="text/html",
                                 body=b"\xef\xbb\xbf" + "Café".encode()).text()
