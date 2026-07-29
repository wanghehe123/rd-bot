from __future__ import annotations

import unittest

from scripts.evaluation.rd_eval_oracle import verify_result


class OracleContractTest(unittest.TestCase):
    def test_oracle_fails_when_expected_test_id_is_not_collected(self) -> None:
        result = verify_result(
            test_output="1 passed",
            expected_test_ids=["test_issue::test_regression"],
        )

        self.assertEqual("TEST_FAIL", result.verdict)
        self.assertEqual(("test_issue::test_regression",), result.missing_test_ids)

    def test_oracle_accepts_exactly_once_collected_passing_test_ids(self) -> None:
        result = verify_result(
            test_output='RD_EVAL_COLLECTED_TEST_IDS=["test_issue::test_regression"]\n1 passed',
            expected_test_ids=["test_issue::test_regression"],
        )

        self.assertEqual("PASS", result.verdict)
        self.assertEqual((), result.missing_test_ids)


if __name__ == "__main__":
    unittest.main()
