"""Offline intent detection over the latest user message plus the page context.

Deterministic, ordered rules over a normalised message (lower-cased, chat shorthand expanded, near-miss keywords
repaired). The intent evaluation set (``eval/assistant_intents.py``) holds the accuracy bar.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Any

from app.assistant.help_topics import find_topic
from app.assistant.text import application_codes, normalise

INTENTS = (
    "greeting", "help", "navigate", "list_jobs", "job_insights", "applicant_insights", "compare_applicants",
    "attention_digest", "my_interviews", "fairness_summary", "decide_application", "assign_work_sample",
    "schedule_interview", "generate_work_sample", "generate_interview_kit", "update_job_status", "create_job",
    "cancel", "unknown",
)
WRITE_INTENTS = frozenset({"decide_application", "assign_work_sample", "schedule_interview", "generate_work_sample",
                           "generate_interview_kit", "update_job_status", "create_job"})


@dataclass
class Intent:
    name: str
    slots: dict[str, Any] = field(default_factory=dict)


_JOB_NOUN = r"(?:jobs?|postings?|roles?|positions?|openings?|vacanc(?:y|ies)|requisitions?|reqs?|job description|jd|listing|hiring)"
_LEAD = r"^(?:(?:hey|hi|ok|okay|so|now|also|and|then|yes|yeah|please)[, ]+)*(?:(?:can|could|would|will) you\s+|i (?:want|need|would like|wanna) to\s+|i'?d like to\s+|let'?s\s+|lets\s+|help me\s+|go ahead and\s+|quickly\s+|just\s+)*"
_QUESTION = re.compile(r"^(?:what|what's|whats|how|how's|hows|why|which|who|when|where|is|are|does|do|can|could|explain|tell me|meaning|define)\b|\?\s*$")
_EXPLAIN = re.compile(r"\b(?:what (?:is|are|does|do)|what's|whats|how (?:does|do|is|are)|explain|meaning of|define|tell me (?:about|what)|"
                      r"why (?:is|are|does|do|can'?t|cannot|don'?t)|difference between|help me understand|what do you mean)\b")

_CANCEL_RE = re.compile(r"^(?:(?:ok(?:ay)?|no|actually|hmm|please|wait)[, ]+)*(?:cancel(?: (?:it|this|that))?|stop|abort|never ?mind|forget (?:it|that|about it)|scrap (?:it|that|this)|"
                        r"discard(?: (?:it|this|that|the draft))?|start (?:over|again|afresh|from scratch)|restart|reset|"
                        r"clear(?: (?:it|this|the draft))?|leave it|drop it)\b")
_RESTART_RE = re.compile(r"\bstart (?:over|again|afresh|from scratch)\b|\brestart\b|\breset\b")
_GREETING_RE = re.compile(r"^(?:hi+|hello|hey|hiya|namaste|namaskar|good (?:morning|afternoon|evening)|yo|thanks|thank you|ok thanks|"
                          r"great thanks|cool|ok|okay)\b")
_CAPABILITIES_RE = re.compile(r"\bwhat (?:can|all can|else can) you do\b|\bwhat do you do\b|\bhow can you help\b|\bwhat are you\b|"
                              r"^help\s*[?!.]*$|\bwho are you\b|\byour (?:features|capabilities)\b")

_CREATE_JOB_RES = (
    re.compile(_LEAD + r"(?:post|create|add|draft|write|publish|open|put up|raise|start|set up|make)\s+(?:up\s+)?"
               r"(?!(?:the|this|that|it|my|our|these|those)\b)(?:a|an|one|new|another|\d+|two|three)?\s*(?:new\s+)?(?:[\w#+./-]+\s+){0,5}?" + _JOB_NOUN + r"\b"),
    re.compile(r"\b(?:we are|we're|i am|i'm|im|we)\s+(?:hiring|looking for|recruiting|trying to hire)\b"),
    re.compile(_LEAD + r"(?:hire|recruit)\s+(?:a|an|\d+|two|three|four|five|some|few|more)\b"),
    re.compile(r"\b(?:need|want|plan) to (?:hire|recruit)\b|\bnew (?:job|posting|opening|requisition|vacancy|position)\b"),
    re.compile(r"\b(?:job posting|job post|job ad|job advert)\s+for\b|\bhiring for (?:a|an)\b"),
    re.compile(r"\b(?:we have|we've got|we got|there is|there's) (?:a |an )?(?:new |urgent )?(?:requirement|opening|vacancy|position|need) for\b"),
    re.compile(r"^(?:urgently |urgent )?(?:looking for|hiring|requirement for|required|wanted|(?:an? )?(?:new )?(?:opening|openings|vacancy|"
               r"vacancies|position|positions) for)\s*:?\s+(?:a|an|\d+|two|three|four|five|one)?\s*\w"),
    # "need 3 freshers for customer support", "we require a java dev urgently", "require 2 resources for SAP FICO"
    re.compile(r"^(?:(?:urgently|urgent)[, ]+)?(?:we |i )?(?:need|needs|require|requires|want)\s+(?:a|an|\d+|one|two|three|four|five|few|some|more)\s+"
               r"(?:[\w#+./-]+\s+){0,4}?(?:analyst|engineer|developer|dev|designer|manager|executive|accountant|intern|lead|architect|"
               r"scientist|specialist|associate|consultant|recruiter|tester|admin|administrator|writer|officer|coordinator|"
               r"representative|agent|nurse|teacher|technician|fresher|resource|trainee|staff|hire|programmer|operator|"
               r"assistant|supervisor|driver|clerk|sales ?person|telecaller)(?:e?s)?\b"),
)
_JOB_STATUS_VERBS = (
    (re.compile(r"\b(?:unpublish|put (?:it|the [\w ]+|this [\w ]+)?\s*on hold|on hold|pause|freeze|hold)\b"), "ON_HOLD"),
    (re.compile(r"\b(?:re-?open|resume|unpause|un-?hold|reactivate|re-?publish|publish|go live)\b|"
                r"\bmake (?:it |this(?: \w+)? |the \w+(?: \w+)? )?live\b"), "PUBLISHED"),
    (re.compile(r"\b(?:close|shut|archive|end|stop accepting)\b"), "CLOSED"),
    (re.compile(r"\bcancel\b"), "CANCELLED"),
)
_DECISIONS = (
    (re.compile(r"\bundo (?:the )?shortlist\b"), None),
    (re.compile(r"\bshortlist(?:ed)?\b|\bput (?:\S+ )?(?:on|in) (?:the )?shortlist\b"), "SHORTLIST"),
    (re.compile(r"\b(?:reject(?:ed)?|decline|turn down|say no to|not (?:moving|going) forward with|drop)\b"), "REJECT"),
    (re.compile(r"\b(?:move|moving|advance|progress|push|send|take|put)\b.{0,40}\b(?:to|into|for) (?:the )?interviews?(?: stage| round)?\b|"
                r"\binterview stage\b|\bmark (?:\S+ )?(?:as )?interviewing\b"), "INTERVIEW"),
    (re.compile(r"\b(?:select|hire|make (?:an |the )?offer|offer (?:the )?(?:job|role|position)|extend (?:an )?offer|mark (?:\S+ )?(?:as )?selected)\b"),
     "SELECT"),
)
_SCHEDULE_RE = re.compile(r"\b(?:schedule|reschedule|book|set ?up|arrange|fix|plan|organi[sz]e|line up|set)\b.{0,50}\b(?:interviews?|call|meeting|round)\b")
_SCHEDULE_TIME_RE = re.compile(r"\binterviews?\b.{0,40}\b(?:tomorrow|today|at \d|\d\s*(?:am|pm)|on (?:mon|tue|wed|thu|fri|sat|sun))")
_ASSIGN_WS_RE = re.compile(r"\b(?:assign|send|give|share|set)\b.{0,40}\b(?:work samples?|assignments?|take[- ]homes?|tasks?|tests?|exercises?|case stud(?:y|ies))\b")
_GENERATE_WS_RE = re.compile(r"\b(?:generate|create|draft|make|prepare|build|design|write|come up with|suggest)\b.{0,40}\b(?:work samples?|take[- ]homes?|"
                             r"assignments?|exercises?|case stud(?:y|ies))\b")
_GENERATE_KIT_RE = re.compile(r"\b(?:generate|create|draft|make|prepare|build|write|come up with|suggest|give me|need|get)\b.{0,40}\b(?:interview kits?|"
                              r"interview questions?|question (?:bank|set|list)|kit)\b|\binterview kit\b")
_COMPARE_RE = re.compile(r"\bcompare\b|\bcomparison\b|\bvs\.?\b|\bversus\b|\bdifference between\b|\bwho(?:'s| is) (?:the )?(?:better|stronger)\b|"
                         r"\bwhich (?:one |of (?:the|them|these) )?(?:is|looks|seems) (?:better|stronger)\b|"
                         r"\bside[- ]by[- ]side\b|\bstack up\b|\bbetter fit\b")
_NAVIGATE_RE = re.compile(_LEAD + r"(?:open|go to|goto|take me to|navigate to|bring up|jump to|link (?:me )?to|show (?:me )?(?:the )?[\w ]{0,30}"
                          r"(?:page|tab|screen|section)|where (?:is|can i find|do i find)|how do i get to)\b")
_FAIRNESS_RE = re.compile(r"\bfairness\b|\badverse impact\b|\bbias(?:ed)? (?:report|check|summary)\b|\bany bias\b|\bbias (?:in|against|towards?)\b|\bselection rates?\b|\bdiversity\b|"
                          r"\bfour[- ]fifths\b|\b4/5(?:ths)?\b|\beeo\b|\bequal opportunity\b|\bis (?:the|this|our) .{0,40}\b(?:fair|biased)\b|"
                          r"\bfair across\b|\bbiased\b")
_DIGEST_RE = re.compile(r"\battention\b|\burgent\b|\bpending\b|\bto-?do\b|\bpriorit\w*\b|\bcatch me up\b|\bwhat'?s new\b|\banything new\b|"
                        r"\boverdue\b|\bbacklog\b|\binbox\b|\bfollow[- ]?ups?\b|\bwhat should i (?:do|work on|focus on|look at)\b|\bmy day\b|"
                        r"\btoday'?s (?:tasks|work|agenda)\b|\bdaily (?:digest|summary|brief)\b|\bdigest\b|\baction items?\b|"
                        r"\bneeds? (?:my )?(?:review|action|attention)\b|\bwaiting (?:for|on) me\b|\bstuck\b|\bscorecards?\b|"
                        r"\bnew (?:applicants|applications|candidates|resumes)\b|\bunscored\b|\bawaiting (?:review|scoring)\b|"
                        r"\bdeadlines? (?:coming|expiring|soon|this week)\b|\bexpiring\b|\banything i (?:missed|need to)\b|\bwhat'?s up today\b|"
                        r"\bon my plate\b|\btoday (?:summary|update|digest|tasks|agenda|brief)\b|\bsummary (?:for|of) (?:today|the day)\b")
_INTERVIEWS_RE = re.compile(r"\bmy (?:upcoming |next )?interviews?\b|\binterviews? (?:today|tomorrow|this week|next week|lined up|scheduled|"
                            r"coming up|upcoming|i have|for me|on my plate)\b|\b(?:upcoming|today|tomorrow|next|scheduled) interviews?\b|"
                            r"\bwho (?:am i|do i|i am) (?:interviewing|meeting)\b|\binterview schedule\b|\bmy (?:schedule|calendar|agenda)\b|"
                            r"\bdo i have (?:any )?interviews?\b|\bany interviews?\b|\binterviews? do i have\b|\b(?:am i|i am|i'm) meeting\b")
_JOB_INSIGHT_RE = re.compile(r"\bhow(?:'s| is| are)?\b.{0,60}\b(?:doing|going|performing|progressing|looking|coming along)\b|\bstatus\b|"
                             r"\bpipeline\b|\bprogress\b|\bfunnel\b|\bupdate on\b|\bstats\b|\bstatistics\b|\bnumbers\b|\binsights?\b|"
                             r"\bhow many (?:applicants|applications|candidates|resumes|people|profiles)\b|\b(?:top|best|strongest|leading) "
                             r"(?:\d+ |few |five |three |ten )?(?:candidates|applicants|profiles|people|matches)\b|\branking\b|"
                             r"\bwho (?:applied|is leading|are the top|is on top)\b|\bmost missing\b|\bmissing requirements?\b|"
                             r"\btime in stage\b|\bsummar(?:y|ise|ize)\b|\boverview\b|\breport on\b|\banalysis\b|\bbreakdown\b|"
                             r"\bresponse\b|\bapplicants? (?:for|on|to)\b|\bapplications? (?:for|on|to)\b")
_LIST_JOBS_RE = re.compile(r"\b(?:list|show|which|what|all|my|our|open|active|current|live|published|draft|closed|how many|any|kitne|kitni)\b.{0,30}"
                           r"\b(?:jobs|postings|positions|openings|roles|vacancies|requisitions|job posts)\b|"
                           r"\bwhat (?:are we|am i|roles are we) hiring\b|\b(?:jobs|postings) list\b")
_APPLICANT_REF_RE = re.compile(r"\b(?:this|the current|current|that) (?:candidate|applicant|application|person|profile|one)\b|"
                               r"\b(?:him|her|them|this guy|this girl)\b")
_THIS_JOB_RE = re.compile(r"\b(?:this|the current|current|that) " + _JOB_NOUN + r"\b|\bhere\b")
_APPLICANT_Q_RE = re.compile(r"\btell me (?:more )?about\b|\bsummar\w*\b|\bhow (?:strong|good|is)\b|\bevidence\b|\bprofile\b|\bdetails?\b|"
                             r"\bstatus\b|\bwhat about\b|\bexplain\b|\bwhy\b|\bgaps?\b|\bfit\b|\bcoverage\b|\bverified\b|\bopen questions?\b|"
                             r"\bwhat (?:should|do) i ask\b|\bscore\b|\bstrengths?\b|\bweakness\w*\b|\binsights?\b|\boverview\b|\bdoing\b")
_NAV_TARGETS: tuple[tuple[re.Pattern[str], str], ...] = tuple((re.compile(pattern), target) for pattern, target in (
    (r"\bfairness\b|\badverse impact\b", "fairness"),
    (r"\binterview kit\b|\bkit\b", "interview-kit"),
    (r"\bwork samples?\b|\bassignments?\b", "work-samples"),
    (r"\b(?:job )?details\b|\bedit (?:the )?job\b|\bjob settings\b", "details"),
    (r"\bapplicants\b|\branking\b|\bcandidates\b|\bpipeline\b", "applicants"),
    (r"\bclaims\b", "claims"),
    (r"\bverification(?: queue)?\b|\bverdicts\b|\bshadow\b", "verification"),
    (r"\bmy interviews\b|\binterviews\b|\bscorecards?\b", "interviews"),
    (r"\baudit\b", "audit"),
    (r"\busers\b|\bteam members\b|\bstaff\b", "users"),
    (r"\binvit\w+\b", "invitations"),
    (r"\borgani[sz]ation(?: settings)?\b|\bsettings\b|\bbranding\b", "organization"),
    (r"\bnotifications?\b", "notifications"),
    (r"\bsecurity\b|\bmfa\b|\b2fa\b|\btwo[- ]factor\b", "security"),
    (r"\bpassword\b", "password"),
    (r"\bnew job\b|\bcreate (?:a )?job\b|\bjob form\b", "new-job"),
    (r"\bjobs\b|\bjob list\b|\bpostings\b", "jobs"),
    (r"\badmin\b|\bdashboard\b", "admin"),
    (r"\bevidence\b|\bapplicant page\b|\bprofile\b", "evidence"),
    (r"\bassistant\b", "assistant"),
))


def job_phrase(raw: str) -> str | None:
    """The job a message names ("the Backend Engineer posting" -> "Backend Engineer"), if any."""
    text = " ".join((raw or "").replace("’", "'").split())
    patterns = (
        r"\b(?:the|our|my)\s+(?P<t>[A-Za-z0-9#+./&' -]{2,60}?)\s+" + _JOB_NOUN + r"\b",
        r"\b(?:for|of|on|about|in|to)\s+(?:the\s+|our\s+|my\s+)?(?P<t>[A-Za-z0-9#+./&' -]{2,60}?)\s+" + _JOB_NOUN + r"\b",
        r"^(?:how(?:'s| is| are)?|hows)\s+(?:the\s+|our\s+)?(?P<t>[A-Za-z0-9#+./&' -]{2,60}?)\s+(?:" + _JOB_NOUN + r"\s+)?"
        r"(?:doing|going|performing|progressing|looking|coming along)\b",
        r"\b(?:for|of|on|about)\s+(?:the\s+)?(?P<t>[A-Z][\w#+./&-]*(?:\s+(?:[A-Z][\w#+./&-]*|of|and|&)){0,5})",
        r"\b" + _JOB_NOUN + r"\s*[:\-]\s*(?P<t>[A-Za-z0-9#+./&' -]{2,60})$",
        r"\bfor\s+(?:the\s+)?(?P<t>[A-Za-z][A-Za-z0-9#+./& -]{2,40}?)\s*[?.!]*$",
    )
    for pattern in patterns:
        match = re.search(pattern, text, re.I if not pattern.startswith(r"\b(?:for|of|on|about)\s+(?:the\s+)?(?P<t>[A-Z]") else 0)
        if not match:
            continue
        phrase = re.sub(r"^(?:the|our|my|a|an)\s+", "", match.group("t").strip(" .,'?!"), flags=re.I)
        phrase = re.sub(r"\s+(?:job|posting|role|position|opening)$", "", phrase, flags=re.I)
        words = phrase.lower().split()
        if set(words) & {"this", "that", "these", "those", "current", "report", "tab", "page", "link", "screen"}:
            continue
        if not words or len(words) > 6 or words[0] in {"this", "that", "current", "new", "all", "open", "my", "our", "any", "which",
                                                        "what", "each", "every", "these", "those", "other", "it", "your", "a", "an"}:
            continue
        if all(word in {"the", "job", "jobs", "applicants", "candidates", "hiring", "it", "things", "everything"} for word in words):
            continue
        if re.fullmatch(r"(?:today|tomorrow|this week|next week|interviews?|me|us|hold|now|later|him|her|them|it|"
                        r"interview|an interview|(?:a |an )?(?:intern|interns)|marketing|review|approval)", phrase.lower()):
            continue
        return phrase
    return None


def nav_target(text: str) -> str | None:
    for pattern, target in _NAV_TARGETS:
        if pattern.search(text):
            return target
    return None


def _decision(text: str) -> str | None:
    for pattern, decision in _DECISIONS:
        if pattern.search(text):
            return decision or "UNDO_SHORTLIST"
    return None


def _job_status(text: str) -> str | None:
    for pattern, status in _JOB_STATUS_VERBS:
        if pattern.search(text):
            return status
    return None


def detect_intent(message: str, context: Any = None) -> Intent:
    """The intent of ``message`` (the latest user message) given the page ``context``."""
    raw = (message or "").strip()
    text = normalise(raw)
    if not text:
        return Intent("unknown")
    codes = application_codes(raw)
    page = getattr(context, "page", "other") if context is not None else "other"
    context_code = getattr(context, "applicationCode", None) if context is not None else None
    applicant_ref = bool(_APPLICANT_REF_RE.search(text))
    if applicant_ref and context_code and context_code not in codes:
        codes = codes + [context_code] if codes else [context_code]
    phrase = job_phrase(raw)
    this_job = bool(_THIS_JOB_RE.search(text))
    words = text.split()
    question = bool(_QUESTION.search(text))
    slots: dict[str, Any] = {"codes": codes, "jobPhrase": phrase, "thisJob": this_job}

    if len(words) <= 6 and _CANCEL_RE.search(text) and not re.search(r"\b(?:job|posting|interview|role|position)\b", text):
        return Intent("cancel", {**slots, "restart": bool(_RESTART_RE.search(text))})
    if _CAPABILITIES_RE.search(text):
        return Intent("greeting", {**slots, "capabilities": True})
    if len(words) <= 4 and _GREETING_RE.search(text) and not codes:
        return Intent("greeting", {**slots, "thanks": bool(re.search(r"\bthanks?\b|\bthank you\b", text))})

    explaining = bool(_EXPLAIN.search(text)) and not codes
    topic = find_topic(text)
    if explaining and topic and not phrase and not re.search(r"\b(?:for|of|on) (?:this|the|my|our) " + _JOB_NOUN, text):
        return Intent("help", {**slots, "topic": topic.key})

    # ---- writes (imperative wording; questions about the same words are reads)
    imperative = re.search(r"^(?:how many|who|which|list|show|what|do i|do we|are there|is there|any)\b", text) is None
    if imperative and not codes and _GENERATE_WS_RE.search(text) and not _ASSIGN_WS_RE.search(text) and not _NAVIGATE_RE.search(text):
        return Intent("generate_work_sample", slots)
    if not codes and any(pattern.search(text) for pattern in _CREATE_JOB_RES) \
            and not re.search(r"^(?:how|what|which|who|show|list|any|open|active|live)\b(?!.*\b(?:new|a|an)\b)", text) \
            and not (_NAVIGATE_RE.search(text) and not re.search(r"\b(?:a|an|new|another)\b", text)):
        return Intent("create_job", slots)
    if imperative:
        if _GENERATE_KIT_RE.search(text) and not _NAVIGATE_RE.search(text):
            return Intent("generate_interview_kit", slots)
        if _ASSIGN_WS_RE.search(text) and (codes or applicant_ref or page == "applicant"):
            return Intent("assign_work_sample", slots)
        if _GENERATE_WS_RE.search(text) and not _NAVIGATE_RE.search(text):
            return Intent("generate_work_sample", slots)
        if (_SCHEDULE_RE.search(text) or (_SCHEDULE_TIME_RE.search(text) and (codes or applicant_ref))) \
                and not re.search(r"\bmy interviews\b", text):
            return Intent("schedule_interview", slots)
        decision = _decision(text)
        if decision and (codes or applicant_ref or page == "applicant") and not _COMPARE_RE.search(text):
            if decision != "UNDO_SHORTLIST":
                reason = re.search(r"\b(?:because|since|as|reason[: ]+|due to|for)\s+(.{3,300})$", raw, re.I)
                return Intent("decide_application", {**slots, "decision": decision,
                                                     "reason": reason.group(1).strip(" .") if reason else None})
        status = _job_status(text)
        if status and re.search(_LEAD + r"(?:un|re-?)?(?:publish|close|pause|put|hold|re-?open|resume|cancel|archive|shut|end|freeze|"
                                          r"unpause|reactivate|stop|make|go)\b", text) and \
                (phrase or this_job or re.search(r"\b(?:it|job|posting|role|position|opening|hiring)\b", text)) and not codes:
            return Intent("update_job_status", {**slots, "status": status})

    if _COMPARE_RE.search(text) and (len(codes) >= 1 or re.search(r"\btop\s*(\d+|two|three|four|five)?\b", text)):
        top = re.search(r"\btop\s*(\d+|two|three|four|five)\b", text)
        return Intent("compare_applicants", {**slots, "top": top.group(1) if top else None})
    if _NAVIGATE_RE.search(text):
        target = nav_target(text)
        if target:
            return Intent("navigate", {**slots, "target": target})
    if (explaining or question) and topic and not codes and not phrase and not this_job and topic.key != "fairness" \
            and not _DIGEST_RE.search(text) and not _INTERVIEWS_RE.search(text):
        return Intent("help", {**slots, "topic": topic.key})
    if _FAIRNESS_RE.search(text):
        return Intent("fairness_summary", slots)
    if codes and not _LIST_JOBS_RE.search(text):
        return Intent("applicant_insights", slots)
    if _INTERVIEWS_RE.search(text) and not re.search(r"\bscorecards?\b|\bwithout\b|\bmissing\b", text):
        rng = "today" if "today" in text else "tomorrow" if "tomorrow" in text else "week" if "week" in text else \
            "upcoming" if re.search(r"\b(?:upcoming|next|coming up|lined up)\b", text) else None
        return Intent("my_interviews", {**slots, "range": rng})
    if _DIGEST_RE.search(text) and not (phrase and _JOB_INSIGHT_RE.search(text)):
        return Intent("attention_digest", slots)
    if _LIST_JOBS_RE.search(text) and not this_job and not (phrase and _JOB_INSIGHT_RE.search(text)):
        status = next((value for word, value in (("draft", "DRAFT"), ("closed", "CLOSED"), ("on hold", "ON_HOLD"),
                                                 ("published", "PUBLISHED"), ("live", "PUBLISHED"), ("open", "PUBLISHED"),
                                                 ("active", "PUBLISHED")) if re.search(rf"\b{word}\b", text)), None)
        return Intent("list_jobs", {**slots, "status": status})
    if _JOB_INSIGHT_RE.search(text) and (phrase or this_job or page in {"job", "applicant"} or re.search(_JOB_NOUN, text)):
        return Intent("job_insights", slots)
    if applicant_ref and context_code:
        return Intent("applicant_insights", slots)
    if page == "applicant" and context_code and _APPLICANT_Q_RE.search(text):
        return Intent("applicant_insights", {**slots, "codes": [context_code]})
    if topic and (question or explaining or len(words) <= 4):
        return Intent("help", {**slots, "topic": topic.key})
    if phrase and (question or _JOB_INSIGHT_RE.search(text)):
        return Intent("job_insights", slots)
    if page == "job" and question:
        return Intent("job_insights", slots)
    return Intent("unknown", slots)
