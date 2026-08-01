#!/usr/bin/env python3
"""Export immutable RD-Bot evaluation trials from PostgreSQL as JSONL.

Evaluation V2 Step 4 — read-only exporter.

This script is intentionally read-only against PostgreSQL: every transaction
opens with ``SET TRANSACTION READ ONLY`` and never issues INSERT / UPDATE /
DELETE. It writes a single ``trials.jsonl`` artifact (one trial per line) so
downstream scorer / report / verify tools can consume an immutable snapshot
without holding a database connection.

The exporter follows the Evaluation V2 spec §10.3 (default
``--replicate-no=0``), §13 (HTTP / download surfaces must never expose
complete prompts, raw Pi private events, secrets, host absolute paths, gold
patches, or withheld runtime tests — every row is run through
``rd_eval_lib.redacted_copy`` before write) and §11 (sort by ``(arm,
case_id)`` to keep the A→B→C→D ordering deterministic across runs).
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Iterable, Mapping, Sequence

if __package__ is None or __package__ == "":
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from scripts.evaluation import rd_eval_lib as lib  # noqa: E402

__all__ = [
    "DatabaseUnavailableError",
    "OutputWriteError",
    "StrictTrialFieldError",
    "REQUIRED_TRIAL_FIELDS",
    "TrialExportConfig",
    "TrialRow",
    "TrialEventRow",
    "build_trial_payload",
    "filter_by_replicate",
    "load_trials",
    "load_trial_events",
    "redact_and_write",
    "sort_trials_for_export",
    "validate_required_fields",
    "version_string",
]

SCRIPT_VERSION = "1.0.0"
DEFAULT_REPLICATE_NO = 0
PG_TRIALS_TABLE = "public.rd_evaluation_trials"
PG_TRIAL_EVENTS_TABLE = "public.rd_evaluation_trial_events"

#: Field set the strict exporter refuses to live without. Mirrors the
#: ``CodingBenchmarkTrialRow`` contract; ``caseId`` / ``arm`` / ``trialId``
#: are the three identity fields that downstream scorer / report depend on.
REQUIRED_TRIAL_FIELDS: tuple[str, ...] = (
    "trialId",
    "caseId",
    "arm",
)

# Optional fields that the exporter still propagates as ``""`` (or ``[]`` for
# collections) when the underlying column is NULL. They are listed so a
# caller can introspect what we surface, not for validation.
OPTIONAL_TRIAL_FIELDS: tuple[str, ...] = (
    "campaignId",
    "replicateNo",
    "status",
    "verdict",
    "attemptNo",
    "version",
    "leaseOwner",
    "leaseExpiresAtEpochMillis",
    "errorCategory",
    "errorMessage",
    "frozenInputJson",
    "runtimeAttestationJson",
    "patchSummaryJson",
    "metricsJson",
    "createdAtEpochMillis",
    "updatedAtEpochMillis",
)

OPTIONAL_EVENT_FIELDS: tuple[str, ...] = (
    "eventId",
    "trialId",
    "fromStatus",
    "toStatus",
    "version",
    "message",
    "errorCategory",
    "errorMessage",
    "occurredAtEpochMillis",
)


class DatabaseUnavailableError(RuntimeError):
    """Raised when the PostgreSQL exporter cannot reach the trial table.

    Covers missing ``--db-url``, unreachable host, missing table, and
    insufficient privileges. We deliberately collapse these into one
    fail-fast bucket because the spec requires the script to refuse
    partial output rather than silently skip campaigns.
    """


class StrictTrialFieldError(ValueError):
    """Raised by :func:`validate_required_fields` under ``--strict``."""

    def __init__(self, trial_index: int, missing: Sequence[str]) -> None:
        self.trial_index = trial_index
        self.missing = tuple(missing)
        message = (
            f"trial #{trial_index} is missing required fields: "
            + ", ".join(self.missing)
        )
        super().__init__(message)


class OutputWriteError(OSError):
    """Raised when the atomic write (tmp + fsync + rename) fails."""


@dataclass(frozen=True)
class TrialExportConfig:
    """Resolved exporter configuration, immutable once parsed."""

    campaign_id: int
    db_url: str
    output_path: Path
    replicate_no: int | str  # ``0``, ``1``, or ``"all"``
    strict: bool
    include_events: bool


@dataclass(frozen=True)
class TrialRow:
    """Decoded row from ``rd_evaluation_trials`` — pure data carrier."""

    raw: dict[str, Any]

    def to_payload(self) -> dict[str, Any]:
        """Convert the raw row to the exported JSON shape.

        Field names match the camelCase contract used by the Evaluation V2
        scorer / report stages. JSONB columns are surfaced as already-decoded
        objects (``frozenInputJson`` etc.) so downstream tools can iterate
        without re-parsing.
        """

        return build_trial_payload(self.raw)


@dataclass(frozen=True)
class TrialEventRow:
    """Decoded row from ``rd_evaluation_trial_events``."""

    raw: dict[str, Any]

    def to_payload(self) -> dict[str, Any]:
        return {
            "eventId": self.raw.get("eventId", ""),
            "trialId": self.raw.get("trialId", ""),
            "fromStatus": self.raw.get("fromStatus", ""),
            "toStatus": self.raw.get("toStatus", ""),
            "version": self.raw.get("version", 0),
            "message": self.raw.get("message", ""),
            "errorCategory": self.raw.get("errorCategory", ""),
            "errorMessage": self.raw.get("errorMessage", ""),
            "occurredAtEpochMillis": self.raw.get("occurredAtEpochMillis", 0),
        }


def version_string() -> str:
    """Return the script version. Used by ``--version``."""

    return f"rd_eval_export_trials {SCRIPT_VERSION}"


def _parse_replicate_no(value: str) -> int | str:
    """Validate ``--replicate-no`` and normalise ``all``."""

    if value == "all":
        return "all"
    if value not in {"0", "1"}:
        raise argparse.ArgumentTypeError(
            "--replicate-no must be 0, 1, or 'all' (spec §10.3 restricts replicates to 0/1)"
        )
    return int(value)


def _build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="rd_eval_export_trials",
        description=(
            "Export immutable RD-Bot evaluation trials from PostgreSQL as JSONL. "
            "Read-only: never writes to PG."
        ),
    )
    parser.add_argument(
        "--campaign-id",
        required=True,
        type=int,
        help="Numeric campaign (rd_evaluation_runs.id) to export.",
    )
    parser.add_argument(
        "--db-url",
        default=os.environ.get("RD_EVAL_PG_URL", ""),
        help=(
            "PostgreSQL connection URL, e.g. postgresql://user:pass@host:5432/rdbot"
            " (supports ?sslmode=...). Defaults to $RD_EVAL_PG_URL; empty fails fast."
        ),
    )
    parser.add_argument(
        "--output",
        required=True,
        type=Path,
        help="Output JSONL path. Written atomically (tmp + fsync + rename).",
    )
    parser.add_argument(
        "--replicate-no",
        type=_parse_replicate_no,
        default=DEFAULT_REPLICATE_NO,
        help=(
            "Replicate filter: 0 (default, primary 20x4), 1, or 'all'. "
            "Spec §10.3 mandates replicate_no=0 for the primary campaign."
        ),
    )
    parser.add_argument(
        "--strict",
        action="store_true",
        help="Reject any trial missing required identity fields (trialId/caseId/arm).",
    )
    parser.add_argument(
        "--include-events",
        action="store_true",
        help="Embed rd_evaluation_trial_events rows under each trial's 'events' key.",
    )
    parser.add_argument(
        "--version",
        action="version",
        version=version_string(),
    )
    return parser


def _coerce_int(value: Any, default: int = 0) -> int:
    if value is None:
        return default
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def _epoch_millis(value: Any) -> int:
    """Best-effort conversion of TIMESTAMPTZ / datetime / string to epoch ms."""

    if value is None or value == "":
        return 0
    if isinstance(value, datetime):
        if value.tzinfo is None:
            value = value.replace(tzinfo=timezone.utc)
        return int(value.timestamp() * 1000)
    if isinstance(value, (int, float)):
        seconds = float(value)
        if seconds > 1e18:
            return int(seconds / 1_000_000)
        if seconds > 1e15:
            return int(seconds / 1_000_000)
        if seconds > 1e12:
            return int(seconds)
        return int(seconds * 1000)
    text = str(value).strip()
    if not text:
        return 0
    try:
        return int(text)
    except ValueError:
        pass
    try:
        cleaned = text.replace("Z", "+00:00")
        parsed = datetime.fromisoformat(cleaned)
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=timezone.utc)
        return int(parsed.timestamp() * 1000)
    except ValueError:
        return 0


def _as_jsonb(value: Any) -> dict[str, Any]:
    """Decode JSONB-like column values to dict, returning ``{}`` on miss."""

    if value is None:
        return {}
    if isinstance(value, dict):
        return dict(value)
    if isinstance(value, (bytes, bytearray)):
        try:
            decoded = value.decode("utf-8")
        except UnicodeDecodeError:
            return {}
        try:
            parsed = json.loads(decoded)
        except json.JSONDecodeError:
            return {}
        return parsed if isinstance(parsed, dict) else {}
    if isinstance(value, str):
        if not value:
            return {}
        try:
            parsed = json.loads(value)
        except json.JSONDecodeError:
            return {}
        return parsed if isinstance(parsed, dict) else {}
    return {}


def build_trial_payload(raw: Mapping[str, Any]) -> dict[str, Any]:
    """Convert a raw DB row mapping to the exported JSON shape.

    The mapping uses the camelCase names produced by
    :func:`_row_to_camel_mapping`. The shape matches the Evaluation V2
    downstream contract: trial identity fields first, then run metadata,
    then JSONB payloads (already-decoded objects).
    """

    trial_id = raw.get("trialId", "")
    case_id = raw.get("caseId", "")
    arm = raw.get("arm", "")
    payload: dict[str, Any] = {
        "trialId": trial_id,
        "campaignId": _coerce_int(raw.get("campaignId", 0)),
        "caseId": case_id,
        "arm": arm,
        "replicateNo": _coerce_int(raw.get("replicateNo", 0)),
        "status": raw.get("status", ""),
        "verdict": raw.get("verdict", "PENDING"),
        "attemptNo": _coerce_int(raw.get("attemptNo", 1)),
        "version": _coerce_int(raw.get("version", 0)),
        "leaseOwner": raw.get("leaseOwner", ""),
        "leaseExpiresAtEpochMillis": _epoch_millis(raw.get("leaseExpiresAtEpochMillis")),
        "errorCategory": raw.get("errorCategory", ""),
        "errorMessage": raw.get("errorMessage", ""),
        "frozenInputJson": _as_jsonb(raw.get("frozenInputJson")),
        "runtimeAttestationJson": _as_jsonb(raw.get("runtimeAttestationJson")),
        "patchSummaryJson": _as_jsonb(raw.get("patchSummaryJson")),
        "metricsJson": _as_jsonb(raw.get("metricsJson")),
        "createdAtEpochMillis": _epoch_millis(raw.get("createdAtEpochMillis")),
        "updatedAtEpochMillis": _epoch_millis(raw.get("updatedAtEpochMillis")),
        "events": raw.get("events", []),
    }
    return payload


def validate_required_fields(
    payload: Mapping[str, Any],
    *,
    strict: bool,
    trial_index: int,
) -> list[str]:
    """Return the list of missing identity fields.

    Under ``strict=True``, raises :class:`StrictTrialFieldError` the first
    time we see a missing identity field. Under non-strict mode the function
    still returns the missing names so callers can emit warnings.
    """

    missing = [field for field in REQUIRED_TRIAL_FIELDS if not payload.get(field)]
    if strict and missing:
        raise StrictTrialFieldError(trial_index, missing)
    return missing


def filter_by_replicate(
    trials: Iterable[TrialRow],
    replicate_no: int | str,
) -> list[TrialRow]:
    """Apply ``--replicate-no`` to the trial list (default 0; ``all`` skips)."""

    materialized = list(trials)
    if replicate_no == "all":
        return materialized
    keep = int(replicate_no)
    return [trial for trial in materialized if int(trial.raw.get("replicateNo", -1)) == keep]


def sort_trials_for_export(trials: Iterable[TrialRow]) -> list[TrialRow]:
    """Sort by ``(arm, caseId)`` to keep the A→B→C→D order deterministic.

    Falls back to ``trialId`` as a final tiebreaker so two trials on the
    same arm/caseId (which the table's UNIQUE constraint forbids anyway)
    still produce a stable ordering.
    """

    return sorted(
        trials,
        key=lambda trial: (
            str(trial.raw.get("arm", "")),
            str(trial.raw.get("caseId", "")),
            str(trial.raw.get("trialId", "")),
        ),
    )


def _row_to_camel_mapping(row: Mapping[str, Any]) -> dict[str, Any]:
    """Translate psycopg's snake_case keys into the camelCase export names.

    Keeping the mapping centralised here makes the unit tests trivial: they
    can fabricate rows in camelCase directly without needing psycopg.
    """

    return {
        "trialId": row.get("id", ""),
        "campaignId": _coerce_int(row.get("campaign_id")),
        "caseId": row.get("case_id", ""),
        "arm": row.get("arm", ""),
        "replicateNo": _coerce_int(row.get("replicate_no")),
        "status": row.get("status", ""),
        "verdict": row.get("verdict", "PENDING"),
        "attemptNo": _coerce_int(row.get("attempt_no", 1)),
        "version": _coerce_int(row.get("version", 0)),
        "leaseOwner": row.get("lease_owner", ""),
        "leaseExpiresAtEpochMillis": row.get("lease_expires_at"),
        "errorCategory": row.get("error_category", ""),
        "errorMessage": row.get("error_message", ""),
        "frozenInputJson": row.get("frozen_input_json"),
        "runtimeAttestationJson": row.get("runtime_attestation_json"),
        "patchSummaryJson": row.get("patch_summary_json"),
        "metricsJson": row.get("metrics_json"),
        "createdAtEpochMillis": row.get("created_at"),
        "updatedAtEpochMillis": row.get("updated_at"),
    }


def load_trials(
    db_url: str,
    campaign_id: int,
    *,
    connect: Callable[[str], Any] | None = None,
) -> list[TrialRow]:
    """Read all trials for a campaign from PostgreSQL (read-only transaction).

    ``connect`` is overridable so tests can inject an in-memory stub without
    having psycopg installed. The default path uses ``psycopg.connect``.
    """

    if not db_url:
        raise DatabaseUnavailableError(
            "--db-url (or RD_EVAL_PG_URL) is required to export trials; refusing to run."
        )
    factory = connect or _default_connect
    try:
        connection = factory(db_url)
    except DatabaseUnavailableError:
        raise
    except Exception as exc:  # pragma: no cover - actual PG failures
        raise DatabaseUnavailableError(f"cannot connect to PostgreSQL: {exc}") from exc

    try:
        with connection.cursor() as cursor:
            cursor.execute("SELECT to_regclass(%s)", (PG_TRIALS_TABLE,))
            row = cursor.fetchone()
            present = row and row[0] is not None
            if not present:
                raise DatabaseUnavailableError(
                    f"table {PG_TRIALS_TABLE} is missing; run the P4 web eval console migration first"
                )
            cursor.execute("SET TRANSACTION READ ONLY")
            cursor.execute(
                """
                SELECT id, campaign_id, case_id, arm, replicate_no, status, verdict,
                       attempt_no, version, lease_owner, lease_expires_at, error_category,
                       error_message, frozen_input_json, runtime_attestation_json,
                       patch_summary_json, metrics_json, created_at, updated_at
                  FROM rd_evaluation_trials
                 WHERE campaign_id = %s
                 ORDER BY created_at, id
                """,
                (campaign_id,),
            )
            trials: list[TrialRow] = []
            for db_row in cursor.fetchall():
                column_names = [desc.name for desc in cursor.description or []]
                snake_row = dict(zip(column_names, db_row))
                trials.append(TrialRow(raw=_row_to_camel_mapping(snake_row)))
            return trials
    except DatabaseUnavailableError:
        raise
    except Exception as exc:  # pragma: no cover - actual PG failures
        raise DatabaseUnavailableError(f"query against {PG_TRIALS_TABLE} failed: {exc}") from exc
    finally:
        try:
            connection.close()
        except Exception:  # pragma: no cover - best-effort close
            pass


def load_trial_events(
    db_url: str,
    campaign_id: int,
    trial_ids: Sequence[str],
    *,
    connect: Callable[[str], Any] | None = None,
) -> dict[str, list[TrialEventRow]]:
    """Read every event for the given trials (read-only transaction).

    Returns ``{trial_id: [events sorted by occurredAt]}``. ``trial_ids`` is
    normalised so SQL parameter substitution stays inside our control.
    """

    if not trial_ids:
        return {}
    factory = connect or _default_connect
    try:
        connection = factory(db_url)
    except DatabaseUnavailableError:
        raise
    except Exception as exc:  # pragma: no cover - actual PG failures
        raise DatabaseUnavailableError(f"cannot connect to PostgreSQL: {exc}") from exc

    try:
        with connection.cursor() as cursor:
            cursor.execute("SELECT to_regclass(%s)", (PG_TRIAL_EVENTS_TABLE,))
            row = cursor.fetchone()
            present = row and row[0] is not None
            if not present:
                raise DatabaseUnavailableError(
                    f"table {PG_TRIAL_EVENTS_TABLE} is missing; cannot embed --include-events"
                )
            cursor.execute("SET TRANSACTION READ ONLY")
            cursor.execute(
                """
                SELECT id, trial_id, from_status, to_status, version, message,
                       error_category, error_message, occurred_at
                  FROM rd_evaluation_trial_events
                 WHERE campaign_id = %s
                   AND trial_id = ANY(%s)
                 ORDER BY trial_id, occurred_at, id
                """,
                (campaign_id, list(trial_ids)),
            )
            events: dict[str, list[TrialEventRow]] = {tid: [] for tid in trial_ids}
            for db_row in cursor.fetchall():
                column_names = [desc.name for desc in cursor.description or []]
                snake_row = dict(zip(column_names, db_row))
                mapped = {
                    "eventId": _coerce_int(snake_row.get("id")),
                    "trialId": snake_row.get("trial_id", ""),
                    "fromStatus": snake_row.get("from_status", ""),
                    "toStatus": snake_row.get("to_status", ""),
                    "version": _coerce_int(snake_row.get("version")),
                    "message": snake_row.get("message", ""),
                    "errorCategory": snake_row.get("error_category", ""),
                    "errorMessage": snake_row.get("error_message", ""),
                    "occurredAtEpochMillis": snake_row.get("occurred_at"),
                }
                events.setdefault(mapped["trialId"], []).append(TrialEventRow(raw=mapped))
            return events
    except DatabaseUnavailableError:
        raise
    except Exception as exc:  # pragma: no cover - actual PG failures
        raise DatabaseUnavailableError(
            f"query against {PG_TRIAL_EVENTS_TABLE} failed: {exc}"
        ) from exc
    finally:
        try:
            connection.close()
        except Exception:  # pragma: no cover - best-effort close
            pass


def _default_connect(db_url: str) -> Any:
    """Lazy psycopg import; keeps ``import`` cheap when psycopg is absent."""

    try:
        import psycopg  # type: ignore[import-not-found]
    except ImportError as exc:  # pragma: no cover - import guard
        raise DatabaseUnavailableError(
            "psycopg is required to export from PostgreSQL; install psycopg[binary]"
        ) from exc
    return psycopg.connect(db_url)


def redact_and_write(
    trials: Sequence[TrialRow],
    output_path: Path,
    *,
    events_by_trial: Mapping[str, Sequence[TrialEventRow]] | None = None,
    include_events: bool = False,
    strict: bool = False,
) -> int:
    """Write the trials as a redacted, sorted, atomic JSONL file.

    Returns the number of trials written. ``output_path`` is opened with
    ``O_TRUNC`` via a sibling ``.tmp`` file, ``fsync``-ed, then renamed
    atomically so a half-written ``trials.jsonl`` cannot leak downstream.
    """

    output_path = Path(output_path)
    parent = output_path.parent
    if parent and not parent.exists():
        parent.mkdir(parents=True, exist_ok=True)
    sorted_trials = sort_trials_for_export(trials)
    payloads: list[dict[str, Any]] = []
    for index, trial in enumerate(sorted_trials):
        payload = trial.to_payload()
        if include_events:
            events = events_by_trial.get(trial.raw.get("trialId", ""), []) if events_by_trial else []
            payload["events"] = [event.to_payload() for event in events]
        else:
            payload.pop("events", None)
        validate_required_fields(payload, strict=strict, trial_index=index)
        payloads.append(payload)

    redacted_payloads = [lib.redacted_copy(payload) for payload in payloads]
    tmp_path = output_path.with_name(output_path.name + ".tmp")
    try:
        with open(tmp_path, "w", encoding="utf-8", newline="\n") as handle:
            for payload in redacted_payloads:
                line = json.dumps(payload, ensure_ascii=False, sort_keys=True)
                handle.write(line + "\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(tmp_path, output_path)
    except OSError as exc:
        try:
            if tmp_path.exists():
                tmp_path.unlink()
        except OSError:
            pass
        raise OutputWriteError(f"cannot write {output_path}: {exc}") from exc

    return len(sorted_trials)


def export_trials(
    config: TrialExportConfig,
    *,
    connect: Callable[[str], Any] | None = None,
    stderr_write: Callable[[str], None] | None = None,
) -> int:
    """End-to-end orchestrator. Returns the number of exported trials."""

    stderr = stderr_write or (lambda line: print(line, file=sys.stderr))
    trials = load_trials(
        config.db_url,
        config.campaign_id,
        connect=connect,
    )
    if not config.strict and trials:
        for index, trial in enumerate(trials):
            payload = trial.to_payload()
            missing = validate_required_fields(payload, strict=False, trial_index=index)
            if missing:
                stderr(
                    f"warning: trial #{index} missing fields {missing}; export continues"
                )

    filtered = filter_by_replicate(trials, config.replicate_no)
    if not filtered:
        stderr(
            f"warning: no trials match campaign_id={config.campaign_id} "
            f"replicate_no={config.replicate_no}"
        )

    events_by_trial: dict[str, list[TrialEventRow]] = {}
    if config.include_events and filtered:
        trial_ids = [trial.raw.get("trialId", "") for trial in filtered]
        events_by_trial = load_trial_events(
            config.db_url,
            config.campaign_id,
            [tid for tid in trial_ids if tid],
            connect=connect,
        )

    exported = redact_and_write(
        filtered,
        config.output_path,
        events_by_trial=events_by_trial,
        include_events=config.include_events,
        strict=config.strict,
    )
    stderr(f"exported {exported} trials to {config.output_path}")
    return exported


def _parse_args(argv: Sequence[str] | None = None) -> TrialExportConfig:
    parser = _build_arg_parser()
    args = parser.parse_args(argv)
    if not args.db_url:
        raise DatabaseUnavailableError(
            "--db-url is required (or set RD_EVAL_PG_URL); refusing to run with no DB target"
        )
    return TrialExportConfig(
        campaign_id=args.campaign_id,
        db_url=args.db_url,
        output_path=args.output,
        replicate_no=args.replicate_no,
        strict=args.strict,
        include_events=args.include_events,
    )


def main(argv: Sequence[str] | None = None) -> int:
    try:
        config = _parse_args(argv)
    except DatabaseUnavailableError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    except SystemExit as exc:
        return int(exc.code or 0)
    try:
        count = export_trials(config)
    except DatabaseUnavailableError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 3
    except StrictTrialFieldError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 4
    except OutputWriteError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 5
    print(count)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())