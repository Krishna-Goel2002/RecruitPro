"""Run state (``<runs-dir>/<run-id>/state.json``), saved after every step so ``cleanup`` works after a crash.

It holds the throwaway candidate passwords in plain text (needed to withdraw and delete later). The runs directory
is git-ignored; delete it when you no longer need cleanup.
"""
from __future__ import annotations

import json
import os
import threading
from pathlib import Path
from typing import Any


class RunState:
    def __init__(self, path: Path, data: dict[str, Any]) -> None:
        self.path = path
        self.data = data
        self._lock = threading.RLock()

    @classmethod
    def open(cls, runs_dir: str | Path, run_id: str, base_url: str | None = None,
             create: bool = True) -> "RunState":
        path = Path(runs_dir) / run_id / "state.json"
        if path.exists():
            data = json.loads(path.read_text(encoding="utf-8"))
        elif not create:
            raise FileNotFoundError(f"no state for run {run_id} at {path}")
        else:
            data = {"runId": run_id, "baseUrl": base_url, "jobs": {}, "candidates": {}}
        state = cls(path, data)
        if create:
            state.save()
        return state

    @property
    def run_id(self) -> str:
        return self.data["runId"]

    def job(self, key: str) -> dict[str, Any] | None:
        return self.data["jobs"].get(key)

    def set_job(self, key: str, **values: Any) -> None:
        with self._lock:
            self.data["jobs"].setdefault(key, {}).update(values)
            self.save()

    def candidate(self, resume_id: str) -> dict[str, Any]:
        with self._lock:
            return self.data["candidates"].setdefault(resume_id, {"applications": {}})

    def update_candidate(self, resume_id: str, **values: Any) -> None:
        with self._lock:
            self.candidate(resume_id).update(values)
            self.save()

    def set_application(self, resume_id: str, job_key: str, application_id: int) -> None:
        with self._lock:
            self.candidate(resume_id).setdefault("applications", {})[job_key] = application_id
            self.save()

    def save(self) -> None:
        with self._lock:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            tmp = self.path.with_suffix(".tmp")
            tmp.write_text(json.dumps(self.data, indent=2, default=str), encoding="utf-8")
            os.replace(tmp, self.path)
