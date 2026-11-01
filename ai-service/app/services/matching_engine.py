import re

from app.models.intelligence import (
    MatchFact,
    MatchRequest,
    MatchResponse,
    RequirementMatch,
)
from app.services.resume_parser import SKILLS, _contains_phrase, are_distinct, extract_skills, is_parent_of


ALGORITHM_VERSION = "evidence-coverage-2"
STOP_WORDS = {"and", "or", "with", "the", "a", "an", "of", "in", "to", "for", "experience", "knowledge", "skills", "years", "year"}
PROTECTED_TERMS = {"age", "aged", "gender", "male", "female", "man", "woman", "religion", "caste", "race", "ethnicity", "disability", "disabled", "nationality", "marital", "pregnant", "birth", "photo", "photograph"}


def match_requirements(request: MatchRequest) -> MatchResponse:
    results: list[RequirementMatch] = []
    total_weight = 0
    matched_weight = 0
    facts = [fact for fact in request.facts if fact.type not in {"EMAIL", "PHONE"}]

    for requirement in request.requirements:
        weight = 2 if requirement.mandatory else 1
        total_weight += weight
        candidates = _candidate_facts(requirement.type, facts)
        result = _match_one(requirement, candidates, all_facts=facts)
        if result.status == "MATCHED":
            matched_weight += weight
        results.append(result)

    score = round(100 * matched_weight / total_weight, 1) if total_weight else None
    return MatchResponse(
        score=score,
        scoreLabel="Observed requirement evidence coverage (not a probability of success)",
        algorithmVersion=ALGORITHM_VERSION,
        requirements=results,
    )


def _candidate_facts(kind: str, facts: list[MatchFact]) -> list[MatchFact]:
    if kind == "SKILL":
        return [fact for fact in facts if fact.type == "SKILL"]
    if kind == "EDUCATION":
        return [fact for fact in facts if fact.type == "EDUCATION"]
    if kind == "CERTIFICATION":
        return [fact for fact in facts if fact.type == "CERTIFICATION"]
    if kind == "EXPERIENCE":
        return [fact for fact in facts if fact.type == "EXPERIENCE"]
    return [fact for fact in facts if fact.type in {"SKILL", "EDUCATION", "EXPERIENCE", "CERTIFICATION"}]


def _match_one(requirement, facts: list[MatchFact], all_facts: list[MatchFact] | None = None) -> RequirementMatch:
    if requirement.type not in {"SKILL", "EDUCATION", "CERTIFICATION", "EXPERIENCE"}:
        return _result(requirement.id, "NOT_OBSERVED", None, "This requirement category is not scored by the deterministic matcher and needs human review.")
    if not facts:
        # For SKILL requirements with no SKILL facts, try cross-category fallback before giving up.
        if requirement.type == "SKILL" and all_facts:
            required_skills = _skills_in_requirement(requirement.description)
            if required_skills:
                fallback = _cross_category_skill_fallback(requirement, required_skills, all_facts)
                if fallback is not None:
                    return fallback
        return _result(requirement.id, "NOT_OBSERVED", None, "No extracted information in this category; this does not mean the candidate lacks the requirement.")

    if requirement.type == "EXPERIENCE":
        return _match_experience(requirement, facts)

    required_skills = _skills_in_requirement(requirement.description) if requirement.type == "SKILL" else set()
    if required_skills:
        observed: dict[str, MatchFact] = {}
        for fact in facts:
            for skill in _skills_in_requirement(fact.value):
                observed.setdefault(skill, fact)
        found = required_skills & observed.keys()
        if len(found) == len(required_skills):
            matched_facts = [observed[skill] for skill in sorted(found)]
            return _result_from_facts(requirement.id, "MATCHED", matched_facts,
                                      f"{_source_label(matched_facts)} contains all named required skill terms: " + ", ".join(sorted(found)) + ".")
        if found:
            # Check whether the remaining missing skills can be subsumed by child skills in observed facts
            missing = required_skills - found
            subsumed_by: dict[str, tuple[str, MatchFact]] = {}
            for req_s in missing:
                for obs_s, obs_fact in observed.items():
                    if is_parent_of(req_s, obs_s):
                        subsumed_by[req_s] = (obs_s, obs_fact)
                        break
            effective_found = found | set(subsumed_by.keys())
            if len(effective_found) == len(required_skills):
                direct_facts = [observed[s] for s in found]
                child_facts = [subsumed_by[s][1] for s in subsumed_by]
                all_matched_facts = direct_facts + child_facts
                child_desc = ", ".join(
                    f"{subsumed_by[s][0]} (satisfies {s})" for s in sorted(subsumed_by)
                )
                return _result_from_facts(
                    requirement.id, "MATCHED", all_matched_facts,
                    f"{_source_label(all_matched_facts)} establishes all required skills including via specialization: {child_desc}.",
                )
            # Partial direct + partial subsumption — still UNCLEAR
            skill = sorted(found)[0]
            return _result(requirement.id, "UNCLEAR", observed[skill], "Some named skill terms were found; the full requirement was not verified.")

        # No directly found skills — check for full subsumption (no direct overlap at all)
        subsumed_by_full: dict[str, tuple[str, MatchFact]] = {}
        for req_s in required_skills:
            for obs_s, obs_fact in observed.items():
                if is_parent_of(req_s, obs_s):
                    subsumed_by_full[req_s] = (obs_s, obs_fact)
                    break
        if subsumed_by_full:
            effective_found_full = set(subsumed_by_full.keys())
            child_facts_full = [subsumed_by_full[s][1] for s in subsumed_by_full]
            child_desc_full = ", ".join(
                f"{subsumed_by_full[s][0]} (satisfies {s})" for s in sorted(subsumed_by_full)
            )
            if len(effective_found_full) == len(required_skills):
                return _result_from_facts(
                    requirement.id, "MATCHED", child_facts_full,
                    f"{_source_label(child_facts_full)} establishes all required skills via specialization: {child_desc_full}.",
                )
            # Some but not all required skills subsumed
            first_fact = child_facts_full[0]
            return _result(requirement.id, "UNCLEAR", first_fact,
                           "Some requirement skills were subsumed by a specialization; the full requirement was not verified.")

        has_distinct = any(are_distinct(req_s, obs_s) for req_s in required_skills for obs_s in observed.keys())
        if has_distinct:
            # Try cross-category fallback before declaring NOT_OBSERVED (distinction guard fired)
            if all_facts:
                fallback = _cross_category_skill_fallback(requirement, required_skills, all_facts)
                if fallback is not None:
                    return fallback
            return _result(requirement.id, "NOT_OBSERVED", None,
                           "No comparable requirement terms were found in the extracted information; this does not mean the candidate lacks the requirement.")

        lexical = _lexical_result(requirement.id, requirement.description, facts)
        if lexical.status == "NOT_OBSERVED" and all_facts:
            fallback = _cross_category_skill_fallback(requirement, required_skills, all_facts)
            if fallback is not None:
                return fallback
        return lexical

    return _lexical_result(requirement.id, requirement.description, facts)


def _cross_category_skill_fallback(
    requirement, required_skills: set[str], all_facts: list[MatchFact]
) -> RequirementMatch | None:
    """
    Cross-category fallback for SKILL requirements.

    When primary SKILL fact matching returns NOT_OBSERVED, check EXPERIENCE facts for
    explicit taxonomy-recognised skill names matching the requirement. Subsumption
    (5C.3) is also applied here.

    Returns a RequirementMatch if EXPERIENCE evidence supports the requirement,
    or None if no relevant evidence was found.

    Guarantees:
    - Only fires when primary SKILL result is NOT_OBSERVED (called at those exit points).
    - Uses extract_skills (taxonomy-based), not raw lexical matching, so generic terms
      in unrelated experience text do not trigger a spurious match.
    - Searches fact.value AND fact.evidence to capture inline technology mentions.
    - Provenance (CANDIDATE_REVIEW vs RESUME) is propagated from the source fact.
    - Does not infer years of experience from a skill fact or cross-category fact.
    - Does not change scoring weights or thresholds.
    """
    exp_facts = [f for f in all_facts if f.type == "EXPERIENCE"]
    if not exp_facts:
        return None

    # Build observed-skills map from EXPERIENCE facts (value + evidence text)
    exp_observed: dict[str, MatchFact] = {}
    for fact in exp_facts:
        for skill in _skills_in_requirement(fact.value + " " + fact.evidence):
            exp_observed.setdefault(skill, fact)

    if not exp_observed:
        return None

    # Direct skill match in experience facts
    found = required_skills & exp_observed.keys()

    # Subsumption: resolve remaining required skills from child skills in experience facts
    subsumed_by: dict[str, tuple[str, MatchFact]] = {}
    if len(found) < len(required_skills):
        missing = required_skills - found
        for req_s in missing:
            for obs_s, obs_fact in exp_observed.items():
                if is_parent_of(req_s, obs_s):
                    subsumed_by[req_s] = (obs_s, obs_fact)
                    break

    effective_found = found | set(subsumed_by.keys())

    if not effective_found:
        return None  # No relevant skill evidence in experience facts

    if len(effective_found) == len(required_skills):
        # All required skills satisfied via experience evidence
        direct_facts = [exp_observed[s] for s in found]
        child_facts = [subsumed_by[s][1] for s in subsumed_by]
        all_matched = direct_facts + child_facts

        desc_parts: list[str] = []
        if found:
            desc_parts.append(", ".join(sorted(found)))
        if subsumed_by:
            desc_parts.append(
                ", ".join(f"{subsumed_by[s][0]} (satisfies {s})" for s in sorted(subsumed_by))
            )
        skill_desc = "; ".join(desc_parts)

        return _result_from_facts(
            requirement.id, "MATCHED", all_matched,
            f"{_source_label(all_matched)} identifies all required skills ({skill_desc}) within experience evidence.",
        )

    # Some but not all required skills found — UNCLEAR
    first_s = sorted(effective_found)[0]
    first_fact = exp_observed.get(first_s) or subsumed_by[first_s][1]
    return _result(
        requirement.id, "UNCLEAR", first_fact,
        "Some required skills were identified within experience evidence; the full skill requirement was not verified.",
    )


def _match_experience(requirement, facts: list[MatchFact]) -> RequirementMatch:
    req_skills = _skills_in_requirement(requirement.description)
    req_tokens = _tokens(requirement.description)
    non_skill_tokens = req_tokens - {s.lower() for s in req_skills}

    evaluated: list[tuple[str, int | None, MatchFact]] = []
    for fact in facts:
        fact_text = fact.value + " " + fact.evidence
        fact_skills = _skills_in_requirement(fact_text)
        fact_tokens = _tokens(fact_text)
        f_years = _extract_years(fact)

        if req_skills:
            skills_found = req_skills & fact_skills
            if len(skills_found) == len(req_skills):
                if non_skill_tokens:
                    non_skill_overlap = non_skill_tokens & fact_tokens
                    if len(non_skill_overlap) / len(non_skill_tokens) >= 0.5:
                        tier = "FULL"
                    else:
                        tier = "PARTIAL"
                else:
                    tier = "FULL"
            elif len(skills_found) > 0:
                tier = "PARTIAL"
            else:
                overlap = req_tokens & fact_tokens
                has_distinct = any(are_distinct(rs, fs) for rs in req_skills for fs in fact_skills)
                if not has_distinct and overlap and (len(overlap) / len(req_tokens) >= 0.67):
                    tier = "PARTIAL"
                else:
                    tier = "NONE"
                # Subsumption: if no skill overlap at all, check whether any observed fact skill
                # is a child of any required skill (e.g. MySQL satisfies SQL in experience).
                if tier == "NONE" and req_skills and not skills_found:
                    for req_s in req_skills:
                        for fs in fact_skills:
                            if is_parent_of(req_s, fs):
                                # Treat the skill domain as matched; re-evaluate tier via non-skill tokens
                                if non_skill_tokens:
                                    non_skill_overlap = non_skill_tokens & fact_tokens
                                    tier = "FULL" if len(non_skill_overlap) / len(non_skill_tokens) >= 0.5 else "PARTIAL"
                                else:
                                    tier = "FULL"
                                break
                        if tier != "NONE":
                            break
        else:
            if not req_tokens:
                tier = "FULL"
            else:
                overlap = req_tokens & fact_tokens
                ratio = len(overlap) / len(req_tokens) if req_tokens else 0.0
                if ratio >= 0.67:
                    tier = "FULL"
                elif ratio > 0.0:
                    tier = "PARTIAL"
                else:
                    tier = "NONE"

        evaluated.append((tier, f_years, fact))

    min_years = requirement.minimumYears

    def sort_key(item: tuple[str, int | None, MatchFact]):
        tier, years, _ = item
        tier_rank = 3 if tier == "FULL" else (2 if tier == "PARTIAL" else 1)
        if min_years is not None:
            years_rank = 2 if (years is not None and years >= min_years) else (1 if years is not None else 0)
        else:
            years_rank = 1 if years is not None else 0
        return (tier_rank, years_rank, years or 0)

    evaluated.sort(key=sort_key, reverse=True)
    best_tier, best_years, best_fact = evaluated[0]
    source = _source_label([best_fact])

    if best_tier == "FULL":
        if min_years is not None:
            min_yr_str = "1 year" if min_years == 1 else f"{min_years} years"
            if best_years is not None:
                yr_str = "1 year" if best_years == 1 else f"{best_years} years"
                if best_years >= min_years:
                    return _result(requirement.id, "MATCHED", best_fact,
                                   f"{source} explicitly states a duration ({yr_str}) meeting the minimum requirement ({min_yr_str}) for {requirement.description}.")
                return _result(requirement.id, "NOT_OBSERVED", best_fact,
                               f"{source} states a shorter duration ({yr_str}) than the requested minimum ({min_yr_str}); other experience is not inferred.")
            return _result(requirement.id, "UNCLEAR", best_fact,
                           f"{source} establishes relevant experience in '{requirement.description}', but the duration could not be verified from an explicit year count.")
        return _result(requirement.id, "MATCHED", best_fact,
                       f"{source} establishes relevant experience for '{requirement.description}'.")

    if best_tier == "PARTIAL":
        if min_years is not None:
            min_yr_str = "1 year" if min_years == 1 else f"{min_years} years"
            if best_years is not None and best_years >= min_years:
                yr_str = "1 year" if best_years == 1 else f"{best_years} years"
                return _result(requirement.id, "UNCLEAR", best_fact,
                               f"Duration requirement is met ({yr_str}), but domain relevance to '{requirement.description}' is only partially established; specific requirement terms were not confirmed.")
            return _result(requirement.id, "UNCLEAR", best_fact,
                           f"Some comparable experience terms were found, but full relevance and required duration ({min_yr_str}) were not verified.")
        return _result(requirement.id, "UNCLEAR", best_fact,
                       f"Some comparable terms were found, but the experience requirement is not fully verified.")

    # best_tier == "NONE"
    facts_with_years = [item for item in evaluated if item[1] is not None]
    if facts_with_years:
        facts_with_years.sort(key=lambda item: item[1] or 0, reverse=True)
        dur_fact = facts_with_years[0][2]
        dur_years = facts_with_years[0][1]
        dur_source = _source_label([dur_fact])
        dur_yr_str = "1 year" if dur_years == 1 else f"{dur_years} years"
        return _result(requirement.id, "UNCLEAR", dur_fact,
                       f"{dur_source} establishes {dur_yr_str} of experience, but relevance to '{requirement.description}' was not observed in the extracted evidence.")

    return _result(requirement.id, "NOT_OBSERVED", None,
                   "No comparable experience or duration was observed in the extracted information; this does not mean the candidate lacks the requirement.")


def _lexical_result(requirement_id: int, requirement: str, facts: list[MatchFact]) -> RequirementMatch:
    tokens = _tokens(requirement)
    if not tokens:
        return _result(requirement_id, "NOT_OBSERVED", None, "The requirement contains no comparable terms.")
    best: tuple[float, MatchFact] | None = None
    for fact in facts:
        overlap = tokens & _tokens(fact.value + " " + fact.evidence)
        ratio = len(overlap) / len(tokens)
        if best is None or ratio > best[0]:
            best = ratio, fact
    if best is None or best[0] == 0:
        return _result(requirement_id, "NOT_OBSERVED", None, "No comparable requirement terms were found in the extracted information; this does not mean the candidate lacks the requirement.")
    if best[0] >= 0.67:
        return _result(requirement_id, "MATCHED", best[1], f"{_source_label([best[1]])} shares at least two thirds of the comparable requirement terms.")
    return _result(requirement_id, "UNCLEAR", best[1], "Some comparable terms were found, but the requirement is not fully verified.")


def _result(requirement_id: int, status: str, fact: MatchFact | None, explanation: str) -> RequirementMatch:
    review_status = fact.reviewStatus if fact else "UNREVIEWED"
    return RequirementMatch(
        requirementId=requirement_id,
        status=status,
        evidence=_fact_evidence(fact) if fact else None,
        sourceSection=fact.sourceSection if fact else None,
        provenance=("CANDIDATE_REVIEW" if review_status in {"CONFIRMED", "CORRECTED"} else "RESUME") if fact else None,
        explanation=explanation,
    )


def _result_from_facts(requirement_id: int, status: str, facts: list[MatchFact], explanation: str) -> RequirementMatch:
    unique_evidence = list(dict.fromkeys(_fact_evidence(fact) for fact in facts))
    unique_sections = list(dict.fromkeys(fact.sourceSection for fact in facts))
    reviewed = any(fact.reviewStatus in {"CONFIRMED", "CORRECTED"} for fact in facts)
    return RequirementMatch(
        requirementId=requirement_id,
        status=status,
        evidence=" | ".join(unique_evidence)[:500],
        sourceSection="; ".join(unique_sections)[:80],
        provenance="CANDIDATE_REVIEW" if reviewed else "RESUME",
        explanation=explanation,
    )


def _source_label(facts: list[MatchFact]) -> str:
    return "Candidate-reviewed information" if any(fact.reviewStatus in {"CONFIRMED", "CORRECTED"} for fact in facts) else "Resume evidence"


def _fact_evidence(fact: MatchFact) -> str:
    evidence = fact.evidence or fact.value
    if fact.reviewStatus == "CORRECTED":
        return f"Candidate correction: {evidence}"[:500]
    if fact.reviewStatus == "CONFIRMED":
        return f"Candidate-confirmed resume evidence: {evidence}"[:500]
    return f"Resume evidence: {evidence}"[:500]


def _skills_in_requirement(value: str) -> set[str]:
    return extract_skills(value)


def _tokens(value: str) -> set[str]:
    normalized = re.sub(r"\bjava[\s-]+script\b", "javascript", value, flags=re.I)
    return {token for token in re.findall(r"[a-z0-9+#.]+", normalized.lower()) if token not in STOP_WORDS and token not in PROTECTED_TERMS and len(token) > 1}


def _extract_years(fact: MatchFact) -> int | None:
    text = fact.value + " " + fact.evidence
    match = re.search(r"\b(\d{1,2})\s*\+?\s*(?:years?|yrs?)\b", text, re.I)
    if match:
        return int(match.group(1))
    range_match = re.search(r"\b(20\d\d|19\d\d)\s*[-–—to]+\s*(20\d\d|present|current)\b", text, re.I)
    if range_match:
        start_year = int(range_match.group(1))
        end_str = range_match.group(2).lower()
        end_year = 2026 if end_str in {"present", "current"} else int(end_str)
        if end_year >= start_year:
            return end_year - start_year
    return None
