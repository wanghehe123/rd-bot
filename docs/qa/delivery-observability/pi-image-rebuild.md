# Pi image rebuild evidence (WP-2 / task 3.9)

Date: 2026-08-15

This change added `AgentRuntimeMeasurementParser` and a host-side
`runtime-measurement.json` artifact inside `DockerPiAgentExecutor`.

Inspected paths (no edits):

- `bootstrap/src/main/resources/executor/pi/src/rd-pi-bridge.mjs`
- `bootstrap/src/main/resources/executor/pi/src/protocol.mjs`
- `bootstrap/src/main/resources/executor/pi/Dockerfile`
- `bootstrap/src/main/resources/executor/pi/Dockerfile.qa`

No Pi bridge, protocol, or image resource files were modified. Therefore both
Pi images do **not** need to be rebuilt for `upgrade-delivery-observability`
WP-2 runtime measurement.

Rebuild would be required only if those resources change later in this change.
