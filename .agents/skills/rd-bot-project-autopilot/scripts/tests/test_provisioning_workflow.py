from __future__ import annotations

import hashlib
import tempfile
import unittest
from pathlib import Path
from typing import Any

from scripts.github_client import GitHubTransportError
from scripts.iteration_state import ManifestError, ManifestStore, RunStatus, create_provision_manifest
from scripts.provisioning import build_provision_plan
from scripts.rd_bot_client import ApiTransportError
from scripts.workflow import AutopilotWorkflow, ProjectProvisioningWorkflow, WorkflowError


PROJECT_ID = "7480000000000000000"


def provision_plan() -> dict[str, Any]:
    return build_provision_plan(
        run_id="autopilot-web-123",
        idea="Build a habit tracker web page",
        success_criteria=["The page supports one complete habit check-in", "A focused automated test passes"],
        github_owner="alice",
    )


class GitHubSpy:
    def __init__(self, plan: dict[str, Any]) -> None:
        self.plan = plan
        self.create_calls: list[dict[str, str]] = []
        self.get_calls = 0
        self.create_error: Exception | None = None

    def authenticated_user(self) -> str:
        return "alice"

    def create_private_repository(self, *, owner: str, name: str, description: str) -> dict[str, Any]:
        self.create_calls.append({"owner": owner, "name": name, "description": description})
        if self.create_error:
            raise self.create_error
        return self.repository()

    def get_repository(self, owner: str, name: str) -> dict[str, Any]:
        self.get_calls += 1
        if owner != self.plan["repository"]["owner"] or name != self.plan["repository"]["name"]:
            raise AssertionError("unexpected repository reconciliation target")
        return self.repository()

    def repository(self) -> dict[str, Any]:
        repository = self.plan["repository"]
        return {
            "owner": {"login": repository["owner"]},
            "name": repository["name"],
            "full_name": f"{repository['owner']}/{repository['name']}",
            "html_url": f"https://github.com/{repository['owner']}/{repository['name']}",
            "private": True,
            "default_branch": "main",
            "description": repository["description"],
        }


class RdBotSpy:
    def __init__(self, plan: dict[str, Any]) -> None:
        self.plan = plan
        self.knowledge_base_calls: list[dict[str, Any]] = []
        self.document_calls: list[dict[str, Any]] = []
        self.project_calls: list[dict[str, Any]] = []
        self.documents: list[dict[str, Any]] = []
        self.knowledge_base = {
            "id": "kb-1",
            "name": plan["knowledgeBase"]["name"],
            "description": plan["knowledgeBase"]["description"],
            "enabled": True,
        }
        self.project: dict[str, Any] | None = None
        self.document_error_for: str | None = None

    def preflight_knowledge_base(self, _payload: dict[str, Any]) -> None:
        return None

    def create_knowledge_base(self, payload: dict[str, Any]) -> dict[str, Any]:
        self.knowledge_base_calls.append(payload)
        return dict(self.knowledge_base)

    def list_knowledge_bases(self, name: str | None = None, page: int = 1, page_size: int = 100) -> dict[str, Any]:
        del page, page_size
        records = [self.knowledge_base] if name == self.knowledge_base["name"] else []
        return {"records": records}

    def get_knowledge_base(self, knowledge_base_id: str) -> dict[str, Any]:
        if knowledge_base_id != self.knowledge_base["id"]:
            raise AssertionError("unexpected knowledge base reconciliation target")
        return dict(self.knowledge_base)

    def preflight_knowledge_document(self, _knowledge_base_id: str, _payload: dict[str, Any]) -> None:
        return None

    def write_knowledge_document(self, knowledge_base_id: str, payload: dict[str, Any]) -> dict[str, Any]:
        self.document_calls.append(payload)
        if self.document_error_for == payload["sourceName"]:
            raise ApiTransportError("connection reset", ambiguous=True)
        document = {
            "id": f"doc-{len(self.documents) + 1}",
            "knowledgeBaseId": knowledge_base_id,
            "sourceName": payload["sourceName"],
            "knowledgeType": payload["knowledgeType"],
            "mimeType": payload["mimeType"],
            "checksum": hashlib.sha256(payload["content"].encode("utf-8")).hexdigest(),
            "rawPreview": payload["content"][:2_000],
        }
        self.documents.append(document)
        return dict(document)

    def list_knowledge_documents(
        self,
        knowledge_base_id: str,
        keyword: str | None = None,
        page: int = 1,
        page_size: int = 100,
    ) -> dict[str, Any]:
        del knowledge_base_id, keyword, page, page_size
        return {"records": [dict(document) for document in self.documents]}

    def preflight_project(self, _payload: dict[str, Any]) -> None:
        return None

    def create_project(self, payload: dict[str, Any]) -> dict[str, Any]:
        self.project_calls.append(payload)
        self.project = {"projectId": PROJECT_ID, **payload}
        return dict(self.project)

    def list_projects(self, keyword: str | None = None, page: int = 1, page_size: int = 100) -> dict[str, Any]:
        del page, page_size
        records = [self.project] if self.project and keyword == self.project["projectKey"] else []
        return {"records": records}

    def get_project(self, project_id: str) -> dict[str, Any]:
        if not self.project or project_id != self.project["projectId"]:
            raise AssertionError("unexpected project reconciliation target")
        return dict(self.project)


class ProjectProvisioningWorkflowTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.plan = provision_plan()
        self.store = ManifestStore(Path(self.temp.name) / "run")
        self.store.initialize(create_provision_manifest(
            self.plan["runId"],
            "live-provision",
            self.plan["idea"],
            self.plan["successCriteria"],
        ))
        self.store.confirm_provision(self.store.freeze_provision_plan(self.plan))
        self.github = GitHubSpy(self.plan)
        self.rd_bot = RdBotSpy(self.plan)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def test_provisions_private_repository_knowledge_sources_and_project_intent_first(self) -> None:
        result = ProjectProvisioningWorkflow(
            self.store,
            self.github,
            self.rd_bot,
            live_enabled=True,
        ).provision()

        self.assertEqual(RunStatus.READY.value, result["status"])
        self.assertEqual(1, len(self.github.create_calls))
        self.assertEqual(1, len(self.rd_bot.knowledge_base_calls))
        self.assertEqual(2, len(self.rd_bot.document_calls))
        self.assertEqual(1, len(self.rd_bot.project_calls))
        self.assertEqual(PROJECT_ID, result["projectId"])
        self.assertEqual(
            [document["sha256"] for document in self.plan["documents"]],
            [source["sha256"] for source in result["provisioning"]["resources"]["sources"]],
        )

    def test_provisioned_run_can_freeze_its_first_iteration_plan_once(self) -> None:
        result = ProjectProvisioningWorkflow(
            self.store,
            self.github,
            self.rd_bot,
            live_enabled=True,
        ).provision()
        self.assertEqual(RunStatus.READY.value, result["status"])

        iteration_plan = {
            **self.plan["iterationPlan"],
            "materials": [],
            "tokenBudgetOverride": 20_000,
        }
        frozen = AutopilotWorkflow(self.store, None).freeze_plan(iteration_plan)

        self.assertEqual(RunStatus.READY.value, frozen["status"])
        self.assertEqual(1, len(frozen["iterations"]))
        self.assertEqual(20_000, frozen["iterations"][0]["plan"]["tokenBudgetOverride"])
        with self.assertRaisesRegex(ManifestError, "cannot freeze plan from READY"):
            AutopilotWorkflow(self.store, None).freeze_plan(iteration_plan)

    def test_ambiguous_github_create_reconciles_once_without_a_second_write(self) -> None:
        self.github.create_error = GitHubTransportError("timeout", ambiguous=True)

        result = ProjectProvisioningWorkflow(
            self.store,
            self.github,
            self.rd_bot,
            live_enabled=True,
        ).provision()

        self.assertEqual(RunStatus.READY.value, result["status"])
        self.assertEqual(1, len(self.github.create_calls))
        self.assertEqual(1, self.github.get_calls)

    def test_ambiguous_source_without_two_exact_matches_waits_without_resending(self) -> None:
        self.rd_bot.document_error_for = "delivery-brief.md"

        result = ProjectProvisioningWorkflow(
            self.store,
            self.github,
            self.rd_bot,
            live_enabled=True,
        ).provision()

        self.assertEqual(RunStatus.WAITING_HUMAN.value, result["status"])
        self.assertEqual("AMBIGUOUS_SOURCE_WRITE", result["pendingApproval"]["type"])
        self.assertEqual(["project-charter.md", "delivery-brief.md"], [item["sourceName"] for item in self.rd_bot.document_calls])
        self.assertEqual([], result["provisioning"]["resources"]["sources"])

    def test_dry_run_never_touches_github_or_rd_bot(self) -> None:
        dry_store = ManifestStore(Path(self.temp.name) / "dry")
        dry_store.initialize(create_provision_manifest(
            self.plan["runId"],
            "dry-run",
            self.plan["idea"],
            self.plan["successCriteria"],
        ))
        dry_store.freeze_provision_plan(self.plan)
        result = ProjectProvisioningWorkflow(dry_store, None, None, live_enabled=False).provision()

        self.assertEqual(RunStatus.DRY_RUN_COMPLETED.value, result["status"])

    def test_live_provision_requires_the_explicit_runtime_gate(self) -> None:
        workflow = ProjectProvisioningWorkflow(self.store, self.github, self.rd_bot, live_enabled=False)
        with self.assertRaisesRegex(WorkflowError, "--live-provision"):
            workflow.provision()
        self.assertEqual([], self.github.create_calls)


if __name__ == "__main__":
    unittest.main()
