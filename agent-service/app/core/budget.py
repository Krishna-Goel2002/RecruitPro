"""Per-verification request budget and wall-clock deadline.

Every outbound HTTP request (API call, page, robots.txt, DID document, ...) is charged here before it is sent.
When the request budget or the deadline is exhausted, ``BudgetExceeded`` is raised and the verifier stops early,
reporting ``usage.truncated = true``.
"""
from __future__ import annotations

import time
from dataclasses import dataclass, field


class BudgetExceeded(Exception):
    """No more outbound requests may be made for this verification."""


@dataclass
class Budget:
    max_requests: int
    deadline_seconds: float = 50.0
    requests: int = 0
    truncated: bool = False
    started: float = field(default_factory=time.monotonic)

    def charge(self) -> None:
        if self.requests >= self.max_requests or time.monotonic() - self.started > self.deadline_seconds:
            self.truncated = True
            raise BudgetExceeded("request budget exhausted")
        self.requests += 1

    @property
    def remaining(self) -> int:
        return max(0, self.max_requests - self.requests)
