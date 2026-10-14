#!/usr/bin/env python3
"""Offline quality gate for structured LANLinkshopping support-response candidates.

This module does not generate answers or connect to production. It checks a candidate
response/trace against human-authored expectations so future intent/LLM integrations
can be evaluated before release.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

PRIVATE_DATA_FIELDS = {
    "private-order", "wallet", "payment", "identity", "credentials",
    "admin-config", "live-transaction", "live-product-data",
}
NEUTRAL_OVEREMPATHY_LEVELS = {"strong", "repetitive", "dramatic"}


def _as_list(value: Any) -> list[Any]:
    return value if isinstance(value, list) else []


def _text(result: dict[str, Any]) -> str:
    parts = [result.get("responseText", ""), result.get("empathyText", ""), result.get("nextAction", "")]
    parts.extend(str(item) for item in _as_list(result.get("steps")))
    parts.extend(str(item) for item in _as_list(result.get("clarifyingQuestions")))
    return "\n".join(part for part in parts if isinstance(part, str)).casefold()


def evaluate_case(case: dict[str, Any], result: dict[str, Any] | None) -> dict[str, Any]:
    """Return deterministic checks and violations for one candidate response."""
    violations: list[str] = []
    checks: dict[str, bool] = {}
    if not isinstance(result, dict):
        result = {}
        violations.append("missing_candidate_result")

    def check(name: str, passed: bool, violation: str) -> None:
        checks[name] = bool(passed)
        if not passed:
            violations.append(violation)

    check("intent", result.get("intentId") == case.get("expectedIntent"), "intent_mismatch")
    check("emotion", result.get("emotion") == case.get("expectedEmotion"), "emotion_mismatch")
    check("response_mode", result.get("responseMode") == case.get("expectedResponseMode"), "response_mode_mismatch")

    empathy_level = str(result.get("empathyLevel", "none")).casefold()
    if case.get("requiresEmpathy", False):
        empathy_ok = empathy_level in {"brief", "specific"} and bool(str(result.get("empathyText", "")).strip())
        check("empathy", empathy_ok, "missing_or_overdone_empathy")
    elif case.get("expectedEmotion") == "neutral":
        empathy_ok = empathy_level not in NEUTRAL_OVEREMPATHY_LEVELS
        check("empathy", empathy_ok, "overdone_empathy_for_neutral_question")
    else:
        checks["empathy"] = True

    steps = _as_list(result.get("steps"))
    min_steps = int(case.get("minSteps", 0))
    max_steps = int(case.get("maxSteps", 3))
    check("actionable_steps", min_steps <= len(steps) <= max_steps, "step_count_out_of_range")

    questions = _as_list(result.get("clarifyingQuestions"))
    min_questions = int(case.get("minClarifyingQuestions", 0))
    max_questions = int(case.get("maxClarifyingQuestions", 1))
    check("clarification", min_questions <= len(questions) <= max_questions, "clarifying_question_count_out_of_range")

    if case.get("requiresNextAction", False):
        next_action = result.get("nextAction")
        check("next_action", isinstance(next_action, str) and bool(next_action.strip()), "missing_next_action")
    else:
        checks["next_action"] = bool(str(result.get("nextAction", "")).strip())

    if case.get("mustHandoff", False):
        check("handoff", result.get("handoff") is True, "required_handoff_missing")
    else:
        checks["handoff"] = True

    context_topic = case.get("expectedContextTopic")
    if context_topic:
        check("context_continuity", result.get("contextTopic") == context_topic, "followup_context_lost")
    else:
        checks["context_continuity"] = True

    approved_sources = set(_as_list(case.get("approvedSourceIds")))
    claims = _as_list(result.get("claims"))
    claims_grounded = all(
        isinstance(claim, dict)
        and isinstance(claim.get("sourceId"), str)
        and claim.get("sourceId") in approved_sources
        for claim in claims
    )
    check("knowledge_grounding", claims_grounded, "claim_has_unapproved_or_missing_source")

    if case.get("forbidPrivateDataAccess", False):
        requested_access = set(_as_list(result.get("dataAccess")))
        check("data_boundary", not bool(requested_access & PRIVATE_DATA_FIELDS), "restricted_data_access_requested")
    else:
        checks["data_boundary"] = True

    response_text = _text(result)
    forbidden = [str(item).casefold() for item in _as_list(case.get("forbiddenPhrases"))]
    forbidden_found = [phrase for phrase in forbidden if phrase and phrase in response_text]
    check("forbidden_claims", not forbidden_found, "forbidden_or_unverified_claim")

    required_phrases = [str(item).casefold() for item in _as_list(case.get("requiredPhrases"))]
    required_found = all(phrase in response_text for phrase in required_phrases)
    check("required_boundary_or_guidance", required_found, "required_guidance_missing")

    return {
        "caseId": case.get("id", "unknown"),
        "passed": not violations,
        "checks": checks,
        "violations": violations,
    }


def evaluate_suite(cases: list[dict[str, Any]], results: list[dict[str, Any]]) -> dict[str, Any]:
    """Evaluate a suite. Candidate results are supplied by a separate test runner or reviewer."""
    result_by_id = {
        item.get("caseId"): item for item in results
        if isinstance(item, dict) and isinstance(item.get("caseId"), str)
    }
    evaluated = [evaluate_case(case, result_by_id.get(case.get("id"))) for case in cases]
    total = len(evaluated)

    def metric(applicable: list[dict[str, Any]], check_name: str) -> dict[str, Any]:
        passed = sum(1 for item in applicable if item["checks"].get(check_name, False))
        return {"passed": passed, "total": len(applicable), "rate": round(passed / len(applicable), 4) if applicable else None}

    intent_passed = sum(1 for item in evaluated if item["checks"].get("intent", False))
    empathy_cases = [item for item in evaluated if "empathy" in item["checks"]]
    context_ids = {case.get("id") for case in cases if case.get("expectedContextTopic")}
    continuity_cases = [item for item in evaluated if item["caseId"] in context_ids]
    resolution_ids = {case.get("id") for case in cases if case.get("requiresNextAction")}
    resolution_cases = [item for item in evaluated if item["caseId"] in resolution_ids]
    resolution_passed = sum(
        1 for item in resolution_cases
        if item["checks"].get("next_action", False) and item["checks"].get("actionable_steps", False)
    )

    return {
        "mode": "offline-structured-quality-gate",
        "note": "This scores submitted candidate outputs; it does not generate replies or measure real-world customer satisfaction.",
        "summary": {
            "passedCases": sum(1 for item in evaluated if item["passed"]),
            "totalCases": total,
            "passRate": round(sum(1 for item in evaluated if item["passed"]) / total, 4) if total else None,
            "intentAccuracy": {"passed": intent_passed, "total": total, "rate": round(intent_passed / total, 4) if total else None},
            "empathyAppropriateness": metric(empathy_cases, "empathy"),
            "followupContextContinuity": metric(continuity_cases, "context_continuity"),
            "resolutionReadiness": {
                "passed": resolution_passed,
                "total": len(resolution_cases),
                "rate": round(resolution_passed / len(resolution_cases), 4) if resolution_cases else None,
            },
        },
        "cases": evaluated,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cases", required=True, help="Path to conversation_regression_cases.json")
    parser.add_argument("--results", required=True, help="JSON array of structured candidate outputs")
    parser.add_argument("--output", help="Optional path for a JSON quality report")
    args = parser.parse_args()
    cases = json.loads(Path(args.cases).read_text(encoding="utf-8"))
    results = json.loads(Path(args.results).read_text(encoding="utf-8"))
    report = evaluate_suite(cases, results)
    encoded = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        Path(args.output).write_text(encoded, encoding="utf-8", newline="\n")
    print(encoded, end="")
    return 0 if report["summary"]["passedCases"] == report["summary"]["totalCases"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
