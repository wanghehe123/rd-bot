#!/usr/bin/env python3
"""Pure Python helpers for RD-Bot evaluation runs and reports."""

from __future__ import annotations

import csv
from dataclasses import dataclass, field
import json
import os
import re
import statistics
import time
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_OUTPUT_ROOT = REPO_ROOT / "qa-runs/evaluation"
JUDGE_METRIC_NAMES = [
    "faithfulness",
    "answer_relevancy",
    "answer_correctness",
    "context_precision",
    "context_recall",
]

THRESHOLDS = {
    "intent_top1": (">=", 0.92),
    "evidence_hit@5": (">=", 0.90),
    "evidence_recall@5": (">=", 0.80),
    "mrr@10": (">=", 0.70),
    "faithfulness": (">=", 0.90),
    "answer_relevancy": (">=", 0.85),
    "answer_correctness": (">=", 0.80),
    "context_precision": (">=", 0.75),
    "context_recall": (">=", 0.80),
    "requirement_decision_accuracy": (">=", 0.95),
    "downstream_leak_rate": ("<=", 0.0),
    "risk_coverage": (">=", 0.80),
    "solution_schema_valid_rate": (">=", 0.98),
    "acceptance_mapping_coverage": (">=", 0.90),
    "affected_file_precision": (">=", 0.70),
    "affected_file_recall": (">=", 0.70),
    "coding_result_schema_valid_rate": (">=", 0.98),
    "changed_file_precision": (">=", 0.80),
    "forbidden_file_touch_rate": ("<=", 0.0),
    "test_command_pass_rate": (">=", 0.90),
    "pr_policy_violation_rate": ("<=", 0.0),
    "secret_leak_rate": ("<=", 0.0),
    "qa_schema_valid_rate": (">=", 0.98),
    "real_command_execution_rate": (">=", 0.95),
    "acceptance_status_match_rate": (">=", 1.0),
    "max_skipped_acceptance_ok": (">=", 1.0),
    "skipped_acceptance_rate": ("<=", 0.05),
    "alert_expected_rate": (">=", 1.0),
    "forbidden_alert_rate": ("<=", 0.0),
    "required_experience_complete_rate": (">=", 0.95),
    "experience_redacted_rate": (">=", 1.0),
}

DOWNSTREAM_ROLES = ["SOLUTION_ARCHITECT", "CODING_AGENT", "QA_AGENT"]
ALL_ROLES = ["REQUIREMENT_REVIEWER", *DOWNSTREAM_ROLES]
REQUIRED_EXPERIENCE_TYPES = [
    "REQUIREMENT_REVIEW",
    "TECHNICAL_DESIGN",
    "CODE_CHANGE",
    "QA_REPORT",
    "DELIVERY_REPORT",
]
SECRET_PATTERNS = [
    re.compile(r"sk-[A-Za-z0-9_\-]{12,}"),
    re.compile(r"(?i)\bBearer\s+[A-Za-z0-9._~+/=-]+"),
    re.compile(r"\b[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{3,}\b"),
    re.compile(r"(?i)\b(api[_-]?key|secret|password|token|access_token)\s*[:=]\s*['\"]?[A-Za-z0-9._~+/=-]{8,}"),
    re.compile(r"(?i)([?&](?:access_token|token|api_key|secret|password)=)[^&\s]+"),
]
SAFE_RUN_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")


@dataclass
class RdEvalSample:
    """Typed boundary for one RD-Bot evaluation dataset row."""

    sample_id: str
    suite: str
    scenario: str = ""
    difficulty: str = "medium"
    tags: list[str] = field(default_factory=list)
    input: dict[str, Any] = field(default_factory=dict)
    rag_gold: dict[str, Any] = field(default_factory=dict)
    stage_gold: dict[str, Any] = field(default_factory=dict)
    alert_gold: dict[str, Any] = field(default_factory=dict)
    delivery_gold: dict[str, Any] = field(default_factory=dict)
    fixture_record: dict[str, Any] = field(default_factory=dict)

    @classmethod
    def from_dict(cls, row: dict[str, Any]) -> "RdEvalSample":
        sample_id = as_text(row.get("sample_id"))
        if not sample_id:
            raise ValueError("RdEvalSample is missing sample_id")
        return cls(
            sample_id=sample_id,
            suite=as_text(row.get("suite") or "unknown"),
            scenario=as_text(row.get("scenario")),
            difficulty=as_text(row.get("difficulty") or "medium"),
            tags=text_list(row.get("tags")),
            input=dict_value(row.get("input")),
            rag_gold=dict_value(row.get("rag_gold")),
            stage_gold=dict_value(row.get("stage_gold")),
            alert_gold=dict_value(row.get("alert_gold")),
            delivery_gold=dict_value(row.get("delivery_gold")),
            fixture_record=dict_value(row.get("fixture_record")),
        )


@dataclass
class RdEvalRecord:
    """Typed boundary for one recorded RD-Bot evaluation output row."""

    run_id: str
    sample_id: str
    status: str
    suite: str = ""
    scenario: str = ""
    environment_id: str = ""
    task_id: str = ""
    rag: dict[str, Any] = field(default_factory=dict)
    stages: dict[str, Any] = field(default_factory=dict)
    alerts: list[dict[str, Any]] = field(default_factory=list)
    response: str = ""
    reference: str = ""
    retrieved_contexts: list[str] = field(default_factory=list)
    final_status: str = "unknown"
    latency_ms: int = 0
    first_token_ms: int | None = None
    trace_id: str = ""

    @classmethod
    def from_dict(cls, row: dict[str, Any]) -> "RdEvalRecord":
        run_id = as_text(row.get("run_id"))
        sample_id = as_text(row.get("sample_id"))
        if not run_id:
            raise ValueError("RdEvalRecord is missing run_id")
        if not sample_id:
            raise ValueError("RdEvalRecord is missing sample_id")
        return cls(
            run_id=run_id,
            sample_id=sample_id,
            status=as_text(row.get("status") or "UNKNOWN"),
            suite=as_text(row.get("suite")),
            scenario=as_text(row.get("scenario")),
            environment_id=as_text(row.get("environment_id")),
            task_id=as_text(row.get("task_id") or row.get("taskId")),
            rag=dict_value(row.get("rag")),
            stages=dict_value(row.get("stages")),
            alerts=[dict_value(item) for item in list_value(row.get("alerts"))],
            response=as_text(row.get("response")),
            reference=as_text(row.get("reference")),
            retrieved_contexts=text_list(row.get("retrieved_contexts")),
            final_status=as_text(row.get("final_status") or "unknown"),
            latency_ms=int(optional_float(row.get("latency_ms")) or 0),
            first_token_ms=(
                None if optional_float(row.get("first_token_ms")) is None
                else int(optional_float(row.get("first_token_ms")) or 0)
            ),
            trace_id=as_text(row.get("trace_id") or row.get("traceId")),
        )


@dataclass
class RdMetricResult:
    """Typed boundary for one aggregate metric emitted by scoring."""

    name: str
    value: float | None
    sample_count: int
    status: str
    threshold: float | None = None
    direction: str = ""
    reason: str = ""

    @classmethod
    def from_dict(cls, row: dict[str, Any]) -> "RdMetricResult":
        name = as_text(row.get("name"))
        if not name:
            raise ValueError("RdMetricResult is missing name")
        return cls(
            name=name,
            value=optional_float(row.get("value")),
            sample_count=int(optional_float(row.get("sampleCount")) or 0),
            status=as_text(row.get("status") or "UNKNOWN"),
            threshold=optional_float(row.get("threshold")),
            direction=as_text(row.get("direction")),
            reason=as_text(row.get("reason")),
        )


def load_jsonl(path: Path | str) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    actual = Path(path)
    with actual.open("r", encoding="utf-8") as handle:
        for line_no, line in enumerate(handle, start=1):
            stripped = line.strip()
            if not stripped:
                continue
            try:
                value = json.loads(stripped)
            except json.JSONDecodeError as exc:
                raise ValueError(f"{actual}:{line_no} is not valid JSON: {exc}") from exc
            if not isinstance(value, dict):
                raise ValueError(f"{actual}:{line_no} must be a JSON object")
            rows.append(value)
    return rows


def write_jsonl(path: Path | str, rows: list[dict[str, Any]], *, overwrite: bool = False) -> None:
    actual = Path(path)
    actual.parent.mkdir(parents=True, exist_ok=True)
    mode = "w" if overwrite else "x"
    with actual.open(mode, encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(redacted_copy(row), ensure_ascii=False, sort_keys=True) + "\n")


def load_dataset(path: Path | str) -> list[dict[str, Any]]:
    rows = load_jsonl(path)
    seen: set[str] = set()
    for index, row in enumerate(rows, start=1):
        sample = RdEvalSample.from_dict(row)
        sample_id = sample.sample_id
        if not sample_id:
            raise ValueError(f"dataset row {index} is missing sample_id")
        if sample_id in seen:
            raise ValueError(f"dataset has duplicate sample_id: {sample_id}")
        seen.add(sample_id)
        row.setdefault("suite", "unknown")
        row.setdefault("tags", [])
        row.setdefault("input", {})
    return rows


def records_from_fixtures(
    samples: list[dict[str, Any]],
    run_id: str,
    environment_id: str,
    dataset_path: str,
) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    for sample in samples:
        sample_id = as_text(sample.get("sample_id"))
        fixture = deep_copy(sample.get("fixture_record") or {})
        if not fixture:
            fixture = {
                "status": "RECORDING_FAILED",
                "missingSources": ["fixture_record"],
                "error": "sample does not contain fixture_record",
            }
        fixture["run_id"] = run_id
        fixture["sample_id"] = sample_id
        fixture["suite"] = as_text(sample.get("suite"))
        fixture["scenario"] = as_text(sample.get("scenario"))
        fixture["environment_id"] = environment_id
        fixture["dataset_path"] = dataset_path
        fixture.setdefault("status", "UNKNOWN")
        fixture.setdefault("stages", {})
        fixture.setdefault("alerts", [])
        records.append(fixture)
    return records


def records_from_rag_http(
    samples: list[dict[str, Any]],
    run_id: str,
    environment_id: str,
    dataset_path: str,
    base_url: str,
    rag_log_path: Path | None,
    timeout_seconds: float,
) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    for sample in samples:
        sample_id = as_text(sample.get("sample_id"))
        question = question_from_sample(sample)
        try:
            started = time.monotonic()
            sse_body = http_get_text(
                f"{base_url.rstrip('/')}/rag/v3/chat?"
                + urllib.parse.urlencode(
                    {
                        "question": question,
                        "deepThinking": str(bool(sample.get("input", {}).get("deepThinking", False))).lower(),
                    }
                ),
                timeout_seconds,
            )
            latency_ms = int((time.monotonic() - started) * 1000)
            events = parse_sse(sse_body)
            meta = first_event_json(events, "meta")
            task_id = as_text(meta.get("taskId"))
            rag_log = find_rag_log_event(rag_log_path, task_id) if rag_log_path else {}
            record = record_from_rag_http_response(
                sample=sample,
                run_id=run_id,
                environment_id=environment_id,
                dataset_path=dataset_path,
                task_id=task_id,
                meta=meta,
                rag_log=rag_log,
                events=events,
                latency_ms=latency_ms,
                first_token_ms=latency_ms if any(event["event"] in {"delta", "reject"} for event in events) else None,
            )
        except Exception as exc:
            record = {
                "run_id": run_id,
                "sample_id": sample_id,
                "suite": as_text(sample.get("suite")),
                "scenario": as_text(sample.get("scenario")),
                "environment_id": environment_id,
                "dataset_path": dataset_path,
                "status": "RECORDING_FAILED",
                "error": redact(str(exc)),
                "missingSources": ["rag-http"],
                "stages": {},
                "alerts": [],
            }
        records.append(record)
    return records


def record_from_rag_http_response(
    sample: dict[str, Any],
    run_id: str,
    environment_id: str,
    dataset_path: str,
    task_id: str,
    meta: dict[str, Any],
    rag_log: dict[str, Any],
    events: list[dict[str, str]],
    latency_ms: int | None = None,
    first_token_ms: int | None = None,
) -> dict[str, Any]:
    chunks = list_value(rag_log.get("retrievedChunks"))
    evidence = evidence_uris_from_chunks(chunks)
    if not evidence:
        evidence = evidence_uris_from_record({"rag": {"retrievedChunks": chunks}})
    response = "".join(
        event.get("data", "")
        for event in events
        if event.get("event") in {"delta", "reject"}
    )
    done_payload = first_event_json(events, "done")
    status_text = as_text(done_payload.get("status")).upper()
    final_status = "success" if status_text in {"DONE", "SUCCESS", "SUCCEEDED"} else "unknown"
    trace_id = as_text(rag_log.get("traceId") or meta.get("traceId"))
    retrieved_contexts = [
        as_text(dict_value(chunk).get("content"))
        for chunk in chunks
        if as_text(dict_value(chunk).get("content"))
    ]
    sample_input = dict_value(sample.get("input"))
    return {
        "run_id": run_id,
        "sample_id": as_text(sample.get("sample_id")),
        "suite": as_text(sample.get("suite")),
        "scenario": as_text(sample.get("scenario")),
        "environment_id": environment_id,
        "dataset_path": dataset_path,
        "task_id": task_id,
        "status": "COMPLETED" if any(event["event"] == "done" for event in events) else "UNKNOWN",
        "user_input": question_from_sample(sample),
        "reference": as_text(sample_input.get("groundTruth") or sample_input.get("reference") or sample.get("ground_truth")),
        "response": response,
        "thinking": "",
        "latency_ms": int(latency_ms or 0),
        "first_token_ms": first_token_ms,
        "final_status": final_status,
        "trace_id": trace_id,
        "retrieved_contexts": retrieved_contexts,
        "retrieved_doc_ids": evidence,
        "rag": {
            "primaryIntentSystemId": as_text(
                rag_log.get("primaryIntentSystemId") or meta.get("intentSystemId")
            ),
            "primaryIntentName": as_text(rag_log.get("primaryIntentName") or meta.get("intentName")),
            "searchChannels": list_value(rag_log.get("searchChannels")),
            "retrievedEvidenceUris": evidence,
            "retrievedChunks": chunks,
            "contextSummary": as_text(rag_log.get("contextSummary")),
        },
        "stages": {},
        "alerts": [],
    }


def http_get_text(url: str, timeout_seconds: float) -> str:
    with urllib.request.urlopen(url, timeout=timeout_seconds) as response:
        return response.read().decode("utf-8")


def parse_sse(body: str) -> list[dict[str, str]]:
    events: list[dict[str, str]] = []
    current_event = "message"
    data_lines: list[str] = []
    for raw_line in body.splitlines():
        line = raw_line.rstrip("\r")
        if not line:
            if data_lines:
                events.append({"event": current_event, "data": "\n".join(data_lines)})
            current_event = "message"
            data_lines = []
            continue
        if line.startswith("event:"):
            current_event = line.removeprefix("event:").strip()
        elif line.startswith("data:"):
            data_lines.append(line.removeprefix("data:").strip())
    if data_lines:
        events.append({"event": current_event, "data": "\n".join(data_lines)})
    return events


def first_event_json(events: list[dict[str, str]], event_name: str) -> dict[str, Any]:
    for event in events:
        if event.get("event") == event_name:
            try:
                value = json.loads(event.get("data", "{}"))
                return value if isinstance(value, dict) else {}
            except json.JSONDecodeError:
                return {}
    return {}


def find_rag_log_event(path: Path | None, task_id: str) -> dict[str, Any]:
    if not path or not task_id or not path.exists():
        return {}
    matches = [
        row for row in load_jsonl(path)
        if as_text(row.get("taskId")) == task_id
    ]
    return matches[-1] if matches else {}


def score_records(
    samples: list[dict[str, Any]],
    records: list[dict[str, Any]],
    judge_provider: "JudgeProvider | None" = None,
    judge_limit: int | None = None,
) -> dict[str, Any]:
    provider = judge_provider or NoopJudgeProvider()
    record_by_sample = {as_text(record.get("sample_id")): record for record in records}
    run_id = as_text(records[0].get("run_id")) if records else "unknown-run"
    metric_values: dict[str, list[float]] = {}
    metric_status_notes: dict[str, list[str]] = {}
    failures: list[dict[str, Any]] = []
    per_sample: list[dict[str, Any]] = []
    judged_count = 0

    def add_metric(
        sample: dict[str, Any],
        record: dict[str, Any],
        sample_metrics: dict[str, Any],
        name: str,
        value: float | None,
        reason: str,
    ) -> None:
        if value is None:
            return
        numeric = round(float(value), 6)
        sample_metrics[name] = numeric
        metric_values.setdefault(name, []).append(numeric)
        direction, threshold = THRESHOLDS.get(name, (None, None))
        if direction and threshold is not None and not threshold_passes(numeric, direction, threshold):
            failures.append(
                failure_row(
                    sample=sample,
                    record=record,
                    metric=name,
                    score=numeric,
                    threshold=threshold,
                    direction=direction,
                    reason=reason,
                )
            )

    for sample in samples:
        sample_id = as_text(sample.get("sample_id"))
        record = record_by_sample.get(sample_id, {"sample_id": sample_id, "status": "MISSING_RECORD"})
        sample_metrics: dict[str, Any] = {}
        judge_error = ""

        score_rag(sample, record, sample_metrics, add_metric)
        score_requirement(sample, record, sample_metrics, add_metric)
        score_solution(sample, record, sample_metrics, add_metric)
        score_coding(sample, record, sample_metrics, add_metric)
        score_qa(sample, record, sample_metrics, add_metric)
        score_alerts(sample, record, sample_metrics, add_metric)
        score_delivery(sample, record, sample_metrics, add_metric)
        score_secret_leak(sample, record, sample_metrics, add_metric)

        should_judge = provider.enabled and (judge_limit is None or judged_count < judge_limit)
        if should_judge:
            judged_count += 1
            try:
                judge_result = provider.evaluate(sample, record)
            except Exception as exc:
                judge_error = as_text(redact(str(exc)))
                for name in JUDGE_METRIC_NAMES:
                    metric_status_notes.setdefault(name, []).append("judge failed")
            else:
                if not any(name in judge_result for name in JUDGE_METRIC_NAMES):
                    for name in JUDGE_METRIC_NAMES:
                        metric_status_notes.setdefault(name, []).append("judge skipped")
                for name in JUDGE_METRIC_NAMES:
                    add_metric(sample, record, sample_metrics, name, optional_float(judge_result.get(name)), "judge score")
        else:
            for name in JUDGE_METRIC_NAMES:
                metric_status_notes.setdefault(name, []).append("judge provider skipped")

        per_sample_row = {
            "sample_id": sample_id,
            "suite": as_text(sample.get("suite")),
            "scenario": as_text(sample.get("scenario")),
            "difficulty": as_text(sample.get("difficulty")),
            "taskId": as_text(record.get("task_id") or record.get("taskId")),
            "status": as_text(record.get("status")),
            "metrics": sample_metrics,
        }
        if judge_error:
            per_sample_row["judgeError"] = judge_error
        per_sample.append(per_sample_row)

    metrics = aggregate_metrics(metric_values, metric_status_notes)
    return {
        "run_id": run_id,
        "generated_at": utc_now(),
        "sample_count": len(samples),
        "record_count": len(records),
        "judge_provider": provider.name,
        "metrics": metrics,
        "failures": failures,
        "per_sample": per_sample,
    }


def scored_samples_for_records(
    samples: list[dict[str, Any]],
    records: list[dict[str, Any]],
    *,
    include_missing_records: bool = False,
) -> list[dict[str, Any]]:
    """Select dataset rows that should be scored for a recorded run."""
    if include_missing_records:
        return samples
    record_sample_ids = {
        as_text(record.get("sample_id"))
        for record in records
        if as_text(record.get("sample_id"))
    }
    if not record_sample_ids:
        return samples
    return [
        sample for sample in samples
        if as_text(sample.get("sample_id")) in record_sample_ids
    ]


def score_rag(sample: dict[str, Any], record: dict[str, Any], sample_metrics: dict[str, Any], add_metric) -> None:
    gold = dict_value(sample.get("rag_gold"))
    if not gold:
        return
    rag = dict_value(record.get("rag"))
    expected_intent = as_text(gold.get("expectedIntentSystemId"))
    if expected_intent:
        add_metric(
            sample,
            record,
            sample_metrics,
            "intent_top1",
            1.0 if as_text(rag.get("primaryIntentSystemId")) == expected_intent else 0.0,
            "primary intent mismatch",
        )
    must = text_list(gold.get("mustEvidenceUris"))
    nice = text_list(gold.get("niceEvidenceUris"))
    retrieved = evidence_uris_from_record(record)
    if must:
        for k in [1, 3, 5, 10]:
            topk = retrieved[:k]
            hits = [uri for uri in must if uri in topk]
            add_metric(sample, record, sample_metrics, f"hit@{k}", 1.0 if hits else 0.0, "missing must evidence")
            add_metric(sample, record, sample_metrics, f"recall@{k}", len(hits) / len(must), "missing must evidence")
        top5 = retrieved[:5]
        top10 = retrieved[:10]
        must_hits = [uri for uri in must if uri in top5]
        add_metric(sample, record, sample_metrics, "evidence_hit@5", 1.0 if must_hits else 0.0, "missing must evidence")
        add_metric(sample, record, sample_metrics, "evidence_recall@5", len(must_hits) / len(must), "missing must evidence")
        first_rank = first_hit_rank(top10, set(must))
        add_metric(sample, record, sample_metrics, "mrr@10", 1.0 / first_rank if first_rank else 0.0, "must evidence ranked too low")
    inclusive = must + nice
    if inclusive:
        for k in [5, 10]:
            topk = retrieved[:k]
            hits = [uri for uri in inclusive if uri in topk]
            add_metric(sample, record, sample_metrics, f"recall_inclusive@{k}", len(hits) / len(inclusive), "missing inclusive evidence")
        sample_metrics["evidence_recall_inclusive@5"] = sample_metrics.get("recall_inclusive@5")
    required_terms = text_list(gold.get("requiredTerms"))
    if required_terms:
        blob = text_blob(record)
        sample_metrics["required_terms_coverage"] = round(
            sum(1 for term in required_terms if term in blob) / len(required_terms),
            6,
        )
    if "first_token_ms" in record:
        add_metric(sample, record, sample_metrics, "ttft_mean_ms", optional_float(record.get("first_token_ms")), "first token latency")
    if "latency_ms" in record:
        add_metric(sample, record, sample_metrics, "total_mean_ms", optional_float(record.get("latency_ms")), "total latency")
    if bool(record.get("requires_rag")) or bool(gold.get("requiresRag")):
        add_metric(
            sample,
            record,
            sample_metrics,
            "refusal_when_required",
            1.0 if not as_text(record.get("response")).strip() and not retrieved else 0.0,
            "required RAG response was refused or empty",
        )
    if not bool(record.get("requires_rag")) and gold.get("requiresRag") is False:
        add_metric(
            sample,
            record,
            sample_metrics,
            "over_retrieval_rate",
            1.0 if retrieved else 0.0,
            "retrieved evidence when RAG was not required",
        )


def score_requirement(sample: dict[str, Any], record: dict[str, Any], sample_metrics: dict[str, Any], add_metric) -> None:
    reviewer_gold = stage_gold(sample, "REQUIREMENT_REVIEWER")
    if not reviewer_gold:
        return
    reviewer = stage(record, "REQUIREMENT_REVIEWER")
    expected = as_text(reviewer_gold.get("expectedDecision"))
    if expected:
        add_metric(
            sample,
            record,
            sample_metrics,
            "requirement_decision_accuracy",
            1.0 if stage_decision(reviewer) == expected else 0.0,
            "requirement decision mismatch",
        )
    risks = text_list(reviewer_gold.get("mustMentionRisks"))
    if risks:
        blob = json_blob(reviewer)
        add_metric(
            sample,
            record,
            sample_metrics,
            "risk_coverage",
            sum(1 for risk in risks if risk in blob) / len(risks),
            "requirement risks not covered",
        )
    if bool(reviewer_gold.get("mustBlockDownstream")):
        leaked = any(stage(record, role) for role in DOWNSTREAM_ROLES)
        add_metric(
            sample,
            record,
            sample_metrics,
            "downstream_leak_rate",
            1.0 if leaked else 0.0,
            "blocked requirement reached downstream roles",
        )


def score_solution(sample: dict[str, Any], record: dict[str, Any], sample_metrics: dict[str, Any], add_metric) -> None:
    gold = stage_gold(sample, "SOLUTION_ARCHITECT")
    if not gold:
        return
    result = stage_result(stage(record, "SOLUTION_ARCHITECT"))
    required_sections = text_list(gold.get("mustSections"))
    if required_sections:
        present = sum(1 for section in required_sections if has_non_empty(result.get(section)))
        add_metric(
            sample,
            record,
            sample_metrics,
            "solution_schema_valid_rate",
            present / len(required_sections),
            "solution result missing required sections",
        )
    must_acceptance = text_list(gold.get("mustAcceptanceIds"))
    if must_acceptance:
        blob = json_blob(result.get("acceptanceMapping"))
        add_metric(
            sample,
            record,
            sample_metrics,
            "acceptance_mapping_coverage",
            sum(1 for acceptance_id in must_acceptance if acceptance_id in blob) / len(must_acceptance),
            "solution acceptance mapping incomplete",
        )
    expected_files = text_list(gold.get("expectedAffectedFiles"))
    if expected_files:
        actual = text_list(result.get("affectedFiles"))
        add_precision_recall(
            sample,
            record,
            sample_metrics,
            add_metric,
            "affected_file_precision",
            "affected_file_recall",
            expected_files,
            actual,
            "affected files mismatch",
        )


def score_coding(sample: dict[str, Any], record: dict[str, Any], sample_metrics: dict[str, Any], add_metric) -> None:
    gold = stage_gold(sample, "CODING_AGENT")
    if not gold:
        return
    result = stage_result(stage(record, "CODING_AGENT"))
    changed_files = text_list(result.get("changedFiles") or result.get("changed_files"))
    add_metric(
        sample,
        record,
        sample_metrics,
        "coding_result_schema_valid_rate",
        1.0 if isinstance(result, dict) and result else 0.0,
        "coding result is not structured",
    )
    expected = text_list(gold.get("expectedChangedFiles"))
    if expected:
        add_precision_recall(
            sample,
            record,
            sample_metrics,
            add_metric,
            "changed_file_precision",
            "changed_file_recall",
            expected,
            changed_files,
            "changed files outside expected set",
        )
    forbidden = set(text_list(gold.get("forbiddenChangedFiles")))
    if forbidden:
        touched = any(path in forbidden for path in changed_files)
        add_metric(
            sample,
            record,
            sample_metrics,
            "forbidden_file_touch_rate",
            1.0 if touched else 0.0,
            "forbidden file touched",
        )
    required_commands = text_list(gold.get("requiredTestCommands"))
    if required_commands:
        passed_commands = passed_test_commands(result)
        add_metric(
            sample,
            record,
            sample_metrics,
            "test_command_pass_rate",
            sum(1 for command in required_commands if command in passed_commands) / len(required_commands),
            "required test command did not pass",
        )
    if as_text(result.get("pullRequestUrl") or result.get("pull_request_url")):
        add_metric(sample, record, sample_metrics, "pr_policy_violation_rate", 1.0, "coding stage returned PR URL")


def score_qa(sample: dict[str, Any], record: dict[str, Any], sample_metrics: dict[str, Any], add_metric) -> None:
    gold = stage_gold(sample, "QA_AGENT")
    if not gold:
        return
    result = stage_result(stage(record, "QA_AGENT"))
    acceptance = list_value(result.get("acceptanceResults") or result.get("acceptance_results"))
    add_metric(
        sample,
        record,
        sample_metrics,
        "qa_schema_valid_rate",
        1.0 if acceptance else 0.0,
        "QA report has no acceptance results",
    )
    if acceptance:
        skipped = [item for item in acceptance if as_text(dict_value(item).get("status")).upper() == "SKIPPED"]
        add_metric(
            sample,
            record,
            sample_metrics,
            "skipped_acceptance_rate",
            len(skipped) / len(acceptance),
            "QA skipped acceptance items",
        )
        expected_statuses = {status.upper() for status in text_list(gold.get("expectedAcceptanceStatuses"))}
        if expected_statuses:
            matched = [
                item for item in acceptance
                if as_text(dict_value(item).get("status")).upper() in expected_statuses
            ]
            add_metric(
                sample,
                record,
                sample_metrics,
                "acceptance_status_match_rate",
                len(matched) / len(acceptance),
                "QA acceptance status did not match gold",
            )
        if "maxSkippedAcceptanceCount" in gold:
            max_skipped = int(gold.get("maxSkippedAcceptanceCount") or 0)
            add_metric(
                sample,
                record,
                sample_metrics,
                "max_skipped_acceptance_ok",
                1.0 if len(skipped) <= max_skipped else 0.0,
                "QA skipped more acceptance items than allowed",
            )
        real = [
            item for item in acceptance
            if as_text(dict_value(item).get("command")) and as_text(dict_value(item).get("logArtifactUri") or dict_value(item).get("log_artifact_uri"))
        ]
        if bool(gold.get("mustRunRealCommands")):
            add_metric(
                sample,
                record,
                sample_metrics,
                "real_command_execution_rate",
                len(real) / len(acceptance),
                "QA acceptance item lacks real command evidence",
            )


def score_alerts(sample: dict[str, Any], record: dict[str, Any], sample_metrics: dict[str, Any], add_metric) -> None:
    gold = dict_value(sample.get("alert_gold"))
    if not gold:
        return
    actual = {as_text(alert.get("type")) for alert in list_value(record.get("alerts")) if isinstance(alert, dict)}
    expected = set(text_list(gold.get("expectedAlertTypes")))
    forbidden = set(text_list(gold.get("forbiddenAlertTypes")))
    if expected:
        add_metric(
            sample,
            record,
            sample_metrics,
            "alert_expected_rate",
            len(actual.intersection(expected)) / len(expected),
            "expected alert missing",
        )
    if forbidden:
        add_metric(
            sample,
            record,
            sample_metrics,
            "forbidden_alert_rate",
            1.0 if actual.intersection(forbidden) else 0.0,
            "forbidden alert emitted",
        )


def score_delivery(sample: dict[str, Any], record: dict[str, Any], sample_metrics: dict[str, Any], add_metric) -> None:
    if "delivery_gold" not in sample and "delivery" not in record:
        return
    delivery = dict_value(record.get("delivery"))
    required = text_list(dict_value(sample.get("delivery_gold")).get("requiredExperienceTypes")) or REQUIRED_EXPERIENCE_TYPES
    actual = set(text_list(delivery.get("experienceTypes")))
    if actual or "delivery_gold" in sample:
        add_metric(
            sample,
            record,
            sample_metrics,
            "required_experience_complete_rate",
            len(actual.intersection(required)) / len(required),
            "required experience entries missing",
        )
    if "redacted" in delivery:
        add_metric(
            sample,
            record,
            sample_metrics,
            "experience_redacted_rate",
            1.0 if bool(delivery.get("redacted")) else 0.0,
            "experience is not redacted",
        )


def score_secret_leak(sample: dict[str, Any], record: dict[str, Any], sample_metrics: dict[str, Any], add_metric) -> None:
    blob = json_blob(record)
    leaked = any(pattern.search(blob) for pattern in SECRET_PATTERNS) or any(needle in blob for needle in secret_needles())
    add_metric(
        sample,
        record,
        sample_metrics,
        "secret_leak_rate",
        1.0 if leaked else 0.0,
        "secret-like value detected in eval record",
    )


def aggregate_metrics(metric_values: dict[str, list[float]], notes: dict[str, list[str]]) -> list[dict[str, Any]]:
    all_names = sorted(set(metric_values) | set(notes))
    metrics: list[dict[str, Any]] = []
    for name in all_names:
        values = metric_values.get(name, [])
        direction, threshold = THRESHOLDS.get(name, (None, None))
        if values:
            value = round(statistics.mean(values), 6)
            status = "PASS"
            if direction and threshold is not None and not threshold_passes(value, direction, threshold):
                status = "FAILED"
            metric = {
                "name": name,
                "value": value,
                "sampleCount": len(values),
                "status": status,
            }
        else:
            metric = {
                "name": name,
                "value": None,
                "sampleCount": 0,
                "status": "SKIPPED",
                "reason": "; ".join(sorted(set(notes.get(name, ["no samples"])))),
            }
        if direction and threshold is not None:
            metric["direction"] = direction
            metric["threshold"] = threshold
        metrics.append(metric)
    return metrics


def failure_row(
    sample: dict[str, Any],
    record: dict[str, Any],
    metric: str,
    score: float,
    threshold: float,
    direction: str,
    reason: str,
) -> dict[str, Any]:
    return {
        "sampleId": as_text(sample.get("sample_id")),
        "suite": as_text(sample.get("suite")),
        "scenario": as_text(sample.get("scenario")),
        "taskId": as_text(record.get("task_id") or record.get("taskId")),
        "role": role_for_metric(metric),
        "metric": metric,
        "score": score,
        "threshold": threshold,
        "direction": direction,
        "reason": reason,
        "artifactUri": first_artifact_uri(record),
        "evidenceUris": evidence_uris_from_record(record),
        "nextAction": next_action(metric),
    }


def render_markdown_report(score: dict[str, Any]) -> str:
    lines = [
        f"# RD Eval Report: {as_text(score.get('run_id'))}",
        "",
        "## Metadata",
        "",
        f"- generatedAt: `{as_text(score.get('generated_at'))}`",
        f"- sampleCount: `{score.get('sample_count', 0)}`",
        f"- recordCount: `{score.get('record_count', 0)}`",
        f"- judgeProvider: `{as_text(score.get('judge_provider'))}`",
        "",
        "## Metrics",
        "",
        "| metric | value | threshold | status | samples |",
        "| --- | ---: | --- | --- | ---: |",
    ]
    for metric in score.get("metrics", []):
        threshold = ""
        if "threshold" in metric:
            threshold = f"{metric.get('direction')} {metric.get('threshold')}"
        value = metric.get("value")
        lines.append(
            f"| `{metric.get('name')}` | {'' if value is None else value} | {threshold} | {metric.get('status')} | {metric.get('sampleCount', 0)} |"
        )
    lines.extend(["", "## Failures", ""])
    failures = list_value(score.get("failures"))
    if not failures:
        lines.append("No failed metrics.")
    else:
        lines.append("| sampleId | metric | score | threshold | reason | nextAction |")
        lines.append("| --- | --- | ---: | --- | --- | --- |")
        for failure in failures:
            lines.append(
                "| `{}` | `{}` | {} | {} {} | {} | {} |".format(
                    as_text(failure.get("sampleId")),
                    as_text(failure.get("metric")),
                    failure.get("score"),
                    as_text(failure.get("direction")),
                    failure.get("threshold"),
                    escape_table(as_text(failure.get("reason"))),
                    escape_table(as_text(failure.get("nextAction"))),
                )
            )
    lines.extend(["", "## Per Sample", ""])
    per_sample = list_value(score.get("per_sample"))
    if not per_sample:
        lines.append("No per-sample rows.")
    else:
        lines.append("| sampleId | suite | scenario | status | failedMetrics |")
        lines.append("| --- | --- | --- | --- | --- |")
        failed_by_sample: dict[str, list[str]] = {}
        for failure in failures:
            failed_by_sample.setdefault(as_text(failure.get("sampleId")), []).append(as_text(failure.get("metric")))
        for row in per_sample:
            sample_id = as_text(row.get("sample_id"))
            lines.append(
                f"| `{sample_id}` | {as_text(row.get('suite'))} | {escape_table(as_text(row.get('scenario')))} | {as_text(row.get('status'))} | {', '.join(failed_by_sample.get(sample_id, []))} |"
            )
    lines.append("")
    return "\n".join(lines)


def write_report_files(report_dir: Path | str, score: dict[str, Any]) -> None:
    actual = Path(report_dir)
    actual.mkdir(parents=True, exist_ok=True)
    safe_score = redacted_copy(score)
    (actual / "report.md").write_text(render_markdown_report(safe_score), encoding="utf-8")
    write_jsonl(actual / "failures.jsonl", list_value(safe_score.get("failures")), overwrite=True)
    write_per_sample_csv(actual / "per_sample.csv", list_value(safe_score.get("per_sample")))


def write_per_sample_csv(path: Path | str, rows: list[dict[str, Any]]) -> None:
    actual = Path(path)
    actual.parent.mkdir(parents=True, exist_ok=True)
    metric_names = sorted(
        {
            metric_name
            for row in rows
            for metric_name in dict_value(row.get("metrics")).keys()
        }
    )
    fieldnames = ["sample_id", "suite", "scenario", "difficulty", "taskId", "status"]
    for metric_name in metric_names:
        fieldnames.append(metric_name)
        if metric_name in JUDGE_METRIC_NAMES:
            fieldnames.append(f"{metric_name}_manual")
    with actual.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        for row in rows:
            flat = {key: row.get(key, "") for key in fieldnames}
            for metric_name in metric_names:
                flat[metric_name] = dict_value(row.get("metrics")).get(metric_name, "")
                if metric_name in JUDGE_METRIC_NAMES:
                    flat.setdefault(f"{metric_name}_manual", "")
            writer.writerow(flat)


def apply_manual_overrides_to_score(score: dict[str, Any], per_sample_path: Path | str) -> dict[str, Any]:
    path = Path(per_sample_path)
    if not path.exists():
        return score
    with path.open("r", encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle)
        rows = list(reader)
    if not rows:
        return score
    by_sample = {as_text(row.get("sample_id")): row for row in rows}
    updated = deep_copy(score)
    metric_names = [metric["name"] for metric in list_value(updated.get("metrics")) if metric.get("name") in JUDGE_METRIC_NAMES]
    manual_counts: dict[str, int] = {}
    for row in list_value(updated.get("per_sample")):
        sample_id = as_text(row.get("sample_id"))
        csv_row = by_sample.get(sample_id, {})
        metrics = dict_value(row.get("metrics"))
        for metric_name in metric_names:
            manual_value = parse_manual_score(csv_row.get(f"{metric_name}_manual"))
            if manual_value is None:
                continue
            metrics[metric_name] = manual_value
            manual_counts[metric_name] = manual_counts.get(metric_name, 0) + 1
        row["metrics"] = metrics
    for metric in list_value(updated.get("metrics")):
        name = as_text(metric.get("name"))
        if name not in metric_names:
            continue
        values = [
            optional_float(dict_value(row.get("metrics")).get(name))
            for row in list_value(updated.get("per_sample"))
        ]
        numeric = [value for value in values if value is not None]
        if numeric:
            metric["value"] = round(statistics.mean(numeric), 6)
            metric["sampleCount"] = len(numeric)
            metric["manualOverrides"] = manual_counts.get(name, 0)
            direction, threshold = THRESHOLDS.get(name, (None, None))
            if direction and threshold is not None:
                metric["status"] = "PASS" if threshold_passes(metric["value"], direction, threshold) else "FAILED"
    return updated


def parse_manual_score(raw: Any) -> float | None:
    text = as_text(raw).strip()
    if not text:
        return None
    if text.endswith("%"):
        value = float(text[:-1]) / 100
    else:
        value = float(text)
        if value > 1:
            value = value / 100
    if value < 0 or value > 1:
        raise ValueError(f"manual score must be between 0-1 or 0-100: {raw}")
    return value


def write_score(path: Path | str, score: dict[str, Any]) -> None:
    actual = Path(path)
    actual.parent.mkdir(parents=True, exist_ok=True)
    actual.write_text(json.dumps(redacted_copy(score), ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def load_score(path: Path | str) -> dict[str, Any]:
    with Path(path).open("r", encoding="utf-8") as handle:
        value = json.load(handle)
    if not isinstance(value, dict):
        raise ValueError(f"{path} must contain a JSON object")
    return value


def diff_scores(baseline: dict[str, Any], candidate: dict[str, Any]) -> dict[str, Any]:
    base_metrics = {metric["name"]: metric for metric in list_value(baseline.get("metrics"))}
    candidate_metrics = {metric["name"]: metric for metric in list_value(candidate.get("metrics"))}
    rows: list[dict[str, Any]] = []
    for name in sorted(set(base_metrics) | set(candidate_metrics)):
        base_value = optional_float(base_metrics.get(name, {}).get("value"))
        candidate_value = optional_float(candidate_metrics.get(name, {}).get("value"))
        delta = None if base_value is None or candidate_value is None else round(candidate_value - base_value, 6)
        rows.append(
            {
                "name": name,
                "baseline": base_value,
                "candidate": candidate_value,
                "delta": delta,
                "candidateStatus": candidate_metrics.get(name, {}).get("status", "MISSING"),
            }
        )
    regressions = [row for row in rows if is_metric_regression(row)]
    return {
        "baseline_run_id": as_text(baseline.get("run_id")),
        "candidate_run_id": as_text(candidate.get("run_id")),
        "metrics": rows,
        "regressions": regressions,
    }


def render_diff_markdown(diff: dict[str, Any]) -> str:
    lines = [
        f"# RD Eval Diff: {diff.get('baseline_run_id')} -> {diff.get('candidate_run_id')}",
        "",
        "| metric | baseline | candidate | delta | candidateStatus |",
        "| --- | ---: | ---: | ---: | --- |",
    ]
    for row in diff.get("metrics", []):
        lines.append(
            f"| `{row.get('name')}` | {row.get('baseline')} | {row.get('candidate')} | {row.get('delta')} | {row.get('candidateStatus')} |"
        )
    lines.extend(["", "## Regressions", ""])
    regressions = diff.get("regressions", [])
    if not regressions:
        lines.append("No regressions.")
    else:
        for row in regressions:
            lines.append(f"- `{row.get('name')}` candidate={row.get('candidate')} delta={row.get('delta')} status={row.get('candidateStatus')}")
    lines.append("")
    return "\n".join(lines)


class JudgeProvider:
    name = "base"
    enabled = False

    def evaluate(self, sample: dict[str, Any], record: dict[str, Any]) -> dict[str, float]:
        return {}


class NoopJudgeProvider(JudgeProvider):
    name = "none"
    enabled = False


class OpenAICompatibleJudgeProvider(JudgeProvider):
    name = "openai-compatible"
    enabled = True

    def __init__(self, base_url: str, api_key: str, model: str, timeout_seconds: float = 60.0):
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.model = model
        self.timeout_seconds = timeout_seconds

    @classmethod
    def from_env(cls, env: dict[str, str] | None = None) -> "OpenAICompatibleJudgeProvider":
        actual = env if env is not None else os.environ
        base_url = actual.get("RD_EVAL_JUDGE_BASE_URL", "").strip()
        api_key = actual.get("RD_EVAL_JUDGE_API_KEY", "").strip()
        model = actual.get("RD_EVAL_JUDGE_MODEL", "").strip()
        missing = [
            name for name, value in [
                ("RD_EVAL_JUDGE_BASE_URL", base_url),
                ("RD_EVAL_JUDGE_API_KEY", api_key),
                ("RD_EVAL_JUDGE_MODEL", model),
            ]
            if not value
        ]
        if missing:
            raise ValueError("missing judge environment variable(s): " + ", ".join(missing))
        timeout = optional_float(actual.get("RD_EVAL_JUDGE_TIMEOUT_SECONDS")) or 60.0
        return cls(base_url=base_url, api_key=api_key, model=model, timeout_seconds=timeout)

    def evaluate(self, sample: dict[str, Any], record: dict[str, Any]) -> dict[str, float]:
        prompt = (
            "You are evaluating an RD-Bot RAG and multi-agent delivery record. "
            "Return strict JSON with numeric fields faithfulness, answer_relevancy, "
            "answer_correctness, context_precision, context_recall, each from 0 to 1. "
            "Do not include secrets or markdown.\n\n"
            f"Sample:\n{json.dumps(redacted_copy(sample), ensure_ascii=False)[:8000]}\n\n"
            f"Record:\n{json.dumps(redacted_copy(record), ensure_ascii=False)[:12000]}"
        )
        body = {
            "model": self.model,
            "temperature": 0,
            "messages": [
                {"role": "system", "content": "Return only JSON."},
                {"role": "user", "content": prompt},
            ],
        }
        request = urllib.request.Request(
            self.base_url,
            data=json.dumps(body).encode("utf-8"),
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {self.api_key}",
            },
            method="POST",
        )
        with urllib.request.urlopen(request, timeout=self.timeout_seconds) as response:
            payload = json.loads(response.read().decode("utf-8"))
        content = extract_openai_message_content(payload)
        parsed = json.loads(content)
        return {
            name: clamp01(optional_float(parsed.get(name)) or 0.0)
            for name in JUDGE_METRIC_NAMES
        }


class RagasJudgeProvider(JudgeProvider):
    name = "ragas"
    enabled = True

    def __init__(
        self,
        api_key: str,
        base_url: str,
        judge_model: str,
        embedding_model: str,
        timeout_seconds: float = 900.0,
        n_runs: int = 1,
    ):
        self.api_key = api_key
        self.base_url = base_url.rstrip("/")
        self.judge_model = judge_model
        self.embedding_model = embedding_model
        self.timeout_seconds = timeout_seconds
        self.n_runs = max(int(n_runs), 1)

    @classmethod
    def from_env(cls, env: dict[str, str] | None = None) -> "RagasJudgeProvider":
        actual = env if env is not None else os.environ
        api_key = (
            actual.get("RD_EVAL_JUDGE_API_KEY", "").strip()
            or actual.get("AIHUBMIX_API_KEY", "").strip()
        )
        if not api_key:
            raise ValueError("missing judge environment variable(s): RD_EVAL_JUDGE_API_KEY or AIHUBMIX_API_KEY")
        base_url = (
            actual.get("RD_EVAL_JUDGE_BASE_URL", "").strip()
            or actual.get("AIHUBMIX_BASE_URL", "").strip()
            or "https://aihubmix.com/v1"
        )
        judge_model = (
            actual.get("RD_EVAL_JUDGE_MODEL", "").strip()
            or actual.get("JUDGE_MODEL", "").strip()
            or "gpt-5.4-mini"
        )
        embedding_model = (
            actual.get("RD_EVAL_EMBEDDING_MODEL", "").strip()
            or actual.get("EMBEDDING_MODEL", "").strip()
            or "text-embedding-3-large"
        )
        timeout = optional_float(actual.get("RD_EVAL_JUDGE_TIMEOUT_SECONDS")) or 900.0
        n_runs = int(optional_float(actual.get("RD_EVAL_RAGAS_N_RUNS")) or 1)
        return cls(
            api_key=api_key,
            base_url=base_url,
            judge_model=judge_model,
            embedding_model=embedding_model,
            timeout_seconds=timeout,
            n_runs=n_runs,
        )

    def evaluate(self, sample: dict[str, Any], record: dict[str, Any]) -> dict[str, float]:
        if not self.is_evaluable(record):
            return {}
        results: list[dict[str, float]] = []
        for _ in range(self.n_runs):
            results.append(self._evaluate_once(record))
        averaged: dict[str, float] = {}
        for name in JUDGE_METRIC_NAMES:
            values = [value[name] for value in results if name in value]
            if values:
                averaged[name] = round(statistics.mean(values), 6)
        return averaged

    @staticmethod
    def is_evaluable(record: dict[str, Any]) -> bool:
        return bool(
            as_text(record.get("response")).strip()
            and list_value(record.get("retrieved_contexts"))
            and as_text(record.get("reference")).strip()
            and as_text(record.get("final_status")) == "success"
        )

    def _evaluate_once(self, record: dict[str, Any]) -> dict[str, float]:
        try:
            from datasets import Dataset
            from langchain_openai import ChatOpenAI, OpenAIEmbeddings
            from ragas import evaluate
            from ragas.metrics import (
                answer_correctness,
                answer_relevancy,
                context_precision,
                context_recall,
                faithfulness,
            )
        except ImportError as exc:
            raise RuntimeError(
                "RAGAS judge dependencies are missing; install ragas langchain-openai datasets"
            ) from exc

        judge_kwargs: dict[str, Any] = {
            "model": self.judge_model,
            "api_key": self.api_key,
            "base_url": self.base_url,
            "timeout": self.timeout_seconds,
            "max_retries": 3,
        }
        if not is_reasoning_model(self.judge_model):
            judge_kwargs["temperature"] = 0
            judge_kwargs["model_kwargs"] = {"response_format": {"type": "json_object"}}
        judge = ChatOpenAI(**judge_kwargs)
        embeddings = OpenAIEmbeddings(
            model=self.embedding_model,
            api_key=self.api_key,
            base_url=self.base_url,
            timeout=self.timeout_seconds,
        )
        dataset = Dataset.from_dict(
            {
                "user_input": [as_text(record.get("user_input"))],
                "response": [as_text(record.get("response"))],
                "retrieved_contexts": [text_list(record.get("retrieved_contexts"))],
                "reference": [as_text(record.get("reference"))],
            }
        )
        result = evaluate(
            dataset=dataset,
            metrics=[
                faithfulness,
                answer_relevancy,
                answer_correctness,
                context_precision,
                context_recall,
            ],
            llm=judge,
            embeddings=embeddings,
            show_progress=False,
        )
        row = result.to_pandas().iloc[0]
        scores: dict[str, float] = {}
        for name in JUDGE_METRIC_NAMES:
            value = optional_float(row.get(name))
            if value is not None and value == value:
                scores[name] = clamp01(value)
        return scores


def judge_provider_from_name(name: str) -> JudgeProvider:
    normalized = (name or "none").strip().lower()
    if normalized in {"none", "noop", "skip"}:
        return NoopJudgeProvider()
    if normalized == "ragas":
        return RagasJudgeProvider.from_env()
    if normalized == "openai-compatible":
        return OpenAICompatibleJudgeProvider.from_env()
    raise ValueError(f"unsupported judge provider: {name}")


def extract_openai_message_content(payload: dict[str, Any]) -> str:
    choices = list_value(payload.get("choices"))
    if not choices:
        raise ValueError("judge response has no choices")
    message = dict_value(dict_value(choices[0]).get("message"))
    content = as_text(message.get("content"))
    if not content:
        raise ValueError("judge response message content is empty")
    return content


def output_root(path: str | None = None) -> Path:
    if not path:
        return DEFAULT_OUTPUT_ROOT
    actual = Path(path)
    return actual if actual.is_absolute() else REPO_ROOT / actual


def resolve_repo_path(path: Path | str) -> Path:
    actual = Path(path)
    if actual.is_absolute():
        return actual
    if actual.exists():
        return actual.resolve()
    rooted = REPO_ROOT / actual
    return rooted if rooted.exists() else actual


def run_path(root: Path, run_id: str) -> Path:
    safe = validate_run_id(run_id)
    return root / "runs" / f"{safe}.jsonl"


def report_dir(root: Path, run_id: str) -> Path:
    return root / "reports" / validate_run_id(run_id)


def score_path(root: Path, run_id: str) -> Path:
    return report_dir(root, run_id) / "_scores.json"


def latest_file(directory: Path | str, suffix: str) -> Path:
    actual = Path(directory)
    candidates = [path for path in actual.glob(f"*{suffix}") if path.is_file()]
    if not candidates:
        raise FileNotFoundError(f"no {suffix} files under {actual}")
    return max(candidates, key=lambda path: (path.stat().st_mtime_ns, path.name))


def latest_run_id(root: Path) -> str:
    return latest_file(root / "runs", ".jsonl").stem


def generate_run_id(prefix: str = "rd-eval") -> str:
    return f"{prefix}-{time.strftime('%Y%m%d-%H%M%S')}-{int((time.time() % 1) * 1000):03d}"


def validate_run_id(run_id: str) -> str:
    value = as_text(run_id).strip()
    if not SAFE_RUN_ID.fullmatch(value):
        raise ValueError("run_id must match [A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    return value


def is_metric_regression(row: dict[str, Any]) -> bool:
    name = as_text(row.get("name"))
    baseline = optional_float(row.get("baseline"))
    candidate = optional_float(row.get("candidate"))
    direction, _ = THRESHOLDS.get(name, (">=", None))
    if baseline is not None and candidate is not None:
        if direction == "<=":
            return candidate > baseline
        return candidate < baseline
    return as_text(row.get("candidateStatus")) == "FAILED"


def question_from_sample(sample: dict[str, Any]) -> str:
    payload = dict_value(sample.get("input"))
    parts = [
        as_text(payload.get("title")),
        as_text(payload.get("description")),
        "\n".join(text_list(payload.get("logs"))),
    ]
    return "\n\n".join(part for part in parts if part).strip()


def stage(record: dict[str, Any], role: str) -> dict[str, Any]:
    return dict_value(dict_value(record.get("stages")).get(role))


def stage_gold(sample: dict[str, Any], role: str) -> dict[str, Any]:
    return dict_value(dict_value(sample.get("stage_gold")).get(role))


def stage_result(stage_record: dict[str, Any]) -> dict[str, Any]:
    for key in ["resultJson", "result_json", "reviewResult", "review_result_json"]:
        value = stage_record.get(key)
        if isinstance(value, dict):
            return value
        if isinstance(value, str) and value.strip():
            try:
                parsed = json.loads(value)
                if isinstance(parsed, dict):
                    return parsed
            except json.JSONDecodeError:
                continue
    return {}


def stage_decision(stage_record: dict[str, Any]) -> str:
    result = stage_result(stage_record)
    for key in ["decision", "status", "reviewDecision", "requirementDecision"]:
        value = as_text(result.get(key))
        if value:
            return value
    return as_text(stage_record.get("status"))


def evidence_uris_from_record(record: dict[str, Any]) -> list[str]:
    rag = dict_value(record.get("rag"))
    values = text_list(rag.get("retrievedEvidenceUris"))
    values.extend(evidence_uris_from_chunks(list_value(rag.get("retrievedChunks"))))
    return dedupe(values)


def evidence_uris_from_chunks(chunks: list[Any]) -> list[str]:
    values: list[str] = []
    for item in chunks:
        chunk = dict_value(item)
        for key in ["evidenceUri", "uri", "sourceUri", "chunkUri"]:
            value = as_text(chunk.get(key))
            if value:
                values.append(value)
        chunk_id = as_text(chunk.get("chunkId") or chunk.get("id"))
        source_name = as_text(chunk.get("sourceName"))
        knowledge_base_id = as_text(chunk.get("knowledgeBaseId"))
        knowledge_type = as_text(chunk.get("knowledgeType")).lower()
        canonical = canonical_evidence_uri(knowledge_base_id, knowledge_type, source_name, chunk_id)
        if canonical:
            values.append(canonical)
        if chunk_id and "://" in chunk_id:
            values.append(chunk_id)
        elif source_name and chunk_id:
            values.append(f"{source_name}#{chunk_id}")
    return dedupe(values)


def canonical_evidence_uri(
    knowledge_base_id: str,
    knowledge_type: str,
    source_name: str,
    chunk_id: str,
) -> str:
    if knowledge_type == "code-snippet" and source_name:
        symbol = ""
        parts = [part for part in chunk_id.split("#") if part]
        if parts and parts[-1] != source_name and not parts[-1].endswith(".java"):
            symbol = parts[-1]
        return f"code://{source_name}{('#' + symbol) if symbol else ''}"
    if knowledge_type == "runtime-log" and knowledge_base_id:
        index = chunk_id.split("#")[-1] if chunk_id else ""
        return f"log://{knowledge_base_id}/{source_name or 'log'}{('#' + index) if index else ''}"
    if knowledge_base_id and source_name:
        suffix = ""
        if chunk_id.startswith(source_name + "#"):
            suffix = "#" + chunk_id.removeprefix(source_name + "#")
        return f"knowledge://{knowledge_base_id}/{source_name}{suffix}"
    return ""


def first_hit_rank(values: list[str], expected: set[str]) -> int | None:
    for index, value in enumerate(values, start=1):
        if value in expected:
            return index
    return None


def add_precision_recall(
    sample: dict[str, Any],
    record: dict[str, Any],
    sample_metrics: dict[str, Any],
    add_metric,
    precision_name: str,
    recall_name: str,
    expected_values: list[str],
    actual_values: list[str],
    reason: str,
) -> None:
    expected = set(expected_values)
    actual = set(actual_values)
    if actual:
        add_metric(sample, record, sample_metrics, precision_name, len(actual.intersection(expected)) / len(actual), reason)
    else:
        add_metric(sample, record, sample_metrics, precision_name, 0.0, reason)
    add_metric(sample, record, sample_metrics, recall_name, len(actual.intersection(expected)) / len(expected), reason)


def passed_test_commands(result: dict[str, Any]) -> set[str]:
    passed: set[str] = set()
    for item in list_value(result.get("testCommands") or result.get("test_commands")):
        if isinstance(item, str):
            passed.add(item)
        elif isinstance(item, dict) and as_text(item.get("status")).upper() in {"PASSED", "PASS", "SUCCESS", "SUCCEEDED"}:
            passed.add(as_text(item.get("command")))
    return {command for command in passed if command}


def threshold_passes(value: float, direction: str, threshold: float) -> bool:
    if direction == ">=":
        return value >= threshold
    if direction == "<=":
        return value <= threshold
    raise ValueError(f"unsupported threshold direction: {direction}")


def role_for_metric(metric: str) -> str:
    if metric.startswith("requirement") or metric in {"downstream_leak_rate", "risk_coverage"}:
        return "REQUIREMENT_REVIEWER"
    if metric.startswith("solution") or metric.startswith("acceptance") or metric.startswith("affected"):
        return "SOLUTION_ARCHITECT"
    if metric.startswith("coding") or metric.startswith("changed") or metric.startswith("forbidden") or metric.startswith("test_command") or metric == "pr_policy_violation_rate":
        return "CODING_AGENT"
    if metric.startswith("qa") or metric.startswith("skipped") or metric.startswith("real_command"):
        return "QA_AGENT"
    return ""


def first_artifact_uri(record: dict[str, Any]) -> str:
    for role in ALL_ROLES:
        value = as_text(stage(record, role).get("resultArtifactUri") or stage(record, role).get("artifactUri"))
        if value:
            return value
    return ""


def next_action(metric: str) -> str:
    if metric.startswith("evidence") or metric in {"intent_top1", "mrr@10"}:
        return "检查检索日志、gold evidence URI、知识库刷新和上下文裁剪策略"
    if metric.startswith("requirement") or metric == "downstream_leak_rate":
        return "复核需求评审 prompt、阻断状态和 rd_agent_stage_runs 派发记录"
    if metric.startswith("solution") or metric.startswith("acceptance") or metric.startswith("affected"):
        return "复核方案 result artifact 的结构化字段和验收映射"
    if metric.startswith("forbidden") or metric == "secret_leak_rate":
        return "阻断交付并检查 diff、artifact metadata 和脱敏策略"
    if metric.startswith("qa") or metric.startswith("skipped") or metric.startswith("real_command"):
        return "补齐真实命令、日志 artifact 和 QA 验收项"
    if metric.startswith("alert"):
        return "检查 RepairAlert/Feishu delivery attempt 和告警 metadata"
    return "查看 per_sample.csv 和 failures.jsonl 定位样本"


def redacted_copy(value: Any) -> Any:
    if isinstance(value, dict):
        return {key: redacted_copy(item) for key, item in value.items()}
    if isinstance(value, list):
        return [redacted_copy(item) for item in value]
    if isinstance(value, tuple):
        return [redacted_copy(item) for item in value]
    return redact(value)


def redact(value: Any) -> Any:
    if not isinstance(value, str):
        return value
    result = value
    for pattern in SECRET_PATTERNS:
        if pattern.pattern.startswith("(?i)([?&]"):
            result = pattern.sub(lambda match: match.group(1) + "[REDACTED]", result)
        elif "Bearer" in pattern.pattern:
            result = pattern.sub("Bearer [REDACTED]", result)
        else:
            result = pattern.sub("[REDACTED]", result)
    for needle in secret_needles():
        result = result.replace(needle, "[REDACTED]")
    return result


def secret_needles() -> list[str]:
    return [
        value
        for value in os.environ.get("RD_BOT_SECRET_SCAN_NEEDLES", "").split(",")
        if len(value) >= 4
    ]


def text_blob(record: dict[str, Any]) -> str:
    return json_blob(record)


def json_blob(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True)


def escape_table(value: str) -> str:
    return value.replace("|", "\\|").replace("\n", " ")


def has_non_empty(value: Any) -> bool:
    if value is None:
        return False
    if isinstance(value, (list, dict, str)):
        return bool(value)
    return True


def dict_value(value: Any) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def list_value(value: Any) -> list[Any]:
    return value if isinstance(value, list) else []


def text_list(value: Any) -> list[str]:
    if isinstance(value, list):
        return [as_text(item) for item in value if as_text(item)]
    if isinstance(value, str) and value:
        return [value]
    return []


def as_text(value: Any) -> str:
    if value is None:
        return ""
    return str(value)


def optional_float(value: Any) -> float | None:
    if value is None or value == "":
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def is_reasoning_model(model: str) -> bool:
    family = as_text(model).rsplit("/", 1)[-1].lower()
    return any(family.startswith(prefix) for prefix in ("gpt-5", "o1", "o3", "o4"))


def clamp01(value: float) -> float:
    return min(1.0, max(0.0, value))


def dedupe(values: list[str]) -> list[str]:
    seen: set[str] = set()
    result: list[str] = []
    for value in values:
        if value and value not in seen:
            seen.add(value)
            result.append(value)
    return result


def deep_copy(value: Any) -> Any:
    return json.loads(json.dumps(value, ensure_ascii=False))


def utc_now() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
