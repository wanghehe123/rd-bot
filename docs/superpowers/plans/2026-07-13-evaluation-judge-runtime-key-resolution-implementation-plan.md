# Evaluation Judge Runtime Key Resolution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the Python OpenAI-compatible Judge resolve its API key from the macOS user `launchctl` environment at scoring time when a directly launched backend did not inherit that environment.

**Architecture:** Keep Base URL and model in `scripts/evaluation-judge.yaml`. In `rd_eval_lib.py`, resolve `RD_EVAL_JUDGE_API_KEY` from a supplied mapping or `os.environ` first; only a no-argument production call with no key may use a Darwin-only `/bin/launchctl getenv` fallback. The fallback uses fixed argv, a one-second timeout, and returns no process output to callers or logs.

**Tech Stack:** Python standard library `platform`, `subprocess`, `unittest.mock`.

---

### Task 1: Specify the runtime-resolution behavior with failing tests

**Files:**
- Modify: `scripts/evaluation/tests/test_rd_eval_lib.py:1-12, 354-385`
- Test: `scripts/evaluation/tests/test_rd_eval_lib.py`

- [x] **Step 1: Add imports and a failing macOS fallback test**

```python
import subprocess

@mock.patch("scripts.evaluation.rd_eval_lib.subprocess.run")
@mock.patch("scripts.evaluation.rd_eval_lib.platform.system", return_value="Darwin")
def test_openai_provider_reads_key_from_launchctl_when_process_environment_is_empty(
        self, platform_system, run
):
    config_path = self.root / "evaluation-judge.yaml"
    config_path.write_text(
        "RD_EVAL_JUDGE_BASE_URL: https://judge.example.test/v1\\n"
        "RD_EVAL_JUDGE_MODEL: judge-model\\n",
        encoding="utf-8",
    )
    run.return_value = subprocess.CompletedProcess(
        ["/bin/launchctl", "getenv", "RD_EVAL_JUDGE_API_KEY"], 0, "launchctl-test-key\\n", ""
    )

    with mock.patch.dict(os.environ, {}, clear=True):
        provider = lib.OpenAICompatibleJudgeProvider.from_env(config_path=config_path)

    self.assertEqual(provider.api_key, "launchctl-test-key")
    run.assert_called_once_with(
        ["/bin/launchctl", "getenv", "RD_EVAL_JUDGE_API_KEY"],
        capture_output=True,
        check=False,
        text=True,
        timeout=1.0,
    )
```

- [x] **Step 2: Run the focused test and verify RED**

Run:

```bash
python3 -m unittest scripts.evaluation.tests.test_rd_eval_lib.RdEvalLibTest.test_openai_provider_reads_key_from_launchctl_when_process_environment_is_empty
```

Expected: FAIL because `OpenAICompatibleJudgeProvider.from_env()` raises `ValueError` before it invokes `launchctl`.

- [x] **Step 3: Add failure-path tests**

```python
@mock.patch("scripts.evaluation.rd_eval_lib.subprocess.run")
@mock.patch("scripts.evaluation.rd_eval_lib.platform.system", return_value="Darwin")
def test_openai_provider_treats_blank_launchctl_output_as_missing_key(self, platform_system, run):
    run.return_value = subprocess.CompletedProcess([], 0, "\\n", "")
    with mock.patch.dict(os.environ, {}, clear=True):
        with self.assertRaisesRegex(ValueError, "RD_EVAL_JUDGE_API_KEY"):
            lib.OpenAICompatibleJudgeProvider.from_env(config_path=self.judge_config())

@mock.patch("scripts.evaluation.rd_eval_lib.subprocess.run")
@mock.patch("scripts.evaluation.rd_eval_lib.platform.system", return_value="Linux")
def test_openai_provider_does_not_invoke_launchctl_off_macos(self, platform_system, run):
    with mock.patch.dict(os.environ, {}, clear=True):
        with self.assertRaisesRegex(ValueError, "RD_EVAL_JUDGE_API_KEY"):
            lib.OpenAICompatibleJudgeProvider.from_env(config_path=self.judge_config())
    run.assert_not_called()
```

### Task 2: Implement the constrained macOS fallback

**Files:**
- Modify: `scripts/evaluation/rd_eval_lib.py:6-15, 1704-1713`
- Test: `scripts/evaluation/tests/test_rd_eval_lib.py`

- [x] **Step 1: Add standard-library imports and constants**

```python
import platform
import subprocess

OPENAI_JUDGE_API_KEY_ENV = "RD_EVAL_JUDGE_API_KEY"
MACOS_LAUNCHCTL_EXECUTABLE = "/bin/launchctl"
MACOS_LAUNCHCTL_TIMEOUT_SECONDS = 1.0
```

- [x] **Step 2: Implement the exact-key resolver**

```python
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
    return result.stdout.strip() if result.returncode == 0 else ""
```

- [x] **Step 3: Use the resolver in the provider factory**

```python
api_key = resolve_openai_judge_api_key(env)
if not api_key:
    raise ValueError("missing judge environment variable(s): RD_EVAL_JUDGE_API_KEY")
```

- [x] **Step 4: Run focused and full Python tests**

Run:

```bash
python3 -m unittest scripts.evaluation.tests.test_rd_eval_lib.RdEvalLibTest.test_openai_provider_reads_key_from_launchctl_when_process_environment_is_empty
python3 -m unittest scripts.evaluation.tests.test_rd_eval_lib
```

Expected: both commands exit `0`; the existing explicit-mapping test continues to bypass the fallback.

### Task 3: Update the runtime contract and verify direct startup

**Files:**
- Modify: `scripts/evaluation-judge.yaml:1-2`
- Modify: `docs/qa/2026-07-13-task-run-evaluation-acceptance-report.md`

- [x] **Step 1: Update the configuration comment**

```yaml
# RD_EVAL_JUDGE_API_KEY is read from the process environment, with a macOS
# launchctl fallback at Python scoring time for directly launched backends.
```

- [x] **Step 2: Record the no-secret runtime test**

Document the command, direct-start PID, `SET/EMPTY`-only observations, HTTP
status, run ID, final status, and shutdown evidence. Do not include the key or
the full task execution artifact.

- [x] **Step 3: Run static secret checks**

Run:

```bash
git diff --check
rg -n --glob '!scripts/evaluation/tests/test_rd_eval_lib.py' 'sk-[A-Za-z0-9]{20,}|RD_EVAL_JUDGE_API_KEY:\s*[^#[:space:]]' scripts/evaluation-judge.yaml scripts/evaluation/rd_eval_lib.py docs
```

Expected: no whitespace errors and no plaintext key matches.

- [x] **Step 4: Commit**

Do not commit. Repository policy requires an explicit user request before staging or committing.
