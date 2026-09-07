# MEA Coding Read Model QA handoff (2026-09-07)

## Scope

B04–B06 on `codex/mea-coding-read-model`: Coding MEA snapshot + stage-result HTTP, REPEATABLE_READ read adapters, fixture/identities.

## Evidence

| Gate | Result |
| --- | --- |
| Unit | `CodingMeaQueryEngineTest`, `StageResultQueryEngineTest`, `PostgresCodingMeaReadAdapterTest`, `RdTaskCodingMeaControllerTest`, `RdTaskStageResultControllerTest` PASS |
| Real PG RR | `PostgresCodingMeaRepeatableReadRealSmokeTest` PASS via tunnel `127.0.0.1:15432` → VM `rdbot` (`-Drd.integration.coding-mea.enabled=true`) |
| OpenSpec | `openspec validate mea-coding-read-model --strict` PASS |
| Live HTTP | 2026-09-07 deploy jar SHA-256 `0420536721682cc00443cb4359dbfc8616da81adc162023b2a5d189604d5b43e`; backend `Started RdBotApplication` at `2026-09-07T11:11:49+08:00`. Artifacts: `/tmp/mea-coding-read-model-http/` on VM. |

Live probes (all `coding-mea` **200**):

| Identity | taskId | notes |
| --- | --- | --- |
| W1g | `7502261872498970624` | `available=true`, 25 commands, 4 decisions, 30 links; stage `7502273116039680000` result **200** (`ARTIFACT_PREVIEW`) |
| W2B | `7502311153071165440` | 200 (do not un-cancel) |
| W3J | `7502300444828504064` | `available=false`, `NO_CODING_STAGE` |
| W1e | `7502196308401328128` | 200 (not a release pass) |
| C06 ASK | `7502550384997699584` | 200 |

Cross-task stage on W1g → **404**.

## Identities (read-only)

| Label | taskId |
| --- | --- |
| W1e | `7502196308401328128` |
| W1g | `7502261872498970624` |
| W2B | `7502311153071165440` (do not un-cancel) |
| W3J | `7502300444828504064` |
| C06 ASK | `7502550384997699584` |

## Endpoints

- `GET /admin/rd-tasks/{taskId}/coding-mea`
- `GET /admin/rd-tasks/{taskId}/manager-decisions/{decisionHash}`
- `GET /admin/rd-tasks/{taskId}/stage-runs/{stageRunId}/result`
- `GET /admin/rd-tasks/{taskId}/stage-runs/{stageRunId}/result/content`

Fixture: `openspec/changes/mea-coding-read-model/fixtures/coding-mea-v1.json`

## Non-claims

- Not B07 R0 (no full 12×3).
- Remediations may be empty until a production `AgentRemediationRoundStore` bean exists; read path stays optional.
