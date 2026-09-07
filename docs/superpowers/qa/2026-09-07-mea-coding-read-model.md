# MEA Coding Read Model QA handoff (2026-09-07)

## Scope

B04–B06 on `codex/mea-coding-read-model`: Coding MEA snapshot + stage-result HTTP, REPEATABLE_READ read adapters, fixture/identities.

## Evidence

| Gate | Result |
| --- | --- |
| Unit | `CodingMeaQueryEngineTest`, `StageResultQueryEngineTest`, `PostgresCodingMeaReadAdapterTest`, `RdTaskCodingMeaControllerTest`, `RdTaskStageResultControllerTest` PASS |
| Real PG RR | `PostgresCodingMeaRepeatableReadRealSmokeTest` PASS via tunnel `127.0.0.1:15432` → VM `rdbot` (`-Drd.integration.coding-mea.enabled=true`) |
| OpenSpec | `openspec validate mea-coding-read-model --strict` PASS |
| Live HTTP | filled after deploy of this branch |

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
