"""A small thread-safe in-process cache with a time-to-live (RDAP bootstrap and registration dates).

The agent is otherwise stateless; this cache only holds public registry facts (which RDAP server serves a TLD, when a
domain was registered) so repeated checks of the same employer or issuer domain do not hit the registries again.
Only successful lookups are stored. Demo and test networks use their own instances.
"""
from __future__ import annotations

import threading
import time
from typing import Any, Callable

DEFAULT_TTL_SECONDS = 24 * 3600
MAX_ENTRIES = 5000


class TtlCache:
    def __init__(self, ttl_seconds: float = DEFAULT_TTL_SECONDS, *, clock: Callable[[], float] = time.monotonic,
                 max_entries: int = MAX_ENTRIES) -> None:
        self.ttl_seconds = ttl_seconds
        self._clock = clock
        self._max_entries = max_entries
        self._lock = threading.Lock()
        self._data: dict[str, tuple[float, Any]] = {}

    def get(self, key: str) -> Any | None:
        with self._lock:
            entry = self._data.get(key)
            if entry is None:
                return None
            stored_at, value = entry
            if self._clock() - stored_at > self.ttl_seconds:
                del self._data[key]
                return None
            return value

    def put(self, key: str, value: Any) -> None:
        with self._lock:
            if len(self._data) >= self._max_entries and key not in self._data:
                # Drop the oldest entry; the cache is a convenience, not a store.
                oldest = min(self._data, key=lambda name: self._data[name][0])
                del self._data[oldest]
            self._data[key] = (self._clock(), value)

    def clear(self) -> None:
        with self._lock:
            self._data.clear()

    def __len__(self) -> int:
        with self._lock:
            return len(self._data)


_SHARED = TtlCache()


def shared_rdap_cache() -> TtlCache:
    """The process-wide cache used by the real network."""
    return _SHARED
