"""Built-in help: short, accurate answers about how RecruitPro screens and verifies (handbook chapter 2)."""
from __future__ import annotations

import re
from dataclasses import dataclass


@dataclass(frozen=True)
class HelpTopic:
    key: str
    title: str
    pattern: re.Pattern[str]
    text: str


def _topic(key: str, title: str, pattern: str, text: str) -> HelpTopic:
    return HelpTopic(key, title, re.compile(pattern, re.I), text)


TOPICS: tuple[HelpTopic, ...] = (
    _topic("verified_vs_claimed", "Verified vs claimed coverage",
           r"\bverified\s+(?:vs\.?|versus|and|or)\s+claimed\b|\bclaimed\s+(?:vs\.?|versus|and|or)\s+verified\b|"
           r"\bdifference between (?:verified|claimed)\b|\bclaimed coverage\b",
           "**Verified coverage** counts only requirements matched by evidence at RESUME_STATED or higher. "
           "**Claimed coverage** also counts the candidate's own claims (SELF_CLAIMED), so it is always at least "
           "the verified figure. A claim never raises the verified number until a recruiter corroborates it, a work "
           "sample demonstrates it or an interview verifies it."),
    _topic("coverage", "Coverage",
           r"\bcoverage\b|\bscore\b|\bpercentage\b|\bhow (?:is|are) (?:the )?(?:candidates?|applicants?) (?:scored|ranked)\b",
           "Coverage is the weighted share of a job's requirements that have matching evidence: must-haves weigh 2, "
           "nice-to-haves 1, and OTHER requirements are left to human judgement. **Verified coverage** counts "
           "evidence at RESUME_STATED or higher; **claimed coverage** also counts the candidate's own claims. "
           "Coverage is a count of evidenced requirements, **not a probability** of success, and every number links "
           "to the evidence behind it."),
    _topic("evidence_ladder", "Evidence ladder",
           r"\bevidence\s+ladder\b|\bladder\b|\btiers?\b|\bevidence levels?\b|\bself[_ ]claimed\b|\bresume[_ ]stated\b|"
           r"\bcorroborated\b|\bdemonstrated\b|\binterview[_ ]verified\b",
           "Every piece of evidence sits on a five-step ladder: **SELF_CLAIMED** (the candidate says so) → "
           "**RESUME_STATED** (in the parsed resume) → **CORROBORATED** (a recruiter checked a claim, or a confirmed "
           "verification verdict) → **DEMONSTRATED** (a scored work sample, 3 of 4 or better) → "
           "**INTERVIEW_VERIFIED** (a structured-interview rating of 3 of 4 or better). Each requirement reports the "
           "highest tier at which it is still matched; for 'X and Y' the weakest piece decides."),
    _topic("ranking_groups", "Ranking groups",
           r"\branking groups?\b|\bmust[_ ]haves?[_ ]unverified\b|\bunscored\b|\branked\b|\bhow (?:does|is) (?:the )?ranking\b",
           "Applicants are ranked on the server in three groups. **RANKED**: all must-have gates verified; ordered by "
           "verified coverage, then claimed coverage, then application date. **MUST_HAVES_UNVERIFIED**: at least one "
           "knockout requirement is not yet verified; same order inside the group. **UNSCORED**: no current match "
           "yet (still parsing, or only OTHER requirements)."),
    _topic("gates", "Knockouts and gates",
           r"\bknock[- ]?outs?\b|\bgates?\b|\bmust[- ]haves?\b|\bauto[- ]?reject",
           "A knockout is a must-have written with must/required wording. It is a **ranking gate, never an automatic "
           "rejection**: an applicant whose knockout is not yet verified sits in MUST_HAVES_UNVERIFIED until a "
           "recruiter corroborates the claim, a work sample demonstrates it or an interview verifies it. Rejecting is "
           "always an explicit decision by a person. OTHER requirements are never knockouts."),
    _topic("blind_review", "Blind-first review",
           r"\bblind\b|\breveal\b|\banonymi[sz]ed?\b|\bidentity\b|\bnames? hidden\b|\b(?:see|show|hidden|hide)\b.{0,25}\bnames?\b",
           "With blind review on (the default), applicants are shown by application code (e.g. A-3014) with "
           "de-identified evidence; name, contact details, cover letter and resume file stay hidden. The identity is "
           "revealed automatically on shortlist, or by an HR manager after shortlisting, and every reveal is audited. "
           "Coverage, evidence, work samples and interview data are visible either way."),
    _topic("verification", "Claim verification",
           r"\bclaim ledger\b|\bverif(?:y|ication|ied) (?:a |the )?claims?\b|\bverdicts?\b|\bverification\b|\bcorroborat",
           "Candidates can back a claim with a source (for example a code repository or a reference). The "
           "verification service checks ownership and produces a verdict; the candidate decides whether to share "
           "it. A shared verdict that a recruiter confirms becomes CORROBORATED evidence. Recruiters can also verify "
           "or reject a claim directly. Verification never lowers a candidate's standing on its own."),
    _topic("shadow_mode", "Shadow mode",
           r"\bshadow\b",
           "Verification starts in **SHADOW** mode for every organisation: verdicts are produced and can be labelled "
           "by staff to measure accuracy, but they are not shown on applicants and never affect ranking. An admin "
           "switches the organisation to **VISIBLE** once the shadow metrics are good enough."),
    _topic("work_samples", "Work samples",
           r"\bwork samples?\b|\btake[- ]home\b|\bassignments?\b|\brubric\b",
           "A work sample is a short (15-45 minute) task generated from the job's requirements, with a 4-point anchored "
           "rubric per criterion. When a reviewer scores a criterion 3 or 4, the linked requirement becomes "
           "DEMONSTRATED evidence for that applicant."),
    _topic("interviews", "Structured interviews",
           r"\binterview kits?\b|\bscorecards?\b|\bstructured interviews?\b",
           "Interview kits contain questions linked to requirements, each with anchored 1-4 ratings. A completed "
           "scorecard with a rating of 3 or more on a linked question makes that requirement INTERVIEW_VERIFIED. "
           "Interviewers see unverified must-haves first so they know what to probe."),
    _topic("fairness", "Fairness report",
           r"\bfairness\b|\badverse impact\b|\bfour[- ]fifths\b|\b4/5\b|\bbias\b|\bdiversity\b",
           "The fairness report compares selection rates between voluntary self-reported groups and flags an adverse-"
           "impact ratio under 0.8 (the four-fifths rule of thumb). Small groups are suppressed. It is a monitoring "
           "signal for review, not a legal finding; survey answers are never used in matching or ranking."),
    _topic("assistant", "About the assistant",
           r"\bwhat can you do\b|\bhow (?:do|can) (?:i|you) use (?:you|the assistant)\b|\bassistant\b|\bprivacy\b|\bexternal model\b",
           "I can summarise a job's pipeline, explain an applicant's evidence, compare applicants, list what needs "
           "your attention, show your interviews and the fairness summary, help you post a job, and prepare actions "
           "such as shortlisting or scheduling an interview. I act only with your permissions, refer to candidates by "
           "application code, and **every change waits for your confirmation**."),
)


def find_topic(text: str) -> HelpTopic | None:
    for topic in TOPICS:
        if topic.pattern.search(text):
            return topic
    return None


def topic_by_key(key: str) -> HelpTopic | None:
    return next((topic for topic in TOPICS if topic.key == key), None)
