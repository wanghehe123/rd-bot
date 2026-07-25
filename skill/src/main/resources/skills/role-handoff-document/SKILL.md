---
name: role-handoff-document
description: Create a bounded Markdown handoff for the direct downstream RD-Bot role after requirement review, solution design, or coding.
---

# RD-Bot Role Handoff Document

Use this skill only in `REQUIREMENT_REVIEWER`, `SOLUTION_ARCHITECT`, or
`CODING_AGENT`. It turns the durable outcome of the current role into one
controlled document for the next role.

## Required Output

1. Read `/work/input/prompt.md`, `/work/input/context.json`, and any attached
   upstream handoff documents first.
2. Create exactly one UTF-8 Markdown file at:

   ```text
   /work/output/handoff/next.md
   ```

3. Keep it within the configured RD-Bot handoff token budget. Favor concrete
   facts, paths, commands, constraints, and unresolved questions over prose.
4. Write the normal structured JSON result separately to
   `/work/output/result.json`. Its `next_prompt` field is only a compact
   pointer to this document.

## Markdown Shape

Use the relevant headings below. Empty sections may be omitted.

```markdown
# Downstream Handoff

## Goal And Scope
## Evidence And Decisions
## Files Or Interfaces
## Implementation Or Verification Steps
## Commands Already Run
## Risks And Open Questions
```

- Reviewers: record acceptance coverage, missing information, risks, and the
  precise decision that lets planning proceed.
- Architects: record affected files, interfaces/data contracts, ordered
  implementation steps, and mapped acceptance tests.
- Coding agents: record actual changed files, commands and outcomes, known
  limitations, and the exact QA checks that still need to run.

## Boundaries

- Do not include object-store URLs, credentials, signed URLs, access keys, or
  raw provider/Docker metadata.
- Do not copy full tool transcripts. Keep only the command, outcome, and a
  short diagnostic when it matters to the downstream role.
- Do not write outside `/work/output/handoff/next.md`.
- Do not create a pull request or modify files outside the current role's
  stated responsibility.
