"""Domain trust (app/trust.py): employer domains of references and badge issuer domains. RDAP, MX and home pages are
all mocked (FakeNet); nothing touches the network."""
from __future__ import annotations

import json

import pytest

from app.core.budget import Budget
from app.core.config import load_settings
from app.demo.fakes import FakePage
from app.fetch.cache import TtlCache
from app.fetch.ssrf import FetchError
from app.trust import (BADGE_ISSUER, TRUSTED, UNTRUSTED, WEAK, assess_employer_domain, assess_issuer_domain,
                       builtin_issuers, issuer_domain, name_tokens, rdap_lookup)
from eval.builders import NOW, FakeNet, days_ago, did_key_of, ed25519_key, employer_home, es256_key, ob2_documents, \
    ob3_payload, sign
from tests.conftest import HEADERS

SETTINGS = load_settings({"AGENT_SERVICE_TOKEN": "t"})
CLAIMS = [{"id": "claim:1", "type": "EXPERIENCE", "value": "Analyst at Acme"}]


def _reference(client, *, employer="acme.com", email="acme.com", name="Acme Corp", relationship="MANAGER",
               answer="CONFIRM", **extra):
    body = {"claims": CLAIMS, "referee": {"relationship": relationship, "emailDomain": email,
                                          "employerDomain": employer, "employerName": name},
            "answers": [{"claimId": "claim:1", "answer": answer}], **extra}
    response = client.post("/api/v1/verify/reference", headers=HEADERS, json=body)
    assert response.status_code == 200, response.text
    return response.json()


def _codes(verdict) -> set[str]:
    return {note["code"] for note in verdict["integrityNotes"]}


@pytest.fixture
def acme(fake_net: FakeNet) -> FakeNet:
    fake_net.pages["https://acme.com/"] = employer_home("Acme Corp")
    return fake_net


# ------------------------------------------------------------------------------------------- references
def test_established_employer_domain_is_trusted_by_the_agent(client, acme):
    data = _reference(client)
    verdict = data["verdicts"][0]
    assert verdict["verdict"] == "VERIFIED" and verdict["ownershipProven"] is True
    trust = verdict["domainTrust"]
    assert trust["status"] == TRUSTED and trust["trustedBy"] == "AGENT" and trust["check"] == "EMPLOYER_DOMAIN"
    assert trust["domain"] == "acme.com" and trust["cappedFrom"] is None
    assert trust["signals"]["rdap"] == "ok" and trust["signals"]["mx"] == "present"
    assert trust["signals"]["homepage"] == "match" and trust["signals"]["ageDays"] >= 365
    assert not _codes(verdict) & {"EMPLOYER_DOMAIN_UNPROVEN", "EMPLOYER_DOMAIN_NEW"}
    item = next(item for item in data["evidence"] if item["key"] == "employer-domain")
    assert item["kind"] == "PAGE" and item["url"] == "https://acme.com/" and "registered" in item["snapshotText"]


def test_new_employer_domain_is_untrusted(client, acme):
    """The gaming path: the candidate registers acme-corp.example and a friend confirms from a mailbox there."""
    acme.registered["acme.com"] = days_ago(60)
    data = _reference(client)
    verdict = data["verdicts"][0]
    assert verdict["verdict"] == "PARTLY_VERIFIED" and verdict["ownershipProven"] is True
    assert {"EMPLOYER_DOMAIN_UNPROVEN", "EMPLOYER_DOMAIN_NEW"} <= _codes(verdict)
    trust = verdict["domainTrust"]
    assert trust["status"] == UNTRUSTED and trust["trustedBy"] is None and trust["cappedFrom"] == "VERIFIED"
    assert trust["signals"]["ageDays"] == 60
    assert any("capped at partly verified" in reason["text"] for reason in verdict["reasons"])
    assert verdict["probeQuestion"]
    # a young domain is decided by its age: no MX lookup or home page fetch needed
    assert "https://acme.com/" not in acme.calls


@pytest.mark.parametrize("change,signal", [
    ("rdap_down", ("rdap", "unreachable")),
    ("unknown_to_registry", ("rdap", "not_found")),
    ("no_mx", ("mx", "none")),
    ("other_home_page", ("homepage", "no_match")),
    ("no_home_page", ("homepage", "unreachable")),
    ("no_name", ("homepage", "no_name")),
])
def test_missing_signals_make_the_domain_weak(client, acme, change, signal):
    name = "Acme Corp"
    if change == "rdap_down":
        acme.rdap_down = True
    elif change == "unknown_to_registry":
        acme.registered["acme.com"] = None
    elif change == "no_mx":
        acme.default_mx = False
    elif change == "other_home_page":
        acme.pages["https://acme.com/"] = employer_home("Globex Industries")
    elif change == "no_home_page":
        del acme.pages["https://acme.com/"]
    else:
        name = None
    verdict = _reference(client, name=name)["verdicts"][0]
    assert verdict["verdict"] == "PARTLY_VERIFIED"
    assert verdict["domainTrust"]["status"] == WEAK and verdict["domainTrust"]["cappedFrom"] == "VERIFIED"
    assert verdict["domainTrust"]["signals"][signal[0]] == signal[1]
    assert "EMPLOYER_DOMAIN_UNPROVEN" in _codes(verdict) and "EMPLOYER_DOMAIN_NEW" not in _codes(verdict)


def test_json_ld_organization_name_matches(client, acme):
    org = json.dumps({"@context": "https://schema.org", "@type": "Corporation", "name": "ACME Corp."})
    acme.pages["https://acme.com/"] = FakePage(f'<html><head><script type="application/ld+json">{org}</script>'
                                               f"</head><body><p>Welcome</p></body></html>")
    verdict = _reference(client, name="Acme Corporation")["verdicts"][0]
    assert verdict["verdict"] == "VERIFIED" and verdict["domainTrust"]["signals"]["homepage"] == "match"


def test_home_page_on_www_and_legal_suffixes(client, acme):
    del acme.pages["https://acme.com/"]
    acme.pages["https://www.acme.com/"] = employer_home("Acme")
    assert _reference(client, name="Acme Pvt. Ltd.")["verdicts"][0]["verdict"] == "VERIFIED"
    assert name_tokens("The Acme Company Pvt. Ltd.") == ["acme"]


def test_organisation_list_trusts_the_domain_without_network(client, acme):
    acme.rdap_down = True
    verdict = _reference(client, employer="eu.acme.com", email="eu.acme.com",
                         trustedEmployerDomains=["acme.com"])["verdicts"][0]
    assert verdict["verdict"] == "VERIFIED"
    assert verdict["domainTrust"]["status"] == TRUSTED and verdict["domainTrust"]["trustedBy"] == "ORGANIZATION"
    assert acme.calls == []


def test_candidate_owned_employer_domain_is_untrusted(client, acme):
    verdict = _reference(client, employer="team.acme.com", email="team.acme.com",
                         candidateDomains=["acme.com"])["verdicts"][0]
    assert verdict["verdict"] == "PARTLY_VERIFIED"
    assert verdict["domainTrust"]["status"] == UNTRUSTED and verdict["domainTrust"]["candidateControlled"] is True


def test_unanchored_references_do_not_check_the_domain(client, acme):
    data = _reference(client, email="gmail.com")
    assert data["verdicts"][0]["verdict"] == "PARTLY_VERIFIED" and data["verdicts"][0]["domainTrust"] is None
    assert acme.calls == [] and not any(item["key"] == "employer-domain" for item in data["evidence"])


def test_other_answers_carry_the_trust_but_are_not_raised(client, acme):
    acme.registered["acme.com"] = days_ago(10)
    verdict = _reference(client, answer="DISAGREE")["verdicts"][0]
    assert verdict["verdict"] == "CONTRADICTED" and verdict["domainTrust"]["cappedFrom"] is None
    assert "EMPLOYER_DOMAIN_NEW" in _codes(verdict)


def test_home_page_fetch_goes_through_the_ssrf_guard(client, acme):
    acme.hosts["acme.com"] = ["10.0.0.5"]
    acme.hosts["www.acme.com"] = ["169.254.169.254"]
    verdict = _reference(client)["verdicts"][0]
    assert verdict["verdict"] == "PARTLY_VERIFIED"
    assert verdict["domainTrust"]["signals"]["homepage"] == "unreachable"
    assert not any("acme.com/" in call and "rdap" not in call for call in acme.calls)


def test_dns_trouble_never_fails_the_reference(client, acme):
    def broken(host: str) -> list[str]:
        raise FetchError("DNS_FAILURE", "boom")

    original = acme.network

    def network():
        net = original()
        net.mx_resolver = broken
        return net

    acme.network = network  # type: ignore[method-assign]
    verdict = _reference(client)["verdicts"][0]
    assert verdict["verdict"] == "PARTLY_VERIFIED" and verdict["domainTrust"]["signals"]["mx"] == "unknown"


# ----------------------------------------------------------------------------------------------- RDAP
def _fetcher(net: FakeNet):
    network = net.network()
    return network, network.fetcher(SETTINGS, Budget(20))


def test_rdap_walks_up_to_the_registered_domain_and_caches():
    net = FakeNet(registered={"eu.acme.com": None, "acme.com": days_ago(800)})
    network, fetcher = _fetcher(net)
    result = rdap_lookup("eu.acme.com", fetcher, network.rdap_cache)
    assert result.state == "ok" and result.queried == "acme.com"
    assert net.calls.count("https://rdap.test/domain/eu.acme.com") == 1
    calls = len(net.calls)
    assert rdap_lookup("eu.acme.com", fetcher, network.rdap_cache).registered_at == result.registered_at
    assert len(net.calls) == calls  # cached: no new requests


def test_rdap_never_queries_a_public_second_level():
    net = FakeNet(registered={"acme.co.uk": None})
    network, fetcher = _fetcher(net)
    assert rdap_lookup("acme.co.uk", fetcher, network.rdap_cache).state == "not_found"
    assert "https://rdap.test/domain/co.uk" not in net.calls


def test_rdap_failures_are_unreachable_and_not_cached():
    net = FakeNet(rdap_down=True)
    network, fetcher = _fetcher(net)
    assert rdap_lookup("acme.com", fetcher, network.rdap_cache).state == "unreachable"
    assert len(network.rdap_cache) == 0
    net = FakeNet(registered={"acme.com": None})
    net.pages["https://rdap.test/domain/acme.com"] = FakePage(json.dumps({"events": []}), "application/rdap+json")
    network, fetcher = _fetcher(net)
    assert rdap_lookup("acme.com", fetcher, network.rdap_cache).state == "unreachable"
    network, fetcher = _fetcher(FakeNet())
    assert rdap_lookup("acme.unknowntld", fetcher, network.rdap_cache).state == "unreachable"


def test_no_network_is_weak_never_trusted():
    trust = assess_employer_domain("acme.com", "Acme", now=NOW, fetcher=None, mx_lookup=None, cache=None)
    assert trust.status == WEAK
    trust = assess_issuer_domain("issuer.example", now=NOW, fetcher=None, cache=None)
    assert trust.status == WEAK


def test_cache_expires():
    clock = [0.0]
    cache = TtlCache(10, clock=lambda: clock[0])
    cache.put("k", 1)
    assert cache.get("k") == 1
    clock[0] = 11
    assert cache.get("k") is None


# --------------------------------------------------------------------------------------------- badges
BADGE_CLAIM = [{"id": "fact:1", "type": "CERTIFICATION", "value": "Data Analysis with Python"}]


def _badge(client, **body):
    payload = {"claims": BADGE_CLAIM, "holderEmail": "holder@example.org", "holderName": "Ana Sample", **body}
    response = client.post("/api/v1/verify/badge", headers=HEADERS, json=payload)
    assert response.status_code == 200, response.text
    return response.json()["verdicts"][0]


def _jwks_badge(net: FakeNet, issuer: str = "https://issuer.example") -> str:
    key = es256_key()
    net.pages[f"{issuer}/.well-known/jwks.json"] = FakePage(json.dumps({"keys": [{**key.jwk, "kid": "k1"}]}),
                                                            "application/json")
    return sign(ob3_payload(issuer, "Data Analysis with Python"), key, kid="k1")


def test_issuer_on_a_candidate_owned_domain_is_untrusted(client, fake_net):
    """The gaming path: a correctly signed badge from an issuer on the candidate's own (proven) website."""
    credential = _jwks_badge(fake_net, "https://badges.asha-portfolio.example")
    verdict = _badge(client, credential=credential, candidateDomains=["asha-portfolio.example"])
    assert verdict["verdict"] == "PARTLY_VERIFIED" and verdict["ownershipProven"] is True
    assert "ISSUER_UNTRUSTED" in _codes(verdict)
    trust = verdict["domainTrust"]
    assert trust["check"] == BADGE_ISSUER and trust["status"] == UNTRUSTED and trust["candidateControlled"] is True
    assert trust["cappedFrom"] == "VERIFIED"


def test_young_issuer_domain_is_untrusted(client, fake_net):
    fake_net.registered["issuer.example"] = days_ago(20)
    verdict = _badge(client, credential=_jwks_badge(fake_net))
    assert verdict["verdict"] == "PARTLY_VERIFIED" and verdict["domainTrust"]["status"] == UNTRUSTED
    assert "ISSUER_UNTRUSTED" in _codes(verdict)


def test_issuer_rdap_unreachable_is_not_trusted(client, fake_net):
    fake_net.rdap_down = True
    verdict = _badge(client, credential=_jwks_badge(fake_net))
    assert verdict["verdict"] == "PARTLY_VERIFIED" and verdict["domainTrust"]["status"] == WEAK


def test_established_issuer_is_trusted(client, fake_net):
    verdict = _badge(client, credential=_jwks_badge(fake_net))
    assert verdict["verdict"] == "VERIFIED"
    assert verdict["domainTrust"]["status"] == TRUSTED and verdict["domainTrust"]["trustedBy"] == "AGENT"
    assert "ISSUER_UNTRUSTED" not in _codes(verdict)


def test_builtin_allowlist_and_organisation_list(client, fake_net):
    assert "credly.com" in builtin_issuers() and "badgr.io" in builtin_issuers()
    fake_net.rdap_down = True
    url, pages = ob2_documents("https://api.credly.com", badge_name="Data Analysis with Python")
    fake_net.pages.update(pages)
    verdict = _badge(client, url=url)
    assert verdict["verdict"] == "VERIFIED" and verdict["domainTrust"]["signals"]["allowlisted"] is True
    verdict = _badge(client, credential=_jwks_badge(fake_net), trustedIssuerDomains=["issuer.example"])
    assert verdict["verdict"] == "VERIFIED" and verdict["domainTrust"]["trustedBy"] == "ORGANIZATION"


def test_did_key_keeps_its_own_cap_and_has_no_domain_check(client, fake_net):
    key = ed25519_key()
    did = did_key_of(key)
    verdict = _badge(client, credential=sign(ob3_payload(did, "Data Analysis with Python"), key))
    assert verdict["verdict"] == "PARTLY_VERIFIED" and verdict["domainTrust"] is None
    assert "ISSUER_NOT_DOMAIN_BOUND" in _codes(verdict) and fake_net.calls == []


def test_issuer_domain_helper():
    assert issuer_domain("did:web:badges.example.org") == "badges.example.org"
    assert issuer_domain("did:web:example.com%3A8443:users:ana") == "example.com"
    assert issuer_domain("https://www.Credly.com/api/v1/issuers/1") == "credly.com"
    assert issuer_domain("did:key:z6Mk") is None and issuer_domain("urn:uuid:1") is None


# ----------------------------------------------------------------------------------------------- demo
@pytest.fixture
def demo_client(monkeypatch: pytest.MonkeyPatch):
    from fastapi.testclient import TestClient

    from app.main import create_app

    monkeypatch.setenv("AGENT_DEMO_FIXTURES", "true")
    with TestClient(create_app()) as test_client:
        yield test_client


def test_demo_employer_domains(demo_client):
    trusted = _reference(demo_client, employer="demo-analytics.example", email="demo-analytics.example",
                         name="Demo Analytics")
    verdict = trusted["verdicts"][0]
    assert verdict["verdict"] == "VERIFIED" and verdict["domainTrust"]["trustedBy"] == "AGENT"
    assert "DEMO_FIXTURES: synthetic recorded data" in trusted["warnings"]
    young = _reference(demo_client, employer="demo-newco.example", email="demo-newco.example", name="Demo Newco")
    verdict = young["verdicts"][0]
    assert verdict["verdict"] == "PARTLY_VERIFIED"
    assert {"EMPLOYER_DOMAIN_UNPROVEN", "EMPLOYER_DOMAIN_NEW"} <= _codes(verdict)


# ------------------------------------------------------------------------------------- bug-hunt regressions
@pytest.mark.parametrize("issuer", ["https://mira.github.io", "https://badges.mira.netlify.app"])
def test_issuer_on_a_shared_hosting_subdomain_does_not_inherit_the_platform_age(client, fake_net, issuer):
    """Anyone can publish a JWKS on <name>.github.io; RDAP 404s there and the walk reached github.io (registered in
    2008), so a self-issued badge used to verify. The platform's age must not count."""
    fake_net.registered.update({"mira.github.io": None, "badges.mira.netlify.app": None, "mira.netlify.app": None})
    verdict = _badge(client, credential=_jwks_badge(fake_net, issuer))
    assert verdict["verdict"] == "PARTLY_VERIFIED" and "ISSUER_UNTRUSTED" in _codes(verdict)
    trust = verdict["domainTrust"]
    assert trust["status"] == WEAK and trust["signals"]["sharedHosting"] in {"github.io", "netlify.app"}
    assert not any("rdap.test" in call for call in fake_net.calls)
    # an organisation may still trust it explicitly
    verdict = _badge(client, credential=_jwks_badge(fake_net, issuer),
                     trustedIssuerDomains=[issuer.split("://", 1)[1]])
    assert verdict["verdict"] == "VERIFIED"


def test_employer_domain_on_shared_hosting_is_weak():
    trust = assess_employer_domain("acme.herokuapp.com", "Acme", now=NOW, fetcher=None, mx_lookup=None,
                                   cache=TtlCache())
    assert trust.status == WEAK and trust.signals["sharedHosting"] == "herokuapp.com"
    assert assess_issuer_domain("github.io", now=NOW, fetcher=None, cache=None).signals["sharedHosting"] is None
