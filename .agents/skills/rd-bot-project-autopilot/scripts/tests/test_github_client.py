from __future__ import annotations

import subprocess
import unittest

from scripts.github_client import GitHubClient, GitHubPolicyError, GitHubTransportError


class RecordingRunner:
    def __init__(self, responses: list[object]) -> None:
        self.responses = list(responses)
        self.calls: list[list[str]] = []

    def __call__(self, argv: list[str], timeout_seconds: float) -> subprocess.CompletedProcess[str]:
        del timeout_seconds
        self.calls.append(list(argv))
        response = self.responses.pop(0)
        if isinstance(response, BaseException):
            raise response
        assert isinstance(response, subprocess.CompletedProcess)
        return response


def completed(argv: list[str], code: int, stdout: str, stderr: str) -> subprocess.CompletedProcess[str]:
    return subprocess.CompletedProcess(argv, code, stdout=stdout, stderr=stderr)


class GitHubClientTest(unittest.TestCase):
    def test_creates_only_a_private_auto_initialized_personal_repository(self) -> None:
        runner = RecordingRunner([
            completed(["gh", "auth", "status"], 0, "", ""),
            completed(["gh", "api", "user"], 0, '{"login":"alice"}', ""),
            completed(
                [],
                0,
                '{"full_name":"alice/habit-tracker-web-123","private":true,"owner":{"login":"alice"},"default_branch":"main"}',
                "",
            ),
        ])
        client = GitHubClient(runner=runner)

        repository = client.create_private_repository(
            owner="alice",
            name="habit-tracker-web-123",
            description="[autopilot:autopilot-web-123:github:123456abcdef] generated project",
        )

        self.assertTrue(repository["private"])
        self.assertEqual("alice", repository["owner"]["login"])
        self.assertEqual(
            [
                ["gh", "auth", "status"],
                ["gh", "api", "user"],
                [
                    "gh", "api", "--method", "POST", "/user/repos",
                    "-f", "name=habit-tracker-web-123",
                    "-f", "description=[autopilot:autopilot-web-123:github:123456abcdef] generated project",
                    "-F", "private=true", "-F", "auto_init=true",
                ],
            ],
            runner.calls,
        )

    def test_rejects_owner_mismatch_non_private_response_and_invalid_repository_name(self) -> None:
        runner = RecordingRunner([
            completed(["gh", "auth", "status"], 0, "", ""),
            completed(["gh", "api", "user"], 0, '{"login":"alice"}', ""),
        ])
        client = GitHubClient(runner=runner)
        with self.assertRaisesRegex(GitHubPolicyError, "authenticated"):
            client.create_private_repository(
                owner="other-org",
                name="habit-tracker-web-123",
                description="[autopilot:autopilot-web-123:github:123456abcdef] generated project",
            )

        with self.assertRaisesRegex(GitHubPolicyError, "repository name"):
            GitHubClient(runner=RecordingRunner([])).get_repository("alice", "../../escape")

    def test_timeout_on_create_is_ambiguous_and_error_does_not_leak_token(self) -> None:
        runner = RecordingRunner([
            completed(["gh", "auth", "status"], 0, "", ""),
            completed(["gh", "api", "user"], 0, '{"login":"alice"}', ""),
            subprocess.TimeoutExpired(["gh", "api"], timeout=20),
        ])
        client = GitHubClient(runner=runner)
        with self.assertRaises(GitHubTransportError) as raised:
            client.create_private_repository(
                owner="alice",
                name="habit-tracker-web-123",
                description="[autopilot:autopilot-web-123:github:123456abcdef] generated project",
            )
        self.assertTrue(raised.exception.ambiguous)


if __name__ == "__main__":
    unittest.main()
