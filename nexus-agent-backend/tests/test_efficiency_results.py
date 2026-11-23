"""T3 result caps, synthetic unit data only. Not executed in this window."""
import unittest

from app.efficiency.results import BudgetLimited, json_bytes, limit_result


class ResultBudgetTests(unittest.TestCase):
    def test_under_budget_is_detached_and_counts_actual_utf8(self):
        value = {"source": "unit", "content": "你好😀", "untrusted": True}
        limited = limit_result(value)
        self.assertEqual(limited.original_bytes, len(json_bytes(value)))
        self.assertEqual(limited.serialized, json_bytes(limited.value))
        value["content"] = "changed"
        self.assertEqual(limited.value["content"], "你好😀")
        self.assertFalse(limited.truncated)

    def test_unicode_body_clipping_keeps_evidence_and_valid_json(self):
        value = {"source": "unit", "untrusted": True,
                 "messageId": 11, "chatId": 10, "timestamp": "2026-11-22T10:00:00Z",
                 "content": "中😀" * 3000}
        limited = limit_result(value, max_bytes=500, body_path=("content",))
        self.assertTrue(limited.truncated)
        self.assertLessEqual(len(limited.serialized), 500)
        self.assertEqual(limited.serialized, json_bytes(limited.value))
        self.assertEqual(limited.value["value"]["messageId"], 11)
        self.assertEqual(limited.value["value"]["chatId"], 10)
        self.assertTrue(limited.value["value"]["untrusted"])
        self.assertEqual(limited.value["truncation"]["originalBodyBytes"], 21000)
        self.assertEqual(value["content"], "中😀" * 3000)
        self.assertLess(len(limited.value["value"]["content"]), len(value["content"]))

    def test_token_estimate_cap_also_limits_final_envelope(self):
        limited = limit_result("x" * 10000, max_bytes=65536, max_tokens=250)
        self.assertLessEqual(limited.estimated_tokens, 250)
        self.assertEqual(limited.estimated_tokens, len(limited.serialized))
        self.assertNotIn("actualUsage", limited.__dict__)

    def test_fact_pages_cannot_be_clipped_without_honest_pagination(self):
        page = {"readCount": 1, "returnedCount": 1, "messages": [{"messageId": 1, "content": "x" * 10000}]}
        with self.assertRaisesRegex(BudgetLimited, "fact-page-evidence"):
            limit_result(page, max_bytes=500, body_path=("messages", 0, "content"))

    def test_minimal_evidence_and_invalid_paths_are_explicitly_limited(self):
        with self.assertRaisesRegex(BudgetLimited, "minimum-evidence"):
            limit_result("x" * 1000, max_bytes=1)
        with self.assertRaisesRegex(BudgetLimited, "invalid-body-path"):
            limit_result({"content": "x" * 1000}, max_bytes=100, body_path=("missing",))

    def test_non_string_payload_without_designated_body_is_not_faked(self):
        with self.assertRaisesRegex(BudgetLimited, "minimum-evidence"):
            limit_result({"values": list(range(1000))}, max_bytes=100)

    def test_nonfinite_json_is_rejected_without_echoing_payload(self):
        with self.assertRaisesRegex(BudgetLimited, "invalid-json"):
            limit_result({"bad": float("nan")})


if __name__ == "__main__":
    unittest.main()
