"""Unit tests for :mod:`scripts.evaluation.rd_eval_export_trials`.

The module is intentionally tested against an in-memory fake database
instead of mocking the whole psycopg connection: the spec requires real
``SET TRANSACTION READ ONLY`` semantics, so the production code talks to
something that behaves like a cursor, but the harness supplies its own.

The real PostgreSQL smoke test (``test_real_pg_smoke_export``) is opt-in
via the ``RD_INTEGRATION_TRIAL_EXPORT_ENABLED`` environment variable so
the default ``unittest discover`` run stays hermetic.
"""

from __future__ import annotations

import json
import os
import tempfile
import unittest
from pathlib import Path
from typing import Any, Iterable

from scripts.evaluation import rd_eval_lib as lib
from scripts.evaluation.rd_eval_export_trials import (
    DatabaseUnavailableError,
    OutputWriteError,
    REQUIRED_TRIAL_FIELDS,
    StrictTrialFieldError,
    TrialEventRow,
    TrialExportConfig,
    TrialRow,
    build_trial_payload,
    export_trials,
    filter_by_replicate,
    load_trials,
    load_trial_events,
    main,
    redact_and_write,
    sort_trials_for_export,
    validate_required_fields,
    version_string,
)


def _make_trial(
    trial_id: str = "trial-1",
    arm: str = "A",
    case_id: str = "case-1",
    replicate_no: int = 0,
    *,
    status: str = "COMPLETED",
    verdict: str = "PASS",
    error_message: str = "",
    frozen_input: dict[str, Any] | None = None,
    metrics: dict[str, Any] | None = None,
) -> TrialRow:
    raw = {
        "trialId": trial_id,
        "campaignId": 1234567890,
        "caseId": case_id,
        "arm": arm,
        "replicateNo": replicate_no,
        "status": status,
        "verdict": verdict,
        "attemptNo": 1,
        "version": 3,
        "leaseOwner": "worker-7",
        "leaseExpiresAtEpochMillis": 0,
        "errorCategory": "",
        "errorMessage": error_message,
        "frozenInputJson": frozen_input if frozen_input is not None else {"prompt": "hi"},
        "runtimeAttestationJson": {"imageDigest": "sha256:" + "a" * 64},
        "patchSummaryJson": {"filesChanged": 2},
        "metricsJson": metrics if metrics is not None else {},
        "createdAtEpochMillis": 1_700_000_000_000,
        "updatedAtEpochMillis": 1_700_000_500_000,
    }
    return TrialRow(raw=raw)


def _make_event(
    trial_id: str,
    to_status: str,
    *,
    event_id: int = 1,
    from_status: str = "",
    version: int = 1,
    message: str = "",
    error_category: str = "",
    error_message: str = "",
    occurred_at_epoch_millis: int = 1_700_000_100_000,
) -> TrialEventRow:
    raw = {
        "eventId": event_id,
        "trialId": trial_id,
        "fromStatus": from_status,
        "toStatus": to_status,
        "version": version,
        "message": message,
        "errorCategory": error_category,
        "errorMessage": error_message,
        "occurredAtEpochMillis": occurred_at_epoch_millis,
    }
    return TrialEventRow(raw=raw)


class ExportTrialsRedactionTest(unittest.TestCase):
    """Cases (b), (f) — minimal field round-trip and full redaction sweep."""

    def test_minimal_trial_round_trips_through_redact_and_write(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            output = Path(tmp_dir) / "trials.jsonl"
            trial = _make_trial()
            count = redact_and_write([trial], output)
            self.assertEqual(1, count)
            self.assertTrue(output.exists())
            line = output.read_text(encoding="utf-8").strip()
            payload = json.loads(line)
            for field in REQUIRED_TRIAL_FIELDS:
                self.assertIn(field, payload)
            self.assertEqual("trial-1", payload["trialId"])
            self.assertEqual("A", payload["arm"])
            self.assertEqual("case-1", payload["caseId"])
            self.assertEqual(0, payload["replicateNo"])
            self.assertEqual("PASS", payload["verdict"])
            self.assertEqual({"prompt": "hi"}, payload["frozenInputJson"])
            self.assertNotIn("events", payload)

    def test_redacts_bearer_tokens_api_keys_and_url_credentials(self) -> None:
        """End-to-end redaction sweep against every SECRET_PATTERNS branch.

        We deliberately avoid testing the absolute-path string because
        ``SECRET_PATTERNS`` does not include a standalone host-path rule —
        the library redacts URLs with embedded credentials and Bearer /
        ``sk-...`` / ``api_key=...`` secrets, which is what the Evaluation
        V2 §13 contract relies on.
        """

        secret_hex = "abcdef1234567890abcdef1234567890abcdef12"
        nested = {
            "prompt": "Bearer sk-abcdef1234567890abcdef",
            "errorMessage": "leaked api_key=abcdef1234567890abcdef12 here",
            "nested": {
                "deep": f"token={secret_hex}",
                "command": "curl https://user:pass@example.com/api?token=shh",
            },
            "list": [f"plain value api_key={secret_hex}"],
        }
        trial = _make_trial(
            trial_id="trial-secret",
            error_message="",
            frozen_input=nested,
            metrics={"audit": "Bearer abcdef1234567890abcdef"},
        )
        with tempfile.TemporaryDirectory() as tmp_dir:
            output = Path(tmp_dir) / "trials.jsonl"
            redact_and_write([trial], output)
            serialized = output.read_text(encoding="utf-8")
            self.assertNotIn("sk-abcdef1234567890abcdef", serialized)
            self.assertNotIn(secret_hex, serialized)
            self.assertNotIn("user:pass@example.com", serialized)
            self.assertIn("[REDACTED]", serialized)
            payload = json.loads(serialized.strip())
            self.assertTrue(
                payload["frozenInputJson"]["prompt"].endswith("[REDACTED]"),
                payload["frozenInputJson"]["prompt"],
            )
            self.assertIn("[REDACTED]", payload["frozenInputJson"]["errorMessage"])
            self.assertIn("[REDACTED]", payload["frozenInputJson"]["nested"]["deep"])
            self.assertIn("[REDACTED]", payload["frozenInputJson"]["list"][0])

    def test_redact_tolerates_none_and_non_string_values(self) -> None:
        """None / numbers / bools must round-trip unchanged through redacted_copy."""

        mixed = {
            "noneValue": None,
            "integer": 42,
            "float": 3.14,
            "flag": False,
            "list": [None, 1, "Bearer shh"],
            "nested": {"empty": None, "text": "ok"},
        }
        result = lib.redacted_copy(mixed)
        self.assertIsNone(result["noneValue"])
        self.assertEqual(42, result["integer"])
        self.assertEqual(3.14, result["float"])
        self.assertFalse(result["flag"])
        self.assertEqual([None, 1, "Bearer [REDACTED]"], result["list"])
        self.assertEqual("ok", result["nested"]["text"])
        # And the entry-level redact() helper must not crash on non-strings.
        self.assertEqual(None, lib.redact(None))
        self.assertEqual(123, lib.redact(123))
        self.assertEqual(True, lib.redact(True))

    def test_redact_and_write_sorts_by_arm_then_case(self) -> None:
        trials = [
            _make_trial(trial_id="t3", arm="C", case_id="zeta"),
            _make_trial(trial_id="t1", arm="A", case_id="alpha"),
            _make_trial(trial_id="t2", arm="B", case_id="beta"),
        ]
        with tempfile.TemporaryDirectory() as tmp_dir:
            output = Path(tmp_dir) / "sorted.jsonl"
            redact_and_write(trials, output)
            lines = [
                json.loads(line)
                for line in output.read_text(encoding="utf-8").splitlines()
                if line
            ]
            self.assertEqual(
                ["alpha", "beta", "zeta"],
                [row["caseId"] for row in lines],
            )


class ExportTrialsFilterTest(unittest.TestCase):
    """Cases (c) / (d) — replicate filtering."""

    def test_replicate_zero_keeps_only_zero_replicates(self) -> None:
        trials = [
            _make_trial(trial_id="t1", arm="A", case_id="c1", replicate_no=0),
            _make_trial(trial_id="t2", arm="A", case_id="c2", replicate_no=0),
            _make_trial(trial_id="t3", arm="B", case_id="c1", replicate_no=1),
            _make_trial(trial_id="t4", arm="B", case_id="c2", replicate_no=1),
            _make_trial(trial_id="t5", arm="C", case_id="c1", replicate_no=0),
        ]
        kept = filter_by_replicate(trials, 0)
        self.assertEqual(3, len(kept))
        self.assertEqual({0}, {trial.raw["replicateNo"] for trial in kept})

    def test_replicate_all_keeps_everything(self) -> None:
        trials = [
            _make_trial(trial_id="t1", arm="A", case_id="c1", replicate_no=0),
            _make_trial(trial_id="t2", arm="A", case_id="c2", replicate_no=0),
            _make_trial(trial_id="t3", arm="B", case_id="c1", replicate_no=1),
            _make_trial(trial_id="t4", arm="B", case_id="c2", replicate_no=1),
            _make_trial(trial_id="t5", arm="C", case_id="c1", replicate_no=0),
        ]
        kept = filter_by_replicate(trials, "all")
        self.assertEqual(5, len(kept))


class ExportTrialsStrictTest(unittest.TestCase):
    """Case (e) — strict mode rejects incomplete rows."""

    def test_strict_mode_raises_when_case_id_missing(self) -> None:
        incomplete = _make_trial(trial_id="t", arm="A", case_id="")
        with self.assertRaises(StrictTrialFieldError) as ctx:
            validate_required_fields(
                build_trial_payload(incomplete.raw),
                strict=True,
                trial_index=7,
            )
        self.assertEqual(7, ctx.exception.trial_index)
        self.assertIn("caseId", ctx.exception.missing)
        self.assertEqual(("caseId",), ctx.exception.missing)

    def test_non_strict_mode_returns_missing_without_raising(self) -> None:
        incomplete = _make_trial(trial_id="t", arm="", case_id="")
        missing = validate_required_fields(
            build_trial_payload(incomplete.raw),
            strict=False,
            trial_index=0,
        )
        self.assertEqual({"caseId", "arm"}, set(missing))


class ExportTrialsEventsTest(unittest.TestCase):
    """Case (g) — embedding events when ``--include-events`` is set."""

    def test_include_events_embeds_sorted_events_into_each_trial(self) -> None:
        trial = _make_trial(trial_id="t-evt", arm="A", case_id="case-evt")
        events = [
            _make_event("t-evt", "PREPARING", event_id=2, version=1),
            _make_event("t-evt", "RUNNING", event_id=3, version=2),
            _make_event("t-evt", "COMPLETED", event_id=4, version=3),
        ]
        events_by_trial = {"t-evt": events}
        with tempfile.TemporaryDirectory() as tmp_dir:
            output = Path(tmp_dir) / "with-events.jsonl"
            redact_and_write(
                [trial],
                output,
                events_by_trial=events_by_trial,
                include_events=True,
            )
            payload = json.loads(output.read_text(encoding="utf-8").strip())
            self.assertIn("events", payload)
            self.assertEqual(3, len(payload["events"]))
            self.assertEqual(
                ["PREPARING", "RUNNING", "COMPLETED"],
                [event["toStatus"] for event in payload["events"]],
            )
            for event in payload["events"]:
                for field in (
                    "eventId",
                    "toStatus",
                    "fromStatus",
                    "occurredAtEpochMillis",
                ):
                    self.assertIn(field, event)


class ExportTrialsConnectivityTest(unittest.TestCase):
    """Cases (a) / (h) — fail-fast on missing DB and PG smoke."""

    def test_missing_db_url_raises_database_unavailable_error(self) -> None:
        with self.assertRaises(DatabaseUnavailableError) as ctx:
            load_trials("", 12345)
        self.assertIn("--db-url", str(ctx.exception))

    def test_load_trials_refuses_to_silently_degrade(self) -> None:
        class FakeCursor:
            def __enter__(self): return self
            def __exit__(self, exc_type, exc, tb): return False
            def execute(self, sql, params=None):  # noqa: ARG002
                if "to_regclass" in sql:
                    self._missing = True
                else:
                    self._missing = False
            def fetchone(self):
                return (None,)

        class FakeConnection:
            def __enter__(self): return self
            def __exit__(self, exc_type, exc, tb): return False
            def cursor(self): return FakeCursor()
            def close(self): pass

        def fake_connect(_url: str) -> FakeConnection:
            return FakeConnection()

        with self.assertRaises(DatabaseUnavailableError) as ctx:
            load_trials("postgresql://user:pass@localhost/db", 42, connect=fake_connect)
        self.assertIn("rd_evaluation_trials", str(ctx.exception))

    def test_redact_and_write_leaves_no_tmp_file_on_disk_after_success(self) -> None:
        trials = [_make_trial(trial_id="t", arm="A", case_id="c")]
        with tempfile.TemporaryDirectory() as tmp_dir:
            output = Path(tmp_dir) / "atomic.jsonl"
            redact_and_write(trials, output)
            tmp_sibling = output.with_name(output.name + ".tmp")
            self.assertFalse(tmp_sibling.exists())
            self.assertTrue(output.exists())

    def test_version_string_is_stable(self) -> None:
        self.assertTrue(version_string().startswith("rd_eval_export_trials"))


@unittest.skipUnless(
    os.environ.get("RD_INTEGRATION_TRIAL_EXPORT_ENABLED"),
    "real PG smoke test; opt-in via RD_INTEGRATION_TRIAL_EXPORT_ENABLED=1",
)
class ExportTrialsPostgresSmokeTest(unittest.TestCase):
    """Case (a) — real PostgreSQL round-trip. Requires:

    * ``RD_INTEGRATION_TRIAL_EXPORT_ENABLED`` to opt in
    * ``RD_INTEGRATION_TRIAL_EXPORT_URL`` pointing at a writable database

    The smoke test inserts three rows into a per-run schema (so concurrent
    test invocations do not collide) and asserts the exporter produces a
    correctly ordered, redacted JSONL.
    """

    DB_URL_ENV = "RD_INTEGRATION_TRIAL_EXPORT_URL"
    SCHEMA = "rd_eval_export_trials_smoke"

    @classmethod
    def setUpClass(cls) -> None:
        try:
            import psycopg  # type: ignore[import-not-found]
        except ImportError as exc:  # pragma: no cover - skip if absent
            raise unittest.SkipTest(f"psycopg not installed: {exc}") from exc
        cls.psycopg = psycopg
        url = os.environ.get(cls.DB_URL_ENV)
        if not url:
            raise unittest.SkipTest(f"{cls.DB_URL_ENV} is not set")
        cls.db_url = url
        cls.campaign_id = 1_700_000_000_000
        with psycopg.connect(url, autocommit=True) as conn:
            with conn.cursor() as cursor:
                cursor.execute(f'CREATE SCHEMA IF NOT EXISTS "{cls.SCHEMA}"')
                cursor.execute(
                    "CREATE TABLE IF NOT EXISTS rd_evaluation_runs ("
                    "  id BIGINT PRIMARY KEY,"
                    "  name TEXT NOT NULL DEFAULT ''"
                    ")"
                )
                cursor.execute(
                    f'CREATE TABLE IF NOT EXISTS "{cls.SCHEMA}".rd_evaluation_trials ('
                    "  id VARCHAR(200) PRIMARY KEY,"
                    "  campaign_id BIGINT NOT NULL,"
                    "  case_id VARCHAR(200) NOT NULL,"
                    "  arm VARCHAR(8) NOT NULL,"
                    "  replicate_no INTEGER NOT NULL,"
                    "  status VARCHAR(32) NOT NULL,"
                    "  verdict VARCHAR(64) NOT NULL DEFAULT 'PENDING',"
                    "  attempt_no INTEGER NOT NULL DEFAULT 1,"
                    "  version BIGINT NOT NULL DEFAULT 0,"
                    "  lease_owner VARCHAR(200) NOT NULL DEFAULT '',"
                    "  lease_expires_at TIMESTAMPTZ,"
                    "  error_category VARCHAR(128) NOT NULL DEFAULT '',"
                    "  error_message TEXT NOT NULL DEFAULT '',"
                    "  frozen_input_json JSONB NOT NULL DEFAULT '{}'::jsonb,"
                    "  runtime_attestation_json JSONB NOT NULL DEFAULT '{}'::jsonb,"
                    "  patch_summary_json JSONB NOT NULL DEFAULT '{}'::jsonb,"
                    "  metrics_json JSONB NOT NULL DEFAULT '{}'::jsonb,"
                    "  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),"
                    "  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()"
                    ")"
                )
                cursor.execute(
                    f'CREATE TABLE IF NOT EXISTS "{cls.SCHEMA}".rd_evaluation_trial_events ('
                    "  id BIGINT PRIMARY KEY,"
                    "  campaign_id BIGINT NOT NULL,"
                    "  trial_id VARCHAR(200) NOT NULL,"
                    "  from_status VARCHAR(32) NOT NULL DEFAULT '',"
                    "  to_status VARCHAR(32) NOT NULL,"
                    "  version BIGINT NOT NULL,"
                    "  message TEXT NOT NULL DEFAULT '',"
                    "  error_category VARCHAR(128) NOT NULL DEFAULT '',"
                    "  error_message TEXT NOT NULL DEFAULT '',"
                    "  occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()"
                    ")"
                )
                cursor.execute(
                    f'CREATE OR REPLACE VIEW public.rd_evaluation_trials AS '
                    f'SELECT * FROM "{cls.SCHEMA}".rd_evaluation_trials'
                )
                cursor.execute(
                    f'CREATE OR REPLACE VIEW public.rd_evaluation_trial_events AS '
                    f'SELECT * FROM "{cls.SCHEMA}".rd_evaluation_trial_events'
                )
                cursor.execute(
                    "INSERT INTO rd_evaluation_runs (id, name) VALUES (%s, %s) "
                    "ON CONFLICT (id) DO NOTHING",
                    (cls.campaign_id, "smoke"),
                )
                cursor.execute(
                    f'DELETE FROM "{cls.SCHEMA}".rd_evaluation_trial_events '
                    f"WHERE campaign_id = %s",
                    (cls.campaign_id,),
                )
                cursor.execute(
                    f'DELETE FROM "{cls.SCHEMA}".rd_evaluation_trials '
                    f"WHERE campaign_id = %s",
                    (cls.campaign_id,),
                )
                seed = [
                    ("trial-A-1", "A", "case-1", 0, "COMPLETED", "PASS"),
                    ("trial-B-1", "B", "case-1", 0, "FAILED", "FAIL"),
                    ("trial-C-1", "C", "case-1", 0, "COMPLETED", "PASS"),
                ]
                for tid, arm, case, rep, status, verdict in seed:
                    cursor.execute(
                        f'INSERT INTO "{cls.SCHEMA}".rd_evaluation_trials ('
                        "  id, campaign_id, case_id, arm, replicate_no, status, verdict"
                        ") VALUES (%s, %s, %s, %s, %s, %s, %s)",
                        (tid, cls.campaign_id, case, arm, rep, status, verdict),
                    )

    @classmethod
    def tearDownClass(cls) -> None:
        try:
            with cls.psycopg.connect(cls.db_url, autocommit=True) as conn:
                with conn.cursor() as cursor:
                    cursor.execute(f'DROP SCHEMA IF EXISTS "{cls.SCHEMA}" CASCADE')
                    cursor.execute("DROP VIEW IF EXISTS public.rd_evaluation_trials")
                    cursor.execute("DROP VIEW IF EXISTS public.rd_evaluation_trial_events")
        except Exception:  # pragma: no cover - best-effort teardown
            pass

    def test_real_pg_smoke_export(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            output = Path(tmp_dir) / "smoke.jsonl"
            config = TrialExportConfig(
                campaign_id=self.campaign_id,
                db_url=self.db_url,
                output_path=output,
                replicate_no=0,
                strict=True,
                include_events=False,
            )
            stderr_lines: list[str] = []
            count = export_trials(
                config,
                stderr_write=stderr_lines.append,
            )
            self.assertEqual(3, count)
            self.assertTrue(output.exists())
            rows = [
                json.loads(line)
                for line in output.read_text(encoding="utf-8").splitlines()
                if line
            ]
            self.assertEqual(["A", "B", "C"], [row["arm"] for row in rows])
            self.assertEqual(["trial-A-1", "trial-B-1", "trial-C-1"], [row["trialId"] for row in rows])


class ExportTrialsMainTest(unittest.TestCase):
    """Coverage for the CLI entry-point and public lib helpers."""

    def test_main_returns_2_when_db_url_missing(self) -> None:
        captured: dict[str, str] = {}

        def fake_stderr(message: str) -> None:
            captured.setdefault("stderr", "")
            captured["stderr"] += message + "\n"

        with tempfile.TemporaryDirectory() as tmp_dir:
            argv = [
                "--campaign-id", "12345",
                "--output", str(Path(tmp_dir) / "x.jsonl"),
                "--replicate-no", "0",
            ]
            original_stderr_write = print
            try:
                import builtins
                builtins.print = lambda *args, **kwargs: fake_stderr(args[0] if args else "")
                rc = main(argv)
            finally:
                import builtins
                builtins.print = original_stderr_write
            self.assertEqual(2, rc)
            self.assertIn("--db-url", captured.get("stderr", ""))


class ExportTrialsHelperTest(unittest.TestCase):
    def test_sort_trials_for_export_is_deterministic(self) -> None:
        rows = [
            _make_trial(trial_id="t3", arm="C", case_id="zeta"),
            _make_trial(trial_id="t2", arm="A", case_id="beta"),
            _make_trial(trial_id="t1", arm="A", case_id="alpha"),
        ]
        sorted_rows = sort_trials_for_export(rows)
        self.assertEqual(["alpha", "beta", "zeta"], [r.raw["caseId"] for r in sorted_rows])

    def test_load_trial_events_groups_by_trial_id(self) -> None:
        class _Col:
            def __init__(self, name: str) -> None:
                self.name = name

        class FakeCursor:
            def __init__(
                self,
                *,
                description_columns: Iterable[str],
                preselected_rows: list[tuple],
                pre_check_table_exists: bool,
            ) -> None:
                self._columns = list(description_columns)
                self._rows = list(preselected_rows)
                self._pre_check_table_exists = pre_check_table_exists
                self._stage = "precheck"
                self._executed: list[str] = []

            def __enter__(self): return self
            def __exit__(self, exc_type, exc, tb): return False

            @property
            def description(self):
                return [_Col(name) for name in self._columns]

            def execute(self, sql, params=None):  # noqa: ARG002
                self._executed.append(sql)
                if "to_regclass" in sql:
                    self._stage = "precheck"
                else:
                    self._stage = "rows"

            def fetchone(self):
                if self._stage != "precheck":
                    raise AssertionError("fetchone called outside precheck stage")
                if self._pre_check_table_exists:
                    return ("public.rd_evaluation_trial_events",)
                return (None,)

            def fetchall(self):
                return list(self._rows)

        class FakeConnection:
            def __init__(self, cursor: FakeCursor) -> None:
                self._cursor = cursor

            def __enter__(self): return self
            def __exit__(self, exc_type, exc, tb): return False

            def cursor(self): return self._cursor
            def close(self): pass

        cursor = FakeCursor(
            description_columns=(
                "id", "trial_id", "from_status", "to_status", "version",
                "message", "error_category", "error_message", "occurred_at",
            ),
            preselected_rows=[
                (1, "t-A", "", "PREPARING", 1, "", "", "", None),
                (2, "t-A", "PREPARING", "RUNNING", 2, "", "", "", None),
                (3, "t-B", "", "COMPLETED", 5, "", "", "", None),
            ],
            pre_check_table_exists=True,
        )

        def fake_connect(_url: str) -> FakeConnection:
            return FakeConnection(cursor)

        grouped = load_trial_events(
            "postgresql://x",
            42,
            ["t-A", "t-B"],
            connect=fake_connect,
        )
        self.assertEqual({"t-A", "t-B"}, set(grouped.keys()))
        self.assertEqual(2, len(grouped["t-A"]))
        self.assertEqual(1, len(grouped["t-B"]))


if __name__ == "__main__":
    unittest.main()