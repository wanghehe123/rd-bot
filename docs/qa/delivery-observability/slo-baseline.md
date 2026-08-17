# Delivery observability SLO baseline (WP-5)

Captured **2026-08-15** against the deterministic Java catalog
`DeliveryObservabilityFixtures.catalog()` and the WP-0 local PostgreSQL notes in
`baseline.md`. This is an **observation-only** baseline. It is **not** a
production SLO, and notifications remain disabled
(`rd.observability.delivery.slo.observation-only=true`,
`notifications-enabled=false`).

## Dataset

| Item | Value |
| --- | --- |
| Fixture tasks | 6 (success, running, retry, human failure, negative duration, missing usage) |
| Terminal / judged | 5 / 5 in the 24h fixture clock |
| Local `rdbot_acceptance` requirement tasks | 24 (WP-0) |
| Local `ragent` requirement tasks | 101 (WP-0 leftover, not production) |
| Baseline age | 0 days of live observation on this change |
| Configured minDays / minTerminalSamples | 7 / 30 |

## Window percentiles (fixture clock 24h)

From `DeliveryObservabilityQueryServiceTest`:

- Success rate 4/5 (running excluded)
- End-to-end sampleCount 5; P99 suppressed (`minP99Samples=30`)
- Context phase P50 = 180s on the success fixture (adjacent `entered_at`)
- Human intervention 1/5
- Estimated cost present only when CNY keys exist; missing usage omits cost

## Eligibility

The configured gate is **not met**:

- window age < 7 days of live traffic
- fixture terminal samples (5) < 30
- local DBs were not a continuous production baseline

Conclusion: **不可定 SLO**. Candidates may be recorded in observation-only mode.
No threshold is published as a formal SLO. Alert notifications stay off.

## Suggested next measurement (not enabled)

Keep observation-only until a production-equivalent window reports ≥7 days and
≥30 judged terminal tasks, then review P50/P95/P99, queue oldest age, lease
loss, saturation and estimated CNY before enabling notifications.
