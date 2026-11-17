"""Optional LLM rephrasing of free text (summaries and bullets) - never changes the gold labels.

``--llm gemini`` reads GEMINI_API_KEY (+ GEMINI_MODEL, default gemini-2.0-flash); ``--llm groq`` reads
GROQ_API_KEY (+ GROQ_MODEL, default llama-3.3-70b-versatile). Each line that carries skills is
sent with its exact skill surfaces; the rewrite is accepted only if every surface is still present
verbatim and the taxonomy lint finds no new skill. Otherwise the original line is kept. Only the
standard library is used (urllib), and nothing is sent unless --llm is given.
"""
from __future__ import annotations

import json
import os
import urllib.request

from resumelab.compose import Bullets, Document, Para, RoleEntry
from resumelab.taxonomy import load as load_taxonomy

PROMPT = ("Rewrite each resume line below so it reads like a real person wrote it. Keep the meaning, the numbers and the tone "
          "(keep weak or informal lines informal). Keep every phrase listed in 'keep' exactly as written. Do not add any tool, "
          "technology, skill, certification or employer. Reply with a JSON list of strings, one per input line, same order.\n\n")


class Rephraser:
    def __init__(self, provider: str, log=print) -> None:
        self.provider = provider
        self.log = log
        if provider == "gemini":
            self.key = os.environ.get("GEMINI_API_KEY")
            self.model = os.environ.get("GEMINI_MODEL", "gemini-2.0-flash")
        else:
            self.key = os.environ.get("GROQ_API_KEY")
            self.model = os.environ.get("GROQ_MODEL", "llama-3.3-70b-versatile")
        if not self.key:
            raise SystemExit(f"--llm {provider}: set {'GEMINI_API_KEY' if provider == 'gemini' else 'GROQ_API_KEY'}")
        self.accepted = self.rejected = 0

    def _call(self, prompt: str) -> str:
        if self.provider == "gemini":
            url = f"https://generativelanguage.googleapis.com/v1beta/models/{self.model}:generateContent?key={self.key}"
            body = {"contents": [{"parts": [{"text": prompt}]}], "generationConfig": {"temperature": 0.7, "responseMimeType": "application/json"}}
            headers = {"Content-Type": "application/json"}
        else:
            url = "https://api.groq.com/openai/v1/chat/completions"
            body = {"model": self.model, "temperature": 0.7, "messages": [{"role": "user", "content": prompt}]}
            headers = {"Content-Type": "application/json", "Authorization": f"Bearer {self.key}"}
        request = urllib.request.Request(url, data=json.dumps(body).encode(), headers=headers, method="POST")
        with urllib.request.urlopen(request, timeout=60) as response:  # noqa: S310 - fixed provider URLs
            data = json.loads(response.read())
        if self.provider == "gemini":
            return data["candidates"][0]["content"]["parts"][0]["text"]
        return data["choices"][0]["message"]["content"]

    def rephrase(self, doc: Document) -> None:
        lines = []
        for section in doc.sections:
            if section.kind not in {"SUMMARY", "EXPERIENCE", "PROJECTS", "INTERNSHIPS"}:
                continue
            for block in section.blocks:
                if isinstance(block, Para):
                    lines.append(block.line)
                elif isinstance(block, Bullets):
                    lines += block.items
                elif isinstance(block, RoleEntry):
                    lines += block.bullets
        if not lines:
            return
        payload = [{"line": line.text, "keep": sorted(set(line.surfaces.values()))} for line in lines]
        try:
            text = self._call(PROMPT + json.dumps(payload, ensure_ascii=False))
            start, end = text.find("["), text.rfind("]")
            rewritten = json.loads(text[start:end + 1])
        except Exception as exc:  # noqa: BLE001 - any provider failure keeps the original text
            self.log(f"  llm: kept original text ({type(exc).__name__})")
            return
        taxonomy = load_taxonomy()
        for line, new in zip(lines, rewritten):
            if not isinstance(new, str) or not new.strip():
                continue
            if not all(surface in new for surface in line.surfaces.values()):
                self.rejected += 1
                continue
            if taxonomy is not None:
                before = {skill.name for skill, _ in taxonomy.mentions(line.text)} | set(line.skills)
                after = {skill.name for skill, _ in taxonomy.mentions(new)}
                if after - before:
                    self.rejected += 1
                    continue
            line.text = new.strip()
            self.accepted += 1
