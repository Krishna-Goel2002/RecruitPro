"""Reference verifier: deterministic rules plus the employer-domain trust check (mocked RDAP, MX, home page)."""
from __future__ import annotations

import pytest

from app.verifiers.reference import domains_match, is_free_mail
from eval.builders import employer_home
from tests.conftest import HEADERS


@pytest.fixture(autouse=True)
def _acme_home(fake_net):
    """acme.com is an established employer: registered 10 years ago (FakeNet default), MX, home page names it."""
    fake_net.pages["https://acme.com/"] = employer_home("Acme Corp")


def _post(client, relationship, email_domain, employer, answers, holder=None, employer_name="Acme Corp", **extra):
    claims = [{"id": "claim:1", "type": "EXPERIENCE", "value": "Analyst at Acme"},
              {"id": "claim:2", "type": "SKILL", "value": "SQL"}]
    body = {"claims": claims, "referee": {"relationship": relationship, "emailDomain": email_domain,
                                          "employerDomain": employer, "employerName": employer_name},
            "answers": answers, "holderName": holder, **extra}
    response = client.post("/api/v1/verify/reference", headers=HEADERS, json=body)
    assert response.status_code == 200, response.text
    return response.json()


@pytest.mark.parametrize("relationship,email,employer,answer,expected,owned", [
    ("MANAGER", "acme.com", "acme.com", "CONFIRM", "VERIFIED", True),
    ("PEER", "uk.acme.com", "acme.com", "CONFIRM", "VERIFIED", True),
    ("CLIENT", "ACME.com", "www.acme.com", "CONFIRM", "VERIFIED", True),
    ("REPORT", "acme.com", "acme.com", "CONFIRM", "PARTLY_VERIFIED", False),
    ("TEACHER", "acme.com", "acme.com", "CONFIRM", "PARTLY_VERIFIED", False),
    ("MANAGER", "gmail.com", "acme.com", "CONFIRM", "PARTLY_VERIFIED", False),
    ("MANAGER", "gmail.com", "gmail.com", "CONFIRM", "PARTLY_VERIFIED", False),
    ("MANAGER", "acme.com.evil.io", "acme.com", "CONFIRM", "PARTLY_VERIFIED", False),
    ("MANAGER", "acme.com", None, "CONFIRM", "PARTLY_VERIFIED", False),
    ("MANAGER", "acme.com", "acme.com", "PARTLY", "PARTLY_VERIFIED", True),
    ("MANAGER", "acme.com", "acme.com", "CANNOT_CONFIRM", "NOT_FOUND", True),
    ("MANAGER", "acme.com", "acme.com", "DISAGREE", "CONTRADICTED", True),
])
def test_rules(client, relationship, email, employer, answer, expected, owned):
    data = _post(client, relationship, email, employer, [{"claimId": "claim:1", "answer": answer,
                                                          "comment": "Left in 2019, not 2021."}])
    first, second = data["verdicts"]
    assert first["verdict"] == expected and first["ownershipProven"] is owned
    assert second["verdict"] == "NOT_FOUND"  # no answer for claim 2: absence stays neutral
    if expected == "CONTRADICTED":
        assert first["reasons"][0]["quote"] == "Left in 2019, not 2021."


def test_comment_is_de_identified_when_the_holder_name_is_known(client):
    data = _post(client, "MANAGER", "acme.com", "acme.com",
                 [{"claimId": "claim:1", "answer": "DISAGREE", "comment": "Priya Tester never worked at acme.com"}],
                 holder="Priya Tester")
    quote = data["verdicts"][0]["reasons"][0]["quote"]
    assert "Priya" not in quote and "acme.com" not in quote


def test_free_mail_helpers():
    assert is_free_mail("Gmail.com") and is_free_mail("someone@outlook.com") and not is_free_mail("acme.com")
    assert domains_match("eu.acme.com", "acme.com") and not domains_match("notacme.com", "acme.com")
    assert not domains_match("acme.com", "com")


# ------------------------------------------------------------------------------------- bug-hunt regressions
@pytest.mark.parametrize("domain", ["hotmail.fr", "outlook.de", "live.in", "yahoo.com.br", "gmx.at", "hotmail.co.uk",
                                    "yandex.kz", "mail.ru", "proton.me"])
def test_country_variants_of_free_mail_are_free_mail(domain):
    assert is_free_mail(domain) and is_free_mail(f"someone@{domain}")


@pytest.mark.parametrize("domain", ["acme.com", "acme.co.uk", "mail.ibm.com", "outlookconsulting.com", "live-nation.com",
                                    "yahoo-inc.example", "mailchimp.com", "eu.acme.com"])
def test_company_domains_are_not_free_mail(domain):
    assert not is_free_mail(domain)


def test_a_free_mail_country_domain_never_anchors_a_reference(client, fake_net):
    """hotmail.fr is old, has MX and its home page names Microsoft: before, a sock-puppet mailbox there "verified" a
    claimed job at Microsoft."""
    fake_net.pages["https://hotmail.fr/"] = employer_home("Microsoft")
    data = _post(client, "MANAGER", "hotmail.fr", "hotmail.fr", [{"claimId": "claim:1", "answer": "CONFIRM"}],
                 employer_name="Microsoft")
    first = data["verdicts"][0]
    assert first["verdict"] == "PARTLY_VERIFIED" and first["ownershipProven"] is False
    assert "free e-mail" in first["reasons"][0]["text"] and "FREE_MAIL_REFEREE" in data["warnings"]
