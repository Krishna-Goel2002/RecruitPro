"""HR assistant intent evaluation: ``python -m eval.assistant_intents [--check] [--verbose]``.

A hand-written set of staff utterances (plain, Indian-English phrasing, chat shorthand and typos), each with the page
context it was said on and the intent the offline planner must detect. ``--check`` exits with status 1 when accuracy
falls below the threshold. The set is written by the team, not sampled from users: treat the number as a regression
guard, not as real-world accuracy.
"""
from __future__ import annotations

import argparse
import sys
from collections import Counter
from types import SimpleNamespace

from app.assistant.intents import detect_intent

THRESHOLD = 0.9
JOB = {"page": "job", "jobId": 12, "jobTitle": "Backend Engineer"}
APPLICANT = {"page": "applicant", "jobId": 12, "jobTitle": "Backend Engineer", "applicationCode": "A-3014"}
NONE: dict = {}

CASES: list[tuple[str, dict, str]] = [
    # ---- greetings / capabilities
    ("hi", NONE, "greeting"), ("Hello there", NONE, "greeting"), ("namaste", NONE, "greeting"),
    ("what can you do?", NONE, "greeting"), ("thanks!", NONE, "greeting"), ("good morning", NONE, "greeting"),
    # ---- help
    ("How does verified coverage work?", NONE, "help"), ("what is the evidence ladder", NONE, "help"),
    ("explain knockouts", NONE, "help"), ("whats the difference between verified and claimed coverage", NONE, "help"),
    ("what is blind review?", NONE, "help"), ("what does shadow mode mean", NONE, "help"),
    ("how are candidates ranked?", NONE, "help"), ("what is MUST_HAVES_UNVERIFIED", NONE, "help"),
    ("explain wat is a gate pls", NONE, "help"), ("how does claim verification work", NONE, "help"),
    ("what does DEMONSTRATED tier mean", NONE, "help"),
    # ---- navigation
    ("Open the fairness report for this job", JOB, "navigate"), ("take me to the interview kit", JOB, "navigate"),
    ("go to my interviews", NONE, "navigate"), ("open audit log", NONE, "navigate"),
    ("where can i find the verification queue", NONE, "navigate"), ("open work samples tab", JOB, "navigate"),
    ("go to jobs", NONE, "navigate"), ("open claims tab", APPLICANT, "navigate"),
    # ---- list jobs
    ("list my jobs", NONE, "list_jobs"), ("which jobs are open right now?", NONE, "list_jobs"),
    ("show all draft postings", NONE, "list_jobs"), ("what are we hiring for currently", NONE, "list_jobs"),
    ("how many open positions do we have", NONE, "list_jobs"), ("show closed jobs", NONE, "list_jobs"),
    # ---- job insights
    ("How is the Backend Engineer posting doing?", NONE, "job_insights"),
    ("how's this job doing", JOB, "job_insights"), ("pipeline status for data analyst role", NONE, "job_insights"),
    ("Kindly share the status of the Java developer opening", NONE, "job_insights"),
    ("top candidates for the backend engineer job", NONE, "job_insights"),
    ("how many applicants for the QA role?", NONE, "job_insights"),
    ("give me a summary of this posting", JOB, "job_insights"),
    ("which requirements are most missing here", JOB, "job_insights"),
    ("hw is the sales executive job going", NONE, "job_insights"),
    ("who are the top 5 candidates", JOB, "job_insights"),
    ("any update on the frontend developer position?", NONE, "job_insights"),
    # ---- applicant insights
    ("Tell me about A-3014", NONE, "applicant_insights"), ("what about a3001?", NONE, "applicant_insights"),
    ("summary of this candidate", APPLICANT, "applicant_insights"), ("how strong is A-77", NONE, "applicant_insights"),
    ("what should i ask this applicant in the interview", APPLICANT, "applicant_insights"),
    ("evidence for A-3014 pls", NONE, "applicant_insights"), ("candidate 3014 details", NONE, "applicant_insights"),
    ("tell me abt him", APPLICANT, "applicant_insights"), ("why is A-3014 in must haves unverified", NONE, "applicant_insights"),
    # ---- compare
    ("Compare A-3001 and A-3014", NONE, "compare_applicants"), ("A-3001 vs A-3014", NONE, "compare_applicants"),
    ("compare this one with A-3001", APPLICANT, "compare_applicants"), ("compare the top 3", JOB, "compare_applicants"),
    ("who is better, A-12 or A-15?", NONE, "compare_applicants"), ("comapre a-3001 and a-3002", NONE, "compare_applicants"),
    # ---- attention digest
    ("What needs my attention today?", NONE, "attention_digest"), ("anything urgent?", NONE, "attention_digest"),
    ("catch me up", NONE, "attention_digest"), ("what's pending from my side", NONE, "attention_digest"),
    ("which interviews dont have scorecards yet", NONE, "attention_digest"),
    ("any new applicants since yesterday", NONE, "attention_digest"), ("what should i work on now", NONE, "attention_digest"),
    ("my to-do list pls", NONE, "attention_digest"), ("pending work samples to score?", NONE, "attention_digest"),
    # ---- my interviews
    ("my interviews this week", NONE, "my_interviews"), ("do I have any interviews today?", NONE, "my_interviews"),
    ("upcoming interviews", NONE, "my_interviews"), ("who am i interviewing tomorrow", NONE, "my_interviews"),
    ("show my intervew schedule", NONE, "my_interviews"), ("interviews lined up for me", NONE, "my_interviews"),
    # ---- fairness
    ("fairness summary for this job", JOB, "fairness_summary"), ("is our hiring biased?", NONE, "fairness_summary"),
    ("show adverse impact for backend engineer job", NONE, "fairness_summary"), ("selection rates by group", JOB, "fairness_summary"),
    ("check fairnes for this posting", JOB, "fairness_summary"),
    # ---- decisions
    ("Shortlist A-3014", NONE, "decide_application"), ("pls shortlsit a-3014", NONE, "decide_application"),
    ("reject A-3001 because no SQL experience", NONE, "decide_application"), ("reject this candidate", APPLICANT, "decide_application"),
    ("move A-3014 to interview", NONE, "decide_application"), ("select A-3014", NONE, "decide_application"),
    ("lets make an offer to A-3014", NONE, "decide_application"), ("rejct him", APPLICANT, "decide_application"),
    ("Kindly shortlist this applicant", APPLICANT, "decide_application"),
    # ---- schedule / work samples / kits
    ("schedule an interview with A-3014 tomorrow at 3pm", NONE, "schedule_interview"),
    ("set up interview for this candidate on monday 11am", APPLICANT, "schedule_interview"),
    ("pls schedul intervew for A-3001", NONE, "schedule_interview"), ("book a call with A-3014 next friday 4 pm", NONE, "schedule_interview"),
    ("assign the work sample to A-3014", NONE, "assign_work_sample"), ("send this candidate the take-home task", APPLICANT, "assign_work_sample"),
    ("generate a work sample for this job", JOB, "generate_work_sample"), ("create an assignment for the data analyst role", NONE, "generate_work_sample"),
    ("generate interview kit for backend engineer", NONE, "generate_interview_kit"), ("prepare interview questions for this job", JOB, "generate_interview_kit"),
    ("make an interview kit", JOB, "generate_interview_kit"),
    # ---- job status
    ("close this job", JOB, "update_job_status"), ("put the backend engineer posting on hold", NONE, "update_job_status"),
    ("publish this posting", JOB, "update_job_status"), ("reopen the QA job", NONE, "update_job_status"),
    ("pause hiring for this role", JOB, "update_job_status"),
    # ---- create job
    ("Post a job for a senior data analyst in Pune", NONE, "create_job"),
    ("I want to hire 2 react developers in Bangalore", NONE, "create_job"),
    ("create a new job posting", NONE, "create_job"), ("we are hiring a product designer, remote", NONE, "create_job"),
    ("need a QA engineer in Hyderabad asap", NONE, "create_job"), ("post a JD for java developer with 3-5 yrs exp", NONE, "create_job"),
    ("open a new position for sales executive in Delhi NCR", NONE, "create_job"),
    ("help me write a job post for an accountant", NONE, "create_job"), ("hire an intern for marketing", NONE, "create_job"),
    ("new opening: Customer Support Executive, Chennai", NONE, "create_job"),
    ("draft a job for HR recruiter, mumbai, full time", NONE, "create_job"), ("we need to recruit 3 data engineers", NONE, "create_job"),
    # ---- cancel
    ("cancel", NONE, "cancel"), ("never mind", NONE, "cancel"), ("start over", NONE, "cancel"), ("forget it", NONE, "cancel"),
    # ---- second round, written after the rules (first-run accuracy on these 46 was 0.76; kept as regression cases)
    ("hey, can you tell me how the devops engineer role is performing", NONE, "job_insights"),
    ("status update on data scientist hiring?", NONE, "job_insights"), ("how many ppl applied for this job", JOB, "job_insights"),
    ("whats happening with the UI designer opening", NONE, "job_insights"),
    ("show me the best applicants for this role", JOB, "job_insights"),
    ("give me the ranking for the backend engineer job", NONE, "job_insights"),
    ("A-3014 ka status kya hai", NONE, "applicant_insights"), ("is A-3014 a good fit?", NONE, "applicant_insights"),
    ("what are the gaps for this applicant", APPLICANT, "applicant_insights"), ("A-3014", NONE, "applicant_insights"),
    ("please do the needful and shortlist A-2001", NONE, "decide_application"),
    ("kindly reject A-2002, not relevant profile", NONE, "decide_application"),
    ("move this candidate to interview round", APPLICANT, "decide_application"), ("hire A-3014", NONE, "decide_application"),
    ("can you fix an interview with A-3014 on 25th nov at 11", NONE, "schedule_interview"),
    ("arrange interview for him day after tomorrow 4pm", APPLICANT, "schedule_interview"),
    ("give A-3014 the work sample", NONE, "assign_work_sample"), ("make a take home test for this job", JOB, "generate_work_sample"),
    ("suggest interview questions for the data analyst role", NONE, "generate_interview_kit"),
    ("I need interview questions for this posting", JOB, "generate_interview_kit"),
    ("close the hiring for backend engineer", NONE, "update_job_status"), ("make this job live", JOB, "update_job_status"),
    ("we have a requirement for 3 java developers in pune", NONE, "create_job"),
    ("Raise a new requisition for business analyst", NONE, "create_job"), ("create JD for senior accountant, Kolkata", NONE, "create_job"),
    ("looking for a full stack developer, 5 yrs exp, remote", NONE, "create_job"),
    ("can u post a job for graphic designer", NONE, "create_job"),
    ("anything I missed today?", NONE, "attention_digest"), ("what's on my plate", NONE, "attention_digest"),
    ("give me today's summary", NONE, "attention_digest"), ("any interviews tomorrow?", NONE, "my_interviews"),
    ("which candidates am I meeting today", NONE, "my_interviews"),
    ("is the backend engineer job fair across groups", NONE, "fairness_summary"), ("adverse impact ratio", JOB, "fairness_summary"),
    ("how do work samples work", NONE, "help"), ("what does corroborated mean", NONE, "help"),
    ("why can't I see candidate names", NONE, "help"), ("open settings", NONE, "navigate"),
    ("take me to the applicants list", JOB, "navigate"), ("show all jobs", NONE, "list_jobs"), ("jobs list", NONE, "list_jobs"),
    ("which roles are live", NONE, "list_jobs"), ("compare A-1 and A-2 and A-3", NONE, "compare_applicants"),
    ("how does A-3014 stack up against A-3001", NONE, "compare_applicants"), ("ok cancel that", NONE, "cancel"),
    ("discard the draft", NONE, "cancel"),
    # ---- third round, measured blind before any rule change (24/25 first run)
    ("how are we doing on the product manager search", NONE, "job_insights"), ("numbers for this job pls", JOB, "job_insights"),
    ("who's leading for the QA position", NONE, "job_insights"), ("details of A-4410", NONE, "applicant_insights"),
    ("what did A-4410 score on the work sample", NONE, "applicant_insights"),
    ("shortlist A-4410 and A-4411", NONE, "decide_application"), ("not moving forward with A-4410", NONE, "decide_application"),
    ("schedule A-4410 for an interview on 3 dec 10 am", NONE, "schedule_interview"),
    ("send the assignment to this applicant", APPLICANT, "assign_work_sample"),
    ("build an interview kit for this role", JOB, "generate_interview_kit"), ("put it on hold", JOB, "update_job_status"),
    ("I'd like to post an opening for a nurse in Kochi", NONE, "create_job"), ("Hiring: 2 interns, marketing, Mumbai", NONE, "create_job"),
    ("what's new", NONE, "attention_digest"), ("any scorecards pending?", NONE, "attention_digest"),
    ("interviews this week?", NONE, "my_interviews"), ("selection rate check for the backend job", NONE, "fairness_summary"),
    ("what is a knockout requirement", NONE, "help"), ("how is coverage calculated", NONE, "help"), ("go to admin", NONE, "navigate"),
    ("list draft jobs", NONE, "list_jobs"), ("A-1 vs A-2 vs A-3", NONE, "compare_applicants"), ("stop", NONE, "cancel"),
    ("tell me something interesting", NONE, "unknown"), ("how is A-4410 doing", NONE, "applicant_insights"),
    # ---- unknown
    ("what's the weather in Pune", NONE, "unknown"), ("asdfgh", NONE, "unknown"), ("tell me a joke", NONE, "unknown"),
]


def evaluate(verbose: bool = False) -> dict:
    failures = []
    per_intent: Counter = Counter()
    correct: Counter = Counter()
    for text, context, expected in CASES:
        ctx = SimpleNamespace(page=context.get("page", "other"), jobId=context.get("jobId"), jobTitle=context.get("jobTitle"),
                              applicationCode=context.get("applicationCode"), applicationId=None, role="HR_MANAGER")
        got = detect_intent(text, ctx).name
        per_intent[expected] += 1
        if got == expected:
            correct[expected] += 1
        else:
            failures.append((text, expected, got))
    accuracy = sum(correct.values()) / len(CASES)
    result = {"cases": len(CASES), "accuracy": round(accuracy, 4), "failures": failures,
              "per_intent": {name: f"{correct[name]}/{count}" for name, count in sorted(per_intent.items())}}
    if verbose:
        for text, expected, got in failures:
            print(f"  MISS {text!r}: expected {expected}, got {got}")
    return result


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help=f"exit 1 when accuracy < {THRESHOLD}")
    parser.add_argument("--verbose", action="store_true", help="print every miss")
    args = parser.parse_args(argv)
    result = evaluate(verbose=args.verbose)
    print(f"assistant intents: {result['cases']} cases, accuracy {result['accuracy']:.3f} (threshold {THRESHOLD})")
    for name, score in result["per_intent"].items():
        print(f"  {name:24s} {score}")
    if args.check and result["accuracy"] < THRESHOLD:
        print("FAILED: offline intent accuracy below threshold")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
