# Project Runtime Profiles and Compact Role Handoffs

## Goal

Make the requirement-delivery pipeline practical for real repositories without
turning every stage transition into a large, repetitive JSON prompt.

The change has two deliberately separate concerns:

1. A project administrator can upload one Dockerfile for each supported
   requirement-delivery role. RD-Bot builds it, verifies that it is a usable
   Claude Code runtime, stores the Dockerfile in RustFS, and uses the verified
   image for that role.
2. An upstream role can write a bounded Markdown handoff under a dedicated
   output path. RD-Bot persists that file in RustFS, attaches its verified
   contents to the next isolated workspace, and passes a compact pointer in
   place of the accumulated raw role-result JSON.

The first release supports only `CLAUDE_CODE`. There is no user-supplied image
name, arbitrary container command, or public object URL.

## Current Failure Modes

- The standard Claude QA image is generic and does not contain the Python
  environment needed by some projects. Agents then spend their turn attempting
  `sudo` or `apt-get` instead of validating the repair.
- `RequirementDeliveryEngine` currently serializes all prior role results into
  both the retrieval clue and the next role prompt. The bootstrap adapter also
  expands Docker metadata, evidence manifests, and raw agent JSON into that
  result. This is expensive, fragile, and makes downstream reasoning worse.
- Object storage is already private (`s3://` via RustFS), but there is no
  controlled role-to-role artifact protocol.

## Design

### Runtime profile

Persist a `ProjectRuntimeProfile` per `(projectId, role)` with:

- `agentType`: currently `CLAUDE_CODE` only.
- `role`: one of the four requirement-delivery roles.
- immutable image tag produced by the server.
- private RustFS URI, SHA-256, and original filename for the uploaded
  Dockerfile.
- validation status, a redacted validation summary, and timestamps.

The upload flow accepts one small Dockerfile as multipart input. It writes an
ephemeral build context, runs `docker build`, then runs the image with a shell
entrypoint and verifies all of the following:

```text
command -v claude
claude --version
command -v rd-claude-entrypoint
test -d /work
id rdbot
```

Only after the image has built and passed the smoke check does RD-Bot upload the
Dockerfile to RustFS and persist the profile. The Dockerfile is intentionally
limited to a standalone build context: it may use `FROM`, `RUN`, `ENV`, etc.,
but cannot rely on arbitrary project files via `COPY`.

At execution time, the requirement adapter resolves the verified role image
for the task's project and places it in the protected executor policy. The
Docker executor honors that image only when the policy has marked it verified;
otherwise it keeps the existing global image fallback. A custom QA runtime is
normally based on `rd-bot/claude-code-qa:local`; non-QA roles normally extend
`rd-bot/claude-code:local`.

### Markdown handoff

Every non-QA role is asked to produce both its required JSON result and, when
it has useful downstream context, a Markdown file at:

```text
/work/output/handoff/next.md
```

The JSON includes a small `next_prompt` object declaring the target role and a
short summary. The Markdown body is the detailed plan, decisions, affected
files, commands, and caveats. The server enforces a project default handoff
budget in tokens (implemented as a conservative character limit) and rejects
oversized or non-Markdown files.

The bootstrap adapter uploads a collected `HANDOFF_MARKDOWN` artifact to
`rd-role-handoffs` in RustFS and inserts a compact `roleHandoff` object into
the result. The engine extracts only the latest relevant handoff reference for
the next stage; it does not interpolate the complete upstream result history.
The adapter resolves that private object itself and supplies it as a normal
isolated workspace attachment. The next prompt identifies its local path and
hash. No agent receives RustFS credentials or a public object URL.

For remediation after QA failure, the compact QA remediation record remains
inline because it is short and needs no broad plan document.

### UI and API

Project Management gets a Runtime Image action. Its dialog selects one role,
shows the Claude Code-only constraint and validation result, and uploads a
Dockerfile. The corresponding API is:

```text
GET    /admin/projects/{projectId}/runtime-profiles
PUT    /admin/projects/{projectId}/runtime-profiles/{role}  multipart Dockerfile
DELETE /admin/projects/{projectId}/runtime-profiles/{role}
```

The handoff token limit is a server configuration default in this iteration;
it is not exposed as a per-task prompt knob, so it cannot be bypassed by a
task payload.

## Implementation Order

1. Add the plan, tests, domain ports/models, in-memory stores, and PostgreSQL
   migration for runtime profiles. Run the new tests red first.
2. Add the Dockerfile build-and-smoke adapter, private storage persistence,
   project API, and image resolution into `EngineRequirementExecutorAdapter`
   plus `DockerClaudeCodeExecutor`.
3. Add the handoff artifact type, prompt/schema contract, RustFS publisher,
   compact upstream selection, and private attachment materialization.
4. Add the small role handoff skill and project management dialog/service.
5. Run focused Maven and frontend checks, then a real Docker build/smoke and a
   real RustFS upload/open/read round trip. Record the result in a QA report.

## Acceptance Criteria

- An unsupported agent type, role, unsafe Dockerfile, or failed `claude
  --version` smoke cannot become an active runtime profile.
- A verified profile alters only its selected role image. Without a profile,
  the existing global image behavior is unchanged.
- A Markdown handoff is uploaded as a private RustFS object with a SHA-256 and
  materialized only under `/work/input/attachments` for the direct downstream
  role.
- Downstream prompt/context contains compact handoff metadata and must not
  contain complete Docker metadata or prior raw agent result JSON.
- QA remediation retains enough failure detail to route a coding retry.
- Existing role JSON validation and QA evidence validation still pass.
