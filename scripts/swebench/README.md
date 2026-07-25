# SWE-bench Lite 10-task pilot

This directory prepares a paired, low-cost pilot for direct Claude Code and
RD-Bot. It never loads evaluator-only reference material into an agent prompt
or RD-Bot knowledge base.

## What is created

- Ten RD-Bot projects and ten task-scoped knowledge bases.
- Each project's displayed "default branch" is the task's immutable
  SWE-bench base commit, not the repository's moving `main` branch.
- One issue-context document and six exact-checkout repository documents per
  task, all indexed in the matching knowledge base.
- Two isolated worktrees per task:
  - Claude Code: `/Volumes/WishDisk/codes/swe/worktrees/<instance_id>`
  - RD-Bot: `/Volumes/WishDisk/codes/swe/worktrees/rd-bot/<instance_id>`
- Two equivalent prompts per task under
  `qa-runs/swebench-lite-10/prompts/{claude-code,rd-bot}/`.

The authoritative mapping of instance, project, knowledge base, worktrees, and
prompt checksums is `qa-runs/swebench-lite-10/run-plan.json`.

## Preflight

Run this before the manual agent pass. It requires the RD-Bot backend at
`http://127.0.0.1:18080`.

```bash
python3 scripts/swebench/verify_swebench_setup.py
```

Print the paired mapping when selecting tasks manually:

```bash
jq -r '.tasks[] | [
  .instance_id,
  .project.project_key,
  .knowledge_base.name,
  .agent_workspaces.claude_code,
  .agent_workspaces.rd_bot,
  .prompt_paths.claude_code.path,
  .prompt_paths.rd_bot.path
] | @tsv' qa-runs/swebench-lite-10/run-plan.json
```

## Direct Claude Code

Use the matching Claude Code worktree and prompt. Keep the model, budget,
turn limit, and timeout fixed for all ten tasks.

```bash
ROOT=/Users/wish233/Documents/RD-Bot
INSTANCE_ID=astropy__astropy-12907
WORKSPACE="/Volumes/WishDisk/codes/swe/worktrees/${INSTANCE_ID}"
OUTPUT="${ROOT}/qa-runs/swebench-lite-10/claude-code/${INSTANCE_ID}"
mkdir -p "$OUTPUT"
(
  cd "$WORKSPACE"
  cat "${ROOT}/qa-runs/swebench-lite-10/prompts/claude-code/${INSTANCE_ID}.md" | claude -p \
    --bare \
    --dangerously-skip-permissions \
    --no-session-persistence \
    --output-format stream-json \
    --verbose \
    --max-budget-usd 4 \
    --max-turns 30
) > "${OUTPUT}/claude-events.jsonl" 2>&1
```

For RD-Bot, select the same `instance_id` project and its matching RD-Bot
prompt/worktree from `run-plan.json`. Do not reuse the Claude Code worktree.

## After the agent pass

This keeps checking project bindings and exact base commits while allowing the
expected code changes:

```bash
python3 scripts/swebench/verify_swebench_setup.py --allow-dirty-worktrees
```

Export both patch sets. The exporter does not stage or change files. It fails
when it finds untracked files unless `--include-untracked` is explicitly used.

```bash
python3 scripts/swebench/export_swebench_predictions.py \
  --agent claude-code \
  --model-name claude-code-2.1.202 \
  --include-untracked

python3 scripts/swebench/export_swebench_predictions.py \
  --agent rd-bot \
  --model-name rd-bot-claude-code-2.1.202 \
  --include-untracked
```

The JSONL files are suitable for the SWE-bench harness. The JSON-list files
are suitable for `sb-cli`:

```bash
INSTANCE_IDS_CSV="$(jq -r '.instance_id' qa-runs/swebench-lite-10/manifest.jsonl | paste -sd, -)"

sb-cli submit swe-bench_lite test \
  --predictions_path qa-runs/swebench-lite-10/predictions-claude-code.json \
  --instance_ids "$INSTANCE_IDS_CSV" \
  --run_id claude-code-lite-10
```

Run the RD-Bot JSON file with the same instance list and otherwise unchanged
submission settings.
