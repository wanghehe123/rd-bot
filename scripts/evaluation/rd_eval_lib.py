#!/usr/bin/env python3
"""Pure Python helpers for RD-Bot evaluation runs and reports."""

from __future__ import annotations

import csv
from dataclasses import dataclass, field
import hashlib
import html
import json
import os
import platform
import re
import statistics
import subprocess
import time
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_OUTPUT_ROOT = REPO_ROOT / "qa-runs/evaluation"
DEFAULT_OPENAI_JUDGE_CONFIG_PATH = REPO_ROOT / "scripts/evaluation-judge.yaml"
OPENAI_JUDGE_CONFIG_KEYS = ("RD_EVAL_JUDGE_BASE_URL", "RD_EVAL_JUDGE_MODEL")
OPENAI_JUDGE_API_KEY_ENV = "RD_EVAL_JUDGE_API_KEY"
MACOS_LAUNCHCTL_EXECUTABLE = "/bin/launchctl"
MACOS_LAUNCHCTL_TIMEOUT_SECONDS = 1.0
JUDGE_TASK_RUN_XML_INPUT_BUDGET_BYTES = 13_000
JUDGE_MAX_COMPLETION_TOKENS = 1_500
JUDGE_XML_VERSION = "2"
JUDGE_XML_SECTIONS = (
    "task_input",
    "rag_evaluation_parameters",
    "role_execution_results",
)
JUDGE_METRIC_NAMES = [
    "faithfulness",
    "answer_relevancy",
    "answer_correctness",
    "context_precision",
    "context_recall",
]
CONTEXT_DEPENDENT_JUDGE_METRICS = {"faithfulness", "context_precision", "context_recall"}
FAILED_METRIC_PRIORITY = [
    "retrieval_run_coverage_rate",
    "retrieval_run_integrity_rate",
    "citation_integrity_rate",
    "scope_leak_rate",
    "critical_evidence_coverage_rate",
    "role_specific_evidence_coverage_rate",
    "test_artifact_integrity_rate",
    "provider_attempt_integrity_rate",
    "pr_integrity_rate",
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
    "role_blocking_accuracy": (">=", 1.0),
    "task_terminal_success_rate": (">=", 1.0),
    "role_stage_coverage_rate": (">=", 1.0),
    "stage_success_rate": (">=", 1.0),
    "context_package_coverage_rate": (">=", 1.0),
    "provider_attempt_coverage_rate": (">=", 1.0),
    "result_artifact_coverage_rate": (">=", 1.0),
    "task_timeline_terminal_rate": (">=", 1.0),
    "retrieval_run_coverage_rate": (">=", 1.0),
    "retrieval_run_integrity_rate": (">=", 1.0),
    "citation_integrity_rate": (">=", 1.0),
    "scope_leak_rate": ("<=", 0.0),
    "critical_evidence_coverage_rate": (">=", 1.0),
    "role_specific_evidence_coverage_rate": (">=", 1.0),
    "context_noise_rate": ("<=", 0.25),
    "context_duplicate_rate": ("<=", 0.30),
    "provider_attempt_integrity_rate": (">=", 1.0),
    "test_artifact_integrity_rate": (">=", 1.0),
    "pr_integrity_rate": (">=", 1.0),
    "test_evidence_coverage_rate": (">=", 1.0),
    "pull_request_present_rate": (">=", 1.0),
}

METRIC_DEFINITIONS: dict[str, tuple[str, str, str]] = {
    "intent_top1": ("意图 Top-1 命中率", "确认检索是否路由到正确的意图。", "正确意图样本数除以参与评分的意图样本数。"),
    "evidence_hit@5": ("前 5 条关键证据命中率", "确认前 5 条候选中至少出现一条必须证据。", "命中任意必须证据记 1，否则记 0，再取样本平均值。"),
    "evidence_recall@5": ("前 5 条关键证据召回率", "衡量前 5 条候选覆盖必须证据的程度。", "前 5 条命中的必须证据数除以必须证据总数，再取样本平均值。"),
    "mrr@10": ("前 10 条首个关键证据倒数排名", "衡量首条必须证据是否排在前面。", "每个样本取 1 除以首个必须证据在前 10 条中的排名，未命中记 0，再取平均值。"),
    "faithfulness": ("回答忠实度", "Judge 判断回答是否可由检索上下文支持。", "OpenAI-compatible Judge 在 0 到 1 之间评分，再对已评样本取平均值。"),
    "answer_relevancy": ("回答相关性", "Judge 判断回答是否直接回应任务问题。", "OpenAI-compatible Judge 在 0 到 1 之间评分，再对已评样本取平均值。"),
    "answer_correctness": ("回答正确性", "Judge 对照任务事实和参考结果判断回答是否正确。", "OpenAI-compatible Judge 在 0 到 1 之间评分，再对已评样本取平均值。"),
    "context_precision": ("上下文精确率", "Judge 判断检索上下文是否大多与任务有关。", "OpenAI-compatible Judge 在 0 到 1 之间评分，再对已评样本取平均值。"),
    "context_recall": ("上下文召回率", "Judge 判断上下文是否覆盖回答所需信息。", "OpenAI-compatible Judge 在 0 到 1 之间评分，再对已评样本取平均值。"),
    "requirement_decision_accuracy": ("需求评审决策准确率", "确认需求评审的决策与数据集期望一致。", "决策匹配记 1，否则记 0，再取平均值。"),
    "downstream_leak_rate": ("下游越权流转率", "确认被需求评审阻断的任务没有继续进入下游角色。", "出现任一不应执行的下游阶段记 1，否则记 0，再取平均值。"),
    "risk_coverage": ("需求风险覆盖率", "确认需求评审已识别要求关注的风险。", "结果中命中的必提风险数除以必提风险总数，再取平均值。"),
    "solution_schema_valid_rate": ("方案结构完整率", "确认方案产物包含要求的结构化章节。", "非空必填章节数除以必填章节总数，再取平均值。"),
    "acceptance_mapping_coverage": ("验收映射覆盖率", "确认方案将验收项映射到实施方案。", "映射中命中的验收项数除以期望验收项数，再取平均值。"),
    "affected_file_precision": ("方案影响文件精确率", "确认方案列出的影响文件没有明显噪声。", "正确影响文件数除以方案列出的影响文件数，再取平均值。"),
    "affected_file_recall": ("方案影响文件召回率", "确认方案没有遗漏应修改的文件。", "方案命中的期望影响文件数除以期望影响文件数，再取平均值。"),
    "coding_result_schema_valid_rate": ("编码产物结构完整率", "确认编码阶段返回可审计的结构化结果。", "有非空结构化结果记 1，否则记 0，再取平均值。"),
    "changed_file_precision": ("实际改动文件精确率", "确认实际改动没有超出预期范围。", "正确改动文件数除以实际改动文件数，再取平均值。"),
    "changed_file_recall": ("实际改动文件召回率", "确认应修改的文件均已覆盖。", "实际命中的期望改动文件数除以期望改动文件数，再取平均值。"),
    "forbidden_file_touch_rate": ("禁止文件触碰率", "确认编码没有修改明确禁止的文件。", "触碰任一禁止文件记 1，否则记 0，再取平均值。"),
    "test_command_pass_rate": ("要求测试命令通过率", "确认编码阶段执行并通过要求的测试命令。", "通过的必需命令数除以必需命令总数，再取平均值。"),
    "pr_policy_violation_rate": ("PR 策略违规率", "确认编码阶段没有绕过受控 PR 发布流程。", "编码阶段直接返回 PR URL 记 1，否则记 0，再取平均值。"),
    "secret_leak_rate": ("敏感信息泄漏率", "检查数据集和运行记录是否包含疑似密钥。", "命中任一密钥模式记 1，否则记 0，再取平均值。"),
    "qa_schema_valid_rate": ("QA 产物结构完整率", "确认 QA 阶段提供结构化验收结果。", "存在验收结果记 1，否则记 0，再取平均值。"),
    "real_command_execution_rate": ("真实命令证据率", "确认 QA 验收项有真实命令和日志产物。", "同时有命令和日志产物的验收项数除以验收项数，再取平均值。"),
    "acceptance_status_match_rate": ("验收状态匹配率", "确认 QA 验收状态与数据集期望一致。", "状态匹配的验收项数除以验收项数，再取平均值。"),
    "max_skipped_acceptance_ok": ("跳过验收上限符合率", "确认跳过的验收项没有超过允许上限。", "未超过上限记 1，否则记 0，再取平均值。"),
    "skipped_acceptance_rate": ("跳过验收率", "暴露未执行的验收项比例。", "状态为 SKIPPED 的验收项数除以验收项数，再取平均值。"),
    "alert_expected_rate": ("预期告警覆盖率", "确认应发出的告警均已产生。", "已产生的期望告警数除以期望告警总数，再取平均值。"),
    "forbidden_alert_rate": ("禁止告警触发率", "确认没有产生不应出现的告警。", "出现任一禁止告警记 1，否则记 0，再取平均值。"),
    "required_experience_complete_rate": ("经验沉淀完整率", "确认交付后沉淀了必需的经验类型。", "已沉淀的必需经验类型数除以必需类型数，再取平均值。"),
    "experience_redacted_rate": ("经验脱敏合规率", "确认经验沉淀被标记为已脱敏。", "已脱敏记 1，否则记 0，再取平均值。"),
    "role_blocking_accuracy": ("角色阻断准确率", "确认上游门禁失败时目标角色或交付没有越权继续。", "任务状态、上游门禁状态和目标角色缺席均符合 gold 时记 1，否则记 0，再取平均值。"),
    "task_terminal_success_rate": ("任务成功终态率", "确认任务到达可交付的成功终态。", "终态为 COMMITTED、COMPLETED 或 MERGED 记 1，否则记 0，再取平均值。"),
    "role_stage_coverage_rate": ("角色阶段覆盖率", "确认所有要求的角色阶段均有运行记录。", "存在记录的要求角色数除以要求角色数，再取平均值。"),
    "stage_success_rate": ("角色阶段成功率", "确认各角色最新执行尝试成功。", "状态为 SUCCEEDED 的要求角色数除以要求角色数，再取平均值。"),
    "context_package_coverage_rate": ("上下文包覆盖率", "确认每个角色阶段绑定了上下文包。", "存在 contextPackageId 的要求角色数除以要求角色数，再取平均值。"),
    "provider_attempt_coverage_rate": ("Provider 尝试覆盖率", "确认每个角色阶段保留了 Provider 审计记录。", "存在 Provider 尝试记录的要求角色数除以要求角色数，再取平均值。"),
    "result_artifact_coverage_rate": ("阶段产物覆盖率", "确认每个角色结果引用了可验证产物。", "同时有结果产物 ID 和存在标记的要求角色数除以要求角色数，再取平均值。"),
    "task_timeline_terminal_rate": ("任务时间线终态一致率", "确认任务时间线最后状态与持久化任务状态一致。", "两者一致记 1，否则记 0，再取平均值。"),
    "retrieval_run_coverage_rate": ("检索运行覆盖率", "确认任务要求的 Deep RAG RetrievalRun 均成功完成。", "成功或降级成功的 RetrievalRun consumer 数除以要求 consumer 数，再取平均值。"),
    "retrieval_run_integrity_rate": ("检索运行完整率", "确认成功的 RetrievalRun 具备真实范围、选择证据、质量报告及正确阶段绑定。", "逐个检查要求 consumer 的最新运行；知识库范围、证据 URI/hash、质量报告、候选计数、阶段和上下文绑定全部有效记 1，再按 consumer 聚合。"),
    "citation_integrity_rate": ("引用完整率", "确认角色上下文中的最终引用确实来自所绑定 RetrievalRun 的已选证据。", "上下文中 URI 与 hash 同时匹配绑定 RetrievalRun selected evidence 的引用数除以全部引用数。"),
    "scope_leak_rate": ("范围泄漏率", "确认已选证据没有越出任务允许的知识库、项目或仓库范围。", "显式范围违规或 URI 指向未授权知识库的证据数除以全部已选证据数。"),
    "critical_evidence_coverage_rate": ("关键证据覆盖率", "确认每个角色取得任务根信息及职责所需的关键证据类型。", "已满足的必需证据组数除以根据任务输入和角色生成的全部必需证据组数。"),
    "role_specific_evidence_coverage_rate": ("角色专属证据覆盖率", "确认方案、编码和 QA 角色均取得与职责匹配的独立证据，而非复用需求根材料。", "每个下游角色至少有一条非 sharedRoot、URI/hash 完整且 requiredEvidenceType 符合角色要求的证据记 1，再按角色聚合。"),
    "context_noise_rate": ("上下文噪声率", "发现人工标记为禁止、显式低相关或无效的最终证据。", "命中 forbiddenEvidenceUris、relevanceScore 不大于 0 或显式 irrelevant 的证据数除以全部最终证据数。"),
    "context_duplicate_rate": ("上下文重复率", "发现跨角色重复复制、却未声明共享根或引用复用的正文。", "同一 content hash 在多个上下文中的额外出现次数除以全部上下文证据数；sharedRoot/reuseReference 不计重复。"),
    "provider_attempt_integrity_rate": ("Provider 尝试完整率", "确认每个最新角色阶段都有可审计、顺序和终态一致的 Provider 尝试记录。", "Provider、attempt、status、开始/结束时间完整，失败含错误，且最终成功 Provider 与阶段一致的角色数除以要求角色数。"),
    "test_artifact_integrity_rate": ("测试产物完整率", "确认 QA 的通过结论能由真实命令、退出码和可解析日志产物复核。", "命令非空、状态 PASSED、exitCode=0 且日志 URI/hash 与 manifest 匹配的验收项数除以全部验收项数。"),
    "pr_integrity_rate": ("PR 完整率", "确认 PR URL、目标仓库、分支和提交信息均可核对。", "PR URL 合法且与任务仓库匹配，同时 base/work branch 与 commit SHA 完整时记 1，否则记 0。"),
    "test_evidence_coverage_rate": ("测试证据覆盖率", "确认任务执行留下至少一条持久化测试证据。", "测试证据数大于 0 记 1，否则记 0，再取平均值。"),
    "pull_request_present_rate": ("PR 产物覆盖率", "确认交付任务关联了 Pull Request URL。", "存在 Pull Request URL 记 1，否则记 0，再取平均值。"),
    "ttft_mean_ms": ("首 Token 平均耗时", "衡量模型开始生成前的平均等待时间。", "对参与评分样本的 first_token_ms 取算术平均值。"),
    "total_mean_ms": ("总耗时平均值", "衡量请求从开始到结束的平均耗时。", "对参与评分样本的 latency_ms 取算术平均值。"),
    "refusal_when_required": ("需要检索时拒答率", "发现需要 RAG 却没有回答或证据的请求。", "需要 RAG 且答案为空或无证据记 1，否则记 0，再取平均值。"),
    "over_retrieval_rate": ("非检索请求过度检索率", "发现不需要 RAG 时仍取回证据的请求。", "不需要 RAG 但存在检索证据记 1，否则记 0，再取平均值。"),
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
    # Require a token boundary so ordinary identifiers such as "task-run-..."
    # are not redacted as if their trailing "sk-..." were an API key.
    re.compile(r"(?<![A-Za-z0-9])sk-[A-Za-z0-9_\-]{12,}"),
    re.compile(r"(?i)\bBearer\s+[A-Za-z0-9._~+/=-]+"),
    re.compile(r"\b[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{3,}\b"),
    re.compile(r"(?i)\b(api[_-]?key|secret|password|token|access_token)\s*[:=]\s*['\"]?[A-Za-z0-9._~+/=-]{8,}"),
    re.compile(r"(?i)([?&](?:access_token|token|api_key|secret|password)=)[^&\s]+"),
    re.compile(r"(?i)https?://[A-Za-z0-9._~+%-]+:[A-Za-z0-9._~+%=-]+@"),
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
    label: str = ""
    purpose: str = ""
    calculation: str = ""

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
            label=as_text(row.get("label")),
            purpose=as_text(row.get("purpose")),
            calculation=as_text(row.get("calculation")),
        )


def metric_definition(name: str) -> dict[str, str]:
    """Return user-facing metric metadata without changing scoring semantics."""
    normalized = as_text(name).strip()
    exact = METRIC_DEFINITIONS.get(normalized)
    if exact:
        label, purpose, calculation = exact
        return {"label": label, "purpose": purpose, "calculation": calculation}
    hit = re.fullmatch(r"hit@(\d+)", normalized)
    if hit:
        limit = hit.group(1)
        return {
            "label": f"前 {limit} 条证据命中率",
            "purpose": "确认候选证据列表是否至少命中一条必须证据。",
            "calculation": f"前 {limit} 条命中任意必须证据记 1，否则记 0，再取样本平均值。",
        }
    recall = re.fullmatch(r"recall@(\d+)", normalized)
    if recall:
        limit = recall.group(1)
        return {
            "label": f"前 {limit} 条证据召回率",
            "purpose": "衡量候选证据列表对必须证据的覆盖程度。",
            "calculation": f"前 {limit} 条命中的必须证据数除以必须证据总数，再取样本平均值。",
        }
    inclusive_recall = re.fullmatch(r"recall_inclusive@(\d+)", normalized)
    if inclusive_recall:
        limit = inclusive_recall.group(1)
        return {
            "label": f"前 {limit} 条扩展证据召回率",
            "purpose": "衡量候选证据对必须证据和可选证据的覆盖程度。",
            "calculation": f"前 {limit} 条命中的扩展证据数除以扩展证据总数，再取样本平均值。",
        }
    return {
        "label": normalized or "未知指标",
        "purpose": "用于补充观察当前评测运行的质量信号。",
        "calculation": "按同名评分规则对参与评分的样本聚合计算。",
    }


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
    fixture_records: list[dict[str, Any]] | None = None,
) -> list[dict[str, Any]]:
    external_by_sample: dict[str, dict[str, Any]] = {}
    for row in fixture_records or []:
        sample_id = as_text(dict_value(row).get("sample_id"))
        if not sample_id:
            raise ValueError("external fixture record is missing sample_id")
        if sample_id in external_by_sample:
            raise ValueError(f"external fixture records contain duplicate sample_id: {sample_id}")
        external_by_sample[sample_id] = dict_value(row)
    records: list[dict[str, Any]] = []
    for sample in samples:
        sample_id = as_text(sample.get("sample_id"))
        fixture = deep_copy(external_by_sample.get(sample_id) or sample.get("fixture_record") or {})
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


def selected_evaluation_context_groups(record: dict[str, Any]) -> list[dict[str, Any]]:
    """Return only auditable evidence groups that were selected for the evaluated output."""
    groups: list[dict[str, Any]] = []
    for raw_group in list_value(record.get("retrieved_contexts")):
        if not isinstance(raw_group, dict):
            continue
        group = dict_value(raw_group)
        evidence = [
            dict_value(item)
            for item in list_value(group.get("evidence"))
            if _valid_selected_evidence(dict_value(item))
        ]
        if evidence:
            projected = dict(group)
            projected["evidence"] = evidence
            groups.append(projected)
    if groups:
        return groups

    task_run = dict_value(record.get("task_run"))
    for raw_run in list_value(task_run.get("retrievalRuns")):
        run = dict_value(raw_run)
        evidence = [
            dict_value(item)
            for item in list_value(run.get("selectedEvidenceArtifacts"))
            if _valid_selected_evidence(dict_value(item))
        ]
        if evidence:
            groups.append(
                {
                    "consumer": as_text(run.get("consumerKey")),
                    "runId": as_text(run.get("runId")),
                    "stageRunId": as_text(run.get("stageRunId")),
                    "status": as_text(run.get("status")),
                    "evidence": evidence,
                }
            )
    return groups


def has_selected_evaluation_context(record: dict[str, Any]) -> bool:
    if selected_evaluation_context_groups(record):
        return True
    # Live RAG recordings predate structured source metadata but contain the exact
    # selected chunk text, so they remain legitimate Judge context.
    return any(
        as_text(item).strip()
        for item in list_value(record.get("retrieved_contexts"))
        if not isinstance(item, dict)
    )


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(64 * 1024), b""):
            digest.update(chunk)
    return "sha256:" + digest.hexdigest()


def _repository_commit() -> str:
    try:
        completed = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=REPO_ROOT,
            check=True,
            capture_output=True,
            text=True,
            timeout=2,
        )
        value = completed.stdout.strip()
        return value if re.fullmatch(r"[0-9a-fA-F]{7,64}", value) else "unknown"
    except (OSError, subprocess.SubprocessError):
        return "unknown"


def score_provenance(dataset_path: Path, records_path: Path) -> dict[str, Any]:
    """Build reproducibility metadata without persisting prompts or credentials."""
    scorer_sources = [Path(__file__).resolve(), REPO_ROOT / "scripts/evaluation/rd_eval_score.py"]
    scorer_digest = hashlib.sha256()
    for source in scorer_sources:
        scorer_digest.update(source.name.encode("utf-8"))
        scorer_digest.update(source.read_bytes())
    threshold_payload = json.dumps(THRESHOLDS, ensure_ascii=True, sort_keys=True, separators=(",", ":"))
    version_match = re.search(r"(?i)(?:^|[_-])(v[0-9]+)(?:[._-]|$)", dataset_path.name)
    return {
        "scorerSchemaVersion": "rd-eval-score-v2",
        "datasetId": dataset_path.name,
        "datasetVersion": version_match.group(1).lower() if version_match else "unversioned",
        "datasetSha256": _sha256_file(dataset_path),
        "recordSnapshotSha256": _sha256_file(records_path),
        "scorerSourceSha256": "sha256:" + scorer_digest.hexdigest(),
        "scorerGitCommit": _repository_commit(),
        "thresholdConfigVersion": "rd-eval-thresholds-v2",
        "thresholdConfigSha256": "sha256:" + hashlib.sha256(threshold_payload.encode("utf-8")).hexdigest(),
    }


def score_records(
    samples: list[dict[str, Any]],
    records: list[dict[str, Any]],
    judge_provider: "JudgeProvider | None" = None,
    judge_limit: int | None = None,
) -> dict[str, Any]:
    provider = judge_provider or NoopJudgeProvider()
    record_by_sample = {as_text(record.get("sample_id")): record for record in records}
    run_id = as_text(records[0].get("run_id")) if records else "unknown-run"
    metric_observations: dict[str, dict[str, list[float]]] = {}
    metric_status_notes: dict[str, list[str]] = {}
    sample_metric_reasons: dict[str, dict[str, str]] = {}
    failures: list[dict[str, Any]] = []
    per_sample: list[dict[str, Any]] = []
    judged_count = 0
    judge_assessments: list[JudgeAssessment] = []
    judge_errors: list[str] = []

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
        sample_metric_reasons.setdefault(as_text(sample.get("sample_id")), {})[name] = reason
        explicit_key = as_text(sample.get("aggregationKey"))
        if explicit_key:
            aggregation_key = explicit_key
        elif as_text(sample.get("suite")) == "task-run":
            aggregation_key = (
                as_text(record.get("task_id") or record.get("taskId"))
                or as_text(sample.get("scenario"))
                or as_text(sample.get("sample_id"))
            )
        else:
            aggregation_key = (
                as_text(sample.get("scenario"))
                or as_text(record.get("task_id") or record.get("taskId"))
                or as_text(sample.get("sample_id"))
            )
        metric_observations.setdefault(name, {}).setdefault(aggregation_key, []).append(numeric)
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
        judge_assessment: JudgeAssessment | None = None

        score_rag(sample, record, sample_metrics, add_metric)
        score_requirement(sample, record, sample_metrics, add_metric)
        score_solution(sample, record, sample_metrics, add_metric)
        score_coding(sample, record, sample_metrics, add_metric)
        score_qa(sample, record, sample_metrics, add_metric)
        score_alerts(sample, record, sample_metrics, add_metric)
        score_delivery(sample, record, sample_metrics, add_metric)
        score_role_blocking(sample, record, sample_metrics, add_metric)
        score_task_run(sample, record, sample_metrics, add_metric)
        score_secret_leak(sample, record, sample_metrics, add_metric)

        missing_evaluation_context = (
            as_text(sample.get("suite")) == "task-run"
            and not has_selected_evaluation_context(record)
        )
        if missing_evaluation_context:
            for name in CONTEXT_DEPENDENT_JUDGE_METRICS:
                metric_status_notes.setdefault(name, []).append(
                    "missing evaluation context: no selected evidence with URI and hash"
                )

        should_judge = provider.enabled and (judge_limit is None or judged_count < judge_limit)
        if should_judge:
            judged_count += 1
            try:
                judge_assessment = provider.evaluate_with_context(
                    sample,
                    record,
                    local_metric_context(sample_metrics, sample_metric_reasons.get(sample_id, {})),
                )
            except Exception as exc:
                judge_error = as_text(redact(str(exc)))
                judge_errors.append(judge_error)
                for name in JUDGE_METRIC_NAMES:
                    metric_status_notes.setdefault(name, []).append("judge failed")
            else:
                judge_assessments.append(judge_assessment)
                if not any(name in judge_assessment.scores for name in JUDGE_METRIC_NAMES):
                    for name in JUDGE_METRIC_NAMES:
                        metric_status_notes.setdefault(name, []).append("judge skipped")
                for name in JUDGE_METRIC_NAMES:
                    if missing_evaluation_context and name in CONTEXT_DEPENDENT_JUDGE_METRICS:
                        continue
                    add_metric(
                        sample,
                        record,
                        sample_metrics,
                        name,
                        optional_float(judge_assessment.scores.get(name)),
                        "judge score",
                    )
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
        if judge_assessment and (
            judge_assessment.headline
            or judge_assessment.strengths
            or judge_assessment.risks
            or judge_assessment.next_actions
        ):
            per_sample_row["judgeSummary"] = judge_assessment_payload(judge_assessment)
        per_sample.append(per_sample_row)

    metric_values = {
        name: [statistics.mean(values) for values in groups.values()]
        for name, groups in metric_observations.items()
    }
    metrics = aggregate_metrics(metric_values, metric_status_notes)
    judge_summary = build_judge_summary(provider.name, judge_assessments, judged_count, judge_errors)
    return {
        "run_id": run_id,
        "generated_at": utc_now(),
        "sample_count": len(samples),
        "record_count": len(records),
        "judge_provider": provider.name,
        "metrics": metrics,
        "failures": failures,
        "per_sample": per_sample,
        "summary": build_evaluation_summary(metrics, failures, provider.name, judge_summary),
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
        task_run = dict_value(record.get("task_run"))
        manifest = _artifact_manifest(record, task_run)
        real = [
            item
            for item in (dict_value(raw) for raw in acceptance)
            if as_text(item.get("command"))
            and _artifact_resolves(
                as_text(item.get("logArtifactUri") or item.get("log_artifact_uri")),
                as_text(item.get("logArtifactHash") or item.get("log_artifact_hash")),
                manifest,
            )
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


def score_role_blocking(sample: dict[str, Any], record: dict[str, Any], sample_metrics: dict[str, Any], add_metric) -> None:
    """Verify expected upstream blocking without treating a blocked task as a failed benchmark row."""
    gold = dict_value(sample.get("role_block_gold"))
    if not gold:
        return
    stages = dict_value(record.get("stages"))
    blocked_role = as_text(gold.get("blockedRole"))
    task_status = as_text(record.get("status")).upper()
    expected_task_statuses = {value.upper() for value in text_list(gold.get("expectedTaskStatuses"))}
    required_gate_role = as_text(gold.get("requiredGateRole"))
    expected_gate_statuses = {value.upper() for value in text_list(gold.get("expectedGateStatuses"))}

    target_absent = blocked_role == "DELIVERY" or not dict_value(stages.get(blocked_role))
    task_matches = not expected_task_statuses or task_status in expected_task_statuses
    gate_matches = True
    if required_gate_role:
        gate_status = as_text(dict_value(stages.get(required_gate_role)).get("status")).upper()
        gate_matches = bool(gate_status) and (
            not expected_gate_statuses or gate_status in expected_gate_statuses
        )
    add_metric(
        sample,
        record,
        sample_metrics,
        "role_blocking_accuracy",
        1.0 if target_absent and task_matches and gate_matches else 0.0,
        "blocked role or delivery continued, or the persisted task/gate status did not match gold",
    )


ROLE_SPECIFIC_EVIDENCE_TYPES = {
    "SOLUTION_ARCHITECT": {"ARCHITECTURE", "INTERFACE", "API_CONTRACT", "DATABASE_SCHEMA"},
    "CODING_AGENT": {"CODE_SYMBOL", "SOURCE_CODE", "API_CONTRACT", "DATABASE_SCHEMA"},
    "QA_AGENT": {"TEST_ENTRY", "TEST_CASE", "ACCEPTANCE_TARGET", "API_CONTRACT", "PAGE_BEHAVIOR"},
}


def _evidence_uri(evidence: dict[str, Any]) -> str:
    return as_text(evidence.get("sourceUri") or evidence.get("artifactUri") or evidence.get("uri"))


def _valid_selected_evidence(evidence: dict[str, Any]) -> bool:
    return bool(_evidence_uri(evidence) and as_text(evidence.get("contentHash") or evidence.get("hash")))


def _task_run_context_views(task_run: dict[str, Any]) -> list[dict[str, Any]]:
    return [dict_value(item) for item in list_value(task_run.get("contexts"))]


def _latest_retrieval_by_consumer(task_run: dict[str, Any]) -> dict[str, dict[str, Any]]:
    latest: dict[str, tuple[tuple[int, int], dict[str, Any]]] = {}
    for index, raw in enumerate(list_value(task_run.get("retrievalRuns"))):
        run = dict_value(raw)
        consumer = as_text(run.get("consumerKey"))
        if not consumer:
            continue
        key = (int(optional_float(run.get("attemptNo")) or 0), index)
        current = latest.get(consumer)
        if current is None or key >= current[0]:
            latest[consumer] = (key, run)
    return {consumer: value[1] for consumer, value in latest.items()}


def _context_for_role(
    task_run: dict[str, Any], stages: dict[str, Any], role: str
) -> dict[str, Any]:
    expected_package_id = as_text(dict_value(stages.get(role)).get("contextPackageId"))
    matches = [
        context
        for context in _task_run_context_views(task_run)
        if as_text(context.get("role")) == role
        and (not expected_package_id or as_text(context.get("packageId")) == expected_package_id)
    ]
    return matches[-1] if matches else {}


def _retrieval_integrity(
    consumer: str,
    run: dict[str, Any],
    task_run: dict[str, Any],
    stages: dict[str, Any],
) -> bool:
    if as_text(run.get("status")).upper() not in {"SUCCEEDED", "SUCCEEDED_DEGRADED"}:
        return False
    selected = [dict_value(item) for item in list_value(run.get("selectedEvidenceArtifacts"))]
    selected_count = int(optional_float(run.get("selectedEvidenceCount")) or 0)
    candidate_count = int(optional_float(run.get("candidateCount")) or 0)
    if not selected or not all(_valid_selected_evidence(item) for item in selected):
        return False
    if selected_count <= 0 or selected_count != len(selected) or candidate_count < selected_count:
        return False
    if not text_list(run.get("knowledgeBaseIds")):
        return False
    if not as_text(run.get("qualityReportHash")):
        return False
    if int(optional_float(run.get("scopeViolationCount")) or 0) != 0:
        return False
    if consumer == "REQUIREMENT_BASE":
        return True

    stage = dict_value(stages.get(consumer))
    stage_run_id = as_text(stage.get("stageRunId"))
    if not stage_run_id or as_text(run.get("stageRunId")) != stage_run_id:
        return False
    context = _context_for_role(task_run, stages, consumer)
    if not context or as_text(context.get("retrievalRunId")) != as_text(run.get("runId")):
        return False
    context_evidence = [dict_value(item) for item in list_value(context.get("evidence"))]
    if not context_evidence or not all(_valid_selected_evidence(item) for item in context_evidence):
        return False
    selected_keys = {(_evidence_uri(item), as_text(item.get("contentHash") or item.get("hash"))) for item in selected}
    context_keys = {(_evidence_uri(item), as_text(item.get("contentHash") or item.get("hash"))) for item in context_evidence}
    return bool(selected_keys.intersection(context_keys))


def _role_specific_evidence_covered(
    role: str, task_run: dict[str, Any], stages: dict[str, Any]
) -> bool:
    context = _context_for_role(task_run, stages, role)
    expected_types = ROLE_SPECIFIC_EVIDENCE_TYPES.get(role, set())
    if not context or not expected_types:
        return False
    for raw in list_value(context.get("evidence")):
        evidence = dict_value(raw)
        evidence_type = as_text(evidence.get("requiredEvidenceType")).upper()
        shared_root = evidence.get("sharedRoot") is True or as_text(evidence.get("sharedRoot")).lower() == "true"
        if not shared_root and evidence_type in expected_types and _valid_selected_evidence(evidence):
            return True
    return False


def _selected_evidence_keys(run: dict[str, Any]) -> set[tuple[str, str]]:
    return {
        (_evidence_uri(item), as_text(item.get("contentHash") or item.get("hash")))
        for item in (dict_value(raw) for raw in list_value(run.get("selectedEvidenceArtifacts")))
        if _valid_selected_evidence(item)
    }


def _citation_integrity_rate(
    task_run: dict[str, Any],
    stages: dict[str, Any],
    expected_roles: list[str],
    latest_runs: dict[str, dict[str, Any]],
) -> float:
    total = 0
    valid = 0
    runs_by_id = {
        as_text(run.get("runId")): run
        for run in latest_runs.values()
        if as_text(run.get("runId"))
    }
    roles = expected_roles or [as_text(item.get("role")) for item in _task_run_context_views(task_run)]
    for role in dict.fromkeys(role for role in roles if role):
        context = _context_for_role(task_run, stages, role)
        if not context:
            continue
        run_id = as_text(context.get("retrievalRunId"))
        run = dict_value(runs_by_id.get(run_id) or latest_runs.get(role))
        selected_keys = _selected_evidence_keys(run)
        for raw in list_value(context.get("evidence")):
            evidence = dict_value(raw)
            total += 1
            key = (_evidence_uri(evidence), as_text(evidence.get("contentHash") or evidence.get("hash")))
            if run_id and run_id == as_text(run.get("runId")) and key in selected_keys:
                valid += 1
    return valid / total if total else 0.0


def _repository_identity(url: Any) -> tuple[str, str]:
    text = as_text(url).strip()
    if not text:
        return "", ""
    if text.startswith("git@") and ":" in text:
        host, path = text[4:].split(":", 1)
    else:
        parsed = urllib.parse.urlparse(text)
        host = parsed.hostname or ""
        path = parsed.path
    normalized_path = path.strip("/")
    if normalized_path.endswith(".git"):
        normalized_path = normalized_path[:-4]
    return host.lower(), normalized_path.lower()


def _evidence_out_of_scope(
    evidence: dict[str, Any], run: dict[str, Any], sample: dict[str, Any]
) -> bool:
    if evidence.get("scopeViolation") is True or as_text(evidence.get("scopeViolation")).lower() == "true":
        return True

    allowed_kbs = {value.lower() for value in text_list(run.get("knowledgeBaseIds"))}
    evidence_kb = as_text(evidence.get("knowledgeBaseId")).lower()
    if evidence_kb and allowed_kbs and evidence_kb not in allowed_kbs:
        return True

    uri = _evidence_uri(evidence)
    parsed = urllib.parse.urlparse(uri)
    if parsed.scheme.lower() in {"knowledge", "kb"}:
        uri_kb = urllib.parse.unquote(parsed.netloc).lower()
        if uri_kb and allowed_kbs and uri_kb not in allowed_kbs:
            return True

    task_input = dict_value(sample.get("input"))
    expected_project = as_text(task_input.get("projectId") or sample.get("projectId"))
    evidence_project = as_text(evidence.get("projectId"))
    if expected_project and evidence_project and expected_project != evidence_project:
        return True

    expected_repo = as_text(task_input.get("repositoryUrl"))
    if expected_repo and parsed.scheme.lower() in {"repo", "git", "github", "code"}:
        expected_host, expected_path = _repository_identity(expected_repo)
        normalized_uri = urllib.parse.unquote(uri).lower()
        if expected_host and expected_path and not (
            expected_host in normalized_uri and expected_path in normalized_uri
        ):
            return True
    return False


def _scope_leak_rate(
    sample: dict[str, Any], runs: list[dict[str, Any]]
) -> float:
    total = 0
    leaked = 0
    for run in runs:
        evidence_items = [dict_value(raw) for raw in list_value(run.get("selectedEvidenceArtifacts"))]
        total += len(evidence_items)
        uri_leaks = sum(1 for evidence in evidence_items if _evidence_out_of_scope(evidence, run, sample))
        explicit = max(0, int(optional_float(run.get("scopeViolationCount")) or 0))
        leaked += min(len(evidence_items), max(uri_leaks, explicit))
    return leaked / total if total else 0.0


def _critical_evidence_coverage_rate(
    sample: dict[str, Any],
    task_run: dict[str, Any],
    stages: dict[str, Any],
    expected_roles: list[str],
) -> float:
    task_input = dict_value(sample.get("input"))
    repository_required = bool(as_text(task_input.get("repositoryUrl")))
    acceptance_required = bool(text_list(task_input.get("acceptanceCriteria")))
    satisfied = 0
    total = 0
    for role in expected_roles:
        context = _context_for_role(task_run, stages, role)
        types = {
            as_text(evidence.get("requiredEvidenceType")).upper()
            for evidence in (dict_value(raw) for raw in list_value(context.get("evidence")))
            if _valid_selected_evidence(evidence)
        }
        required_groups: list[set[str]] = [{"REQUIREMENT_ROOT", "REQUIREMENT_MATERIAL"}]
        if repository_required:
            required_groups.append({"REPOSITORY_SCOPE", "REPOSITORY_INFO"})
        if acceptance_required:
            required_groups.append({"ACCEPTANCE_CRITERIA", "ACCEPTANCE_TARGET"})
        if role in ROLE_SPECIFIC_EVIDENCE_TYPES:
            required_groups.append(ROLE_SPECIFIC_EVIDENCE_TYPES[role])
        for group in required_groups:
            total += 1
            if types.intersection(group):
                satisfied += 1
    return satisfied / total if total else 0.0


def _context_noise_rate(
    sample: dict[str, Any], task_run: dict[str, Any]
) -> float:
    gold = dict_value(sample.get("task_run_gold"))
    forbidden = set(text_list(gold.get("forbiddenEvidenceUris")))
    evidence_items = [
        dict_value(raw)
        for context in _task_run_context_views(task_run)
        for raw in list_value(context.get("evidence"))
    ]

    def noisy(evidence: dict[str, Any]) -> bool:
        uri = _evidence_uri(evidence)
        relevance = optional_float(evidence.get("relevanceScore"))
        return (
            uri in forbidden
            or ("relevanceScore" in evidence and relevance is not None and relevance <= 0.0)
            or evidence.get("relevant") is False
            or evidence.get("irrelevant") is True
        )

    return sum(1 for evidence in evidence_items if noisy(evidence)) / len(evidence_items) if evidence_items else 0.0


def _context_duplicate_rate(task_run: dict[str, Any]) -> float:
    evidence_items = [
        dict_value(raw)
        for context in _task_run_context_views(task_run)
        for raw in list_value(context.get("evidence"))
    ]
    hashes: dict[str, int] = {}
    for evidence in evidence_items:
        shared = evidence.get("sharedRoot") is True or as_text(evidence.get("sharedRoot")).lower() == "true"
        reused = (
            evidence.get("reuseReference") is True
            or as_text(evidence.get("reuseReference")).lower() == "true"
            or bool(as_text(evidence.get("reuseReferenceId")))
        )
        content_hash = as_text(evidence.get("contentHash") or evidence.get("hash"))
        if content_hash and not shared and not reused:
            hashes[content_hash] = hashes.get(content_hash, 0) + 1
    duplicate_occurrences = sum(max(0, count - 1) for count in hashes.values())
    return duplicate_occurrences / len(evidence_items) if evidence_items else 0.0


def _provider_attempt_is_complete(stage_record: dict[str, Any]) -> bool:
    attempts = [dict_value(raw) for raw in list_value(stage_record.get("providerAttempts"))]
    if not attempts:
        return False

    success_attempts: list[dict[str, Any]] = []
    for attempt in attempts:
        provider = as_text(attempt.get("provider") or attempt.get("providerName"))
        status = as_text(attempt.get("status")).upper()
        attempt_no = int(optional_float(attempt.get("attempt") or attempt.get("attemptNo")) or 0)
        started = attempt.get("startedAtEpochMillis") or attempt.get("startedAt")
        finished = attempt.get("finishedAtEpochMillis") or attempt.get("finishedAt")
        if not provider or not status or attempt_no <= 0 or not started or not finished:
            return False
        started_number = optional_float(started)
        finished_number = optional_float(finished)
        if started_number is not None and finished_number is not None and finished_number < started_number:
            return False
        if status in {"FAILED", "ERROR", "TIMEOUT"} and not (
            as_text(attempt.get("errorCategory")) and as_text(attempt.get("errorMessage"))
        ):
            return False
        if status in {"SUCCESS", "SUCCEEDED"}:
            success_attempts.append(attempt)

    if as_text(stage_record.get("status")).upper() == "SUCCEEDED":
        if not success_attempts:
            return False
        expected_provider = as_text(stage_record.get("providerName") or stage_record.get("provider"))
        actual_provider = as_text(success_attempts[-1].get("provider") or success_attempts[-1].get("providerName"))
        return bool(expected_provider and expected_provider == actual_provider)
    return True


def _artifact_manifest(record: dict[str, Any], task_run: dict[str, Any]) -> list[dict[str, Any]]:
    source = record.get("artifactManifest") if "artifactManifest" in record else task_run.get("artifacts")
    return [dict_value(raw) for raw in list_value(source)]


def _artifact_resolves(uri: str, expected_hash: str, manifest: list[dict[str, Any]]) -> bool:
    if not uri or not expected_hash:
        return False
    for artifact in manifest:
        artifact_id = as_text(artifact.get("artifactId") or artifact.get("id"))
        artifact_uri = as_text(artifact.get("artifactUri") or artifact.get("uri"))
        artifact_hash = as_text(artifact.get("contentHash") or artifact.get("hash"))
        if uri in {artifact_uri, artifact_id, f"artifact://{artifact_id}"} and artifact_hash == expected_hash:
            return True
    return False


def _test_artifact_integrity_rate(record: dict[str, Any], task_run: dict[str, Any]) -> float:
    qa_result = stage_result(stage(record, "QA_AGENT"))
    acceptance = [
        dict_value(raw)
        for raw in list_value(qa_result.get("acceptanceResults") or qa_result.get("acceptance_results"))
    ]
    if not acceptance:
        return 0.0
    manifest = _artifact_manifest(record, task_run)
    valid = 0
    for item in acceptance:
        uri = as_text(item.get("logArtifactUri") or item.get("log_artifact_uri"))
        content_hash = as_text(item.get("logArtifactHash") or item.get("log_artifact_hash"))
        exit_code = optional_float(item.get("exitCode") if "exitCode" in item else item.get("exit_code"))
        if (
            as_text(item.get("status")).upper() == "PASSED"
            and bool(as_text(item.get("command")))
            and exit_code == 0.0
            and _artifact_resolves(uri, content_hash, manifest)
        ):
            valid += 1
    return valid / len(acceptance)


def _pr_integrity(sample: dict[str, Any], task_run: dict[str, Any]) -> bool:
    pull_request_url = as_text(task_run.get("pullRequestUrl"))
    parsed_pr = urllib.parse.urlparse(pull_request_url)
    path_match = re.fullmatch(r"/([^/]+)/([^/]+)/pull/([1-9][0-9]*)/?", parsed_pr.path)
    if parsed_pr.scheme.lower() != "https" or not parsed_pr.hostname or not path_match:
        return False

    task_input = dict_value(sample.get("input"))
    expected_repo = as_text(task_input.get("repositoryUrl"))
    if expected_repo:
        expected_host, expected_path = _repository_identity(expected_repo)
        actual_path = f"{path_match.group(1)}/{path_match.group(2)}".lower()
        if parsed_pr.hostname.lower() != expected_host or actual_path != expected_path:
            return False

    expected_base = as_text(task_input.get("baseBranch"))
    actual_base = as_text(task_run.get("baseBranch"))
    work_branch = as_text(task_run.get("workBranch"))
    commit_sha = as_text(task_run.get("commitSha"))
    if expected_base and expected_base != actual_base:
        return False
    return bool(actual_base and work_branch and re.fullmatch(r"[0-9a-fA-F]{7,64}", commit_sha))


def score_task_run(sample: dict[str, Any], record: dict[str, Any], sample_metrics: dict[str, Any], add_metric) -> None:
    """Score one persisted task execution snapshot without re-running the target repository."""
    gold = dict_value(sample.get("task_run_gold"))
    task_run = dict_value(record.get("task_run"))
    if not gold and not task_run:
        return

    task_status = as_text(task_run.get("taskStatus")).upper()
    expected_task_statuses = {status.upper() for status in text_list(gold.get("expectedTaskStatuses"))}
    successful_delivery_statuses = {"COMMITTED", "COMPLETED", "MERGED"}
    terminal_matches = (
        task_status in expected_task_statuses
        if expected_task_statuses
        else task_status in successful_delivery_statuses
    )
    delivery_expected = (
        not expected_task_statuses
        or bool(expected_task_statuses.intersection(successful_delivery_statuses))
    )
    add_metric(
        sample,
        record,
        sample_metrics,
        "task_terminal_success_rate",
        1.0 if terminal_matches else 0.0,
        "task did not reach an expected terminal state",
    )

    expected_roles = text_list(gold.get("expectedRoles"))
    stages = dict_value(record.get("stages"))
    if expected_roles:
        present = [role for role in expected_roles if dict_value(stages.get(role))]
        add_metric(
            sample, record, sample_metrics, "role_stage_coverage_rate",
            len(present) / len(expected_roles), "required role stage is missing",
        )
        add_metric(
            sample, record, sample_metrics, "stage_success_rate",
            sum(1 for role in expected_roles if as_text(dict_value(stages.get(role)).get("status")).upper() == "SUCCEEDED")
            / len(expected_roles),
            "latest role attempt did not succeed",
        )
        add_metric(
            sample, record, sample_metrics, "context_package_coverage_rate",
            sum(1 for role in expected_roles if as_text(dict_value(stages.get(role)).get("contextPackageId")))
            / len(expected_roles),
            "latest role attempt is missing its context package",
        )
        add_metric(
            sample, record, sample_metrics, "provider_attempt_coverage_rate",
            sum(1 for role in expected_roles if list_value(dict_value(stages.get(role)).get("providerAttempts")))
            / len(expected_roles),
            "latest role attempt is missing provider audit attempts",
        )
        add_metric(
            sample, record, sample_metrics, "provider_attempt_integrity_rate",
            sum(1 for role in expected_roles if _provider_attempt_is_complete(dict_value(stages.get(role))))
            / len(expected_roles),
            "latest role attempt has incomplete timing/error audit or its successful provider does not match the stage",
        )
        add_metric(
            sample, record, sample_metrics, "result_artifact_coverage_rate",
            sum(
                1 for role in expected_roles
                if as_text(dict_value(stages.get(role)).get("resultArtifactId"))
                and bool(dict_value(stages.get(role)).get("resultArtifactPresent"))
            )
            / len(expected_roles),
            "latest role attempt references a missing or unverifiable result artifact",
        )

    timeline = [dict_value(item) for item in list_value(task_run.get("timeline"))]
    timeline_terminal = as_text(timeline[-1].get("status")).upper() if timeline else ""
    add_metric(
        sample, record, sample_metrics, "task_timeline_terminal_rate",
        1.0 if task_status and timeline_terminal == task_status else 0.0,
        "task timeline does not end at the persisted task status",
    )

    latest_runs = _latest_retrieval_by_consumer(task_run)
    expected_consumers = text_list(gold.get("expectedRetrievalConsumers"))
    if expected_consumers:
        unique_consumers = list(dict.fromkeys(expected_consumers))
        successful_consumers = {
            consumer
            for consumer, run in latest_runs.items()
            if as_text(run.get("status")).upper() in {"SUCCEEDED", "SUCCEEDED_DEGRADED"}
        }
        add_metric(
            sample, record, sample_metrics, "retrieval_run_coverage_rate",
            len(set(unique_consumers).intersection(successful_consumers)) / len(unique_consumers),
            "required Deep RAG retrieval run is missing or unsuccessful",
        )
        add_metric(
            sample, record, sample_metrics, "retrieval_run_integrity_rate",
            sum(
                1
                for consumer in unique_consumers
                if _retrieval_integrity(
                    consumer, dict_value(latest_runs.get(consumer)), task_run, stages
                )
            ) / len(unique_consumers),
            "successful RetrievalRun is missing scoped evidence, quality audit, or immutable stage/context binding",
        )
        expected_run_views = [dict_value(latest_runs.get(consumer)) for consumer in unique_consumers]
        add_metric(
            sample, record, sample_metrics, "scope_leak_rate",
            _scope_leak_rate(sample, expected_run_views),
            "selected evidence escaped the allowed knowledge-base, project, or repository scope",
        )

    if expected_roles or _task_run_context_views(task_run):
        add_metric(
            sample, record, sample_metrics, "citation_integrity_rate",
            _citation_integrity_rate(task_run, stages, expected_roles, latest_runs),
            "context evidence does not match the URI/hash selected by its bound RetrievalRun",
        )
        add_metric(
            sample, record, sample_metrics, "context_noise_rate",
            _context_noise_rate(sample, task_run),
            "selected context contains forbidden or explicitly irrelevant evidence",
        )
        add_metric(
            sample, record, sample_metrics, "context_duplicate_rate",
            _context_duplicate_rate(task_run),
            "the same evidence body was copied across role contexts without a shared-root or reuse reference",
        )

    if expected_roles:
        add_metric(
            sample, record, sample_metrics, "critical_evidence_coverage_rate",
            _critical_evidence_coverage_rate(sample, task_run, stages, expected_roles),
            "one or more role contexts lack required task-root, repository, acceptance, code, architecture, or QA evidence",
        )

    role_specific = [role for role in expected_roles if role in ROLE_SPECIFIC_EVIDENCE_TYPES]
    if role_specific:
        add_metric(
            sample, record, sample_metrics, "role_specific_evidence_coverage_rate",
            sum(1 for role in role_specific if _role_specific_evidence_covered(role, task_run, stages))
            / len(role_specific),
            "downstream role has no independently selected evidence matching its responsibility",
        )

    if delivery_expected:
        add_metric(
            sample, record, sample_metrics, "test_evidence_coverage_rate",
            1.0 if int(optional_float(task_run.get("testEvidenceCount")) or 0) > 0 else 0.0,
            "task execution has no persisted test evidence",
        )
        if "QA_AGENT" in expected_roles or stage(record, "QA_AGENT"):
            add_metric(
                sample, record, sample_metrics, "test_artifact_integrity_rate",
                _test_artifact_integrity_rate(record, task_run),
                "QA pass cannot be reconciled to a command, zero exit code, and manifest-backed log URI/hash",
            )
        add_metric(
            sample, record, sample_metrics, "pull_request_present_rate",
            1.0 if as_text(task_run.get("pullRequestUrl")) else 0.0,
            "task execution has no pull request URL",
        )
        add_metric(
            sample, record, sample_metrics, "pr_integrity_rate",
            1.0 if _pr_integrity(sample, task_run) else 0.0,
            "pull request URL does not match the task repository or lacks base/work branch and commit evidence",
        )


def score_secret_leak(sample: dict[str, Any], record: dict[str, Any], sample_metrics: dict[str, Any], add_metric) -> None:
    blob = json_blob({"sample": sample, "record": record})
    leaked = any(pattern.search(blob) for pattern in SECRET_PATTERNS) or any(needle in blob for needle in secret_needles())
    add_metric(
        sample,
        record,
        sample_metrics,
        "secret_leak_rate",
        1.0 if leaked else 0.0,
        "secret-like value detected in eval dataset or record",
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
        metric.update(metric_definition(name))
        metrics.append(metric)
    return metrics


def local_metric_context(sample_metrics: dict[str, Any], reasons: dict[str, str]) -> list[dict[str, Any]]:
    """Project the just-computed deterministic values into the Judge input contract."""
    context: list[dict[str, Any]] = []
    for name, raw_value in sorted(sample_metrics.items()):
        value = optional_float(raw_value)
        if value is None:
            continue
        direction, threshold = THRESHOLDS.get(name, (None, None))
        definition = metric_definition(name)
        status = "OBSERVED"
        if direction and threshold is not None:
            status = "PASS" if threshold_passes(value, direction, threshold) else "FAILED"
        row: dict[str, Any] = {
            "name": name,
            "label": definition["label"],
            "value": value,
            "status": status,
            "purpose": definition["purpose"],
            "calculation": definition["calculation"],
        }
        if direction and threshold is not None:
            row["direction"] = direction
            row["threshold"] = threshold
        if status == "FAILED" and reasons.get(name):
            row["failureReason"] = reasons[name]
        context.append(row)
    return context


def judge_assessment_payload(assessment: "JudgeAssessment") -> dict[str, Any]:
    payload = {
        "headline": bounded_summary_text(assessment.headline, 240),
        "strengths": bounded_summary_list(assessment.strengths),
        "risks": bounded_summary_list(assessment.risks),
        "nextActions": bounded_summary_list(assessment.next_actions),
    }
    if assessment.prompt_metadata:
        payload["promptMetadata"] = dict(assessment.prompt_metadata)
    return payload


def build_judge_summary(
    provider_name: str,
    assessments: list["JudgeAssessment"],
    attempted_count: int,
    errors: list[str],
) -> dict[str, Any]:
    summaries = [judge_assessment_payload(assessment) for assessment in assessments]
    if summaries:
        status = "PARTIAL" if errors else "AVAILABLE"
        headline = next((summary["headline"] for summary in summaries if summary["headline"]), "")
        if not headline:
            headline = f"Judge 已完成 {len(summaries)} 个样本的结构化评分。"
        strengths = [item for summary in summaries for item in summary["strengths"]]
        risks = [item for summary in summaries for item in summary["risks"]]
        next_actions = [item for summary in summaries for item in summary["nextActions"]]
    elif errors:
        status = "FAILED"
        headline = "Judge 调用失败，未将模型评分计入本次指标。"
        strengths = []
        risks = ["Judge 调用失败，请查看执行日志。"]
        next_actions = ["检查 Judge 环境变量、网络连通性和返回 JSON 格式。"]
    else:
        status = "SKIPPED"
        headline = "本次未启用或未执行 Judge 评分。"
        strengths = []
        risks = []
        next_actions = []
    result = {
        "provider": bounded_summary_text(provider_name or "none", 80),
        "status": status,
        "evaluatedSampleCount": len(assessments),
        "attemptedSampleCount": attempted_count,
        "headline": bounded_summary_text(headline, 240),
        "strengths": bounded_summary_list(strengths),
        "risks": bounded_summary_list(risks),
        "nextActions": bounded_summary_list(next_actions),
    }
    prompt_metadata = next(
        (summary.get("promptMetadata") for summary in summaries if summary.get("promptMetadata")),
        None,
    )
    if prompt_metadata:
        result["promptMetadata"] = prompt_metadata
    return result


def build_evaluation_summary(
    metrics: list[dict[str, Any]],
    failures: list[dict[str, Any]],
    judge_provider: str,
    judge_summary: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Build a bounded, redacted, deterministic summary for people and APIs."""
    failed_metrics = [
        metric for metric in metrics
        if as_text(metric.get("status")).upper() in {"FAIL", "FAILED"}
    ]
    priority = {name: index for index, name in enumerate(FAILED_METRIC_PRIORITY)}
    failed_metrics.sort(
        key=lambda metric: (
            priority.get(as_text(metric.get("name")), len(priority)),
            as_text(metric.get("name")),
        )
    )
    passed_metrics = [
        metric for metric in metrics
        if as_text(metric.get("status")).upper() == "PASS"
    ]
    skipped_metrics = [
        metric for metric in metrics
        if as_text(metric.get("status")).upper() == "SKIPPED"
    ]
    failures_by_metric: dict[str, dict[str, Any]] = {}
    for failure in failures:
        metric_name = as_text(failure.get("metric"))
        if metric_name and metric_name not in failures_by_metric:
            failures_by_metric[metric_name] = failure

    failed_details: list[dict[str, Any]] = []
    for metric in failed_metrics[:8]:
        name = as_text(metric.get("name"))
        failure = failures_by_metric.get(name, {})
        definition = metric_definition(name)
        failed_details.append(
            {
                "name": name,
                "label": bounded_summary_text(metric.get("label") or definition["label"], 80),
                "reason": bounded_summary_text(failure.get("reason") or metric.get("reason") or "指标未达到门槛", 220),
                "nextAction": bounded_summary_text(failure.get("nextAction") or next_action(name), 220),
                "score": optional_float(metric.get("value")),
                "threshold": optional_float(metric.get("threshold")),
                "direction": as_text(metric.get("direction")),
            }
        )

    calculated_count = len([metric for metric in metrics if metric.get("value") is not None])
    if failed_details:
        labels = "、".join(item["label"] for item in failed_details[:3])
        headline = f"发现 {len(failed_metrics)} 项未达标指标，优先处理：{labels}。"
        overall_status = "NOT_OK"
    elif calculated_count:
        headline = f"已计算的 {calculated_count} 项指标均达到当前门槛。"
        overall_status = "PASS"
    else:
        headline = "本次运行未产生可计算指标，请检查数据集和录制记录。"
        overall_status = "NOT_OK"

    judge = judge_summary or build_judge_summary(judge_provider, [], 0, [])
    judge_status = as_text(judge.get("status") or "SKIPPED").upper()
    judge_requested = as_text(judge_provider).strip().lower() not in {"", "none", "noop", "skip"}
    context_incomplete = any(
        as_text(metric.get("name")) in CONTEXT_DEPENDENT_JUDGE_METRICS
        and as_text(metric.get("status")).upper() == "SKIPPED"
        and "missing evaluation context" in as_text(metric.get("reason"))
        for metric in metrics
    )
    judge_incomplete = judge_requested and (
        judge_status in {"FAILED", "PARTIAL", "SKIPPED"} or context_incomplete
    )
    if judge_incomplete:
        overall_status = "INCOMPLETE"
        headline = "Judge 评分未完整完成，当前结果不能作为通过结论。"
        gate_status = "INCOMPLETE"
    elif overall_status == "PASS":
        gate_status = "PASSED"
    else:
        gate_status = "NOT_PASSED"

    return {
        "overallStatus": overall_status,
        "overallPassed": gate_status == "PASSED",
        "gateStatus": gate_status,
        "judgeStatus": judge_status,
        "headline": bounded_summary_text(headline, 240),
        "localMetricCount": calculated_count,
        "passedMetricCount": len(passed_metrics),
        "failedMetricCount": len(failed_metrics),
        "skippedMetricCount": len(skipped_metrics),
        "failedMetrics": failed_details,
        "judge": judge,
    }


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
    metrics = [dict_value(metric) for metric in list_value(score.get("metrics"))]
    failures = [dict_value(failure) for failure in list_value(score.get("failures"))]
    summary = dict_value(score.get("summary")) or build_evaluation_summary(
        metrics,
        failures,
        as_text(score.get("judge_provider")),
    )
    judge = dict_value(summary.get("judge"))
    lines = [
        f"# RD Eval Report: {as_text(score.get('run_id'))}",
        "",
        "## 简要评测报告",
        "",
        f"- 结论: `{bounded_summary_text(summary.get('overallStatus') or 'UNKNOWN', 40)}`",
        f"- 摘要: {bounded_summary_text(summary.get('headline') or '未生成摘要。', 240)}",
        "- 本地指标: 已计算 `{}`，通过 `{}`，未达标 `{}`，跳过 `{}`。".format(
            int(optional_float(summary.get("localMetricCount")) or 0),
            int(optional_float(summary.get("passedMetricCount")) or 0),
            int(optional_float(summary.get("failedMetricCount")) or 0),
            int(optional_float(summary.get("skippedMetricCount")) or 0),
        ),
        "- Judge: `{}`，provider=`{}`，已评样本 `{}`。".format(
            bounded_summary_text(judge.get("status") or "SKIPPED", 40),
            bounded_summary_text(judge.get("provider") or "none", 80),
            int(optional_float(judge.get("evaluatedSampleCount")) or 0),
        ),
        f"- Judge 摘要: {bounded_summary_text(judge.get('headline') or '未生成 Judge 摘要。', 240)}",
        "",
        "### 未达标项",
        "",
    ]
    failed_metrics = [dict_value(item) for item in list_value(summary.get("failedMetrics"))]
    if not failed_metrics:
        lines.append("当前没有门禁失败指标。")
    else:
        for failed in failed_metrics:
            label = bounded_summary_text(failed.get("label") or failed.get("name"), 100)
            reason = bounded_summary_text(failed.get("reason") or "未达到门槛", 220)
            action = bounded_summary_text(failed.get("nextAction") or "查看 failures.jsonl", 220)
            lines.append(f"- `{as_text(failed.get('name'))}`（{label}）：{reason}。建议：{action}")
    judge_strengths = bounded_summary_list(judge.get("strengths"))
    judge_risks = bounded_summary_list(judge.get("risks"))
    judge_actions = bounded_summary_list(judge.get("nextActions"))
    if judge_strengths or judge_risks or judge_actions:
        lines.extend(["", "### Judge 重点", ""])
        for strength in judge_strengths:
            lines.append(f"- 优点：{strength}")
        for risk in judge_risks:
            lines.append(f"- 风险：{risk}")
        for action in judge_actions:
            lines.append(f"- 建议：{action}")
    lines.extend([
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
    ])
    for metric in metrics:
        threshold = ""
        if "threshold" in metric:
            threshold = f"{metric.get('direction')} {metric.get('threshold')}"
        value = metric.get("value")
        lines.append(
            f"| `{metric.get('name')}` | {'' if value is None else value} | {threshold} | {metric.get('status')} | {metric.get('sampleCount', 0)} |"
        )
    lines.extend(["", "## Failures", ""])
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


@dataclass(frozen=True)
class JudgeAssessment:
    """Validated judge scores plus a compact narrative safe for persistence."""

    scores: dict[str, float] = field(default_factory=dict)
    headline: str = ""
    strengths: list[str] = field(default_factory=list)
    risks: list[str] = field(default_factory=list)
    next_actions: list[str] = field(default_factory=list)
    prompt_metadata: dict[str, Any] = field(default_factory=dict)

    @classmethod
    def from_legacy_scores(cls, scores: dict[str, Any]) -> "JudgeAssessment":
        return cls(scores=normalize_judge_scores(scores, strict=False))


def normalize_judge_scores(value: Any, *, strict: bool) -> dict[str, float]:
    scores = dict_value(value)
    normalized: dict[str, float] = {}
    for name in JUDGE_METRIC_NAMES:
        raw = optional_float(scores.get(name))
        if raw is None:
            if strict:
                raise ValueError(f"judge response is missing numeric score: {name}")
            continue
        if raw < 0.0 or raw > 1.0:
            raise ValueError(f"judge score is outside 0..1: {name}")
        normalized[name] = round(raw, 6)
    return normalized


def bounded_summary_list(value: Any, max_items: int = 3, max_chars: int = 180) -> list[str]:
    if not isinstance(value, list):
        return []
    values = [bounded_summary_text(item, max_chars) for item in value]
    return [item for item in dedupe(values) if item][:max_items]


def parse_openai_judge_assessment(value: Any) -> JudgeAssessment:
    parsed = dict_value(value)
    score_payload = dict_value(parsed.get("scores")) or parsed
    return JudgeAssessment(
        scores=normalize_judge_scores(score_payload, strict=True),
        headline=bounded_summary_text(parsed.get("headline"), 240),
        strengths=bounded_summary_list(parsed.get("strengths")),
        risks=bounded_summary_list(parsed.get("risks")),
        next_actions=bounded_summary_list(parsed.get("nextActions")),
    )


def safe_local_metric_context(local_metrics: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Keep the deterministic scoring evidence compact before it enters a model prompt."""
    safe_metrics: list[dict[str, Any]] = []
    for metric in local_metrics[:80]:
        row = dict_value(metric)
        name = bounded_summary_text(row.get("name"), 120)
        if not name:
            continue
        safe_row: dict[str, Any] = {
            "name": name,
            "label": bounded_summary_text(row.get("label") or metric_definition(name)["label"], 120),
            "value": optional_float(row.get("value")),
            "status": bounded_summary_text(row.get("status"), 40),
            "purpose": bounded_summary_text(row.get("purpose") or metric_definition(name)["purpose"], 240),
            "calculation": bounded_summary_text(row.get("calculation") or metric_definition(name)["calculation"], 300),
        }
        threshold = optional_float(row.get("threshold"))
        if threshold is not None:
            safe_row["threshold"] = threshold
        direction = bounded_summary_text(row.get("direction"), 8)
        if direction:
            safe_row["direction"] = direction
        reason = bounded_summary_text(row.get("failureReason") or row.get("reason"), 220)
        if reason:
            safe_row["failureReason"] = reason
        safe_metrics.append(redacted_copy(safe_row))
    return safe_metrics


@dataclass
class _JudgeXmlTextSlot:
    value: str
    max_bytes: int

    @classmethod
    def from_value(cls, value: Any, max_bytes: int) -> "_JudgeXmlTextSlot":
        text = _judge_xml_text(value)
        return cls(text, min(utf8_byte_length(text), max(0, max_bytes)))

    def is_truncated(self) -> bool:
        return utf8_byte_length(self.value) > self.max_bytes


def _judge_xml_slot(value: Any, max_bytes: int, scale: float) -> _JudgeXmlTextSlot:
    return _JudgeXmlTextSlot.from_value(value, max(0, int(max_bytes * scale)))


def utf8_byte_length(value: str) -> int:
    return len(value.encode("utf-8"))


def truncate_utf8(value: str, max_bytes: int) -> str:
    if max_bytes <= 0 or not value:
        return ""
    encoded = value.encode("utf-8")
    if len(encoded) <= max_bytes:
        return value
    truncated = encoded[:max_bytes].decode("utf-8", errors="ignore")
    # XML content is escaped before it reaches this helper. Never leave a partial
    # entity such as "&am" at the byte boundary, otherwise the prompt is invalid XML.
    last_entity_start = truncated.rfind("&")
    if last_entity_start >= 0 and ";" not in truncated[last_entity_start:]:
        truncated = truncated[:last_entity_start]
    return truncated


def _judge_xml_text(value: Any) -> str:
    """Redact then escape one textual value for the outbound Judge document."""
    normalized = as_text(redact(value)).replace("[REDACTED]", "<redacted>")
    return html.escape(normalized, quote=True)


def _judge_xml_attrs(attributes: dict[str, Any]) -> str:
    return "".join(
        f' {key}="{_judge_xml_text(bounded_summary_text(value, 160))}"'
        for key, value in attributes.items()
        if as_text(value)
    )


def _judge_xml_omitted(original_bytes: int, included_bytes: int) -> str:
    return (
        '<omitted reason="input_budget"'
        f' original_bytes="{original_bytes}" included_bytes="{included_bytes}"/>'
    )


def _judge_xml_text_element(
    name: str,
    slot: _JudgeXmlTextSlot,
    attributes: dict[str, Any] | None = None,
    *,
    include_omission: bool = True,
) -> str:
    open_tag = f"<{name}{_judge_xml_attrs(attributes or {})}>"
    close_tag = f"</{name}>"
    if not slot.is_truncated():
        return open_tag + slot.value + close_tag
    included = truncate_utf8(slot.value, slot.max_bytes)
    rendered = open_tag + included + close_tag
    if not include_omission:
        return rendered
    return rendered + _judge_xml_omitted(utf8_byte_length(slot.value), utf8_byte_length(included))


def _judge_xml_json(value: Any) -> str:
    return json.dumps(redacted_copy(value), ensure_ascii=False, sort_keys=True)


def _task_run_expected_roles(sample: dict[str, Any]) -> list[str]:
    gold = dict_value(sample.get("task_run_gold"))
    return [
        bounded_summary_text(role, 120)
        for role in list_value(gold.get("expectedRoles"))
        if as_text(role)
    ]


def _task_run_role_stage(record: dict[str, Any], role: str) -> dict[str, Any]:
    return dict_value(dict_value(record.get("stages")).get(role))


def _task_run_retrieval_runs(record: dict[str, Any], role: str) -> list[dict[str, Any]]:
    runs = list_value(dict_value(record.get("task_run")).get("retrievalRuns"))
    return [dict_value(run) for run in runs if as_text(dict_value(run).get("consumerKey")) == role]


def _task_run_result_text(stage: dict[str, Any]) -> str:
    result = stage.get("result")
    if isinstance(result, dict):
        return _judge_xml_json(result)
    return as_text(redacted_copy(result or stage.get("resultPreview")))


def _task_run_input_parts(sample: dict[str, Any], scale: float) -> tuple[str, list[_JudgeXmlTextSlot]]:
    source = dict_value(sample.get("input"))
    fields = (
        ("taskId", 160),
        ("taskType", 120),
        ("title", 500),
        ("priority", 80),
        ("repositoryUrl", 500),
        ("baseBranch", 160),
        ("ticketId", 160),
        ("ticketTitle", 500),
        ("expectedResult", 2_800),
    )
    slots: list[_JudgeXmlTextSlot] = []
    rendered = ["<task_input>"]
    for name, limit in fields:
        if not as_text(source.get(name)):
            continue
        slot = _judge_xml_slot(source.get(name), limit, scale)
        slots.append(slot)
        rendered.append(_judge_xml_text_element(name, slot))
    criteria = list_value(source.get("acceptanceCriteria"))[:20]
    if criteria:
        rendered.append("<acceptance_criteria>")
        for criterion in criteria:
            slot = _judge_xml_slot(criterion, 360, scale)
            slots.append(slot)
            rendered.append(_judge_xml_text_element("criterion", slot))
        rendered.append("</acceptance_criteria>")
    rendered.append("</task_input>")
    return "".join(rendered), slots


def _task_run_metric_parts(
    local_metrics: list[dict[str, Any]], record: dict[str, Any], scale: float
) -> tuple[str, list[_JudgeXmlTextSlot]]:
    slots: list[_JudgeXmlTextSlot] = []
    rendered = ["<rag_evaluation_parameters>"]
    for metric in safe_local_metric_context(local_metrics):
        attrs = {
            "name": metric.get("name"),
            "label": metric.get("label"),
            "value": metric.get("value"),
            "status": metric.get("status"),
            "direction": metric.get("direction"),
            "threshold": metric.get("threshold"),
        }
        rendered.append(f"<metric{_judge_xml_attrs(attrs)}>")
        for name, value, limit in (
            ("purpose", metric.get("purpose"), 240),
            ("calculation", metric.get("calculation"), 300),
            ("failure_reason", metric.get("failureReason"), 220),
        ):
            if not as_text(value):
                continue
            slot = _judge_xml_slot(value, limit, scale)
            slots.append(slot)
            rendered.append(_judge_xml_text_element(name, slot))
        rendered.append("</metric>")
    for group in selected_evaluation_context_groups(record)[:6]:
        rendered.append(
            f"<retrieval_evidence{_judge_xml_attrs({
                'consumer': group.get('consumer') or group.get('consumerKey'),
                'run_id': group.get('runId'),
                'stage_run_id': group.get('stageRunId'),
                'status': group.get('status'),
            })}>"
        )
        for evidence in list_value(group.get("evidence"))[:4]:
            item = dict_value(evidence)
            rendered.append(
                f"<evidence{_judge_xml_attrs({
                    'id': item.get('evidenceId') or item.get('artifactId'),
                    'source_type': item.get('sourceType'),
                    'uri': _evidence_uri(item),
                    'hash': item.get('contentHash') or item.get('hash'),
                    'required_type': item.get('requiredEvidenceType'),
                    'shared_root': item.get('sharedRoot'),
                    'relevance_score': item.get('relevanceScore'),
                })}>"
            )
            for name, value, limit in (
                ("title", item.get("title"), 220),
                ("preview", item.get("contentPreview") or item.get("summary"), 650),
                ("selection_reason", item.get("selectionReason"), 260),
            ):
                if not as_text(value):
                    continue
                slot = _judge_xml_slot(value, limit, scale)
                slots.append(slot)
                rendered.append(_judge_xml_text_element(name, slot))
            rendered.append("</evidence>")
        rendered.append("</retrieval_evidence>")
    rendered.append("</rag_evaluation_parameters>")
    return "".join(rendered), slots


def _task_run_role_parts(
    record: dict[str, Any], roles: list[str], scale: float
) -> tuple[str, list[_JudgeXmlTextSlot], list[_JudgeXmlTextSlot]]:
    detail_slots: list[_JudgeXmlTextSlot] = []
    result_slots: list[_JudgeXmlTextSlot] = []
    rendered = ["<role_execution_results>"]
    for role in roles:
        stage = _task_run_role_stage(record, role)
        if not stage:
            rendered.append(f'<role name="{_judge_xml_text(role)}" status="MISSING"><missing_stage/></role>')
            continue
        attrs = {
            "name": role,
            "status": stage.get("status") or "UNKNOWN",
            "attempt_no": stage.get("attemptNo"),
            "provider": stage.get("providerName"),
            "context_package_id": stage.get("contextPackageId"),
            "result_present": str(bool(stage.get("resultArtifactPresent"))).lower(),
        }
        rendered.append(f"<role{_judge_xml_attrs(attrs)}>")
        rendered.append("<provider_attempts>")
        for attempt in list_value(stage.get("providerAttempts"))[:4]:
            slot = _judge_xml_slot(_judge_xml_json(dict_value(attempt)), 320, scale)
            detail_slots.append(slot)
            rendered.append(_judge_xml_text_element("attempt", slot))
        rendered.append("</provider_attempts><retrieval_runs>")
        for retrieval in _task_run_retrieval_runs(record, role)[:4]:
            slot = _judge_xml_slot(_judge_xml_json(retrieval), 360, scale)
            detail_slots.append(slot)
            rendered.append(_judge_xml_text_element("retrieval_run", slot))
        rendered.append("</retrieval_runs>")
        result_slot = _judge_xml_slot(_task_run_result_text(stage), 2_000, scale)
        result_slots.append(result_slot)
        rendered.append(_judge_xml_text_element("result", result_slot))
        if as_text(stage.get("errorMessage")):
            error_slot = _judge_xml_slot(stage.get("errorMessage"), 500, scale)
            detail_slots.append(error_slot)
            rendered.append(_judge_xml_text_element("error", error_slot, {"category": stage.get("errorCategory")}))
        rendered.append("</role>")
    task_run = dict_value(record.get("task_run"))
    delivery_attrs = {
        "task_status": task_run.get("taskStatus") or record.get("final_status"),
        "test_evidence_count": task_run.get("testEvidenceCount"),
    }
    rendered.append(f"<delivery_result{_judge_xml_attrs(delivery_attrs)}>")
    if as_text(task_run.get("pullRequestUrl")):
        delivery_url = _judge_xml_slot(task_run.get("pullRequestUrl"), 500, scale)
        detail_slots.append(delivery_url)
        rendered.append(_judge_xml_text_element("pull_request_url", delivery_url))
    delivery_result = _judge_xml_slot(record.get("response"), 1_500, scale)
    result_slots.append(delivery_result)
    rendered.append(_judge_xml_text_element("result", delivery_result))
    rendered.append("</delivery_result></role_execution_results>")
    return "".join(rendered), detail_slots, result_slots


def build_task_run_judge_xml_prompt(
    sample: dict[str, Any],
    record: dict[str, Any],
    local_metrics: list[dict[str, Any]],
    input_budget_bytes: int = JUDGE_TASK_RUN_XML_INPUT_BUDGET_BYTES,
) -> str:
    """Compile a redacted task-run evaluation into the fixed three-section Judge contract."""
    if input_budget_bytes <= 0:
        raise ValueError("task-run judge XML input budget must be positive")
    safe_sample = dict_value(redacted_copy(sample))
    safe_record = dict_value(redacted_copy(record))
    roles = _task_run_expected_roles(safe_sample)
    root_open = (
        f'<rd_bot_task_evaluation version="{JUDGE_XML_VERSION}"'
        f' input_budget_bytes="{input_budget_bytes}"'
        f' output_budget_tokens="{JUDGE_MAX_COMPLETION_TOKENS}">'
    )
    root_close = "</rd_bot_task_evaluation>"

    def render(scale: float) -> str:
        task, _ = _task_run_input_parts(safe_sample, scale)
        metrics, _ = _task_run_metric_parts(local_metrics, safe_record, scale)
        role_results, _, _ = _task_run_role_parts(safe_record, roles, scale)
        return root_open + task + metrics + role_results + root_close

    prompt = render(1.0)
    if utf8_byte_length(prompt) <= input_budget_bytes:
        return prompt

    minimal_prompt = render(0.0)
    if utf8_byte_length(minimal_prompt) > input_budget_bytes:
        raise ValueError("task-run judge XML structure exceeds input budget")

    scale = 1.0
    # Role result slots are generated from the same scale, so each populated role
    # loses capacity evenly rather than whichever map entry happens to serialize first.
    for _ in range(24):
        scale *= 0.72
        prompt = render(scale)
        if utf8_byte_length(prompt) <= input_budget_bytes:
            return prompt
    return minimal_prompt


class JudgeProvider:
    name = "base"
    enabled = False

    def evaluate(self, sample: dict[str, Any], record: dict[str, Any]) -> dict[str, float]:
        return {}

    def evaluate_with_context(
        self,
        sample: dict[str, Any],
        record: dict[str, Any],
        local_metrics: list[dict[str, Any]],
    ) -> JudgeAssessment:
        return JudgeAssessment.from_legacy_scores(self.evaluate(sample, record))


class NoopJudgeProvider(JudgeProvider):
    name = "none"
    enabled = False


class OpenAICompatibleJudgeProvider(JudgeProvider):
    name = "openai-compatible"
    enabled = True

    def __init__(self, base_url: str, api_key: str, model: str, timeout_seconds: float = 60.0):
        self.base_url = openai_chat_completions_url(base_url)
        self.api_key = api_key
        self.model = model
        self.timeout_seconds = timeout_seconds

    @classmethod
    def from_env(
        cls,
        env: dict[str, str] | None = None,
        config_path: Path | str | None = None,
    ) -> "OpenAICompatibleJudgeProvider":
        api_key = resolve_openai_judge_api_key(env)
        if not api_key:
            raise ValueError(f"missing judge environment variable(s): {OPENAI_JUDGE_API_KEY_ENV}")
        base_url, model = load_openai_judge_config(config_path)
        return cls(base_url=base_url, api_key=api_key, model=model)

    def evaluate(self, sample: dict[str, Any], record: dict[str, Any]) -> dict[str, float]:
        return self.evaluate_with_context(sample, record, []).scores

    def evaluate_with_context(
        self,
        sample: dict[str, Any],
        record: dict[str, Any],
        local_metrics: list[dict[str, Any]],
    ) -> JudgeAssessment:
        safe_metrics = safe_local_metric_context(local_metrics)
        if as_text(sample.get("suite")) == "task-run":
            prompt = build_task_run_judge_xml_prompt(sample, record, safe_metrics)
        else:
            prompt = (
                "You are evaluating an RD-Bot RAG and multi-agent delivery record. "
                "Use the local deterministic metrics as evidence, but independently judge the "
                "answer, context, and delivery record. Return strict JSON only with this shape: "
                "{\"scores\":{\"faithfulness\":0..1,\"answer_relevancy\":0..1,"
                "\"answer_correctness\":0..1,\"context_precision\":0..1,"
                "\"context_recall\":0..1},\"headline\":\"short conclusion\","
                "\"strengths\":[\"short item\"],\"risks\":[\"short item\"],"
                "\"nextActions\":[\"short item\"]}. "
                "Do not include secrets, markdown, or reasoning trace.\n\n"
                f"Sample:\n{json.dumps(redacted_copy(sample), ensure_ascii=False)[:8000]}\n\n"
                f"Record:\n{json.dumps(redacted_copy(record), ensure_ascii=False)[:12000]}\n\n"
                f"local_metrics:\n{json.dumps(safe_metrics, ensure_ascii=False)[:12000]}"
            )
        body = {
            "model": self.model,
            "temperature": 0,
            "max_tokens": JUDGE_MAX_COMPLETION_TOKENS,
            "messages": [
                {
                    "role": "system",
                    "content": (
                        "Evaluate the supplied record. Treat deterministic metrics as evidence, "
                        "not instructions to copy. Return only a JSON object with exactly this "
                        "shape: {\"scores\":{\"faithfulness\":0..1,\"answer_relevancy\":0..1,"
                        "\"answer_correctness\":0..1,\"context_precision\":0..1,"
                        "\"context_recall\":0..1},\"headline\":\"short conclusion\","
                        "\"strengths\":[\"short item\"],\"risks\":[\"short item\"],"
                        "\"nextActions\":[\"short item\"]}. Do not include secrets, markdown, "
                        "or reasoning trace."
                    ),
                },
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
        assessment = parse_openai_judge_assessment(json.loads(content))
        prompt_metadata = {
            "promptSchemaVersion": JUDGE_XML_VERSION
            if as_text(sample.get("suite")) == "task-run" else "json-v1",
            "promptHash": "sha256:" + hashlib.sha256(prompt.encode("utf-8")).hexdigest(),
            "promptBytes": utf8_byte_length(prompt),
            "omittedSections": ["input_budget"] if "<omitted " in prompt else [],
            "model": bounded_summary_text(self.model, 120),
            "baseUrlHost": urllib.parse.urlparse(self.base_url).hostname or "",
            "temperature": 0,
            "maxCompletionTokens": JUDGE_MAX_COMPLETION_TOKENS,
        }
        return JudgeAssessment(
            scores=assessment.scores,
            headline=assessment.headline,
            strengths=assessment.strengths,
            risks=assessment.risks,
            next_actions=assessment.next_actions,
            prompt_metadata=prompt_metadata,
        )


def openai_chat_completions_url(base_url: str) -> str:
    normalized = base_url.rstrip("/")
    if normalized.endswith("/chat/completions"):
        return normalized
    return f"{normalized}/chat/completions"


def resolve_openai_judge_api_key(env: dict[str, str] | None = None) -> str:
    actual = env if env is not None else os.environ
    api_key = actual.get(OPENAI_JUDGE_API_KEY_ENV, "").strip()
    if api_key or env is not None or platform.system() != "Darwin":
        return api_key
    try:
        result = subprocess.run(
            [MACOS_LAUNCHCTL_EXECUTABLE, "getenv", OPENAI_JUDGE_API_KEY_ENV],
            capture_output=True,
            check=False,
            text=True,
            timeout=MACOS_LAUNCHCTL_TIMEOUT_SECONDS,
        )
    except (OSError, subprocess.TimeoutExpired):
        return ""
    return (result.stdout or "").strip() if result.returncode == 0 else ""


def load_openai_judge_config(config_path: Path | str | None = None) -> tuple[str, str]:
    path = Path(config_path) if config_path is not None else DEFAULT_OPENAI_JUDGE_CONFIG_PATH
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as exception:
        raise ValueError(f"cannot read OpenAI Judge config: {path.name}") from exception

    values: dict[str, str] = {}
    for line_number, raw_line in enumerate(lines, start=1):
        stripped = raw_line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        if raw_line != raw_line.lstrip():
            raise ValueError(f"invalid OpenAI Judge config indentation at line {line_number}")
        key, separator, raw_value = raw_line.partition(":")
        key = key.strip()
        value = raw_value.strip()
        if not separator or key not in OPENAI_JUDGE_CONFIG_KEYS:
            raise ValueError(f"invalid OpenAI Judge config key at line {line_number}")
        if key in values:
            raise ValueError(f"duplicate OpenAI Judge config key: {key}")
        if value.startswith(("\"", "'")):
            if len(value) < 2 or value[-1] != value[0]:
                raise ValueError(f"invalid quoted OpenAI Judge config value at line {line_number}")
            value = value[1:-1]
        else:
            value = value.split(" #", 1)[0].strip()
        if not value:
            raise ValueError(f"missing OpenAI Judge config value: {key}")
        values[key] = value

    missing = [key for key in OPENAI_JUDGE_CONFIG_KEYS if not values.get(key)]
    if missing:
        raise ValueError("missing OpenAI Judge config value(s): " + ", ".join(missing))
    return values["RD_EVAL_JUDGE_BASE_URL"], values["RD_EVAL_JUDGE_MODEL"]


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
    if metric.startswith("retrieval_run"):
        return "在任务详情打开 RAG 检索，补齐每个 consumer 的成功 RetrievalRun、证据包和引用"
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


def bounded_summary_text(value: Any, max_chars: int = 240) -> str:
    """Redact narrative fields and keep summaries safe for a compact UI payload."""
    normalized = " ".join(as_text(redact(value)).replace("\r", " ").replace("\n", " ").split())
    if len(normalized) <= max_chars:
        return normalized
    return normalized[:max_chars - 1].rstrip() + "..."


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
