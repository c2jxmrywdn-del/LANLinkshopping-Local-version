import json
import unittest
from pathlib import Path

from conversation_quality import evaluate_case, evaluate_suite

CASES_PATH = Path(__file__).with_name("conversation_regression_cases.json")
CASES = json.loads(CASES_PATH.read_text(encoding="utf-8"))


def passing_result(case):
    topic = case.get("expectedContextTopic")
    return {
        "caseId": case["id"],
        "intentId": case["expectedIntent"],
        "emotion": case["expectedEmotion"],
        "responseMode": case["expectedResponseMode"],
        "responseText": " ".join(case.get("requiredPhrases", [])) or "我先说明当前能确认的步骤。",
        "empathyLevel": "brief" if case.get("requiresEmpathy") else "none",
        "empathyText": "反复尝试还没解决，确实会让人着急。我们先排查最关键的一步。" if case.get("requiresEmpathy") else "",
        "steps": ["核对当前页面"] if case.get("minSteps", 0) > 0 else [],
        "clarifyingQuestions": ["你想先处理注册，还是购物车结算？"] if case.get("minClarifyingQuestions", 0) else [],
        "nextAction": "请打开相应页面按步骤检查；仍无法解决时联系人工客服。" if case.get("requiresNextAction") else "",
        "handoff": bool(case.get("mustHandoff")),
        "contextTopic": topic,
        "dataAccess": ["knowledge-base"],
        "claims": [{"text": "按已收录流程操作", "sourceId": case["approvedSourceIds"][0]}] if case.get("approvedSourceIds") else [],
    }


class ConversationQualityRegressionTests(unittest.TestCase):
    def test_fixture_covers_all_requested_quality_dimensions(self):
        self.assertGreaterEqual(len(CASES), 8)
        self.assertTrue(any(case.get("expectedContextTopic") for case in CASES), "follow-up continuity case is required")
        self.assertTrue(any(case.get("requiresEmpathy") for case in CASES), "emotion adaptation case is required")
        self.assertTrue(any(case.get("requiresNextAction") for case in CASES), "resolution-readiness case is required")
        self.assertTrue(any(case.get("forbiddenPhrases") for case in CASES), "safety/false-promise cases are required")

    def test_handcrafted_conforming_traces_pass_the_rubric(self):
        for case in CASES:
            with self.subTest(case=case["id"]):
                report = evaluate_case(case, passing_result(case))
                self.assertTrue(report["passed"], report["violations"])

    def test_intent_misclassification_is_detected(self):
        case = next(item for item in CASES if item["id"] == "refund-navigation-not-live-status")
        result = passing_result(case)
        result["intentId"] = "unsupported-live-transaction"
        report = evaluate_case(case, result)
        self.assertIn("intent_mismatch", report["violations"])

    def test_missing_empathy_for_frustrated_user_is_detected(self):
        case = next(item for item in CASES if item["id"] == "repeat-login-failure")
        result = passing_result(case)
        result["empathyLevel"] = "none"
        result["empathyText"] = ""
        report = evaluate_case(case, result)
        self.assertIn("missing_or_overdone_empathy", report["violations"])

    def test_overdone_empathy_for_neutral_question_is_detected(self):
        case = next(item for item in CASES if item["id"] == "neutral-cookie-navigation")
        result = passing_result(case)
        result["empathyLevel"] = "dramatic"
        report = evaluate_case(case, result)
        self.assertIn("overdone_empathy_for_neutral_question", report["violations"])

    def test_lost_followup_context_is_detected(self):
        case = next(item for item in CASES if item["id"] == "cart-followup-context")
        result = passing_result(case)
        result["contextTopic"] = "account-registration"
        report = evaluate_case(case, result)
        self.assertIn("followup_context_lost", report["violations"])

    def test_missing_actionable_next_step_is_detected(self):
        case = next(item for item in CASES if item["id"] == "repeat-login-failure")
        result = passing_result(case)
        result["steps"] = []
        result["nextAction"] = ""
        report = evaluate_case(case, result)
        self.assertIn("step_count_out_of_range", report["violations"])
        self.assertIn("missing_next_action", report["violations"])

    def test_unsupported_private_data_access_and_promises_are_detected(self):
        case = next(item for item in CASES if item["id"] == "refund-status-live-data")
        result = passing_result(case)
        result["dataAccess"] = ["live-transaction", "wallet"]
        result["responseText"] = "你的退款已退款，一定会到账。"
        report = evaluate_case(case, result)
        self.assertIn("restricted_data_access_requested", report["violations"])
        self.assertIn("forbidden_or_unverified_claim", report["violations"])

    def test_multiple_questions_cannot_overwhelm_user(self):
        case = next(item for item in CASES if item["id"] == "multi-intent-clarification")
        result = passing_result(case)
        result["clarifyingQuestions"] = ["先问一？", "再问二？"]
        report = evaluate_case(case, result)
        self.assertIn("clarifying_question_count_out_of_range", report["violations"])

    def test_claim_without_approved_source_is_detected(self):
        case = next(item for item in CASES if item["id"] == "registration-informal")
        result = passing_result(case)
        result["claims"] = [{"text": "保证一定注册成功", "sourceId": "invented-policy"}]
        result["responseText"] = "保证一定注册成功"
        report = evaluate_case(case, result)
        self.assertIn("claim_has_unapproved_or_missing_source", report["violations"])
        self.assertIn("forbidden_or_unverified_claim", report["violations"])

    def test_suite_reports_intent_empathy_context_and_resolution_metrics(self):
        results = [passing_result(case) for case in CASES]
        report = evaluate_suite(CASES, results)
        summary = report["summary"]
        self.assertEqual(len(CASES), summary["totalCases"])
        self.assertEqual(1.0, summary["intentAccuracy"]["rate"])
        self.assertEqual(1.0, summary["empathyAppropriateness"]["rate"])
        self.assertEqual(1.0, summary["followupContextContinuity"]["rate"])
        self.assertEqual(1.0, summary["resolutionReadiness"]["rate"])
        self.assertIn("does not generate replies", report["note"])


if __name__ == "__main__":
    unittest.main()
