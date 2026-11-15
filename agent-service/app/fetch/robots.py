"""robots.txt handling for the web crawl (user agent ``RecruitProVerifier``).

Policy: 2xx -> parsed rules; 401/403 -> everything disallowed; other 4xx (e.g. 404) -> everything allowed;
5xx, network error or a blocked robots URL -> everything disallowed (conservative). One fetch per origin.
Machine endpoints the candidate pointed us to directly (the ownership well-known file, badge assertions, DID
documents, JWKS, status lists) are fetched without consulting robots.txt.
"""
from __future__ import annotations

from urllib.robotparser import RobotFileParser

ROBOTS_AGENT = "RecruitProVerifier"


class RobotsPolicy:
    def __init__(self, allow_all: bool = False, disallow_all: bool = False, text: str = "") -> None:
        self._parser = RobotFileParser()
        if allow_all:
            self._parser.allow_all = True  # type: ignore[attr-defined]  # documented RobotFileParser flags
        elif disallow_all:
            self._parser.disallow_all = True  # type: ignore[attr-defined]
        else:
            self._parser.parse(text.splitlines())

    def allowed(self, url: str) -> bool:
        return self._parser.can_fetch(ROBOTS_AGENT, url)

    @classmethod
    def from_response(cls, status: int, text: str) -> "RobotsPolicy":
        if 200 <= status < 300:
            return cls(text=text)
        if status in (401, 403):
            return cls(disallow_all=True)
        if 400 <= status < 500:
            return cls(allow_all=True)
        return cls(disallow_all=True)
