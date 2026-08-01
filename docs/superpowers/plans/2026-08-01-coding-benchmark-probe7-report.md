# Coding Benchmark Probe-7 评估报告

- **Campaign**: `7489025388002283520`（`deepseek-probe-7-infra-fix`）
- **Snapshot**: `20260730-coding-v2`
- **终态**: `SUCCEEDED` 100%（总体 `overallPassed=false`，因存在失败 trial）
- **时间**: 2026-08-01 约 02:33–04:02（UTC+8）

## 目标对照

| 目标 | 结果 |
|------|------|
| 允许模型未做出题（`TEST_FAIL`） | 满足：3 个 `TEST_FAIL` |
| 不允许基建类错误（`INFRA_ERROR`） | **满足：0 个 `INFRA_ERROR`** |
| 全链路 agent → patch → oracle | 满足：两端 case 均有真实 oracle/agent 产物 |
| 控制台可看详情 | 已上线 Trial 详情页与产物 API |

## 终局看板

| Case | A | B | C | D |
|------|---|---|---|---|
| `alibaba__fastjson2-2285` | **PASS** | **PASS** | TEST_FAIL | TEST_FAIL |
| `rd-bot--5da2b18042` | **PASS** | PROTOCOL_ERROR | **PASS** | TEST_FAIL |

**Verdict 汇总**: `PASS=4` · `TEST_FAIL=3` · `PROTOCOL_ERROR=1` · `INFRA_ERROR=0`

## 相对 Probe-6b 的改善

| 项 | Probe-6b | Probe-7 |
|----|----------|---------|
| rd-bot arms | 4× `INFRA_ERROR`（dangling cache symlink） | **0× INFRA**；A/C=PASS，D=TEST_FAIL，B=PROTOCOL_ERROR |
| fastjson2 | 仅 B=PASS | A/B=PASS，C/D=TEST_FAIL |
| Trial cache | 部分为 broken symlink | 全部为真实目录（`m2/` 等） |

根因修复：物化时 `toRealPath()` + 拒绝保留 symlink；rd-bot 优先使用 `cache-m2-rdbot`。

## 残留问题

### 1. `rd-bot` Arm B = `PROTOCOL_ERROR`

- 现象：已写出 `candidate.patch`，但无 `result.json`；`agentExit=1`，`oracleExit=-1`。
- 分类：生命周期/协议收尾失败，**不是** oracle 测失败，也不是 cache INFRA。
- 对照用户门槛「只允许评测失败」：此项仍不合格，需后续单独修（agent 有 patch 却未结构化收尾 / 容器非 0 退出）。

### 2. 模型能力失败（可接受）

- fastjson2 C/D、rd-bot D：`TEST_FAIL` + oracle 正常产出 —— 符合「题目可以做不出来」。

## 控制台

- 历史 Campaign + Trial 结果板：`/admin/evaluations/coding-benchmarks`
- Trial 详情：`/admin/evaluations/coding-benchmarks/campaigns/{runId}/trials/{trialId}`
  - 生命周期事件、oracle-result、candidate.patch、result.json 等 allowlisted 产物

## 结论

1. **基建目标达成**：probe 级不再出现 `INFRA_ERROR`；rd-bot 可完整跑通并出现真实 `PASS`。
2. **评测语义基本正确**：多数失败为 `TEST_FAIL`。
3. **下一刀**：收敛 `PROTOCOL_ERROR`（有 patch 无结构化 result）到可恢复收尾或更清晰的 verdict，以满足「除 TEST_FAIL 外不允许其他错误」的严格门禁。
