# README Asset Provenance

All README assets were produced from the candidate source tree of this repository
during the 2026-09-08 personal open-source release preparation. No third-party
artwork, screenshots, logos or copy were used.

| Asset | How it was produced | Source page / scene | Third-party material |
| --- | --- | --- | --- |
| `logo.svg` | Hand-authored SVG drawn for this release (geometric mark + wordmark). | — | None. |
| `hero-dashboard.webp` | Screenshot of the live admin console served by the release Compose stack (`rd-bot/app:oss-test`), captured with headless Chromium (Playwright, from the release QA image) at 1600×900, deviceScaleFactor 2, converted to WebP (cwebp q82). | `/admin/dashboard` with the first-run onboarding checklist and the demo project `hello-rd-bot`. | None; UI is RD-Bot's own React admin. |
| `task-workbench.webp` | Same pipeline, 1440×900. | Task detail workbench of the demo requirement task `[demo] add /healthz endpoint to hello-rd-bot` (CREATED, never executed). | None. |
| `requirement-flow.webp` | Same pipeline, 1440×900, 「任务输入与交付」 tab. | Demo task input & delivery view: repo `https://github.com/example-org/hello-rd-bot.git` (a placeholder), expected result and acceptance criteria. | None. |
| `task-mobile.webp` | Same pipeline, 390×844 viewport. | Same demo task workbench. | None. |
| `quickstart.gif` | Assembled with ffmpeg from: (1) a stylized terminal frame rendered as HTML showing the real `./scripts/rd-bot.sh up` output shape, (2) the live dashboard screenshot, (3) the workbench screenshot. 10 s, 880×495, 6 fps, 206 KB. | — | None. |

## Sensitive-data review

- Every visible identifier is demo data: project `hello-rd-bot`, repository
  `example-org/hello-rd-bot`, demo task IDs created solely for these shots.
- No tokens, API keys, emails, personal names, absolute local paths, internal
  IPs or production task IDs appear in any asset. Verified by visual review of
  each full-size frame plus targeted text scans.
- The stacks used for capture ran with auto-generated throwaway secrets.

## Regeneration

Screenshots can be regenerated from any running stack:
`scripts`-free pipeline — Playwright (bundled in the release QA image) against
`http://127.0.0.1:18080`; see the conversion commands above (cwebp q82).
