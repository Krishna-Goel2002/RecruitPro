"""SSRF guard: URL validation and address checks.

Rules (applied before every request and every redirect hop):

* scheme ``http`` or ``https`` only; explicit port, if any, must be 80 or 443; no user-info in the URL;
* the host is resolved and **every** returned address must be public: private, loopback, link-local, multicast,
  reserved, unspecified, shared (CGNAT), benchmarking, documentation, IPv4-mapped/NAT64/6to4/Teredo wrappers of such
  addresses and cloud metadata addresses are blocked;
* host names such as ``localhost``, ``*.local``, ``*.internal`` and ``metadata.google.internal`` are blocked by name;
* the connection is then made to the checked address (pinned), so a DNS answer that changes between the check and
  the connect (DNS rebinding) cannot redirect the request.
"""
from __future__ import annotations

import ipaddress
import socket
from dataclasses import dataclass
from typing import Callable
from urllib.parse import SplitResult, urlsplit

IPAddress = ipaddress.IPv4Address | ipaddress.IPv6Address
Resolver = Callable[[str], list[str]]

ALLOWED_SCHEMES = {"http", "https"}
ALLOWED_PORTS = {80, 443}
_BLOCKED_NAMES = ("localhost", "localhost.localdomain", "metadata", "metadata.google.internal", "instance-data")
_BLOCKED_SUFFIXES = (".localhost", ".local", ".internal", ".localdomain", ".home.arpa", ".lan", ".intranet", ".corp")
_EXTRA_BLOCKED = tuple(ipaddress.ip_network(net) for net in (
    "0.0.0.0/8", "100.64.0.0/10", "169.254.0.0/16", "192.0.0.0/24", "192.0.2.0/24", "198.18.0.0/15",
    "198.51.100.0/24", "203.0.113.0/24", "224.0.0.0/4", "240.0.0.0/4", "255.255.255.255/32",
    "::/128", "::1/128", "fc00::/7", "fe80::/10", "ff00::/8", "fd00:ec2::254/128", "64:ff9b::/96", "64:ff9b:1::/48",
    "2001:db8::/32", "100::/64",
))


class FetchError(Exception):
    """A fetch failed; ``code`` is a stable machine-readable reason."""

    def __init__(self, code: str, message: str = "") -> None:
        super().__init__(message or code)
        self.code = code


class BlockedUrlError(FetchError):
    """The URL or one of its addresses is not allowed."""

    def __init__(self, message: str) -> None:
        super().__init__("URL_BLOCKED", message)


@dataclass(frozen=True)
class CheckedTarget:
    url: str
    parts: SplitResult
    host: str  # lower-case host name or IP literal (without brackets)
    port: int
    address: str  # the checked, pinned IP address


def is_blocked_address(address: str) -> bool:
    try:
        ip: IPAddress = ipaddress.ip_address(address.split("%", 1)[0])
    except ValueError:
        return True
    if isinstance(ip, ipaddress.IPv6Address):
        if ip.ipv4_mapped is not None:
            return is_blocked_address(str(ip.ipv4_mapped))
        if ip.sixtofour is not None:
            return True  # 6to4 tunnels can reach arbitrary IPv4 space
        if ip.teredo is not None:
            return True
    if (ip.is_private or ip.is_loopback or ip.is_link_local or ip.is_multicast or ip.is_reserved
            or ip.is_unspecified or not ip.is_global):
        return True
    return any(ip in network for network in _EXTRA_BLOCKED)


def system_resolver(host: str) -> list[str]:
    """Resolve with the system resolver (A and AAAA)."""
    try:
        infos = socket.getaddrinfo(host, None, type=socket.SOCK_STREAM)
    except (socket.gaierror, UnicodeError) as exc:
        raise FetchError("DNS_FAILURE", "host could not be resolved") from exc
    return list(dict.fromkeys(str(info[4][0]) for info in infos))


def parse_url(url: str) -> tuple[SplitResult, str, int]:
    """Validate scheme, port, user-info and host name; return (parts, host, port)."""
    try:
        parts = urlsplit(url.strip())
        port = parts.port
    except ValueError as exc:
        raise BlockedUrlError("malformed URL") from exc
    scheme = parts.scheme.lower()
    if scheme not in ALLOWED_SCHEMES:
        raise BlockedUrlError("only http and https URLs are allowed")
    if parts.username is not None or parts.password is not None or "@" in parts.netloc:
        raise BlockedUrlError("credentials in URLs are not allowed")
    host = (parts.hostname or "").strip().lower().rstrip(".")
    if not host:
        raise BlockedUrlError("URL has no host")
    if port is None:
        port = 443 if scheme == "https" else 80
    if port not in ALLOWED_PORTS:
        raise BlockedUrlError("only ports 80 and 443 are allowed")
    if host in _BLOCKED_NAMES or host.endswith(_BLOCKED_SUFFIXES):
        raise BlockedUrlError("internal host names are not allowed")
    try:
        host.encode("ascii")
    except UnicodeEncodeError:
        try:
            host = host.encode("idna").decode("ascii")
        except UnicodeError as exc:
            raise BlockedUrlError("invalid host name") from exc
    return parts, host, port


def _is_ip_literal(host: str) -> bool:
    try:
        ipaddress.ip_address(host)
        return True
    except ValueError:
        return False


def check_url(url: str, resolver: Resolver) -> CheckedTarget:
    """Validate the URL and resolve its host; every address must be public. Returns the pinned target."""
    parts, host, port = parse_url(url)
    if _is_ip_literal(host):
        addresses = [host]
    else:
        addresses = resolver(host)
    if not addresses:
        raise FetchError("DNS_FAILURE", "host could not be resolved")
    for address in addresses:
        if is_blocked_address(address):
            raise BlockedUrlError("host resolves to a non-public address")
    return CheckedTarget(url=url.strip(), parts=parts, host=host, port=port, address=addresses[0])
