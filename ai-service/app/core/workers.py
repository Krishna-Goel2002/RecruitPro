"""A process pool for CPU-bound document parsing, so the event loop never parses PDFs itself.

Workers are started with the ``spawn`` method (safe with the server's threads, identical on
Windows and Linux) and created lazily, so the pool also works under ``TestClient`` without a
lifespan. A parse that exceeds its time limit, or a crashed worker, recycles the pool.
"""
from __future__ import annotations

import asyncio
import functools
import multiprocessing
import threading
from concurrent.futures import ProcessPoolExecutor
from concurrent.futures.process import BrokenProcessPool
from typing import Any, Callable, TypeVar

T = TypeVar("T")


class WorkerCrashed(Exception):
    """A worker process died while handling the job."""


class ParserPool:
    def __init__(self) -> None:
        self._executor: ProcessPoolExecutor | None = None
        self._workers = 0
        self._lock = threading.Lock()

    def _get(self, workers: int) -> ProcessPoolExecutor:
        with self._lock:
            if self._executor is None or self._workers != workers:
                self._discard()
                self._executor = ProcessPoolExecutor(max_workers=workers, mp_context=multiprocessing.get_context("spawn"))
                self._workers = workers
            return self._executor

    def _discard(self) -> None:
        executor, self._executor = self._executor, None
        if executor is None:
            return
        # A stuck worker cannot be cancelled through the public API; terminate the processes so a
        # pathological document cannot hold a worker forever (best effort, private attribute).
        for process in list(getattr(executor, "_processes", {}).values()):
            process.terminate()
        executor.shutdown(wait=False, cancel_futures=True)

    async def run(self, func: Callable[..., T], *args: Any, timeout: float, workers: int, **kwargs: Any) -> T:
        loop = asyncio.get_running_loop()
        future = loop.run_in_executor(self._get(workers), functools.partial(func, *args, **kwargs))
        try:
            return await asyncio.wait_for(future, timeout)
        except asyncio.TimeoutError:
            with self._lock:
                self._discard()
            raise
        except BrokenProcessPool as exc:
            with self._lock:
                self._discard()
            raise WorkerCrashed() from exc

    def shutdown(self) -> None:
        with self._lock:
            if self._executor is not None:
                self._executor.shutdown(wait=True, cancel_futures=True)
                self._executor = None


parser_pool = ParserPool()
