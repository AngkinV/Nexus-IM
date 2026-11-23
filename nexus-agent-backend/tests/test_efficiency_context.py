"""T3 context regressions. Written here; execution is owned by original T1."""
import unittest

from app.efficiency.context import ContextBudget
from app.efficiency.results import BudgetLimited, json_bytes


class ContextBudgetTests(unittest.TestCase):
    def test_whole_serialized_input_and_output_reserve_are_counted(self):
        messages = [{"role": "system", "content": "policy"}, {"role": "user", "content": "你好😀"}]
        probe = ContextBudget(2000, 64)
        estimate = probe.estimate(messages, [])
        self.assertEqual(estimate, len(json_bytes({"messages": messages, "tools": []})) + 256 + 64)
        fitted = ContextBudget(estimate + 64, 64).fit(messages)
        self.assertEqual(fitted.estimated_input_tokens + fitted.reserved_output_tokens, estimate + 64)
        with self.assertRaises(BudgetLimited):
            ContextBudget(estimate + 63, 64).fit(messages)
        self.assertNotIn("usage", fitted.__dict__)

    def test_old_history_turn_is_dropped_with_all_its_tools(self):
        messages = [
            {"role": "system", "content": "policy"},
            {"role": "user", "content": "old" * 2000},
            {"role": "assistant", "content": None, "tool_calls": [{"id": "old", "type": "function"}]},
            {"role": "tool", "tool_call_id": "old", "content": "old result" * 2000},
            {"role": "assistant", "content": "old answer"},
            {"role": "user", "content": "current"},
            {"role": "assistant", "content": None, "tool_calls": [{"id": "new", "type": "function"}]},
            {"role": "tool", "tool_call_id": "new", "content": "new evidence"},
        ]
        fitted = ContextBudget(1600, 100).fit(messages)
        self.assertEqual(fitted.dropped_history_turns, 1)
        self.assertEqual([m["role"] for m in fitted.messages], ["system", "user", "assistant", "tool"])
        self.assertEqual(fitted.messages[-1]["tool_call_id"], "new")
        self.assertLessEqual(fitted.estimated_input_tokens + 100, 1600)

    def test_current_input_and_embedded_attachment_cannot_be_silently_sliced(self):
        for content in ("input" * 10000, "[attachment]\n" + "中😀" * 10000):
            with self.subTest(content_bytes=len(content.encode("utf-8"))):
                with self.assertRaisesRegex(BudgetLimited, "context-required-input"):
                    ContextBudget(1000, 100).fit([{"role": "user", "content": content}])

    def test_internal_tool_results_and_schema_are_in_the_same_cap(self):
        messages = [{"role": "user", "content": "request"},
                    {"role": "assistant", "tool_calls": [{"id": "c", "type": "function"}]},
                    {"role": "tool", "tool_call_id": "c", "content": "result" * 10000}]
        with self.assertRaises(BudgetLimited):
            ContextBudget(2000, 100).fit(messages)
        schema = [{"type": "function", "function": {"name": "t", "description": "schema" * 10000}}]
        with self.assertRaises(BudgetLimited):
            ContextBudget(2000, 100).fit([{"role": "user", "content": "small"}], schema)

    def test_orphan_duplicate_and_unresolved_tools_are_rejected(self):
        cases = [
            [{"role": "tool", "tool_call_id": "missing", "content": "x"}],
            [{"role": "assistant", "tool_calls": [{"id": "a"}]}],
            [{"role": "assistant", "tool_calls": [{"id": "a"}, {"id": "a"}]}],
            [{"role": "assistant", "tool_calls": [{"id": "a"}]}, {"role": "user", "content": "x"}],
            [{"role": "assistant", "tool_calls": [{"id": "a"}]},
             {"role": "tool", "tool_call_id": "a"}, {"role": "tool", "tool_call_id": "a"}],
        ]
        for messages in cases:
            with self.subTest(messages=messages), self.assertRaises(BudgetLimited):
                ContextBudget().fit(messages)

    def test_output_request_may_only_tighten(self):
        messages = [{"role": "user", "content": "x"}]
        self.assertEqual(ContextBudget().fit(messages, max_output_tokens=512).reserved_output_tokens, 512)
        for reserve in (0, True, 1025):
            with self.subTest(reserve=reserve), self.assertRaises(ValueError):
                ContextBudget().fit(messages, max_output_tokens=reserve)

    def test_prepared_receipt_is_detached_from_mutable_factory_inputs(self):
        messages = [{"role": "user", "content": "original"}]
        fitted = ContextBudget().fit(messages)
        messages[0]["content"] = "mutated"
        fitted.messages[0]["content"] = "factory-mutated"
        self.assertEqual(fitted.messages[0]["content"], "original")

    def test_native_images_require_a_provider_aware_estimator(self):
        messages = [{"role": "user", "content": [{"type": "image_url", "image_url": {"url": "https://example.invalid/synthetic.png"}}]}]
        with self.assertRaisesRegex(BudgetLimited, "multimodal-tokenizer-required"):
            ContextBudget().fit(messages)
        fitted = ContextBudget(estimator=lambda m, t: 1000, estimation_method="unit-provider-aware").fit(messages)
        self.assertEqual(fitted.estimated_input_tokens, 1000)
        self.assertEqual(fitted.estimation_method, "unit-provider-aware")

    def test_custom_estimator_requires_label_and_integer(self):
        with self.assertRaises(ValueError):
            ContextBudget(estimator=lambda m, t: 1)
        with self.assertRaises(ValueError):
            ContextBudget(estimator=lambda m, t: True, estimation_method="unit").fit([])


if __name__ == "__main__":
    unittest.main()
