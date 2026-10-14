import unittest

from app import InputError, evaluate


def valid_payload():
    return {
        "question": "购物车怎么结算？",
        "entryId": "cart-checkout-sandbox",
        "keywords": ["购物车", "购物车结算"],
        "draftAnswer": "登录后打开购物车，检查商品并进入结算页面。",
        "currentEntryId": "cart-checkout",
        "currentAnswer": "购物车和结算功能需要先登录。",
        "learningEligible": True,
        "learningEligibilityMessage": "问题位于当前电商学习范围内。",
        "draftKeywordsMatch": True,
        "keywordConflict": False,
    }


class SandboxEvaluationTests(unittest.TestCase):
    def test_safe_candidate_is_ready_but_not_published(self):
        result = evaluate(valid_payload())
        self.assertTrue(result["candidateReadyToExport"])
        self.assertIn("不会自动发布", result["candidateMessage"])
        self.assertNotIn("question", result)

    def test_out_of_scope_is_not_ready(self):
        payload = valid_payload()
        payload["learningEligible"] = False
        result = evaluate(payload)
        self.assertFalse(result["candidateReadyToExport"])
        self.assertEqual("", result["draftAnswerPreview"])

    def test_keyword_conflict_is_not_ready(self):
        payload = valid_payload()
        payload["keywordConflict"] = True
        result = evaluate(payload)
        self.assertFalse(result["candidateReadyToExport"])
        self.assertEqual("", result["draftAnswerPreview"])

    def test_invalid_entry_id_is_not_previewed(self):
        payload = valid_payload()
        payload["entryId"] = "Invalid_ID"
        result = evaluate(payload)
        self.assertFalse(result["candidateReadyToExport"])
        self.assertEqual("", result["draftAnswerPreview"])

    def test_personal_contact_is_rejected_from_preview(self):
        payload = valid_payload()
        payload["draftAnswer"] = "请联系 jason@example.com 获取处理结果。"
        result = evaluate(payload)
        self.assertFalse(result["candidateReadyToExport"])
        self.assertEqual("", result["draftAnswerPreview"])

    def test_credentials_are_rejected_from_preview(self):
        payload = valid_payload()
        payload["draftAnswer"] = "token: abcdef0123456789"
        result = evaluate(payload)
        self.assertFalse(result["candidateReadyToExport"])

    def test_invalid_shape_fails_closed(self):
        with self.assertRaises(InputError):
            evaluate({"question": "x" * 301})

    def test_no_dynamic_code_or_persistence_contract(self):
        result = evaluate(valid_payload())
        self.assertIn("不写入文件或数据库", result["sandboxNotice"])
        self.assertNotIn("question", result)


if __name__ == "__main__":
    unittest.main()
