# Delivery observability frontend acceptance (WP-4 6.8 / WP-9.6)

Captured **2026-08-15** against `http://127.0.0.1:18081/admin/observability` served by an isolated `spring-boot:run` (port 18081, database `rdbot_acceptance`, scheduling disabled). This is local CURRENT evidence, not a production layout sign-off.

## Commands

- Frontend: `node --experimental-strip-types --test test/*.test.ts` → **152 pass**
- `npm run typecheck` → pass
- `npm run build` → pass (`admin-DeliveryObservabilityPage.js` emitted)
- SPA route: `GET /admin/observability` → 200 HTML; nested `/admin/observability/delivery/**` is not captured by the SPA controller

## Viewports

| Viewport | Size | Horizontal overflow | Screenshot |
| --- | --- | --- | --- |
| mobile | 390×844 | no (`scrollWidth == clientWidth`) | `screenshots/delivery-observability-mobile-390.png` |
| tablet | 900×1024 | no | `screenshots/delivery-observability-tablet-900.png` |
| desktop | 1920×1080 | no | `screenshots/delivery-observability-desktop.png` |

## Keyboard

Native controls with `aria-label` receive focus: 时间窗口 (`SELECT`), 角色 (`SELECT`), 刷新交付观测 (`BUTTON`). URL filters stay allowlisted (`window=24h`, no `projectId=all`).

## Console / network

Resource timings for the four read-only APIs were HTTP **200**:

- `/admin/observability/delivery/overview?window=24h&page=1&pageSize=20`
- `/admin/observability/delivery/timeseries?window=24h`
- `/admin/observability/delivery/failures?window=24h&page=1&pageSize=20`
- `/admin/observability/delivery/tasks?window=24h&page=1&pageSize=20`

No `projectId=all`. No 4xx/5xx on those four routes during the browser session. Illegal query checks were done over HTTP (see `live-http.md`): `window=2h` and `projectId=all` return **400**.

## Visual states observed

This run used an empty/failed `delivery_ledger` collector against `rdbot_acceptance`:

- Badge **采集失败** (`collector-failed`)
- Ratios render **不可用**, not a fake 0%
- Stage / usage / failure sections render **无样本** copy rather than invented series
- Counts `已接受/终态/运行中` stay 0 with collector failure (not presented as a healthy zero-rate)

REGRESSION: existing admin nav still includes Dashboard / 任务管理 / 执行追踪; `/admin/observability` is an added item and does not replace those routes.
