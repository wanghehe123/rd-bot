# RD-Bot Python3 量化评测运行文档

日期：2026-07-06

## 1. 目标

本运行文档用于让你在本机自行评测 RD-Bot 的三类质量：

- RAG：意图、证据命中、证据召回、MRR、上下文关键术语覆盖。
- 多 Agent：需求评审、方案设计、编码、QA、交付闭环是否符合 gold。
- 告警与治理：禁发告警、必需经验、脱敏和 AI judge 预留。

基础录制、确定性评分、报告和 diff 采用 Python3 标准库，不依赖 Java 运行时改造。若要和 `/Users/wish233/PycharmProjects/ragenteval` 一样计算 RAGAS 五项 LLM-as-judge 指标，需要额外安装可选依赖 `ragas langchain-openai datasets`；不安装时使用 `--skip-judge` 跳过。

## 1.1 运行目录约定

所有命令默认在仓库根目录运行：

```bash
cd /Users/wish233/Documents/RD-Bot
```

如果终端提示尝试打开 `RD-Bot/scripts/scripts/evaluation/...`，说明当前目录已经在 `scripts/` 下；先执行 `cd /Users/wish233/Documents/RD-Bot`，再运行本文命令。评测脚本内部会把默认输出目录解析到仓库根的 `qa-runs/evaluation/`，但脚本文件路径本身仍然必须从正确目录或用绝对路径调用。

## 2. 文件说明

| 文件 | 作用 |
| --- | --- |
| `scripts/evaluation/datasets/rd_eval_smoke.jsonl` | 版本化评测数据，包含 6 条样本和 fixture record |
| `scripts/evaluation/rd_eval_run.py` | 录制 EvalRecord，支持 fixture 和 live RAG HTTP |
| `scripts/evaluation/rd_eval_score.py` | 计算确定性指标，并预留 AI judge |
| `scripts/evaluation/rd_eval_report.py` | 渲染 `report.md`、`per_sample.csv`、`failures.jsonl` |
| `scripts/evaluation/rd_eval_diff.py` | 对比两次 `_scores.json` |
| `scripts/evaluation/rd_eval_lib.py` | 公共 schema、评分、报告、provider 逻辑 |
| `scripts/evaluation/tests/test_rd_eval_lib.py` | Python unittest 测试 |
| `qa-runs/evaluation/runs/` | 本地运行输出，不提交 |
| `qa-runs/evaluation/reports/` | 本地评测报告，不提交 |

## 3. 评测数据

当前样例数据在：

```bash
scripts/evaluation/datasets/rd_eval_smoke.jsonl
```

包含 6 条样本：

| sample_id | suite | 场景 | 主要验证点 |
| --- | --- | --- | --- |
| `RD-RAG-001` | `rag` | 支付回调证据检索 | `intent_top1`、`evidence_hit@5`、`evidence_recall@5`、`mrr@10` |
| `RD-REQ-001` | `requirement` | 缺日志和订单号时阻断 | `requirement_decision_accuracy`、`downstream_leak_rate`、`risk_coverage` |
| `RD-SOL-001` | `solution` | 支付回调技术方案 | `solution_schema_valid_rate`、`acceptance_mapping_coverage`、影响文件 precision/recall |
| `RD-CODING-001` | `coding` | 编码变更和测试命令 | `changed_file_precision`、`forbidden_file_touch_rate`、`test_command_pass_rate` |
| `RD-QA-001` | `qa` | QA 真实命令验收 | `qa_schema_valid_rate`、`real_command_execution_rate`、`skipped_acceptance_rate` |
| `RD-E2E-001` | `e2e` | 交付闭环 | 禁发告警、五类经验完整、`redacted=true` |

每条数据包含：

- `input`：任务标题、描述、日志等输入材料。
- `rag_gold`：期望意图、must/nice evidence URI、必需术语。
- `stage_gold`：各角色期望决策、必需结构、验收项、文件边界、测试命令。
- `alert_gold`：期望告警和禁止告警。
- `delivery_gold`：必需经验类型。
- `fixture_record`：无需启动 RD-Bot 也能跑通评测链路的录制结果。

## 4. Fixture 模式：不启动 RD-Bot 的本地验证

先跑单测：

```bash
python3 -m unittest scripts.evaluation.tests.test_rd_eval_lib -v
```

录制 fixture run：

```bash
python3 scripts/evaluation/rd_eval_run.py \
  --dataset scripts/evaluation/datasets/rd_eval_smoke.jsonl \
  --source fixture \
  --run-id rd-eval-smoke-local \
  --overwrite
```

输出：

```text
qa-runs/evaluation/runs/rd-eval-smoke-local.jsonl
```

计算分数，不调用 AI judge：

```bash
python3 scripts/evaluation/rd_eval_score.py \
  --run-id rd-eval-smoke-local \
  --skip-judge
```

输出：

```text
qa-runs/evaluation/reports/rd-eval-smoke-local/_scores.json
```

生成报告：

```bash
python3 scripts/evaluation/rd_eval_report.py \
  --run-id rd-eval-smoke-local
```

输出目录：

```text
qa-runs/evaluation/reports/rd-eval-smoke-local/
  _scores.json
  report.md
  per_sample.csv
  failures.jsonl
```

## 5. Live RAG HTTP 模式：连接本机 RD-Bot

前提：本机 RD-Bot bootstrap 已启动，默认地址为：

```text
http://127.0.0.1:18080
```

录制 live RAG：

```bash
python3 scripts/evaluation/rd_eval_run.py \
  --dataset scripts/evaluation/datasets/rd_eval_smoke.jsonl \
  --source rag-http \
  --base-url http://127.0.0.1:18080 \
  --rag-log logs/rag-retrieval.jsonl \
  --run-id rd-eval-live-rag-local \
  --overwrite
```

只验证 RAG 样本时可加 `--limit 1`。`rd_eval_score.py` 默认只对 run JSONL 中实际录到的 sample 打分，避免 partial run 把未录制的需求/方案/编码/QA 样本误判为失败；如需把缺失记录也算成失败，增加 `--strict-missing-records`。

该模式会：

- 调用 `/rag/v3/chat?question=...`。
- 解析 SSE `meta` / `done` 事件。
- 按 `taskId` 反查 `logs/rag-retrieval.jsonl` 中的 `retrievedChunks`。
- 生成同样的 `EvalRecord` JSONL。

后续评分和报告命令相同：

```bash
python3 scripts/evaluation/rd_eval_score.py \
  --run-id rd-eval-live-rag-local \
  --skip-judge

python3 scripts/evaluation/rd_eval_report.py \
  --run-id rd-eval-live-rag-local
```

## 6. AI Judge Provider 预留

默认不调用 AI provider。需要和 ragenteval 对齐的语义指标时，优先使用 RAGAS provider；它按 `user_input / response / retrieved_contexts / reference` 构造 Dataset，并计算：

- `faithfulness`
- `answer_relevancy`
- `answer_correctness`
- `context_precision`
- `context_recall`

可选依赖不要直接装进 Homebrew 的系统 Python；它会触发 PEP 668 的 `externally-managed-environment`。推荐复用 `ragenteval` 的 Python 3.11 虚拟环境：

```bash
RAGAS_PY=/Users/wish233/PycharmProjects/ragenteval/.venv/bin/python
$RAGAS_PY -m ensurepip --upgrade
$RAGAS_PY -m pip install ragas langchain-openai datasets 'langchain-community==0.3.31' socksio
```

如果没有该虚拟环境，再新建项目本地 venv：

```bash
python3 -m venv qa-runs/evaluation/.venv
qa-runs/evaluation/.venv/bin/python -m pip install --upgrade pip
qa-runs/evaluation/.venv/bin/python -m pip install ragas langchain-openai datasets 'langchain-community==0.3.31' socksio
```

只允许配置变量名，不要把 key 写入命令行、文档或报告。推荐静默输入：

```bash
export RD_EVAL_JUDGE_BASE_URL="https://example.com/v1/chat/completions"
export RD_EVAL_JUDGE_MODEL="judge-model-name"
export RD_EVAL_EMBEDDING_MODEL="embedding-model-name"
read -r -s RD_EVAL_JUDGE_API_KEY
export RD_EVAL_JUDGE_API_KEY
```

调用前 5 条样本做 RAGAS judge：

```bash
$RAGAS_PY -B scripts/evaluation/rd_eval_score.py \
  --run-id rd-eval-smoke-local \
  --judge-provider ragas \
  --judge-limit 5
```

兼容模式：

```bash
python3 scripts/evaluation/rd_eval_score.py \
  --run-id rd-eval-smoke-local \
  --judge-provider openai-compatible \
  --judge-limit 5
```

`openai-compatible` 是轻量自定义 judge，不等价于 RAGAS；需要和 ragenteval 对比时不要使用它作为正式分数。

RAGAS 五项阈值对齐 `/Users/wish233/PycharmProjects/ragenteval`：`faithfulness >= 0.90`、`answer_relevancy >= 0.85`、`answer_correctness >= 0.80`、`context_precision >= 0.75`、`context_recall >= 0.80`。

如果没有配置 provider，请使用：

```bash
python3 scripts/evaluation/rd_eval_score.py \
  --run-id rd-eval-smoke-local \
  --skip-judge
```

此时上述 AI 指标在报告中显示为 `SKIPPED`，不等于失败。

## 7. A/B Diff

对比两次分数：

```bash
python3 scripts/evaluation/rd_eval_diff.py \
  --baseline qa-runs/evaluation/reports/rd-eval-smoke-local/_scores.json \
  --candidate qa-runs/evaluation/reports/rd-eval-smoke-local/_scores.json
```

在 CI 或回归门禁中可增加：

```bash
python3 scripts/evaluation/rd_eval_diff.py \
  --baseline qa-runs/evaluation/reports/<baseline_run>/_scores.json \
  --candidate qa-runs/evaluation/reports/<candidate_run>/_scores.json \
  --fail-on-regression
```

回归方向按指标阈值判断：`>=` 指标下降算回归，`<=` 指标上升算回归。

## 8. 如何扩充数据

新增一行 JSONL，至少包含：

```json
{
  "sample_id": "RD-RAG-NEW-001",
  "suite": "rag",
  "scenario": "your-scenario",
  "difficulty": "medium",
  "tags": ["rag"],
  "input": {
    "title": "任务标题",
    "description": "任务描述",
    "logs": ["相关日志"]
  },
  "rag_gold": {
    "expectedIntentSystemId": "payment",
    "mustEvidenceUris": ["knowledge://payment/callback-idempotency"],
    "niceEvidenceUris": [],
    "requiredTerms": ["PAID"],
    "forbiddenTerms": ["无法判断"]
  },
  "fixture_record": {
    "task_id": "local-sample-id",
    "status": "CONTEXT_READY",
    "rag": {
      "primaryIntentSystemId": "payment",
      "retrievedEvidenceUris": ["knowledge://payment/callback-idempotency"],
      "retrievedChunks": []
    },
    "stages": {},
    "alerts": []
  }
}
```

扩充原则：

- `mustEvidenceUris` 必须是可以追踪到知识库、代码片段、文档或日志的稳定 URI。
- `fixture_record` 只用于本地 smoke，真实评测以 live run 录制结果为准。
- 不写入 secret 明文、token、密码、API key 或真实用户敏感信息。
- 对 `QA_AGENT`，`SKIPPED` 只能表示缺环境，不能当通过。

## 9. 当前已验证的命令

本轮已在仓库根目录验证：

```bash
python3 -m unittest scripts.evaluation.tests.test_rd_eval_lib -v
python3 scripts/evaluation/rd_eval_run.py --dataset scripts/evaluation/datasets/rd_eval_smoke.jsonl --source fixture --run-id rd-eval-smoke-local --overwrite
python3 scripts/evaluation/rd_eval_score.py --run-id rd-eval-smoke-local --skip-judge
python3 scripts/evaluation/rd_eval_report.py --run-id rd-eval-smoke-local
```

生成的 smoke 报告位于：

```text
qa-runs/evaluation/reports/rd-eval-smoke-local/report.md
```
