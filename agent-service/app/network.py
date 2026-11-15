"""Outbound network wiring for one verification: the guarded fetcher, code-host API transport, DNS TXT and MX lookups
and the RDAP cache (domain trust).

``Network`` is a seam: production uses the system resolver and real transports; tests pass ``httpx.MockTransport`` and
fake resolvers; demo mode (``AGENT_DEMO_FIXTURES=true`` and a ``demo-`` login/host) swaps in the synthetic fixtures
of ``app.demo`` so the full flow runs offline through the same code paths.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Callable

import httpx

from app.core.budget import Budget
from app.core.config import Settings
from app.fetch.cache import TtlCache, shared_rdap_cache
from app.fetch.client import SafeFetcher, TransportFactory
from app.fetch.ssrf import FetchError, Resolver, system_resolver

TxtResolver = Callable[[str], list[str]]
MxResolver = Callable[[str], list[str]]


def system_txt_resolver(host: str) -> list[str]:
    """TXT records of ``host`` via dnspython (5 s lifetime); [] when there are none."""
    import dns.exception
    import dns.resolver

    try:
        answer = dns.resolver.resolve(host, "TXT", lifetime=5.0)
    except (dns.resolver.NXDOMAIN, dns.resolver.NoAnswer, dns.resolver.NoNameservers):
        return []
    except dns.exception.DNSException as exc:
        raise FetchError("DNS_FAILURE", "TXT lookup failed") from exc
    records = []
    for record in answer:
        records.append(b"".join(record.strings).decode("utf-8", errors="replace"))
    return records


def system_mx_resolver(host: str) -> list[str]:
    """MX exchange host names of ``host`` via dnspython (5 s lifetime); [] when there are none or only a null MX
    (RFC 7505, exchange ``.``). DNS failures raise ``FetchError("DNS_FAILURE")``."""
    import dns.exception
    import dns.resolver

    try:
        answer = dns.resolver.resolve(host, "MX", lifetime=5.0)
    except (dns.resolver.NXDOMAIN, dns.resolver.NoAnswer, dns.resolver.NoNameservers):
        return []
    except dns.exception.DNSException as exc:
        raise FetchError("DNS_FAILURE", "MX lookup failed") from exc
    exchanges = [str(record.exchange).rstrip(".").lower() for record in answer]
    return [exchange for exchange in exchanges if exchange]


@dataclass
class Network:
    resolver: Resolver = system_resolver
    transport_factory: TransportFactory | None = None
    api_transport: httpx.BaseTransport | None = None
    txt_resolver: TxtResolver = system_txt_resolver
    mx_resolver: MxResolver = system_mx_resolver
    rdap_cache: TtlCache = field(default_factory=shared_rdap_cache)
    demo: bool = False
    labels: list[str] = field(default_factory=list)

    def fetcher(self, settings: Settings, budget: Budget) -> SafeFetcher:
        return SafeFetcher(settings, budget, resolver=self.resolver, transport_factory=self.transport_factory)


def is_demo_identifier(value: str) -> bool:
    """``demo-asha``, ``github:demo-asha``, ``https://demo-portfolio.example/...``, ``demo-site.example``."""
    text = value.strip().lower()
    if ":" in text and not text.startswith("http"):
        text = text.split(":", 1)[1]
    if "://" in text:
        text = text.split("://", 1)[1]
    return text.startswith("demo-")
