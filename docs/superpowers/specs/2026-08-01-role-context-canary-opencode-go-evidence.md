# 2026-08-01 Role-Context Canary（opencode-go）证据与性能记录

## 0. 元信息

| 项 | 值 |
| --- | --- |
| 对应方案 | `docs/superpowers/specs/2026-08-01-role-context-optimization-validation-and-improvement-plan.markdown` |
| 触发依据 | 用户确认：双镜像 build、opencode-go、自行触发 attempt、自行审查、Phase 1 现在做、记录缓存/token/耗时 |
| 主 canary 任务 | `7489256180934643712`（`[canary:role-context-20260801-c]`） |
| 项目 | Next Js 16 Sqlite Drizzle Orm / `7487468535443230720` |
| Provider | `opencode-go` + `deepseek-v4-flash` |
| Pi 镜像 | `rd-bot/pi-agent:local` = `sha256:457c1e26ea115fdbd555ccceb68c66fd063c01b153a68a7cbf0bea50e7a41e5b` |
| Pi QA 镜像 | `rd-bot/pi-agent-qa:local` = `sha256:c6c305b56ab2e1755b15de8b15b3ab319548787397ec6eeb6610fa9c49420d5b` |
| Flags | `dynamicStateEnabled=true`，`contextProtocolVersion=LEGACY_ENVIRONMENT_NOTES` |
| 原始产物目录 | `tmp/role-context-canary/7489256180934643712/` |

## 1. 本轮落地与修复

1. **Phase 1**（已提交 `d5a3509c`）：semantic signature 去 `retrievalRunId`、材料旁路移除、硬预算默认 18000。
2. **双镜像 rebuild**：agent + QA 均打 `context-v2` / `local`。
3. **DB**：nextjs-kbr 四角色 `provider_profile_id=opencode-go`。
4. **运行中缺陷修复**（未单独提交，已进当前 jar）：
   - `saveImmutable` 改用 `upsertStageArtifact`（`metadata_json::jsonb`），否则 P0 manifest 写入直接炸库。
   - `dynamicStateEnabled=true` 时，resolver 自动把 `rd_todo_rewrite` / `rd_todo_update_status` / `rd_record_fact` 并入冻结 tool policy（否则 Bridge 在模型调用前 fail-closed）。

## 2. Attempt 轨迹

| 任务 | 结果 | 说明 |
| --- | --- | --- |
| `7489254756330901504` (a) | `DEAD_LETTERED` | 旧 jar：`metadata_json` varchar→jsonb |
| `7489255443185930240` (b) | `FAILED_NEEDS_HUMAN` | jsonb 已修，但 review 策略未放行 state tools：`rd_todo_rewrite is not permitted…`（确定性协议失败，~1.9s） |
| `7489256180934643712` (c) | 进行中 | REVIEWER **SUCCEEDED**；ARCHITECT attempt1 容器 COMPLETED 后主机掉线 → 卡在 RUNNING；lease 回收后因 `RUNNING→CONTEXT_READY` 非法转移短暂失败；人工将 attempt1 标 `FAILED_RETRYABLE` 后 attempt2 **RUNNING** 重跑 |

## 3. 成功 attempt 性能数据（REQUIREMENT_REVIEWER）

来源：`tmp/.../reviewer-metrics.json` + workspace `agent-events.jsonl`。

| 指标 | 值 |
| --- | --- |
| 状态 | COMPLETED / APPROVED / CAN_DO |
| 墙钟（docker） | **71380 ms**（~71.4s） |
| DB stage 时长 | **75.4 s** |
| Turns | 10 |
| input tokens（合计） | **15632** |
| output tokens（合计） | **7913** |
| cacheRead（合计） | **124032** |
| cacheWrite（合计） | **0** |
| input+output | **23545** |
| 缓存命中率 | **88.81%** = `cacheRead/(input+cacheRead)`（按 TURN_COMPLETED 求和） |
| dynamicState | true；`STATE_ACTION_RECORDED=6`；facts=3；state artifacts 已落盘 |
| runtime-context-manifest | `rd-runtime-context-manifest/v1` 已写出 |
| 首轮 cacheRead | 0；自第 2 turn 起非零并递增（符合 prompt cache 行为） |

## 4. 成功 attempt 性能数据（SOLUTION_ARCHITECT，容器侧）

来源：`tmp/.../architect-metrics.json`（主机在 RESULT 收口前曾掉线，DB 可能仍为 RUNNING，以 workspace 为准）。

| 指标 | 值 |
| --- | --- |
| 状态（runtime-meta） | COMPLETED |
| 事件时间窗 | 09:51:44Z → 09:53:00Z（~76s） |
| Turns | 17 |
| input | **16743** |
| output | **7631** |
| cacheRead | **210304** |
| cacheWrite | **0** |
| input+output | **24374** |
| 缓存命中率 | **92.63%** |

## 5. 确定性失败对照（canary-b）

- 失败类别：`PI_BRIDGE_PROTOCOL`
- 错误：`rd_todo_rewrite is not permitted by the frozen tool policy (dynamicStateEnabled)`
- docker `durationMillis=1872`，无 provider token（未进入模型调用）
- 修复后 canary-c 的 frozen `effectiveAllow` 含三项 state tools + `bash/read/rd_submit_result`

## 6. 自行审查（相对 P0/P1/P2 门禁）

| 检查项 | 结论 |
| --- | --- |
| Pi 双镜像已重建且 attempt 使用 `:local` | 通过（docker-meta.image=`rd-bot/pi-agent:local`） |
| opencode-go 真实调用 | 通过（RUNTIME_READY / PROVIDER_* / usage） |
| Phase 1：评审/架构 Prompt 无「完成需求编码…准备 PR」基线 | 通过（role-prompts 抽检 `has_coding_pr_baseline=False`） |
| Phase 1：预算 `maxChars=18000`，usedChars 远小于上限 | 通过（REVIEWER used=2271） |
| P0：ROLE_EXECUTION_INPUT_MANIFEST + runtime-context-manifest | 通过（canary-c） |
| P2：dynamicState 工具 + state artifacts + STATE_ACTION_RECORDED | 通过（canary-c） |
| P2-V4 完整三件套（成功/确定性失败/瞬时） | 部分：成功 + 确定性失败已有；瞬时 fixture 未单独跑；CODING/QA 尚未收口 |
| 主机稳定性 | 风险：本机 backend 多次无栈退出，architect 需 recovery |

## 7. 后续建议

1. 保持 backend 常驻，对 `7489256180934643712` 执行 submit/resume，让 ARCHITECT 收口并继续 CODING/QA；补齐后角色同样记录 token/cache/耗时。
2. 将 jsonb/`saveImmutable` 与 dynamic-state tool-policy merge 单独提交。
3. 调查 backend 无栈退出（疑似外部 SIGTERM / 内存压力），否则长链路 canary 无法稳定跑完。
