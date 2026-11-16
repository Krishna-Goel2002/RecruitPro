"""Domain trust: independent evidence that a domain the candidate typed (a referee's employer domain) or that a badge
names as its issuer belongs to an established organisation, not to the candidate.

Result: ``TRUSTED`` | ``WEAK`` | ``UNTRUSTED`` with plain-language reasons, the raw signals, and who trusted it
(``AGENT`` from the signals below, ``ORGANIZATION`` from an organisation's own list). Only ``TRUSTED`` lets a verdict
reach ``VERIFIED`` (``app.verdicts.finalize`` caps the rest at ``PARTLY_VERIFIED``).

Signals (every network call goes through the SSRF-guarded fetcher or the DNS seam of ``app.network``):

* **RDAP registration date.** The IANA RDAP bootstrap (``https://data.iana.org/rdap/dns.json``) names the registry's
  RDAP server for the TLD; ``<server>/domain/<name>`` gives the ``registration`` event. A domain registered less than
  365 days before the check is ``UNTRUSTED`` (note ``*_NEW``). A name the registry does not know is retried one label up
  (``eu.acme.com`` -> ``acme.com``), at most 3 queries, never for a public second level such as ``co.uk``. RDAP that
  cannot be reached or gives no date means the age is unknown: never trusted by default (``WEAK``). The bootstrap and
  successful lookups are cached for 24 hours (``app.fetch.cache``).
* **MX records** (employer domains): the domain (or the referee's mail domain) receives mail.
* **Home page** (employer domains): ``https://<domain>/`` (then ``https://www.<domain>/``), robots.txt respected,
  mentions every significant token of the employer name (legal suffixes such as Inc, Ltd, Pvt, GmbH ignored), or a
  JSON-LD ``Organization`` on it has the same name.
* **Candidate-controlled domains**: a domain equal to, or a subdomain of, a website the candidate proved ownership of
  is ``UNTRUSTED``.
* **Allow-lists**: a built-in list of well-known Open Badges issuers (``app/data/trusted_issuers.json``, a starting
  list) and the organisation's own lists of trusted employer domains / issuer domains.

Employer domain: organisation list -> TRUSTED (ORGANIZATION); candidate-controlled or young -> UNTRUSTED;
age >= 365 days **and** MX **and** home page names the employer -> TRUSTED (AGENT); otherwise WEAK.
Badge issuer: organisation list -> TRUSTED (ORGANIZATION); built-in allow-list -> TRUSTED (AGENT); candidate-controlled
or young -> UNTRUSTED; age >= 365 days -> TRUSTED (AGENT); otherwise WEAK.
"""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from datetime import datetime
from functools import lru_cache
from pathlib import Path
from typing import Any, Callable
from urllib.parse import unquote, urlsplit

from app.core.clock import iso_z, parse_instant
from app.fetch.cache import TtlCache
from app.fetch.client import DATA_TYPES, PAGE_TYPES, Fetcher
from app.fetch.html import extract_html
from app.fetch.ssrf import FetchError
from app.verifiers.common import tokens

TRUSTED, WEAK, UNTRUSTED = "TRUSTED", "WEAK", "UNTRUSTED"
BY_AGENT, BY_ORGANIZATION = "AGENT", "ORGANIZATION"
EMPLOYER_DOMAIN, BADGE_ISSUER = "EMPLOYER_DOMAIN", "BADGE_ISSUER"
MIN_AGE_DAYS = 365
BOOTSTRAP_URL = "https://data.iana.org/rdap/dns.json"
RDAP_MAX_BYTES = 256 * 1024
BOOTSTRAP_MAX_BYTES = 512 * 1024
HOMEPAGE_MAX_BYTES = 1_000_000
MAX_RDAP_QUERIES = 3
RDAP_TYPES = DATA_TYPES | frozenset({"application/rdap+json"})
# Second-level labels that are registries' public suffixes, never a registrant's domain (no PSL is vendored).
PUBLIC_SECOND_LEVEL = frozenset({"co", "com", "net", "org", "ac", "gov", "edu", "ltd", "plc", "nic", "gen", "firm",
                                 "ind", "res", "mil", "sch", "me", "nom", "or", "ne", "go"})
LEGAL_WORDS = frozenset({"inc", "incorporated", "ltd", "limited", "llc", "llp", "lp", "plc", "pvt", "private", "corp",
                         "corporation", "co", "company", "gmbh", "ag", "sa", "sas", "sarl", "bv", "nv", "pte", "pty",
                         "srl", "spa", "oy", "ab", "kk", "kg", "the", "and", "of"})
# Platforms that hand out subdomains to anyone (the "private" part of the public suffix list, abridged; no PSL is
# vendored). The platform's registration date says nothing about who controls ``someone.github.io``, so the RDAP walk
# up to the platform's own domain must not make such a subdomain look established.
SHARED_HOSTING_SUFFIXES = frozenset({
    "github.io", "githubusercontent.com", "gitlab.io", "bitbucket.io", "codeberg.page", "sourceforge.io",
    "netlify.app", "netlify.com", "vercel.app", "now.sh", "pages.dev", "workers.dev", "herokuapp.com", "herokussl.com",
    "web.app", "firebaseapp.com", "appspot.com", "blogspot.com", "wordpress.com", "wixsite.com", "weebly.com",
    "squarespace.com", "azurewebsites.net", "azurestaticapps.net", "cloudapp.net", "cloudfront.net", "amazonaws.com",
    "onrender.com", "fly.dev", "glitch.me", "repl.co", "replit.app", "replit.dev", "surge.sh", "neocities.org",
    "readthedocs.io", "notion.site", "carrd.co", "medium.com", "substack.com", "tumblr.com", "ngrok.io",
    "ngrok-free.app", "ngrok.app", "duckdns.org", "no-ip.org", "ddns.net", "000webhostapp.com", "webflow.io",
    "framer.website", "framer.app", "deno.dev", "railway.app", "up.railway.app", "pythonanywhere.com",
    "streamlit.app", "hf.space", "translate.goog", "digitaloceanspaces.com", "ondigitalocean.app",
})
DATA_DIR = Path(__file__).resolve().parent / "data"

MxLookup = Callable[[str], list[str]]


# ----------------------------------------------------------------------------------------------- helpers
def normalise_domain(value: str | None) -> str:
    text = (value or "").strip().lower().rstrip(".")
    if "@" in text:
        text = text.rsplit("@", 1)[1]
    if "://" in text:
        text = (urlsplit(text).hostname or "").lower()
    text = text.split("/", 1)[0].split(":", 1)[0]
    return text[4:] if text.startswith("www.") else text


def issuer_domain(issuer_id: str | None) -> str | None:
    """Domain of a badge issuer id: ``did:web:host[:path]`` or an http(s) URL; None for did:key and anything else."""
    value = (issuer_id or "").strip()
    if value.startswith("did:web:"):
        host = unquote(value[len("did:web:"):].split(":", 1)[0])
        return normalise_domain(host) or None
    if value.startswith(("http://", "https://")):
        return normalise_domain(value) or None
    return None


def within(domain: str, parent: str) -> bool:
    """``domain`` equals ``parent`` or is a subdomain of it."""
    domain, parent = normalise_domain(domain), normalise_domain(parent)
    return bool(domain and parent) and (domain == parent or domain.endswith("." + parent))


def listed(domain: str, entries: list[str] | None) -> str | None:
    for entry in entries or []:
        if within(domain, entry):
            return normalise_domain(entry)
    return None


def shared_hosting_platform(domain: str) -> str | None:
    """The shared-hosting platform ``domain`` is a subdomain of (``mira.github.io`` -> ``github.io``), else None."""
    domain = normalise_domain(domain)
    return next((suffix for suffix in SHARED_HOSTING_SUFFIXES if domain.endswith("." + suffix)), None)


def _shared_hosting(trust: "DomainTrust") -> bool:
    platform = shared_hosting_platform(trust.domain)
    trust.signals["sharedHosting"] = platform
    if platform is None:
        return False
    trust.snapshot.append(f"Subdomain of the shared hosting platform {platform}.")
    trust.reasons.append(f"The domain is a subdomain of {platform}, where anyone can publish; the platform's "
                         "registration date says nothing about who controls it.")
    return True


def name_tokens(name: str | None) -> list[str]:
    return [token for token in tokens(name or "") if token not in LEGAL_WORDS]


@lru_cache(maxsize=1)
def builtin_issuers() -> tuple[str, ...]:
    """Domains of the built-in trusted-issuer list (``app/data/trusted_issuers.json``)."""
    data = json.loads((DATA_DIR / "trusted_issuers.json").read_text(encoding="utf-8"))
    return tuple(normalise_domain(entry["domain"]) for entry in data.get("issuers", []) if entry.get("domain"))


# -------------------------------------------------------------------------------------------------- RDAP
@dataclass
class RdapResult:
    state: str  # ok | not_found | unreachable
    registered_at: datetime | None = None
    queried: str | None = None
    server: str | None = None
    detail: str = ""


def _bootstrap(fetcher: Fetcher, cache: TtlCache) -> dict[str, list[str]] | None:
    cached = cache.get("rdap:bootstrap")
    if cached is not None:
        return cached
    try:
        result = fetcher.fetch(BOOTSTRAP_URL, accept=RDAP_TYPES, max_bytes=BOOTSTRAP_MAX_BYTES, respect_robots=False)
        document = json.loads(result.text()) if result.ok else None
    except (FetchError, ValueError, RecursionError):
        return None
    services: dict[str, list[str]] = {}
    for service in (document or {}).get("services", []) if isinstance(document, dict) else []:
        if not isinstance(service, list) or len(service) < 2:
            continue
        urls = [str(url) for url in service[1] if isinstance(url, str) and url.startswith("https://")]
        for tld in service[0]:
            if isinstance(tld, str) and urls:
                services[tld.lower().strip(".")] = urls
    if not services:
        return None
    cache.put("rdap:bootstrap", services)
    return services


def _registration(document: Any) -> datetime | None:
    events = document.get("events") if isinstance(document, dict) else None
    for event in events if isinstance(events, list) else []:
        if isinstance(event, dict) and str(event.get("eventAction", "")).lower() == "registration":
            return parse_instant(event.get("eventDate"))
    return None


def _rdap_names(domain: str, suffix: str) -> list[str]:
    """The domain, then its parents, down to (and excluding) the registry suffix; a public second level such as
    ``co.uk`` is never queried as a parent."""
    labels = domain.split(".")
    suffix_labels = len(suffix.split("."))
    names = []
    for start in range(len(labels) - suffix_labels):
        rest = labels[start:]
        if start > 0 and len(rest) == suffix_labels + 1 and rest[0] in PUBLIC_SECOND_LEVEL:
            break
        names.append(".".join(rest))
    return names[:MAX_RDAP_QUERIES]


def rdap_lookup(domain: str, fetcher: Fetcher, cache: TtlCache) -> RdapResult:
    """Registration date of ``domain`` (or of its registrable parent) from the registry's RDAP server."""
    domain = normalise_domain(domain)
    cached = cache.get("rdap:domain:" + domain)
    if cached is not None:
        return cached
    services = _bootstrap(fetcher, cache)
    if services is None:
        return RdapResult("unreachable", detail="the RDAP bootstrap could not be read")
    labels = domain.split(".")
    suffix = next((".".join(labels[i:]) for i in range(1, len(labels)) if ".".join(labels[i:]) in services), None)
    if suffix is None:
        return RdapResult("unreachable", detail="no RDAP service is registered for this top-level domain")
    base = services[suffix][0].rstrip("/") + "/"
    saw_error = ""
    for name in _rdap_names(domain, suffix):
        try:
            result = fetcher.fetch(base + "domain/" + name, accept=RDAP_TYPES, max_bytes=RDAP_MAX_BYTES,
                                   respect_robots=False)
        except FetchError as exc:
            return RdapResult("unreachable", queried=name, server=base, detail=f"RDAP request failed ({exc.code})")
        if result.status == 404:
            continue
        if not result.ok:
            saw_error = f"RDAP answered HTTP {result.status}"
            break
        try:
            document = json.loads(result.text())
        except (ValueError, RecursionError):
            return RdapResult("unreachable", queried=name, server=base, detail="RDAP answer is not JSON")
        registered = _registration(document)
        if registered is None:
            return RdapResult("unreachable", queried=name, server=base, detail="RDAP gives no registration date")
        outcome = RdapResult("ok", registered_at=registered, queried=name, server=base)
        cache.put("rdap:domain:" + domain, outcome)
        return outcome
    if saw_error:
        return RdapResult("unreachable", server=base, detail=saw_error)
    return RdapResult("not_found", server=base, detail="the registry does not know the domain")


# ------------------------------------------------------------------------------------------- assessment
@dataclass
class DomainTrust:
    check: str  # EMPLOYER_DOMAIN | BADGE_ISSUER
    domain: str
    status: str = WEAK
    trusted_by: str | None = None
    reasons: list[str] = field(default_factory=list)
    signals: dict[str, Any] = field(default_factory=dict)
    candidate_controlled: bool = False
    young: bool = False
    age_days: int | None = None
    snapshot: list[str] = field(default_factory=list)
    homepage_url: str | None = None

    @property
    def trusted(self) -> bool:
        return self.status == TRUSTED


def _age(trust: DomainTrust, rdap: RdapResult, now: datetime) -> str:
    """Records the RDAP signal; returns aged | young | unknown."""
    trust.signals.update({"rdap": rdap.state, "rdapServer": rdap.server,
                          "registeredAt": iso_z(rdap.registered_at) if rdap.registered_at else None})
    if rdap.state != "ok" or rdap.registered_at is None:
        trust.reasons.append(f"The domain's registration date could not be established ({rdap.detail}).")
        trust.snapshot.append(f"RDAP: {rdap.state} ({rdap.detail})")
        return "unknown"
    days = max(0, (now - rdap.registered_at).days)
    trust.age_days = days
    trust.signals["ageDays"] = days
    trust.snapshot.append(f"RDAP: {rdap.queried} registered {rdap.registered_at.date().isoformat()} "
                          f"({days} days before the check; server {rdap.server})")
    if days < MIN_AGE_DAYS:
        trust.young = True
        trust.reasons.append(f"The domain was registered {days} days before the check (less than {MIN_AGE_DAYS}).")
        return "young"
    trust.reasons.append(f"The domain was registered {days // 365} year(s) before the check.")
    return "aged"


def _start(check: str, domain: str, *, org_trusted: list[str] | None,
           candidate_domains: list[str] | None) -> DomainTrust:
    trust = DomainTrust(check=check, domain=domain)
    owned = listed(domain, candidate_domains)
    org = listed(domain, org_trusted)
    trust.candidate_controlled = owned is not None
    trust.signals.update({"candidateControlled": owned is not None, "organizationListed": org is not None})
    trust.snapshot.append(f"Domain: {domain}")
    if owned is not None:
        trust.snapshot.append(f"The candidate proved ownership of {owned}.")
    if org is not None:
        trust.snapshot.append(f"Listed as trusted by the organisation ({org}).")
    return trust


def _finish(trust: DomainTrust, status: str, by: str | None) -> DomainTrust:
    trust.status, trust.trusted_by = status, by
    trust.signals["status"] = status
    trust.signals["trustedBy"] = by
    trust.snapshot.append(f"Result: {status}" + (f" (trusted by {by.lower()})" if by else ""))
    return trust


def _mx(trust: DomainTrust, domains: list[str], lookup: MxLookup | None) -> str:
    if lookup is None:
        trust.signals["mx"] = "unknown"
        trust.reasons.append("Mail servers (MX) were not checked.")
        return "unknown"
    failed = False
    for name in dict.fromkeys(domain for domain in domains if domain):
        try:
            hosts = lookup(name)
        except Exception:  # noqa: BLE001 - any DNS trouble means "unknown", never a failed verification
            failed = True
            continue
        if hosts:
            trust.signals.update({"mx": "present", "mxHosts": hosts[:5]})
            trust.snapshot.append(f"MX of {name}: " + ", ".join(hosts[:5]))
            trust.reasons.append("The domain receives mail (MX records).")
            return "present"
    state = "unknown" if failed else "none"
    trust.signals["mx"] = state
    trust.snapshot.append("MX: " + ("lookup failed" if failed else "none"))
    trust.reasons.append("The MX lookup failed." if failed else "The domain has no mail servers (MX records).")
    return state


def _homepage(trust: DomainTrust, employer_name: str | None, fetcher: Fetcher) -> str:
    wanted = name_tokens(employer_name)
    if not wanted:
        trust.signals["homepage"] = "no_name"
        trust.reasons.append("No employer name was given, so the home page could not be compared with it.")
        trust.snapshot.append("Home page: not compared (no employer name)")
        return "no_name"
    last_error = "not reachable"
    for url in (f"https://{trust.domain}/", f"https://www.{trust.domain}/"):
        try:
            result = fetcher.fetch(url, accept=PAGE_TYPES, max_bytes=HOMEPAGE_MAX_BYTES, respect_robots=True)
        except FetchError as exc:
            last_error = exc.code
            if exc.code == "BUDGET":
                break
            continue
        if not result.ok:
            last_error = f"HTTP {result.status}"
            continue
        page = extract_html(result.text()) if result.content_type in {"text/html", "application/xhtml+xml"} else None
        title = page.title if page else ""
        text = page.text if page else result.text()
        meta = " ".join(value for key, value in (page.meta.items() if page else [])
                        if key in {"og:site_name", "application-name", "og:title", "description"})
        trust.homepage_url = result.url
        trust.signals["homepageUrl"] = result.url
        org_match = next((name for name in (page.organization_names if page else [])
                          if set(name_tokens(name)) == set(wanted)), None)
        available = set(tokens(" ".join([title, meta, text])))
        line = next((candidate for candidate in [title] + text.split("\n")
                     if wanted[0] in set(tokens(candidate))), "")
        trust.snapshot.append(f"Home page: {result.url}" + (f" (title: {title[:200]})" if title else ""))
        if org_match is not None:
            trust.signals["homepage"] = "match"
            trust.snapshot.append(f"Structured data Organization name: {org_match}")
            trust.reasons.append("The domain's home page declares the employer as its organisation.")
            return "match"
        if all(token in available for token in wanted):
            trust.signals["homepage"] = "match"
            trust.snapshot.append(f"Mentions the employer name: {line[:300]}")
            trust.reasons.append("The domain's home page names the employer.")
            return "match"
        trust.signals["homepage"] = "no_match"
        trust.snapshot.append("The employer name was not found on the home page.")
        trust.reasons.append("The domain's home page does not name the employer.")
        return "no_match"
    trust.signals["homepage"] = "unreachable"
    trust.snapshot.append(f"Home page: could not be fetched ({last_error})")
    trust.reasons.append(f"The domain's home page could not be fetched ({last_error}).")
    return "unreachable"


def assess_employer_domain(domain: str, employer_name: str | None, *, now: datetime, fetcher: Fetcher | None,
                           mx_lookup: MxLookup | None, cache: TtlCache | None, mail_domain: str | None = None,
                           org_trusted: list[str] | None = None,
                           candidate_domains: list[str] | None = None) -> DomainTrust:
    domain = normalise_domain(domain)
    trust = _start(EMPLOYER_DOMAIN, domain, org_trusted=org_trusted, candidate_domains=candidate_domains)
    if employer_name:
        trust.snapshot.append(f"Employer name: {employer_name}")
    if trust.signals["organizationListed"]:
        trust.reasons.append("The organisation lists this employer domain as trusted.")
        return _finish(trust, TRUSTED, BY_ORGANIZATION)
    if trust.candidate_controlled:
        trust.reasons.append("The candidate proved ownership of this domain, so it cannot vouch for the employer.")
        return _finish(trust, UNTRUSTED, None)
    if _shared_hosting(trust):
        return _finish(trust, WEAK, None)
    if fetcher is None or cache is None:
        trust.signals["rdap"] = "unreachable"
        trust.reasons.append("The domain could not be checked (no network).")
        return _finish(trust, WEAK, None)
    age = _age(trust, rdap_lookup(domain, fetcher, cache), now)
    if age == "young":
        return _finish(trust, UNTRUSTED, None)
    mx = _mx(trust, [domain, normalise_domain(mail_domain)], mx_lookup)
    page = _homepage(trust, employer_name, fetcher)
    if age == "aged" and mx == "present" and page == "match":
        return _finish(trust, TRUSTED, BY_AGENT)
    return _finish(trust, WEAK, None)


def assess_issuer_domain(domain: str, *, now: datetime, fetcher: Fetcher | None, cache: TtlCache | None,
                         org_trusted: list[str] | None = None,
                         candidate_domains: list[str] | None = None) -> DomainTrust:
    domain = normalise_domain(domain)
    trust = _start(BADGE_ISSUER, domain, org_trusted=org_trusted, candidate_domains=candidate_domains)
    allowlisted = listed(domain, list(builtin_issuers()))
    trust.signals["allowlisted"] = allowlisted is not None
    if trust.signals["organizationListed"]:
        trust.reasons.append("The organisation lists this issuer domain as trusted.")
        return _finish(trust, TRUSTED, BY_ORGANIZATION)
    if allowlisted is not None:
        trust.snapshot.append(f"On the built-in list of trusted Open Badges issuers ({allowlisted}).")
        trust.reasons.append("The issuer is on the built-in list of trusted Open Badges issuers.")
        return _finish(trust, TRUSTED, BY_AGENT)
    if trust.candidate_controlled:
        trust.reasons.append("The candidate proved ownership of the issuer's domain, so the badge is self-issued.")
        return _finish(trust, UNTRUSTED, None)
    if _shared_hosting(trust):
        return _finish(trust, WEAK, None)
    if fetcher is None or cache is None:
        trust.signals["rdap"] = "unreachable"
        trust.reasons.append("The issuer domain could not be checked (no network).")
        return _finish(trust, WEAK, None)
    age = _age(trust, rdap_lookup(domain, fetcher, cache), now)
    if age == "aged":
        return _finish(trust, TRUSTED, BY_AGENT)
    return _finish(trust, UNTRUSTED if age == "young" else WEAK, None)
