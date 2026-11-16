package com.recruitpro.assistant.tool.read;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Built-in help text of the explain tool (docs/handbook/02-hiring-core-and-ai.md, short form). */
final class HelpTopics {

    record Topic(String title, String text, String link) { }

    static final Map<String, Topic> TOPICS = new LinkedHashMap<>();

    static {
        TOPICS.put("VERIFIED_COVERAGE", new Topic("Verified coverage",
                "Verified coverage is the weighted share of a job's requirements matched by evidence at tier "
                        + "RESUME_STATED or higher (mandatory requirements weigh 2, nice-to-haves 1; OTHER requirements "
                        + "are left out). Claimed coverage also counts the candidate's own claims, so it is always at least "
                        + "the verified figure. It is evidence coverage, not a probability of success.", null));
        TOPICS.put("EVIDENCE_TIERS", new Topic("Evidence tiers",
                "Each matched requirement has a tier: SELF_CLAIMED (the candidate says so), RESUME_STATED (in the "
                        + "resume), CORROBORATED (a recruiter of your organisation or a confirmed verification verdict), "
                        + "DEMONSTRATED (scored 3 or 4 in a work sample) and INTERVIEW_VERIFIED (rated 3 or 4 in an "
                        + "interview). RESUME_STATED and above count as verified.", null));
        TOPICS.put("RANKING_GROUPS", new Topic("Ranking groups",
                "Applicants are ranked on the server. RANKED: every knockout requirement verified, ordered by verified "
                        + "then claimed coverage. MUST_HAVES_UNVERIFIED: at least one knockout not verified yet (never an "
                        + "automatic rejection). UNSCORED: not matched yet or nothing scorable.", null));
        TOPICS.put("KNOCKOUTS", new Topic("Knockout requirements",
                "A knockout is a mandatory requirement used as a ranking gate. An applicant without verified evidence "
                        + "for it stays in MUST_HAVES_UNVERIFIED until the claim is corroborated or the skill is "
                        + "demonstrated. Rejection is always an explicit decision by a person.", null));
        TOPICS.put("BLIND_REVIEW", new Topic("Blind-first review",
                "With blind review on, applicants appear as codes (A-3014) until they are shortlisted or a manager "
                        + "reveals the identity; the reveal is recorded in the history and the audit log. The assistant "
                        + "never sends names, e-mails, phone numbers or links to an external model.", null));
        TOPICS.put("WORK_SAMPLES", new Topic("Work samples",
                "A work sample is a short, job-related task with an anchored 1-4 rubric. Draft it (or let the AI draft "
                        + "it), activate it, then assign it to shortlisted or interviewing applicants. Criteria scored 3 "
                        + "or 4 that are linked to a requirement become DEMONSTRATED evidence.", null));
        TOPICS.put("INTERVIEW_KITS", new Topic("Interview kits and scorecards",
                "An interview kit holds structured questions per requirement with anchored ratings. The assigned "
                        + "interviewer submits a scorecard; ratings of 3 or 4 on requirement-linked questions become "
                        + "INTERVIEW_VERIFIED evidence.", null));
        TOPICS.put("FAIRNESS_REPORT", new Topic("Fairness report",
                "The fairness report compares selection rates of voluntary survey groups per stage. The adverse-impact "
                        + "ratio is the lowest rate divided by the highest; below 0.8 the stage is flagged. Small groups "
                        + "are suppressed.", null));
        TOPICS.put("VERIFICATION", new Topic("Verification",
                "Candidates can connect sources (code hosts, documents, references) to verify their claims. Shared "
                        + "verdicts appear on the applicant's Verification tab; a recruiter's confirmation lifts the "
                        + "evidence to CORROBORATED for your organisation only.", null));
        TOPICS.put("ASSISTANT", new Topic("The assistant",
                "The assistant acts as you, with your permissions. It reads through the same services as the portal "
                        + "and never changes anything without your confirmation: every change is a proposed action that "
                        + "you confirm or cancel (it expires after 30 minutes) and that is audited.", null));
    }

    static List<String> names() {
        return List.copyOf(TOPICS.keySet());
    }

    private HelpTopics() {
    }
}
