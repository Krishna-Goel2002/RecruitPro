"""Injectable time source, so polling, pacing and Retry-After waits run instantly in tests."""
from __future__ import annotations

import threading
import time


class Clock:
    """Real time."""

    def now(self) -> float:
        return time.monotonic()

    def wall(self) -> float:
        return time.time()

    def sleep(self, seconds: float) -> None:
        if seconds > 0:
            time.sleep(seconds)


class FakeClock(Clock):
    """Virtual time: ``sleep`` advances the clock immediately. Thread-safe; records every sleep."""

    def __init__(self, start: float = 1_000.0) -> None:
        self._t = start
        self._lock = threading.Lock()
        self.sleeps: list[float] = []

    def now(self) -> float:
        with self._lock:
            return self._t

    def wall(self) -> float:
        return 1_800_000_000.0 + self.now()

    def sleep(self, seconds: float) -> None:
        with self._lock:
            self.sleeps.append(seconds)
            if seconds > 0:
                self._t += seconds
