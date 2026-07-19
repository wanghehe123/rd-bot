#!/usr/bin/env python3
"""Build the versioned RD-Bot quality benchmark and its separate fixture records.

The gold rows deliberately contain no evaluated output. Fixture records live in a
different directory so scorer self-tests cannot accidentally become quality gold.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
DATASET_ID = "rd_eval_quality_v1"
VERSION = "1.0.0"
ANNOTATION_VERSION = "2026-07-14.1"
DATASET_PATH = ROOT / "scripts/evaluation/datasets/rd_eval_quality_v1.jsonl"
RECORDS_PATH = ROOT / "scripts/evaluation/fixtures/records/rd_eval_quality_v1.records.jsonl"
ROLES = ["REQUIREMENT_REVIEWER", "SOLUTION_ARCHITECT", "CODING_AGENT", "QA_AGENT"]
EXPERIENCE_TYPES = [
    "REQUIREMENT_REVIEW",
    "TECHNICAL_DESIGN",
    "CODE_CHANGE",
    "QA_REPORT",
    "DELIVERY_REPORT",
]


def gold_row(sample_id: str, suite: str, scenario: str, difficulty: str, **payload: Any) -> dict[str, Any]:
    return {
        "sample_id": sample_id,
        "suite": suite,
        "scenario": scenario,
        "difficulty": difficulty,
        "dataset_metadata": {
            "datasetId": DATASET_ID,
            "datasetKind": "QUALITY_BENCHMARK",
            "version": VERSION,
            "annotationVersion": ANNOTATION_VERSION,
            "goldRecordSeparation": "physical",
            "annotationSource": "RD-Bot repository behavior and acceptance specifications",
        },
        **payload,
    }


def artifact(artifact_id: str) -> dict[str, str]:
    return {
        "artifactId": artifact_id,
        "artifactUri": f"artifact://{artifact_id}",
        "contentHash": f"sha256:{artifact_id}",
    }


def rag_rows() -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    cases = [
        ("single-fact-payment-state", "easy", "payment", ["code://waimai/payment/PaymentCallbackService.java#markPaid"]),
        ("multi-hop-order-inventory", "hard", "order", ["code://waimai/order/OrderService.java#create", "code://waimai/inventory/InventoryService.java#reserve"]),
        ("negative-coupon-rule", "hard", "coupon", ["knowledge://waimai-kb/coupon-rules#non-stackable"]),
        ("compare-delivery-fees", "medium", "delivery", ["knowledge://waimai-kb/delivery-fee#merchant", "knowledge://waimai-kb/delivery-fee#platform"]),
        ("code-and-log-payment-timeout", "hard", "payment", ["code://waimai/payment/PaymentClient.java#timeout", "log://waimai/payment/timeout-20260714"]),
        ("knowledge-map-document-expansion", "hard", "catalog", ["knowledge://waimai-kb/catalog/map-node-17", "knowledge://waimai-kb/catalog/document-4#chunks-8-11"]),
        ("noisy-history-filtering", "medium", "order", ["knowledge://waimai-kb/order/idempotency"]),
        ("missing-evidence-safe-answer", "medium", "unknown", []),
        ("context-budget-selection", "hard", "product", ["code://waimai/product/ProductController.java#update"]),
        ("partial-vector-channel-failure", "hard", "order", ["knowledge://waimai-kb/order/state-machine"]),
        ("cross-project-isolation", "hard", "payment", ["knowledge://waimai-kb/payment/callback"]),
        ("path-traversal-rejection", "hard", "security", ["knowledge://waimai-kb/security/path-policy"]),
        ("prompt-injection-document", "hard", "security", ["knowledge://waimai-kb/security/prompt-boundary"]),
        ("mixed-chinese-english-query", "medium", "product", ["code://waimai/product/ProductService.java#batchUpdate"]),
        ("api-contract-search", "medium", "product", ["knowledge://waimai-kb/openapi/product-admin#put-product"]),
        ("database-schema-search", "medium", "order", ["knowledge://waimai-kb/schema/orders#status"]),
        ("frontend-route-search", "medium", "product", ["code://waimai/frontend/routes.ts#product-admin"]),
        ("test-entry-search", "medium", "qa", ["code://waimai/tests/ProductAdminE2ETest.java#save"]),
        ("retry-refined-query", "hard", "payment", ["code://waimai/payment/PaymentCallbackService.java#verifySignature"]),
        ("bm25-vector-fusion", "hard", "order", ["knowledge://waimai-kb/order/cancel-policy", "code://waimai/order/OrderCancelService.java#cancel"]),
    ]
    samples: list[dict[str, Any]] = []
    records: list[dict[str, Any]] = []
    for index, (scenario, difficulty, intent, evidence_uris) in enumerate(cases, 1):
        sample_id = f"QB-RAG-{index:03d}"
        required_terms = [intent.upper(), "RD-Bot"] if evidence_uris else ["证据不足"]
        samples.append(gold_row(
            sample_id,
            "rag",
            scenario,
            difficulty,
            input={
                "title": scenario.replace("-", " "),
                "description": f"质量基线场景：{scenario}",
                "projectId": "waimai-project",
            },
            rag_gold={
                "expectedIntentSystemId": intent,
                "mustEvidenceUris": evidence_uris,
                "requiredTerms": required_terms,
                "forbiddenTerms": ["other-project", "ignore previous instructions"],
                **({"requiresRag": False} if not evidence_uris else {}),
            },
            tags=["quality-benchmark", "rag", scenario],
        ))
        chunks = [
            {
                "chunkId": f"{sample_id}-chunk-{rank}",
                "sourceName": uri,
                "knowledgeBaseId": "waimai-kb",
                "knowledgeType": "quality-evidence",
                "score": round(1.0 - rank * 0.01, 3),
                "content": f"RD-Bot {intent.upper()} evidence from {uri}",
            }
            for rank, uri in enumerate(evidence_uris, 1)
        ]
        records.append({
            "sample_id": sample_id,
            "task_id": f"rag-task-{index:03d}",
            "status": "CONTEXT_READY" if evidence_uris else "FAILED_NEEDS_HUMAN",
            "response": (
                f"RD-Bot 已基于 {intent.upper()} 证据完成回答。"
                if evidence_uris else "证据不足，已阻断无依据回答。"
            ),
            "reference": f"Expected behavior for {scenario}",
            "final_status": "success" if evidence_uris else "needs_input",
            "retrieved_contexts": [chunk["content"] for chunk in chunks],
            "rag": {
                "traceId": f"trace-{sample_id}",
                "primaryIntentSystemId": intent,
                "primaryIntentName": intent,
                "searchChannels": ["bm25", "vector", "knowledge-map"],
                "retrievedEvidenceUris": evidence_uris,
                "retrievedChunks": chunks,
                "contextSummary": f"Selected {len(evidence_uris)} auditable evidence item(s).",
            },
            "stages": {},
            "alerts": [],
        })
    return samples, records


def provider_attempt(provider: str = "quality-provider", attempt: int = 1) -> dict[str, Any]:
    return {
        "provider": provider,
        "status": "SUCCESS",
        "attempt": attempt,
        "startedAtEpochMillis": 1_783_950_000_000 + attempt * 100,
        "finishedAtEpochMillis": 1_783_950_000_050 + attempt * 100,
    }


def role_rows() -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    samples: list[dict[str, Any]] = []
    records: list[dict[str, Any]] = []

    reviewer_cases = [
        ("reviewer-approve-complete", "APPROVED", False, ["并发一致性", "回滚风险"]),
        ("reviewer-approve-safe-change", "APPROVED", False, ["兼容旧接口"]),
        ("reviewer-wait-missing-logs", "NEED_INFO", True, ["缺少回调日志", "缺少订单号"]),
        ("reviewer-block-unsafe-secret", "UNSAFE", True, ["凭证暴露", "生产副作用"]),
    ]
    for index, (scenario, decision, blocked, risks) in enumerate(reviewer_cases, 1):
        sample_id = f"QB-ROLE-REVIEW-{index:02d}"
        samples.append(gold_row(
            sample_id, "requirement", scenario, "medium",
            input={"title": scenario, "projectId": "waimai-project"},
            stage_gold={"REQUIREMENT_REVIEWER": {
                "expectedDecision": decision,
                "mustMentionRisks": risks,
                "mustBlockDownstream": blocked,
            }},
            tags=["quality-benchmark", "role", "REQUIREMENT_REVIEWER", "blocked" if blocked else "success"],
        ))
        records.append({
            "sample_id": sample_id,
            "task_id": f"role-review-{index}",
            "status": "FAILED_NEEDS_HUMAN" if blocked else "CONTEXT_READY",
            "stages": {"REQUIREMENT_REVIEWER": {
                "status": "FAILED_NEEDS_HUMAN" if blocked else "SUCCEEDED",
                "attemptNo": 1,
                "providerName": "quality-provider",
                "providerAttempts": [provider_attempt()],
                "resultArtifactUri": f"artifact://{sample_id}/review.json",
                "resultJson": {"decision": decision, "risks": risks},
            }},
            "alerts": [],
        })

    solution_files = [
        "server/src/main/java/com/example/product/ProductController.java",
        "server/src/test/java/com/example/product/ProductControllerTest.java",
    ]
    for index in range(1, 5):
        blocked = index > 2
        scenario = f"architect-{'blocked-upstream' if blocked else 'design-product-admin'}-{index}"
        sample_id = f"QB-ROLE-ARCH-{index:02d}"
        if blocked:
            samples.append(gold_row(
                sample_id, "solution", scenario, "medium",
                input={"title": scenario, "projectId": "waimai-project"},
                stage_gold={"REQUIREMENT_REVIEWER": {
                    "expectedDecision": "NEED_INFO",
                    "mustMentionRisks": ["缺少验收标准"],
                    "mustBlockDownstream": True,
                }},
                role_block_gold={
                    "blockedRole": "SOLUTION_ARCHITECT",
                    "expectedTaskStatuses": ["FAILED_NEEDS_HUMAN"],
                    "requiredGateRole": "REQUIREMENT_REVIEWER",
                    "expectedGateStatuses": ["FAILED_NEEDS_HUMAN"],
                },
                tags=["quality-benchmark", "role", "SOLUTION_ARCHITECT", "blocked"],
            ))
            records.append({
                "sample_id": sample_id,
                "task_id": f"role-arch-{index}",
                "status": "FAILED_NEEDS_HUMAN",
                "stages": {"REQUIREMENT_REVIEWER": {
                    "status": "FAILED_NEEDS_HUMAN",
                    "resultJson": {"decision": "NEED_INFO", "risks": ["缺少验收标准"]},
                }},
                "alerts": [],
            })
            continue
        samples.append(gold_row(
            sample_id, "solution", scenario, "medium",
            input={"title": scenario, "projectId": "waimai-project"},
            stage_gold={"SOLUTION_ARCHITECT": {
                "mustSections": ["affectedFiles", "implementationSteps", "acceptanceMapping", "testPlan"],
                "mustAcceptanceIds": ["AC-1", "AC-2"],
                "expectedAffectedFiles": solution_files,
            }},
            tags=["quality-benchmark", "role", "SOLUTION_ARCHITECT", "success"],
        ))
        records.append({
            "sample_id": sample_id,
            "task_id": f"role-arch-{index}",
            "status": "PLAN_GENERATED",
            "stages": {"SOLUTION_ARCHITECT": {
                "status": "SUCCEEDED",
                "resultJson": {
                    "affectedFiles": solution_files,
                    "implementationSteps": ["修改接口", "补充回归测试"],
                    "acceptanceMapping": {"AC-1": ["接口返回 200"], "AC-2": ["页面刷新可见"]},
                    "testPlan": ["./mvnw -q -pl bootstrap test"],
                },
            }},
            "alerts": [],
        })

    coding_files = [
        "server/src/main/java/com/example/product/ProductService.java",
        "server/src/test/java/com/example/product/ProductServiceTest.java",
    ]
    test_command = "./mvnw -q -pl product -Dtest=ProductServiceTest test"
    for index in range(1, 5):
        blocked = index > 2
        scenario = f"coding-{'blocked-design' if blocked else 'product-change'}-{index}"
        sample_id = f"QB-ROLE-CODE-{index:02d}"
        if blocked:
            samples.append(gold_row(
                sample_id, "coding", scenario, "medium",
                input={"title": scenario, "projectId": "waimai-project"},
                role_block_gold={
                    "blockedRole": "CODING_AGENT",
                    "expectedTaskStatuses": ["FAILED_NEEDS_HUMAN"],
                    "requiredGateRole": "SOLUTION_ARCHITECT",
                    "expectedGateStatuses": ["FAILED_NEEDS_HUMAN"],
                },
                tags=["quality-benchmark", "role", "CODING_AGENT", "blocked"],
            ))
            records.append({
                "sample_id": sample_id,
                "task_id": f"role-code-{index}",
                "status": "FAILED_NEEDS_HUMAN",
                "stages": {"SOLUTION_ARCHITECT": {"status": "FAILED_NEEDS_HUMAN"}},
                "alerts": [],
            })
            continue
        samples.append(gold_row(
            sample_id, "coding", scenario, "medium",
            input={"title": scenario, "projectId": "waimai-project"},
            stage_gold={"CODING_AGENT": {
                "expectedChangedFiles": coding_files,
                "requiredTestCommands": [test_command],
                "forbiddenChangedFiles": [".env", "bootstrap/src/main/resources/application.yaml"],
            }},
            tags=["quality-benchmark", "role", "CODING_AGENT", "success"],
        ))
        records.append({
            "sample_id": sample_id,
            "task_id": f"role-code-{index}",
            "status": "VALIDATING",
            "stages": {"CODING_AGENT": {
                "status": "SUCCEEDED",
                "resultJson": {
                    "changedFiles": coding_files,
                    "testCommands": [{"command": test_command, "status": "PASSED"}],
                },
            }},
            "alerts": [],
        })

    for index in range(1, 5):
        blocked = index > 2
        scenario = f"qa-{'blocks-delivery' if blocked else 'product-acceptance'}-{index}"
        sample_id = f"QB-ROLE-QA-{index:02d}"
        expected_status = "FAILED" if blocked else "PASSED"
        log_id = f"{sample_id.lower()}-log"
        samples.append(gold_row(
            sample_id, "qa", scenario, "hard" if blocked else "medium",
            input={"title": scenario, "projectId": "waimai-project"},
            stage_gold={"QA_AGENT": {
                "mustRunRealCommands": True,
                "expectedAcceptanceStatuses": [expected_status],
                "maxSkippedAcceptanceCount": 0,
            }},
            **({"role_block_gold": {
                "blockedRole": "DELIVERY",
                "expectedTaskStatuses": ["FAILED_NEEDS_HUMAN"],
                "requiredGateRole": "QA_AGENT",
                "expectedGateStatuses": ["FAILED_NEEDS_HUMAN"],
            }} if blocked else {}),
            tags=["quality-benchmark", "role", "QA_AGENT", "blocked" if blocked else "success"],
        ))
        records.append({
            "sample_id": sample_id,
            "task_id": f"role-qa-{index}",
            "status": "FAILED_NEEDS_HUMAN" if blocked else "COMPLETED",
            "stages": {"QA_AGENT": {
                "status": "FAILED_NEEDS_HUMAN" if blocked else "SUCCEEDED",
                "resultJson": {"acceptanceResults": [{
                    "id": "AC-1",
                    "status": expected_status,
                    "command": "npm --prefix frontend run build",
                    "exitCode": 1 if blocked else 0,
                    "logArtifactUri": f"artifact://{log_id}",
                    "logArtifactHash": f"sha256:{log_id}",
                }]},
            }},
            "artifactManifest": [artifact(log_id)],
            "alerts": ([{"type": "QA_BLOCKED"}] if blocked else []),
        })
    return samples, records


def e2e_rows() -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    cases = [
        ("successful-delivery", "COMPLETED", [], []),
        ("waiting-input", "FAILED_NEEDS_HUMAN", ["MATERIAL_REQUIRED"], []),
        ("provider-fallback", "COMPLETED", ["PROVIDER_FALLBACK"], []),
        ("stage-retry", "COMPLETED", ["AGENT_STAGE_RETRY"], []),
        ("lease-recovery", "COMPLETED", ["TASK_RECOVERED"], []),
        ("cancelled", "CANCELLED", ["TASK_CANCELLED"], []),
        ("qa-blocked", "FAILED_NEEDS_HUMAN", ["QA_BLOCKED"], []),
        ("scope-violation-rejected", "FAILED_NEEDS_HUMAN", ["RAG_SCOPE_VIOLATION"], []),
    ]
    samples: list[dict[str, Any]] = []
    records: list[dict[str, Any]] = []
    for index, (scenario, status, expected_alerts, forbidden_alerts) in enumerate(cases, 1):
        sample_id = f"QB-E2E-{index:03d}"
        success = status == "COMPLETED"
        payload: dict[str, Any] = {
            "input": {"title": scenario, "projectId": "waimai-project"},
            "alert_gold": {
                "expectedAlertTypes": expected_alerts,
                "forbiddenAlertTypes": ["SECRET_LEAK_DETECTED", *forbidden_alerts],
            },
            "tags": ["quality-benchmark", "e2e", scenario],
        }
        if success:
            payload["delivery_gold"] = {"requiredExperienceTypes": EXPERIENCE_TYPES}
        else:
            payload["role_block_gold"] = {
                "blockedRole": "DELIVERY",
                "expectedTaskStatuses": [status],
            }
        samples.append(gold_row(sample_id, "e2e", scenario, "hard", **payload))
        records.append({
            "sample_id": sample_id,
            "task_id": f"e2e-task-{index}",
            "status": status,
            "stages": {},
            "delivery": ({
                "pullRequestUrl": f"https://github.com/example/waimai/pull/{100 + index}",
                "deliveryReviewApproved": True,
                "experienceTypes": EXPERIENCE_TYPES,
                "redacted": True,
            } if success else {}),
            "alerts": [{"type": alert_type, "taskId": f"e2e-task-{index}"} for alert_type in expected_alerts],
        })
    return samples, records


def task_success(sample_id: str, project_id: str, repository_url: str, kb_id: str, pr_number: int) -> tuple[dict[str, Any], dict[str, Any]]:
    task_id = sample_id.lower()
    evidence_type = {
        "REQUIREMENT_REVIEWER": "REQUIREMENT_MATERIAL",
        "SOLUTION_ARCHITECT": "ARCHITECTURE",
        "CODING_AGENT": "CODE_SYMBOL",
        "QA_AGENT": "TEST_CASE",
    }
    shared = [
        {
            "evidenceId": f"{task_id}-requirement-root",
            "sourceType": "TASK_MATERIAL",
            "sourceUri": f"task-material://{task_id}/requirement",
            "contentHash": f"sha256:{task_id}-requirement-root",
            "requiredEvidenceType": "REQUIREMENT_ROOT",
            "sharedRoot": True,
            "relevanceScore": 1.0,
        },
        {
            "evidenceId": f"{task_id}-repository-root",
            "sourceType": "REPOSITORY",
            "sourceUri": f"repo://{repository_url.removeprefix('https://')}",
            "contentHash": f"sha256:{task_id}-repository-root",
            "requiredEvidenceType": "REPOSITORY_SCOPE",
            "sharedRoot": True,
            "relevanceScore": 1.0,
        },
        {
            "evidenceId": f"{task_id}-acceptance-root",
            "sourceType": "ACCEPTANCE_CRITERIA",
            "sourceUri": f"task://{task_id}/acceptance",
            "contentHash": f"sha256:{task_id}-acceptance-root",
            "requiredEvidenceType": "ACCEPTANCE_CRITERIA",
            "sharedRoot": True,
            "relevanceScore": 1.0,
        },
    ]
    role_evidence = {
        role: {
            "evidenceId": f"{task_id}-{role.lower()}-evidence",
            "sourceType": "KNOWLEDGE",
            "sourceUri": f"knowledge://{kb_id}/{role.lower()}",
            "knowledgeBaseId": kb_id,
            "projectId": project_id,
            "contentHash": f"sha256:{task_id}-{role.lower()}",
            "contentPreview": f"Project-scoped evidence for {role}",
            "selectionReason": "role-specific critical evidence",
            "requiredEvidenceType": evidence_type[role],
            "sharedRoot": False,
            "relevanceScore": 0.95,
        }
        for role in ROLES
    }
    stages: dict[str, Any] = {}
    contexts: list[dict[str, Any]] = []
    retrieval_runs: list[dict[str, Any]] = [{
        "runId": f"{task_id}-run-base",
        "consumerKey": "REQUIREMENT_BASE",
        "status": "SUCCEEDED",
        "attemptNo": 1,
        "knowledgeBaseIds": [kb_id],
        "candidateCount": 3,
        "selectedEvidenceCount": 1,
        "selectedEvidenceArtifacts": [role_evidence["REQUIREMENT_REVIEWER"]],
        "qualityReportHash": f"sha256:{task_id}-quality-base",
        "scopeViolationCount": 0,
    }]
    for role_index, role in enumerate(ROLES, 1):
        stage_id = f"{task_id}-stage-{role.lower()}"
        context_id = f"{task_id}-context-{role.lower()}"
        run_id = f"{task_id}-run-{role.lower()}"
        selected = [*shared, role_evidence[role]]
        stages[role] = {
            "status": "SUCCEEDED",
            "stageRunId": stage_id,
            "contextPackageId": context_id,
            "resultArtifactId": f"{task_id}-result-{role.lower()}",
            "resultArtifactPresent": True,
            "providerName": "quality-provider",
            "providerAttempts": [provider_attempt(attempt=role_index)],
        }
        contexts.append({
            "packageId": context_id,
            "role": role,
            "retrievalRunId": run_id,
            "evidence": selected,
        })
        retrieval_runs.append({
            "runId": run_id,
            "consumerKey": role,
            "role": role,
            "stageRunId": stage_id,
            "status": "SUCCEEDED",
            "attemptNo": 1,
            "knowledgeBaseIds": [kb_id],
            "candidateCount": 6,
            "selectedEvidenceCount": len(selected),
            "selectedEvidenceArtifacts": selected,
            "qualityReportHash": f"sha256:{task_id}-quality-{role.lower()}",
            "scopeViolationCount": 0,
        })
    qa_log_id = f"{task_id}-qa-log"
    stages["QA_AGENT"]["resultJson"] = {"acceptanceResults": [{
        "id": "AC-1",
        "status": "PASSED",
        "command": "./mvnw -q test",
        "exitCode": 0,
        "logArtifactUri": f"artifact://{qa_log_id}",
        "logArtifactHash": f"sha256:{qa_log_id}",
    }]}
    sample = gold_row(
        sample_id, "task-run", f"task-run-success-{project_id}", "hard",
        input={
            "taskId": task_id,
            "taskType": "REQUIREMENT",
            "title": f"Deliver project {project_id}",
            "projectId": project_id,
            "repositoryUrl": repository_url,
            "baseBranch": "main",
            "acceptanceCriteria": ["All tests pass"],
        },
        task_run_gold={
            "expectedTaskStatuses": ["MERGED"],
            "expectedRoles": ROLES,
            "expectedRetrievalConsumers": ["REQUIREMENT_BASE", *ROLES],
            "forbiddenEvidenceUris": ["knowledge://foreign-kb/secret"],
        },
        tags=["quality-benchmark", "task-run", "success", project_id],
    )
    record = {
        "sample_id": sample_id,
        "task_id": task_id,
        "status": "RECORDED",
        "final_status": "success",
        "stages": stages,
        "task_run": {
            "taskStatus": "MERGED",
            "taskType": "REQUIREMENT",
            "timeline": [{"status": "CREATED"}, {"status": "MERGED"}],
            "contexts": contexts,
            "retrievalRuns": retrieval_runs,
            "artifacts": [artifact(qa_log_id)],
            "testEvidenceCount": 1,
            "pullRequestUrl": f"{repository_url}/pull/{pr_number}",
            "baseBranch": "main",
            "workBranch": f"rd/{task_id}",
            "commitSha": "0123456789abcdef0123456789abcdef01234567",
        },
    }
    return sample, record


def task_run_rows() -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    samples: list[dict[str, Any]] = []
    records: list[dict[str, Any]] = []
    for args in [
        ("QB-TASK-001", "waimai-project", "https://github.com/example/waimai", "waimai-kb", 201),
        ("QB-TASK-002", "retail-project", "https://github.com/example/retail", "retail-kb", 202),
    ]:
        sample, record = task_success(*args)
        samples.append(sample)
        records.append(record)
    blocked_cases = [
        ("QB-TASK-003", "waimai-project", "FAILED_NEEDS_HUMAN", "missing-role-evidence"),
        ("QB-TASK-004", "retail-project", "CANCELLED", "operator-cancelled"),
    ]
    for sample_id, project_id, status, reason in blocked_cases:
        task_id = sample_id.lower()
        samples.append(gold_row(
            sample_id, "task-run", f"task-run-{reason}-{project_id}", "hard",
            input={
                "taskId": task_id,
                "taskType": "REQUIREMENT",
                "title": reason,
                "projectId": project_id,
                "repositoryUrl": f"https://github.com/example/{project_id.removesuffix('-project')}",
                "baseBranch": "main",
            },
            task_run_gold={
                "expectedTaskStatuses": [status],
                "expectedRoles": [],
                "expectedRetrievalConsumers": [],
            },
            tags=["quality-benchmark", "task-run", "expected-block", project_id],
        ))
        records.append({
            "sample_id": sample_id,
            "task_id": task_id,
            "status": "RECORDED",
            "final_status": "expected-block",
            "stages": {},
            "task_run": {
                "taskStatus": status,
                "taskType": "REQUIREMENT",
                "timeline": [{"status": "CREATED"}, {"status": status}],
                "contexts": [],
                "retrievalRuns": [],
                "artifacts": [],
                "testEvidenceCount": 0,
                "pullRequestUrl": "",
                "stopReason": reason,
            },
        })
    return samples, records


def build() -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    samples: list[dict[str, Any]] = []
    records: list[dict[str, Any]] = []
    for builder in (rag_rows, role_rows, e2e_rows, task_run_rows):
        batch_samples, batch_records = builder()
        samples.extend(batch_samples)
        records.extend(batch_records)
    if len(samples) != 48 or len(records) != 48:
        raise AssertionError(f"quality benchmark must contain 48 samples/records, got {len(samples)}/{len(records)}")
    sample_ids = [row["sample_id"] for row in samples]
    record_ids = [row["sample_id"] for row in records]
    if len(set(sample_ids)) != len(sample_ids) or set(sample_ids) != set(record_ids):
        raise AssertionError("gold and record sample IDs must be unique and identical")
    if any("fixture_record" in row for row in samples):
        raise AssertionError("quality gold must not embed fixture records")
    return samples, records


def write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = "".join(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n" for row in rows)
    path.write_text(payload, encoding="utf-8")


def main() -> int:
    samples, records = build()
    write_jsonl(DATASET_PATH, samples)
    write_jsonl(RECORDS_PATH, records)
    print(f"{DATASET_PATH} ({len(samples)} gold rows)")
    print(f"{RECORDS_PATH} ({len(records)} fixture records)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
