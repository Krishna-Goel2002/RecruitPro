"""The offline (rule-based) planner: always available, deterministic, no network.

One call returns exactly one next step for the backend's loop (at most 6 steps per user message):
``tool_call`` (read tools only), ``ask`` (slot filling), ``propose`` (every write) or ``answer``.
Observation data is only ever rendered, never interpreted as instructions.
"""
from __future__ import annotations

import re
from datetime import date, datetime
from typing import Any, Callable

from app.assistant import jobpost
from app.assistant.catalogue import Catalogue
from app.assistant.compose import compose, get, link, notice
from app.assistant.help_topics import TOPICS, topic_by_key
from app.assistant.intents import INTENTS, WRITE_INTENTS, Intent, detect_intent
from app.assistant.text import application_codes, code_to_id, normalise, parse_datetime, word_number
from app.models.assistant import StepRequest
from app.pipeline.deidentify import scrub_contacts

READ_TOOLS = {"list_jobs", "job_insights", "applicant_insights", "compare_applicants", "attention_digest", "my_interviews",
              "fairness_summary"}
DECISION_LABELS = {"SHORTLIST": "Shortlist", "REJECT": "Reject", "INTERVIEW": "Move to interview", "SELECT": "Select"}
JOB_STATUS_LABELS = {"PUBLISHED": "published", "ON_HOLD": "on hold", "CLOSED": "closed", "CANCELLED": "cancelled"}
REJECT_REASONS = ["Must-have requirements not evidenced", "Not enough relevant experience", "Position filled",
                  "Candidate withdrew interest", "Other"]
_AFFIRM = re.compile(r"^(?:yes|yep|yeah|ok|okay|sure|go ahead|looks good|lgtm|perfect|great|fine|done|confirm|publish(?: it)?|"
                     r"post it|save(?: it)?(?: as draft)?|create(?: it)?|ship it|that'?s (?:all|it|fine|good))\b")
_STRONG_QUESTION = re.compile(r"^(?:how|what|who|which|show|list|when|where|why|tell me|give me|open|go to|compare|explain)\b")

ProseWriter = Callable[[dict[str, Any]], "dict[str, Any] | None"]


def _today(request: StepRequest) -> date:
    if request.context.today:
        try:
            return date.fromisoformat(request.context.today)
        except ValueError:
            pass
    return date.today()


def latest_user_text(request: StepRequest) -> str:
    for message in reversed(request.messages):
        if message.role == "user":
            return message.text
    return ""


class OfflinePlanner:
    def __init__(self, request: StepRequest, *, prose_writer: ProseWriter | None = None) -> None:
        self.request = request
        self.context = request.context
        self.catalogue = Catalogue(request.tools)
        self.today = _today(request)
        self.text = latest_user_text(request)
        self.draft = request.draft.model_dump() if request.draft else None
        self.prose_writer = prose_writer
        self.used_writer = False

    # ------------------------------------------------------------------------------------- responses
    def _keep(self) -> dict[str, Any] | None:
        return self.draft

    def tool_call(self, tool: str, args: dict[str, Any], thought: str, draft: Any = "keep") -> dict[str, Any]:
        return {"type": "tool_call", "tool": tool, "args": args, "thought": thought[:200],
                "draft": self._keep() if draft == "keep" else draft}

    def ask(self, text: str, field: str, options: list[str] | None = None, draft: Any = "keep") -> dict[str, Any]:
        return {"type": "ask", "text": text, "field": field, "options": options or [],
                "draft": self._keep() if draft == "keep" else draft}

    def propose(self, action: str, args: dict[str, Any], summary: str, blocks: list[dict[str, Any]] | None = None,
                draft: Any = None) -> dict[str, Any]:
        return {"type": "propose", "action": action, "args": args, "summary": summary, "blocks": blocks or [],
                "draft": self._keep() if draft == "keep" else draft}

    def answer(self, text: str, blocks: list[dict[str, Any]] | None = None, draft: Any = "keep") -> dict[str, Any]:
        return {"type": "answer", "text": text, "blocks": [b for b in (blocks or []) if b],
                "draft": self._keep() if draft == "keep" else draft}

    # ------------------------------------------------------------------------------------- entry
    def plan(self) -> dict[str, Any]:
        intent = detect_intent(self.text, self.context)
        return self.plan_intent(intent)

    def plan_intent(self, intent: Intent) -> dict[str, Any]:
        draft = self.draft
        if intent.name == "cancel":
            return self._cancel(intent)
        if draft and draft.get("kind") == "job_posting" and self._continues_job_flow(intent):
            return self.create_job(intent)
        if draft and draft.get("kind") == "action" and self._continues_action(intent):
            merged = Intent(draft.get("intent", "unknown"), {**draft.get("fields", {})})
            self._fill_pending(merged, draft.get("pending"))
            self.draft = None
            return self.dispatch(merged)
        if draft and draft.get("kind") == "action":
            self.draft = None  # a new request replaces an unfinished one
        return self.dispatch(intent)

    def dispatch(self, intent: Intent) -> dict[str, Any]:
        handler = getattr(self, intent.name, None) if intent.name in INTENTS else None
        if handler is None:
            return self.unknown(intent)
        return handler(intent)

    # ------------------------------------------------------------------------------------- flow helpers
    def _continues_job_flow(self, intent: Intent) -> bool:
        if intent.name in {"create_job", "unknown", "greeting"}:
            return intent.name != "greeting" or bool(_AFFIRM.search(normalise(self.text)))
        lowered = normalise(self.text)
        pending = self.draft.get("pending") if self.draft else None
        if intent.slots.get("codes") or _STRONG_QUESTION.search(lowered) and intent.name not in WRITE_INTENTS:
            # A real question in the middle of the flow ("how is the backend job doing?") is answered; the draft is kept.
            probe = jobpost.extract(self.text, self.draft.get("fields", {}), None, self.today)
            if not (probe.updates or probe.skills) or intent.name in {"help", "navigate", "list_jobs", "attention_digest",
                                                                      "my_interviews", "fairness_summary"}:
                return False
        if intent.name in WRITE_INTENTS - {"create_job"} and not pending:
            return False
        return True

    def _continues_action(self, intent: Intent) -> bool:
        pending = self.draft.get("pending")
        if intent.name in {"unknown", "greeting"}:
            return True
        if pending in {"reason", "startsAt"} and intent.name not in {"help", "navigate", "list_jobs", "attention_digest",
                                                                     "my_interviews"}:
            return True
        if pending in {"applicationCode", "applicationCodes"} and application_codes(self.text) and \
                intent.name in {"applicant_insights", "compare_applicants"}:
            return True
        if pending == "job" and intent.name in {"job_insights", "unknown"}:
            return True
        return False

    def _fill_pending(self, intent: Intent, pending: str | None) -> None:
        text = self.text.strip()
        slots = intent.slots
        if pending == "reason":
            slots["reason"] = scrub_contacts(text)[:500]
        elif pending == "startsAt":
            when = parse_datetime(text, self.today)
            slots["startsAt"] = when.isoformat(timespec="minutes") if when else None
            slots["_retry"] = when is None
        elif pending in {"applicationCode", "applicationCodes"}:
            slots["codes"] = (slots.get("codes") or []) + [c for c in application_codes(text) if c not in (slots.get("codes") or [])]
        elif pending == "job":
            slots["jobPhrase"] = re.sub(r"(?i)^(?:the|for|it'?s|its)\s+", "", text).strip(" .?!")[:120]
            slots["jobId"] = None
        elif pending:
            slots.setdefault("extra", {})[pending] = text[:500]

    def _action_draft(self, intent: Intent, pending: str) -> dict[str, Any]:
        fields = {key: value for key, value in intent.slots.items() if not key.startswith("_")}
        return {"kind": "action", "intent": intent.name, "fields": fields, "pending": pending}

    def _observed(self, tool: str, **match: Any) -> Any:
        """The latest successful observation of ``tool`` whose args include ``match``; False when it failed."""
        for observation in reversed(self.request.observations):
            if observation.tool != tool:
                continue
            if any(observation.args.get(key) != value for key, value in match.items() if key in observation.args):
                continue
            return observation if observation.ok else False
        return None

    def _failed(self, tool: str, observation: Any) -> dict[str, Any]:
        error = None
        for item in reversed(self.request.observations):
            if item.tool == tool and not item.ok:
                error = item.error
                break
        detail = f" ({scrub_contacts(error)[:200]})" if error else ""
        return self.answer(f"I couldn't get that information{detail}.", [notice("The request failed; nothing was changed.", "error")])

    def _not_allowed(self, what: str) -> dict[str, Any]:
        return self.answer(f"{what} isn't available to you here. I can only use the tools your role allows.",
                           [notice("Ask an HR manager or admin if you need this.", "info")])

    # ------------------------------------------------------------------------------------- resolution
    def _values(self, intent: Intent, job_id: int | None = None) -> dict[str, Any]:
        codes = [code for code in intent.slots.get("codes") or [] if code]
        code = codes[0] if codes else None
        app_id = code_to_id(code)
        if code and code == self.context.applicationCode and self.context.applicationId:
            app_id = self.context.applicationId
        values: dict[str, Any] = {
            "jobId": job_id, "applicationCode": code, "applicationId": app_id, "applicationCodes": codes or None,
            "applicationIds": [code_to_id(c) for c in codes] if codes else None, "query": intent.slots.get("jobPhrase"),
            "topic": intent.slots.get("topic"), "status": intent.slots.get("status"),
            "decision": intent.slots.get("decision"), "reason": intent.slots.get("reason"),
            "range": intent.slots.get("range"), "startsAt": intent.slots.get("startsAt"),
            "durationMinutes": intent.slots.get("durationMinutes"),
        }
        values.update(intent.slots.get("extra") or {})
        return values

    def _jobs_from(self, observation: Any) -> list[dict[str, Any]]:
        data = observation.data if observation else None
        items = data if isinstance(data, list) else get(data, "jobs", "items", "content", default=[])
        return [item for item in items if isinstance(item, dict) and get(item, "id", "jobId") is not None and get(item, "title")]

    @staticmethod
    def _title_score(phrase: str, title: str) -> float:
        wanted = set(re.findall(r"[a-z0-9+#]+", phrase.lower())) - {"the", "job", "role", "posting", "position", "opening", "for", "a", "an"}
        have = set(re.findall(r"[a-z0-9+#]+", title.lower()))
        if not wanted:
            return 0.0
        return len(wanted & have) / len(wanted) - 0.01 * len(have - wanted)

    def resolve_job(self, intent: Intent, purpose: str) -> tuple[int | None, dict[str, Any] | None]:
        """(job id, None) when known; otherwise (None, the step that moves toward knowing it)."""
        slots = intent.slots
        if slots.get("jobId"):
            return int(slots["jobId"]), None
        phrase = slots.get("jobPhrase")
        if phrase and self.context.jobId and self.context.jobTitle and \
                self._title_score(phrase, self.context.jobTitle) >= 0.6:
            return self.context.jobId, None
        if not phrase and self.context.jobId:
            return self.context.jobId, None
        listing = self._observed("list_jobs")
        if listing is False:
            return None, self._failed("list_jobs", listing)
        if listing:
            jobs = self._jobs_from(listing)
            if phrase:
                scored = sorted(((self._title_score(phrase, str(get(job, "title"))), job) for job in jobs),
                                key=lambda pair: -pair[0])
                good = [job for score, job in scored if score >= 0.6]
                if len(good) == 1 or (good and scored[0][0] > scored[1][0] + 0.2 if len(scored) > 1 else good):
                    job = good[0]
                    slots["jobId"], slots["jobTitle"] = int(get(job, "id", "jobId")), str(get(job, "title"))
                    return slots["jobId"], None
                if len(good) > 1:
                    options = [str(get(job, "title"))[:80] for job in good[:6]]
                    return None, self.ask(f"Several jobs match '{phrase}'. Which one {purpose}?", "job", options,
                                          draft=self._action_draft(intent, "job"))
                options = [str(get(job, "title"))[:80] for job in jobs[:6]]
                return None, self.answer(f"I couldn't find a job called '{phrase}'.",
                                         [notice("Pick one of your jobs below and ask again.")] +
                                         ([{"kind": "table", "columns": ["Your jobs"], "rows": [[o] for o in options]}] if options else []),
                                         draft=self._action_draft(intent, "job") if options else "keep")
            if not jobs:
                return None, self.answer("You don't have any jobs yet.", [link("Create a job", "/jobs/new")])
            if len(jobs) == 1:
                slots["jobId"], slots["jobTitle"] = int(get(jobs[0], "id", "jobId")), str(get(jobs[0], "title"))
                return slots["jobId"], None
            return None, self.ask(f"Which job {purpose}?", "job", [str(get(job, "title"))[:80] for job in jobs[:6]],
                                  draft=self._action_draft(intent, "job"))
        if self.catalogue.reads("list_jobs"):
            args, _ = self.catalogue.build_args("list_jobs", {"query": phrase} if phrase else {})
            return None, self.tool_call("list_jobs", args, f"Looking up {phrase!r}" if phrase else "Looking up your jobs")
        return None, self.ask(f"Which job {purpose}? Open the job page and ask again, or tell me its title.", "job",
                              draft=self._action_draft(intent, "job"))

    def _job_title(self, job_id: int | None, intent: Intent) -> str:
        if intent.slots.get("jobTitle"):
            return str(intent.slots["jobTitle"])
        if job_id and job_id == self.context.jobId and self.context.jobTitle:
            return self.context.jobTitle
        listing = self._observed("list_jobs")
        for job in self._jobs_from(listing) if listing else []:
            if get(job, "id", "jobId") == job_id:
                return str(get(job, "title"))
        return f"job #{job_id}" if job_id else "this job"

    def _code(self, intent: Intent) -> str | None:
        codes = intent.slots.get("codes") or []
        if codes:
            return codes[0]
        if self.context.applicationCode:
            intent.slots["codes"] = [self.context.applicationCode]
            return self.context.applicationCode
        return None

    def _read(self, intent: Intent, tool: str, values: dict[str, Any], thought: str, match: dict[str, Any]) -> dict[str, Any]:
        observation = self._observed(tool, **match)
        if observation is False:
            return self._failed(tool, observation)
        if observation:
            text, blocks = compose(tool, observation.data, self.context)
            return self.answer(text, blocks)
        args, missing = self.catalogue.build_args(tool, values)
        if missing:
            return self._ask_missing(intent, missing[0].name)
        return self.tool_call(tool, args, thought)

    def _ask_missing(self, intent: Intent, name: str) -> dict[str, Any]:
        key = name.lower().replace("_", "")
        if key in {"applicationcode", "code", "applicationid", "applicantid"}:
            return self.ask("Which applicant? Give me the application code, for example A-3014.", "applicationCode",
                            draft=self._action_draft(intent, "applicationCode"))
        if key in {"applicationcodes", "codes", "applicationids"}:
            return self.ask("Which applicants should I compare? For example: A-3001 and A-3014.", "applicationCodes",
                            draft=self._action_draft(intent, "applicationCodes"))
        if key in {"jobid", "job"}:
            return self.ask("Which job? Tell me its title.", "job", draft=self._action_draft(intent, "job"))
        if key in {"startsat", "startat", "scheduledat", "datetime", "start", "starttime"}:
            return self.ask("When should the interview be? For example 'tomorrow at 3 pm' or '20 Nov 11:30'.", "startsAt",
                            ["Tomorrow 11 am", "Tomorrow 3 pm", "Next Monday 10 am"], draft=self._action_draft(intent, "startsAt"))
        if key in {"reason", "rejectionreason", "comment", "note"}:
            return self.ask("What is the reason? It is recorded with the decision.", "reason", REJECT_REASONS,
                            draft=self._action_draft(intent, "reason"))
        readable = re.sub(r"(?<!^)(?=[A-Z])", " ", name).replace("_", " ").lower()
        return self.ask(f"What should I use for {readable}?", name, draft=self._action_draft(intent, name))

    # ------------------------------------------------------------------------------------- intents: conversation
    def cancel(self, intent: Intent) -> dict[str, Any]:
        return self._cancel(intent)

    def _cancel(self, intent: Intent) -> dict[str, Any]:
        draft = self.draft
        if draft and draft.get("kind") == "job_posting" and intent.slots.get("restart"):
            self.draft = None
            fresh = {"kind": "job_posting", "fields": {}, "pending": "title", "asked": ["title"], "stage": "collecting", "inferred": []}
            return self.ask("Starting over. What role are you hiring for?", "title", [], draft=fresh)
        if draft:
            what = "job draft" if draft.get("kind") == "job_posting" else "request"
            return self.answer(f"Okay, I've discarded the {what}. Nothing was created or changed.", draft=None)
        return self.answer("Okay. Nothing is in progress.", draft=None)

    def greeting(self, intent: Intent) -> dict[str, Any]:
        if intent.slots.get("thanks"):
            return self.answer("You're welcome. Ask me anything about your jobs, applicants or interviews.")
        examples = []
        if self.catalogue.has("job_insights"):
            examples.append("“How is the Backend Engineer posting doing?”")
        if self.catalogue.has("applicant_insights"):
            examples.append("“Tell me about A-3014”")
        if self.catalogue.has("attention_digest"):
            examples.append("“What needs my attention today?”")
        if self.catalogue.has("my_interviews"):
            examples.append("“My interviews this week”")
        if self.catalogue.writes("create_job"):
            examples.append("“Post a job for a senior data analyst in Pune”")
        text = topic_by_key("assistant").text
        if examples:
            text += "\n\nTry: " + ", ".join(examples) + "."
        return self.answer(text)

    def help(self, intent: Intent) -> dict[str, Any]:
        topic = topic_by_key(intent.slots.get("topic") or "")
        if topic is None:
            if self.catalogue.reads("explain"):
                observation = self._observed("explain")
                if observation:
                    text, blocks = compose("explain", observation.data, self.context)
                    return self.answer(text, blocks)
                args, missing = self.catalogue.build_args("explain", {"topic": self.text[:200]})
                if not missing:
                    return self.tool_call("explain", args, "Looking up help")
            return self.answer("Here are the topics I can explain: " + ", ".join(t.title.lower() for t in TOPICS) + ".")
        blocks = []
        if topic.key == "fairness":
            blocks.append(link("Open the fairness report", f"/jobs/{self.context.jobId}?tab=fairness" if self.context.jobId else "/admin/fairness"))
        if topic.key in {"verification", "shadow_mode"} and (self.context.role or "").upper() in {"ADMIN", "HR_MANAGER"}:
            blocks.append(link("Open verification", "/admin/verification"))
        return self.answer(topic.text, blocks)

    def unknown(self, intent: Intent) -> dict[str, Any]:
        hints = []
        if self.context.page in {"job", "applicant"} and self.context.jobId and self.catalogue.has("job_insights"):
            hints.append("“How is this job doing?”")
        if self.context.applicationCode and self.catalogue.has("applicant_insights"):
            hints.append(f"“Tell me about {self.context.applicationCode}”")
        if self.catalogue.has("attention_digest"):
            hints.append("“What needs my attention?”")
        if self.catalogue.writes("create_job"):
            hints.append("“Post a job for …”")
        hints.append("“How does verified coverage work?”")
        return self.answer("I'm not sure what you need. You could ask, for example: " + ", ".join(hints[:4]) + ".")

    # ------------------------------------------------------------------------------------- intents: navigation
    def navigate(self, intent: Intent) -> dict[str, Any]:
        target = intent.slots.get("target")
        job_tabs = {"fairness": "fairness", "interview-kit": "interview-kit", "work-samples": "work-samples",
                    "details": "details", "applicants": None}
        applicant_tabs = {"claims", "evidence", "verification", "interviews", "work-samples"}
        static = {"interviews": ("My interviews", "/interviews"), "audit": ("Audit log", "/admin/audit"),
                  "users": ("Users", "/admin/users"), "invitations": ("Invitations", "/admin/invitations"),
                  "organization": ("Organisation settings", "/admin/organization"),
                  "notifications": ("Notifications", "/notifications"), "security": ("Security settings", "/account/security"),
                  "password": ("Change password", "/account/password"), "new-job": ("New job", "/jobs/new"),
                  "jobs": ("Jobs", "/jobs"), "admin": ("Admin overview", "/admin"), "assistant": ("Assistant", "/assistant"),
                  "verification": ("Verification queue", "/admin/verification")}
        on_applicant = self.context.page == "applicant" and self.context.applicationCode and self.context.jobId
        if target in applicant_tabs and (on_applicant or intent.slots.get("codes")):
            code = self._code(intent)
            app_id = self.context.applicationId if code == self.context.applicationCode and self.context.applicationId else code_to_id(code)
            job_id = self.context.jobId
            if app_id and job_id:
                tab = "" if target == "evidence" else f"?tab={target}"
                return self.answer(f"Here is {code}'s {target.replace('-', ' ')}.",
                                   [link(f"Open {code} — {target.replace('-', ' ')}", f"/jobs/{job_id}/applicants/{app_id}{tab}")])
        if target in job_tabs and (intent.slots.get("jobPhrase") or self.context.jobId or intent.slots.get("thisJob")):
            job_id, step = self.resolve_job(intent, "should I open")
            if step:
                return step
            tab = job_tabs[target]
            title = self._job_title(job_id, intent)
            href = f"/jobs/{job_id}" + (f"?tab={tab}" if tab else "")
            name = {"fairness": "fairness report", "interview-kit": "interview kit", "work-samples": "work samples",
                    "details": "details", None: "applicants"}[tab]
            return self.answer(f"Here is the {name} for {title}.", [link(f"Open {title} — {name}", href)])
        if target == "fairness":
            return self.answer("Here is the organisation fairness report.", [link("Open the fairness report", "/admin/fairness")])
        if target in {"applicants", "details", "work-samples", "interview-kit"}:
            return self.answer("Which job? Open the job from the list, or tell me its title.", [link("Open jobs", "/jobs")])
        if target in static:
            name, href = static[target]
            blocks = [link(f"Open {name.lower() if name[0].isupper() and name not in {'Jobs'} else name}", href)]
            if href.startswith("/admin") and target != "verification" and (self.context.role or "").upper() not in {"", "ADMIN"}:
                blocks.append(notice("This page is for admins.", "info"))
            return self.answer(f"Here is the link to {name[:1].lower() + name[1:] if not name.startswith('My') else 'your interviews'}.", blocks)
        return self.unknown(intent)

    # ------------------------------------------------------------------------------------- intents: reads
    def list_jobs(self, intent: Intent) -> dict[str, Any]:
        if not self.catalogue.reads("list_jobs"):
            return self._not_allowed("The job list")
        values = {"status": intent.slots.get("status")}
        return self._read(intent, "list_jobs", values, "Fetching your jobs", {})

    def job_insights(self, intent: Intent) -> dict[str, Any]:
        if not self.catalogue.reads("job_insights"):
            return self._not_allowed("Job insights")
        job_id, step = self.resolve_job(intent, "do you mean")
        if step:
            return step
        title = self._job_title(job_id, intent)
        return self._read(intent, "job_insights", self._values(intent, job_id), f"Reading the pipeline for {title}",
                          {"jobId": job_id})

    def applicant_insights(self, intent: Intent) -> dict[str, Any]:
        if not self.catalogue.reads("applicant_insights"):
            return self._not_allowed("Applicant details")
        code = self._code(intent)
        if not code:
            return self._ask_missing(intent, "applicationCode")
        values = self._values(intent, self.context.jobId)
        match = {key: values[key] for key in ("applicationCode", "applicationId") if values.get(key) is not None}
        return self._read(intent, "applicant_insights", values, f"Reading the evidence for {code}", match)

    def compare_applicants(self, intent: Intent) -> dict[str, Any]:
        if not self.catalogue.reads("compare_applicants"):
            return self._not_allowed("Comparing applicants")
        codes = list(intent.slots.get("codes") or [])
        if len(codes) == 1 and self.context.applicationCode and self.context.applicationCode not in codes:
            codes.insert(0, self.context.applicationCode)
        top = intent.slots.get("top")
        if len(codes) < 2 and top is not None or (len(codes) < 2 and re.search(r"\btop\b", normalise(self.text))):
            count = max(2, min(word_number(str(top or "3")) or 3, 4))
            job_id, step = self.resolve_job(intent, "should I compare applicants for")
            if step:
                return step
            if not self.catalogue.reads("job_insights"):
                return self._ask_missing(intent, "applicationCodes")
            insights = self._observed("job_insights", jobId=job_id)
            if insights is False:
                return self._failed("job_insights", insights)
            if not insights:
                args, missing = self.catalogue.build_args("job_insights", {"jobId": job_id})
                if missing:
                    return self._ask_missing(intent, missing[0].name)
                return self.tool_call("job_insights", args, "Finding the top applicants")
            ranked = get(insights.data, "top", "topApplicants", "candidates", "applicants", default=[])
            codes = [str(get(item, "code", "applicationCode")) for item in ranked if isinstance(item, dict)
                     and get(item, "code", "applicationCode")][:count]
            if len(codes) < 2:
                return self.answer("There are fewer than two ranked applicants to compare yet.")
        if len(codes) < 2:
            intent.slots["codes"] = codes
            return self._ask_missing(intent, "applicationCodes")
        intent.slots["codes"] = codes[:4]
        values = self._values(intent, self.context.jobId)
        return self._read(intent, "compare_applicants", values, "Comparing " + " and ".join(codes[:4]), {})

    def attention_digest(self, intent: Intent) -> dict[str, Any]:
        if not self.catalogue.reads("attention_digest"):
            if self.catalogue.reads("my_interviews"):
                return self.my_interviews(intent)
            return self._not_allowed("The attention digest")
        return self._read(intent, "attention_digest", {}, "Checking what needs your attention", {})

    def my_interviews(self, intent: Intent) -> dict[str, Any]:
        if not self.catalogue.reads("my_interviews"):
            return self._not_allowed("Interview lists")
        return self._read(intent, "my_interviews", {"range": intent.slots.get("range")}, "Fetching your interviews", {})

    def fairness_summary(self, intent: Intent) -> dict[str, Any]:
        if not self.catalogue.reads("fairness_summary"):
            return self._not_allowed("The fairness summary")
        job_id = None
        needs_job = any(spec.name.lower().replace("_", "") in {"jobid", "job"} and spec.required
                        for spec in self.catalogue.specs.get("fairness_summary", []))
        if intent.slots.get("jobPhrase") or self.context.jobId or needs_job:
            job_id, step = self.resolve_job(intent, "do you mean")
            if step:
                return step
        return self._read(intent, "fairness_summary", self._values(intent, job_id), "Reading the fairness report",
                          {"jobId": job_id} if job_id else {})

    # ------------------------------------------------------------------------------------- intents: writes
    def _write_args(self, intent: Intent, tool: str, values: dict[str, Any]) -> tuple[dict[str, Any] | None, dict[str, Any] | None]:
        args, missing = self.catalogue.build_args(tool, values)
        if missing:
            return None, self._ask_missing(intent, missing[0].name)
        return args, None

    def decide_application(self, intent: Intent) -> dict[str, Any]:
        if not self.catalogue.writes("decide_application"):
            return self._not_allowed("Changing an application's status")
        code = self._code(intent)
        decision = intent.slots.get("decision")
        if not code:
            return self._ask_missing(intent, "applicationCode")
        reason_arg = next((spec.name for spec in self.catalogue.specs.get("decide_application", [])
                           if spec.name.lower().replace("_", "") in {"reason", "rejectionreason", "comment", "note", "notes"}), None)
        if decision == "REJECT" and reason_arg and not intent.slots.get("reason"):
            return self.ask(f"What is the reason for rejecting {code}? It is recorded with the decision.", "reason",
                            REJECT_REASONS, draft=self._action_draft(intent, "reason"))
        job_id = self.context.jobId
        args, step = self._write_args(intent, "decide_application", self._values(intent, job_id))
        if step:
            return step
        summary = f"{DECISION_LABELS.get(decision, 'Update')} {code}"
        if job_id and self.context.jobTitle:
            summary += f" for {self.context.jobTitle}"
        if decision == "REJECT" and intent.slots.get("reason"):
            summary += f" — reason: {intent.slots['reason'][:200]}"
        blocks = []
        if decision == "SHORTLIST":
            blocks.append(notice("Under blind review, shortlisting reveals the candidate's identity to your team.", "info"))
        if decision == "REJECT":
            blocks.append(notice("Unverified must-haves are gates, not reasons on their own: consider a work sample or an "
                                 "interview question before rejecting on them.", "info"))
        others = [other for other in intent.slots.get("codes") or [] if other != code]
        if others:
            blocks.append(notice(f"One change at a time: after you confirm this one, ask me again for {', '.join(others[:5])}.", "info"))
        return self.propose("decide_application", args or {}, summary + ".", blocks)

    def schedule_interview(self, intent: Intent) -> dict[str, Any]:
        if not self.catalogue.writes("schedule_interview"):
            return self._not_allowed("Scheduling interviews")
        code = self._code(intent)
        if not code:
            return self._ask_missing(intent, "applicationCode")
        if not intent.slots.get("startsAt"):
            when = parse_datetime(self.text, self.today) if not intent.slots.get("_retry") else None
            if when:
                intent.slots["startsAt"] = when.isoformat(timespec="minutes")
        if intent.slots.get("startsAt") and datetime.fromisoformat(intent.slots["startsAt"]).date() < self.today:
            intent.slots["startsAt"] = None
            return self.ask("That time is in the past. When should the interview be?", "startsAt",
                            ["Tomorrow 11 am", "Tomorrow 3 pm"], draft=self._action_draft(intent, "startsAt"))
        has_time_arg = any(spec.name.lower().replace("_", "") in {"startsat", "startat", "scheduledat", "datetime", "start", "starttime"}
                           for spec in self.catalogue.specs.get("schedule_interview", []))
        if has_time_arg and not intent.slots.get("startsAt"):
            prefix = "Sorry, I couldn't read that time. " if intent.slots.get("_retry") else ""
            return self.ask(prefix + f"When should the interview with {code} be? For example 'tomorrow at 3 pm'.", "startsAt",
                            ["Tomorrow 11 am", "Tomorrow 3 pm", "Next Monday 10 am"], draft=self._action_draft(intent, "startsAt"))
        duration = re.search(r"\b(\d{2,3})\s*(?:min|mins|minutes)\b|\b(an|one|1)\s*(?:hour|hr)\b", normalise(self.text))
        if duration:
            intent.slots["durationMinutes"] = int(duration.group(1)) if duration.group(1) else 60
        args, step = self._write_args(intent, "schedule_interview", self._values(intent, self.context.jobId))
        if step:
            return step
        when_text = ""
        if intent.slots.get("startsAt"):
            moment = datetime.fromisoformat(intent.slots["startsAt"])
            when_text = " on " + moment.strftime("%a %d %b %Y, %H:%M")
        minutes = intent.slots.get("durationMinutes")
        summary = f"Schedule an interview with {code}{when_text}" + (f" ({minutes} min)" if minutes else "") + "."
        return self.propose("schedule_interview", args or {}, summary,
                            [notice("Times are in your local time zone. The interview kit for the job is attached if one exists.")])

    def assign_work_sample(self, intent: Intent) -> dict[str, Any]:
        if not self.catalogue.writes("assign_work_sample"):
            return self._not_allowed("Assigning work samples")
        code = self._code(intent)
        if not code:
            return self._ask_missing(intent, "applicationCode")
        days = re.search(r"\b(?:in|within)\s+(\d{1,2})\s+days?\b", normalise(self.text))
        if days:
            intent.slots.setdefault("extra", {})["days"] = int(days.group(1))
        args, step = self._write_args(intent, "assign_work_sample", self._values(intent, self.context.jobId))
        if step:
            return step
        title = self._job_title(self.context.jobId, intent) if self.context.jobId else None
        return self.propose("assign_work_sample", args or {},
                            f"Assign the work sample{' for ' + title if title else ''} to {code}.",
                            [notice("A scored work sample (3 of 4 or better) makes the linked requirements DEMONSTRATED evidence.")])

    def _generate(self, intent: Intent, tool: str, what: str) -> dict[str, Any]:
        if not self.catalogue.writes(tool):
            return self._not_allowed(f"Generating {what}s")
        job_id, step = self.resolve_job(intent, f"is the {what} for")
        if step:
            return step
        args, step = self._write_args(intent, tool, self._values(intent, job_id))
        if step:
            return step
        title = self._job_title(job_id, intent)
        return self.propose(tool, args or {}, f"Generate {'an' if what[0] in 'aeiou' else 'a'} {what} for {title} from its requirements.",
                            [notice(f"The {what} is created as a draft for you to review and edit before use.")])

    def generate_work_sample(self, intent: Intent) -> dict[str, Any]:
        return self._generate(intent, "generate_work_sample", "work sample")

    def generate_interview_kit(self, intent: Intent) -> dict[str, Any]:
        return self._generate(intent, "generate_interview_kit", "interview kit")

    def update_job_status(self, intent: Intent) -> dict[str, Any]:
        if not self.catalogue.writes("update_job_status"):
            return self._not_allowed("Changing a job's status")
        job_id, step = self.resolve_job(intent, "should I change")
        if step:
            return step
        args, step = self._write_args(intent, "update_job_status", self._values(intent, job_id))
        if step:
            return step
        status = intent.slots.get("status")
        title = self._job_title(job_id, intent)
        verb = {"PUBLISHED": "Publish", "ON_HOLD": "Put on hold", "CLOSED": "Close", "CANCELLED": "Cancel"}.get(status, "Update")
        blocks = []
        if status in {"CLOSED", "CANCELLED"}:
            blocks.append(notice("Closing stops new applications; existing applicants stay in the pipeline.", "warning"))
        return self.propose("update_job_status", args or {}, f"{verb} {title}.", blocks)

    # ------------------------------------------------------------------------------------- job posting
    def create_job(self, intent: Intent) -> dict[str, Any]:
        if not self.catalogue.writes("create_job"):
            return self._not_allowed("Posting a job")
        draft = self.draft if self.draft and self.draft.get("kind") == "job_posting" else None
        fields = dict(draft.get("fields", {})) if draft else {}
        pending = draft.get("pending") if draft else None
        asked = list(draft.get("asked", [])) if draft else []
        inferred = list(draft.get("inferred", [])) if draft else []
        stage = draft.get("stage", "collecting") if draft else "collecting"
        text = scrub_contacts(self.text)

        extraction = jobpost.extract(text, fields, pending, self.today)
        if len(text) > 500 and text.count("\n") >= 3 and not fields.get("sourceDescription"):
            fields["sourceDescription"] = text[:20000]
        updated, changes = jobpost.apply(fields, extraction)
        for key in extraction.updates:
            if key in inferred:
                inferred.remove(key)
        user_text = list(updated.get("userText", []))
        if text.strip():
            user_text = (user_text + [text[:1000]])[-10:]
        updated["userText"] = user_text
        updated, inferred = jobpost.infer(updated, inferred)
        notes: list[dict[str, Any]] = []
        if updated.get("deadline") and date.fromisoformat(updated["deadline"]) <= self.today:
            updated["deadline"] = None
            notes.append(notice("The closing date has to be in the future.", "warning"))
        if isinstance(updated.get("salaryMin"), int) and isinstance(updated.get("salaryMax"), int) and \
                updated["salaryMin"] > updated["salaryMax"]:
            updated["salaryMin"], updated["salaryMax"] = updated["salaryMax"], updated["salaryMin"]

        gaps = jobpost.missing(updated)
        no_requirements = not (updated.get("skills") or updated.get("extraRequirements") or updated.get("minYears")
                               or updated.get("sourceDescription"))
        if not gaps and no_requirements and "requirements" not in asked and not updated.get("skillsSkipped"):
            gaps = ["requirements"]
        if gaps:
            field = gaps[0]
            if field == "requirements":
                question, options = (f"Which skills are must-haves for the {updated.get('title')} role? Add nice-to-haves too "
                                     "(for example: 'SQL and Excel; Power BI is a plus'), or say 'skip'.", ["Skip"])
            else:
                question, field, options = jobpost.question_for(gaps, updated, self.today)
            if pending and pending == field and not changes and stage == "collecting":
                question = "Sorry, I didn't catch that. " + question
            elif changes and draft is None:
                question = self._understood(updated) + question
            asked = asked + [field] if field not in asked else asked
            new_draft = {"kind": "job_posting", "fields": updated, "pending": field, "asked": asked,
                         "stage": "collecting", "inferred": inferred}
            response = self.ask(question, field, options, draft=new_draft)
            if notes:
                response["text"] = notes[0]["text"] + " " + response["text"]
            return response

        if stage == "review" and not changes:
            lowered = normalise(self.text)
            if not _AFFIRM.search(lowered):
                return self.answer("Tell me what to change, for example 'make it remote', 'change openings to 3' or 'add "
                                   "Docker as nice to have'. Or use Save as draft / Publish on the preview card.")
        args, preview, used_writer = jobpost.build_preview(updated, inferred=inferred, writer=self.prose_writer)
        self.used_writer = used_writer
        updated["requirements"] = args.get("requirements", [])
        new_draft = {"kind": "job_posting", "fields": updated, "pending": None, "asked": asked, "stage": "review",
                     "inferred": inferred}
        blocks: list[dict[str, Any]] = [{"kind": "job_preview", "draft": preview}]
        if stage == "review" and changes:
            blocks.append(notice("Updated: " + self._change_text(changes, updated) + ".", "success"))
        if inferred:
            assumed = ", ".join(f"{name_map(key)} '{display(updated, key)}'" for key in inferred)
            blocks.append(notice(f"I assumed {assumed} from what you said — tell me if that's wrong.", "info"))
        if preview["warnings"]:
            blocks.append(notice(f"{len(preview['warnings'])} wording suggestion{'s' if len(preview['warnings']) != 1 else ''} "
                                 "in the preview: biased or vague wording narrows the applicant pool.", "warning"))
        if not updated.get("salaryMin") and not updated.get("salaryMax"):
            blocks.append(notice("No salary range is shown. Say, for example, 'salary 8-12 LPA' to add one; posted ranges "
                                 "attract more applicants.", "info"))
        blocks.extend(notes)
        final_args = self._create_job_args(args)
        return self.propose("create_job", final_args, jobpost.summary_line(args, preview), blocks, draft=new_draft)

    def _create_job_args(self, natural: dict[str, Any]) -> dict[str, Any]:
        """Natural job fields, plus any argument names the backend's catalogue declares for create_job."""
        aliases = {"vacancies": natural.get("openings"), "minimumSalary": natural.get("salaryMin"),
                   "maximumSalary": natural.get("salaryMax"), "job": natural, "draft": natural, "posting": natural}
        specs = self.catalogue.specs.get("create_job", [])
        names = {spec.name for spec in specs}
        if names and names <= {"job", "draft", "posting", "publish"}:
            return {name: natural for name in names if name != "publish"}
        result = dict(natural)
        for spec in specs:
            if spec.name not in result and aliases.get(spec.name) is not None:
                result[spec.name] = aliases[spec.name]
        return result

    @staticmethod
    def _understood(fields: dict[str, Any]) -> str:
        parts = [fields.get("title"), jobpost.compose_location(fields), jobpost.JOB_TYPES.get(fields.get("jobType", ""), None),
                 jobpost.LEVELS.get(fields.get("experienceLevel", ""), None),
                 f"{fields['openings']} opening{'s' if fields['openings'] != 1 else ''}" if fields.get("openings") else None]
        skills = [skill["name"] for skill in fields.get("skills", [])][:6]
        if skills:
            parts.append("skills: " + ", ".join(skills))
        known = [part for part in parts if part]
        return ("Got it — " + " · ".join(known) + ". ") if known else ""

    @staticmethod
    def _change_text(changes: list[str], fields: dict[str, Any]) -> str:
        readable = []
        for change in changes:
            if change.startswith("+"):
                skill = next((s for s in fields.get("skills", []) if s["name"] == change[1:]), None)
                readable.append(f"added {change[1:]}" + (" (nice to have)" if skill and not skill.get("mandatory") else ""))
            elif change.startswith("-"):
                readable.append(f"removed {change[1:]}")
            elif change.startswith("~"):
                skill = next((s for s in fields.get("skills", []) if s["name"] == change[1:]), None)
                kind = "nice to have" if skill and not skill.get("mandatory") else ("must-have gate" if skill and skill.get("knockout") else "must-have")
                readable.append(f"{change[1:]} is now {kind}")
            elif change == "requirement":
                readable.append("added a requirement")
            elif change in {"city", "workMode"}:
                readable.append(f"location → {jobpost.compose_location(fields)}")
            else:
                readable.append(f"{name_map(change)} → {display(fields, change)}")
        return ", ".join(dict.fromkeys(readable))


def name_map(key: str) -> str:
    return {"experienceLevel": "level", "jobType": "job type", "minYears": "minimum years", "salaryMin": "salary",
            "salaryMax": "salary", "openings": "openings", "deadline": "deadline", "department": "department",
            "title": "title", "summary": "summary", "responsibilities": "responsibilities"}.get(key, key)


def display(fields: dict[str, Any], key: str) -> str:
    value = fields.get(key)
    if key == "experienceLevel":
        return jobpost.LEVELS.get(str(value), str(value))
    if key == "jobType":
        return jobpost.JOB_TYPES.get(str(value), str(value))
    if key == "deadline":
        return date.fromisoformat(value).strftime("%d %b %Y") if value else "none"
    if key in {"salaryMin", "salaryMax"}:
        low, high = fields.get("salaryMin"), fields.get("salaryMax")
        return f"₹{low or 0:,} – ₹{high or 0:,}" if low or high else "not shown"
    if isinstance(value, list):
        return f"{len(value)} items"
    return str(value)
