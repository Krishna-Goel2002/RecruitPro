"""Badge verifier: OB 3.0 VC-JWT signatures (EdDSA/ES256/RS256 via did:key, did:web, JWKS) and OB 2.0 hosted."""
from __future__ import annotations

import json
from datetime import timedelta

import pytest

from app.badges.keys import base58_decode, base58_encode, did_web_url, ed25519_jwk_from_multibase
from app.demo.fakes import FakePage
from eval.builders import (NOW, days_ago, did_key_of, ed25519_key, es256_key, ob2_documents, ob3_payload, rs256_key,
                           sign, status_list, tamper)
from tests.conftest import HEADERS

CLAIM = [{"id": "fact:1", "type": "CERTIFICATION", "value": "Data Analysis with Python"}]
ISSUER = "https://issuer.example"


def _post(client, **body):
    payload = {"claims": CLAIM, "holderEmail": "holder@example.org", "holderName": "Ana Sample", **body}
    response = client.post("/api/v1/verify/badge", headers=HEADERS, json=payload)
    assert response.status_code == 200, response.text
    return response.json()


def _verdict(data) -> str:
    return data["verdicts"][0]["verdict"]


def test_base58_and_did_helpers():
    assert base58_decode(base58_encode(b"\x00\x00abc")) == b"\x00\x00abc"
    key = ed25519_key()
    did = did_key_of(key)
    assert ed25519_jwk_from_multibase(did.split(":")[2]) == key.jwk
    assert did_web_url("did:web:example.com") == "https://example.com/.well-known/did.json"
    assert did_web_url("did:web:example.com:users:ana") == "https://example.com/users/ana/did.json"


def test_eddsa_did_web_is_verified(client, fake_net):
    key = ed25519_key()
    did = "did:web:ed.example"
    fake_net.pages["https://ed.example/.well-known/did.json"] = FakePage(json.dumps({"id": did, "verificationMethod": [
        {"id": f"{did}#k", "type": "Ed25519VerificationKey2020", "publicKeyMultibase": did_key_of(key).split(":")[2]}]}),
        "application/did+json")
    data = _post(client, credential=sign(ob3_payload(did, "Data Analysis with Python"), key, kid=f"{did}#k"))
    assert _verdict(data) == "VERIFIED" and data["evidence"][0]["attributes"]["keySource"] == "did:web"
    assert "issuer-key" in {item["key"] for item in data["evidence"]}


def test_eddsa_did_key_good_bad_expired_wrong_recipient(client):
    key = ed25519_key()
    did = did_key_of(key)
    good = sign(ob3_payload(did, "Data Analysis with Python"), key, kid=f"{did}#{did.split(':')[-1]}")
    data = _post(client, credential=good)
    # the signature and recipient check out, but a did:key issuer is self-certifying: at most partly verified
    assert _verdict(data) == "PARTLY_VERIFIED" and data["verdicts"][0]["ownershipProven"] is True
    assert data["evidence"][0]["attributes"]["signatureValid"] is True
    assert "ISSUER_NOT_DOMAIN_BOUND" in {note["code"] for note in data["verdicts"][0]["integrityNotes"]}
    assert _verdict(_post(client, credential=tamper(good))) == "CONTRADICTED"
    expired = sign(ob3_payload(did, "Data Analysis with Python", expires=NOW - timedelta(days=2)), key)
    assert _verdict(_post(client, credential=expired)) == "PARTLY_VERIFIED"
    wrong = sign(ob3_payload(did, "Data Analysis with Python", email="other@example.org"), key)
    data = _post(client, credential=wrong)
    assert _verdict(data) == "CONTRADICTED" and data["verdicts"][0]["ownershipProven"] is False
    other_key = ed25519_key()
    forged = sign(ob3_payload(did, "Data Analysis with Python"), other_key)  # signed by someone else's key
    assert _verdict(_post(client, credential=forged)) == "CONTRADICTED"


def test_es256_jwks_and_rs256_did_web(client, fake_net):
    es = es256_key()
    fake_net.pages[f"{ISSUER}/.well-known/jwks.json"] = FakePage(json.dumps({"keys": [{**es.jwk, "kid": "k1"}]}),
                                                                 "application/json")
    assert _verdict(_post(client, credential=sign(ob3_payload(ISSUER, "Data Analysis with Python"), es,
                                                    kid="k1"))) == "VERIFIED"
    rs = rs256_key()
    did = "did:web:keys.example:org"
    fake_net.pages["https://keys.example/org/did.json"] = FakePage(json.dumps({"id": did, "verificationMethod": [
        {"id": "#k", "type": "JsonWebKey2020", "publicKeyJwk": rs.jwk}]}), "application/json")
    assert _verdict(_post(client, credential=sign(ob3_payload(did, "Data Analysis with Python"), rs,
                                                    kid=f"{did}#k"))) == "VERIFIED"
    # algorithm confusion: an HS256 token "signed" with the public key text is not accepted
    import jwt as pyjwt
    confused = pyjwt.encode(ob3_payload(ISSUER, "Data Analysis with Python"), "secret", algorithm="HS256",
                            headers={"kid": "k1"})
    assert _verdict(_post(client, credential=confused)) == "NOT_CHECKABLE"


def test_unresolvable_key_and_unsupported_proofs_are_not_checkable(client):
    key = ed25519_key()
    data = _post(client, credential=sign(ob3_payload("did:web:missing.example", "Data Analysis with Python"), key))
    assert _verdict(data) == "NOT_CHECKABLE" and "KEY_UNRESOLVED" in data["warnings"]
    data = _post(client, credential={"type": ["VerifiableCredential"], "credentialSubject": {
        "achievement": {"name": "Data Analysis with Python"}}, "proof": {"type": "DataIntegrityProof"}})
    assert _verdict(data) == "NOT_CHECKABLE"
    assert "proof type not supported yet" in data["verdicts"][0]["reasons"][0]["text"]
    assert data["verdicts"][0]["probeQuestion"]


def test_status_list_revocation(client, fake_net):
    es = es256_key()
    fake_net.pages[f"{ISSUER}/.well-known/jwks.json"] = FakePage(json.dumps({"keys": [{**es.jwk, "kid": "k1"}]}),
                                                                 "application/json")
    list_payload = {"iss": ISSUER, "vc": {"issuer": ISSUER, "credentialSubject": {"encodedList": status_list({3})}}}
    fake_net.pages[f"{ISSUER}/status"] = FakePage(sign(list_payload, es, kid="k1"), "application/vc+jwt")

    def credential(index: int) -> str:
        status = {"type": "BitstringStatusListEntry", "statusPurpose": "revocation", "statusListIndex": index,
                  "statusListCredential": f"{ISSUER}/status"}
        return sign(ob3_payload(ISSUER, "Data Analysis with Python", status=status), es, kid="k1")

    revoked = _post(client, credential=credential(3))
    assert _verdict(revoked) == "CONTRADICTED" and revoked["evidence"][0]["attributes"]["revocation"] == "revoked"
    active = _post(client, credential=credential(4))
    assert _verdict(active) == "VERIFIED" and "STATUS_LIST_UNVERIFIED" not in active["warnings"]
    other = sign(ob3_payload(ISSUER, "Data Analysis with Python", status={"type": "1EdTechRevocationList",
                                                                          "id": f"{ISSUER}/r"}), es, kid="k1")
    data = _post(client, credential=other)
    assert _verdict(data) == "VERIFIED" and "REVOCATION_NOT_CHECKED" in data["warnings"]


def test_ob2_hosted(client, fake_net):
    url, pages = ob2_documents(ISSUER, badge_name="Data Analysis with Python")
    fake_net.pages.update(pages)
    data = _post(client, url=url)
    assert _verdict(data) == "VERIFIED"
    assert {item["key"] for item in data["evidence"]} == {"badge-1", "badge-class", "issuer", "issuer-domain"}
    assert _verdict(_post(client, url=url, holderEmail="else@example.org")) == "CONTRADICTED"
    assert _verdict(_post(client, url=url, holderEmail=None)) == "PARTLY_VERIFIED"
    # a pasted copy is checked against the hosted original
    pasted = json.loads(pages[url].body)
    assert _verdict(_post(client, credential=pasted)) == "VERIFIED"
    pasted["recipient"]["identity"] = "sha256$" + "0" * 64
    assert _verdict(_post(client, credential=json.dumps(pasted))) == "CONTRADICTED"


def test_ob2_foreign_origin_revoked_and_gone(client, fake_net):
    url, pages = ob2_documents(ISSUER, badge_name="Data Analysis with Python", assertion_host="https://copy.example")
    fake_net.pages.update(pages)
    assert _verdict(_post(client, url=url)) == "CONTRADICTED"
    url, pages = ob2_documents("https://issuer2.example", badge_name="Data Analysis with Python", revoked=True)
    fake_net.pages.update(pages)
    assert _verdict(_post(client, url=url)) == "CONTRADICTED"
    fake_net.pages["https://issuer3.example/a"] = FakePage("gone", "text/plain", status=410)
    gone = _post(client, url="https://issuer3.example/a")  # revoked, but the achievement is unknown
    assert _verdict(gone) == "NOT_FOUND"
    assert gone["verdicts"][0]["integrityNotes"][0]["code"] == "CREDENTIAL_INVALID"


def test_achievement_must_match_the_claim(client):
    key = ed25519_key()
    did = did_key_of(key)
    data = _post(client, credential=sign(ob3_payload(did, "Welding Safety"), key),
                 claims=[{"id": "fact:1", "type": "CERTIFICATION", "value": "Data Analysis with Python"},
                         {"id": "fact:2", "type": "SKILL", "value": "Python"}])
    assert [verdict["verdict"] for verdict in data["verdicts"]] == ["NOT_FOUND", "NOT_FOUND"]
    data = _post(client, credential=sign(ob3_payload(did, "Python for Data Analysis"), key),
                 claims=[{"id": "fact:2", "type": "SKILL", "value": "Python"}])
    assert _verdict(data) == "PARTLY_VERIFIED"  # matched by taxonomy skill; did:key issuer caps it


def test_bad_input(client):
    response = client.post("/api/v1/verify/badge", headers=HEADERS, json={"claims": CLAIM, "credential": "not a jwt"})
    assert response.status_code == 422 and response.json()["detail"]["code"] == "BAD_CREDENTIAL"
    response = client.post("/api/v1/verify/badge", headers=HEADERS, json={"claims": CLAIM})
    assert response.status_code == 422


@pytest.mark.parametrize("url", ["http://127.0.0.1/badge", "file:///etc/passwd"])
def test_badge_urls_go_through_the_ssrf_guard(client, url):
    response = client.post("/api/v1/verify/badge", headers=HEADERS, json={"claims": CLAIM, "url": url})
    assert response.status_code == 422 and response.json()["detail"]["code"] == "URL_BLOCKED"


# ------------------------------------------------------------------------------------- bug-hunt regressions
def _forged_ob2(fake_net, *, embed_issuer: bool) -> str:
    """An OB 2.0 badge hosted on the candidate's fresh domain whose issuer profile claims to be a trusted platform's
    issuer and lists the candidate's domain in allowedOrigins."""
    import hashlib

    host = "https://forger.example"
    fake_net.registered["forger.example"] = days_ago(20)
    issuer = {"type": "Issuer", "id": "https://www.credly.com/org/amazon-web-services", "name": "Amazon Web Services",
              "verification": {"allowedOrigins": ["forger.example"]}}
    badge = {"type": "BadgeClass", "id": f"{host}/badges/1", "name": "Data Analysis with Python",
             "issuer": issuer if embed_issuer else f"{host}/issuer"}
    assertion = {"type": "Assertion", "id": f"{host}/assertions/1", "badge": f"{host}/badges/1",
                 "recipient": {"type": "email", "hashed": True, "salt": "s",
                               "identity": "sha256$" + hashlib.sha256(b"holder@example.orgs").hexdigest()},
                 "verification": {"type": "hosted"}, "issuedOn": "2025-01-01T00:00:00Z"}
    for path, document in ((f"{host}/assertions/1", assertion), (f"{host}/badges/1", badge),
                           (f"{host}/issuer", issuer)):
        fake_net.pages[path] = FakePage(json.dumps(document), "application/json")
    return f"{host}/assertions/1"


def test_ob2_issuer_profile_cannot_claim_another_issuer(client, fake_net):
    data = _post(client, url=_forged_ob2(fake_net, embed_issuer=False))
    assert _verdict(data) == "CONTRADICTED"
    assert "claims to be a different issuer" in data["verdicts"][0]["reasons"][0]["text"]
    assert data["verdicts"][0]["domainTrust"] is None


def test_ob2_embedded_issuer_profile_is_not_authoritative(client, fake_net):
    data = _post(client, url=_forged_ob2(fake_net, embed_issuer=True))
    # the embedded profile's id is fetched instead (here it is not served), so its allowedOrigins never count and the
    # assertion is not on the claimed issuer's origin
    assert _verdict(data) == "CONTRADICTED"
    assert "not hosted on the issuer's domain" in data["verdicts"][0]["reasons"][0]["text"]
    assert data["verdicts"][0]["domainTrust"] is None


def test_ob2_issuer_id_is_where_the_profile_was_fetched(client, fake_net):
    url, pages = ob2_documents(ISSUER, badge_name="Data Analysis with Python")
    fake_net.pages.update(pages)
    data = _post(client, url=url)
    assert _verdict(data) == "VERIFIED" and data["evidence"][0]["attributes"]["issuer"] == f"{ISSUER}/issuer"
    del fake_net.pages[f"{ISSUER}/issuer"]  # an unreachable profile: same-origin hosting still holds
    assert _verdict(_post(client, url=url)) == "VERIFIED"


def test_a_matching_name_does_not_outweigh_a_different_email(client, fake_net):
    es = es256_key()
    fake_net.pages[f"{ISSUER}/.well-known/jwks.json"] = FakePage(json.dumps({"keys": [{**es.jwk, "kid": "k1"}]}),
                                                                 "application/json")
    payload = ob3_payload(ISSUER, "Data Analysis with Python", email="someone.else@example.org")
    payload["vc"]["credentialSubject"]["name"] = "Ana Sample"  # same name as the holder, different person
    data = _post(client, credential=sign(payload, es, kid="k1"))
    verdict = data["verdicts"][0]
    assert verdict["verdict"] == "PARTLY_VERIFIED" and verdict["ownershipProven"] is False
    assert "RECIPIENT_NOT_COMPARABLE" in data["warnings"]
    # several e-mail identities: any match counts
    payload = ob3_payload(ISSUER, "Data Analysis with Python", email="work@example.org")
    payload["vc"]["credentialSubject"]["identifier"].append(
        ob3_payload(ISSUER, "x")["vc"]["credentialSubject"]["identifier"][0])  # holder@example.org
    assert _verdict(_post(client, credential=sign(payload, es, kid="k1"))) == "VERIFIED"
    # a name alone still matches, a different name alone is a wrong recipient
    payload = ob3_payload(ISSUER, "Data Analysis with Python")
    payload["vc"]["credentialSubject"] = {**payload["vc"]["credentialSubject"], "identifier": [], "name": "Ana Sample"}
    assert _verdict(_post(client, credential=sign(payload, es, kid="k1"))) == "VERIFIED"
    payload["vc"]["credentialSubject"]["name"] = "Someone Else"
    assert _verdict(_post(client, credential=sign(payload, es, kid="k1"))) == "CONTRADICTED"


def test_every_status_entry_is_checked(client, fake_net):
    es = es256_key()
    fake_net.pages[f"{ISSUER}/.well-known/jwks.json"] = FakePage(json.dumps({"keys": [{**es.jwk, "kid": "k1"}]}),
                                                                 "application/json")
    for name, revoked in (("suspension", set()), ("revocation", {5})):
        payload = {"iss": ISSUER, "vc": {"issuer": ISSUER, "credentialSubject": {"encodedList": status_list(revoked)}}}
        fake_net.pages[f"{ISSUER}/{name}"] = FakePage(sign(payload, es, kid="k1"), "application/vc+jwt")
    status = [{"type": "BitstringStatusListEntry", "statusPurpose": "suspension", "statusListIndex": "5",
               "statusListCredential": f"{ISSUER}/suspension"},
              {"type": "BitstringStatusListEntry", "statusPurpose": "revocation", "statusListIndex": "5",
               "statusListCredential": f"{ISSUER}/revocation"}]
    data = _post(client, credential=sign(ob3_payload(ISSUER, "Data Analysis with Python", status=status), es,
                                         kid="k1"))
    assert _verdict(data) == "CONTRADICTED" and data["evidence"][0]["attributes"]["revocation"] == "revoked"
    status[1]["statusListIndex"] = "6"
    data = _post(client, credential=sign(ob3_payload(ISSUER, "Data Analysis with Python", status=status), es,
                                         kid="k1"))
    assert _verdict(data) == "VERIFIED" and data["evidence"][0]["attributes"]["revocation"] == "active"


def test_malformed_issuer_keys_and_payloads_are_not_server_errors(client, fake_net):
    rs = rs256_key()
    fake_net.pages[f"{ISSUER}/.well-known/jwks.json"] = FakePage(json.dumps({"keys": [{**rs.jwk, "n": "a"}]}),
                                                                 "application/json")
    data = _post(client, credential=sign(ob3_payload(ISSUER, "Data Analysis with Python"), rs))
    assert _verdict(data) == "NOT_CHECKABLE" and "KEY_UNRESOLVED" in data["warnings"]
    import base64

    def b64(value: bytes) -> str:
        return base64.urlsafe_b64encode(value).rstrip(b"=").decode()

    listed = f"{b64(json.dumps({'alg': 'EdDSA'}).encode())}.{b64(b'[1, 2]')}.c2ln"
    data = _post(client, credential=listed)
    assert data["verdicts"][0]["verdict"] == "NOT_FOUND"
    assert "CREDENTIAL_INVALID" in {note["code"] for note in data["verdicts"][0]["integrityNotes"]}
    response = client.post("/api/v1/verify/badge", headers=HEADERS, json={"claims": CLAIM, "credential": "[" * 50_000})
    assert response.status_code == 422 and response.json()["detail"]["code"] == "BAD_CREDENTIAL"


def test_expiry_allows_the_same_clock_skew_as_not_before(client, fake_net):
    es = es256_key()
    fake_net.pages[f"{ISSUER}/.well-known/jwks.json"] = FakePage(json.dumps({"keys": [{**es.jwk, "kid": "k1"}]}),
                                                                 "application/json")
    just_expired = sign(ob3_payload(ISSUER, "Data Analysis with Python", expires=NOW - timedelta(minutes=2)), es,
                        kid="k1")
    assert _verdict(_post(client, credential=just_expired)) == "VERIFIED"
    expired = sign(ob3_payload(ISSUER, "Data Analysis with Python", expires=NOW - timedelta(minutes=10)), es, kid="k1")
    assert _verdict(_post(client, credential=expired)) == "PARTLY_VERIFIED"
