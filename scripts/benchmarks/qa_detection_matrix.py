#!/usr/bin/env python3
"""E4 QA 检出率（缺陷注入）编排器。

向 RD-Bot 自身全栈应用（Spring Boot 后端 + React 前端）注入 15 个已知缺陷，
对比两档 QA 门禁的检出能力：

  - unit_only：仅单元测试（后端 JUnit / 前端 node:test）
  - full     ：单元测试 + 浏览器 E2E（真实 Playwright 引擎，经 browser-use MCP 宿主执行）

检出判定：门禁 FAILED 且定位正确 = detected=1；误报不计。

用法::

  # 1) 跑单测门禁：逐缺陷注入->跑门禁->回滚，产出 unit_results.json
  python3 scripts/benchmarks/qa_detection_matrix.py run-unit

  # 2) 注入单个渲染缺陷供 E2E 实测（浏览器由 MCP 驱动，本命令只改文件不跑测）
  python3 scripts/benchmarks/qa_detection_matrix.py inject <defect_id>
  python3 scripts/benchmarks/qa_detection_matrix.py restore <defect_id>

  # 3) 合并 unit_results.json + e2e_results.json -> matrix.csv
  python3 scripts/benchmarks/qa_detection_matrix.py matrix

  # 导出缺陷语料清单
  python3 scripts/benchmarks/qa_detection_matrix.py corpus
"""

import csv
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "qa-runs/qa-detection-rate/20260725"
FRONTEND = ROOT / "frontend"

# subtype: backend | frontend_unit | frontend_render
# gate: mvn:<TestClass> | node
DEFECTS = [
    # ---------------- 后端 6 个（既有 JUnit 应能检出） ----------------
    {
        "id": "B1-currency-scale", "category": "backend", "subtype": "backend",
        "file": "exec/src/main/java/com/wish/rd/exec/repair/alert/BudgetCurrencyConverter.java",
        "gate": "mvn:BudgetCurrencyConverterTest",
        "old": "        return normalizedUsd.multiply(cnyPerUsd).setScale(4, RoundingMode.HALF_UP);",
        "new": "        return normalizedUsd.multiply(cnyPerUsd).setScale(2, RoundingMode.HALF_UP);",
        "symptom": "USD->CNY 金额精度由 4 位变 2 位，预算换算错误",
        "localization": "BudgetCurrencyConverter.usdToCny setScale",
        "covering_test": "BudgetCurrencyConverterTest.shouldConvertProviderUsdToCnyWithFourDecimalPlaces",
    },
    {
        "id": "B2-allowlist-norule", "category": "backend", "subtype": "backend",
        "file": "exec/src/main/java/com/wish/rd/exec/repair/security/model/ExecutionAllowlistPolicy.java",
        "gate": "mvn:ExecutionAllowlistPolicyTest",
        "old": "        if (!hasAnyRule) {\n            return Decision.reject(\"execution allowlist has no rules\");\n        }",
        "new": "        if (!hasAnyRule) {\n            return Decision.allow();\n        }",
        "symptom": "启用白名单但无规则时错误放行（安全策略绕过）",
        "localization": "ExecutionAllowlistPolicy.evaluate no-rules 分支",
        "covering_test": "ExecutionAllowlistPolicyTest.shouldRejectEnabledPolicyWithoutRules",
    },
    {
        "id": "B3-token-field-swap", "category": "backend", "subtype": "backend",
        "file": "exec/src/main/java/com/wish/rd/exec/repair/docker/usage/ClaudeTokenUsageParser.java",
        "gate": "mvn:ClaudeTokenUsageParserTest",
        "old": "                    outputTokens += nonNegativeLong(usage, \"output_tokens\");",
        "new": "                    outputTokens += nonNegativeLong(usage, \"input_tokens\");",
        "symptom": "输出 token 聚合读错字段（字段映射错误），成本/用量统计失真",
        "localization": "ClaudeTokenUsageParser.parse output_tokens 聚合",
        "covering_test": "ClaudeTokenUsageParserTest.shouldAggregateUniqueAssistantUsageAndFinalCost",
    },
    {
        "id": "B4-registry-key", "category": "backend", "subtype": "backend",
        "file": "exec/src/main/java/com/wish/rd/exec/repair/docker/impl/DockerExecutionRegistry.java",
        "gate": "mvn:DockerExecutionRegistryTest",
        "old": "        String taskId = safe(command.contextJson().get(\"workflowTaskId\"));",
        "new": "        String taskId = safe(command.contextJson().get(\"stageRunId\"));",
        "symptom": "运行态登记读错上下文键，taskId 映射到 stageRunId",
        "localization": "DockerExecutionRegistry.workflowTaskId 上下文键",
        "covering_test": "DockerExecutionRegistryTest.shouldExposeLogicalTaskAndLiveTokenUsageFromMountedEvents",
    },
    {
        "id": "B5-glob-wildcard", "category": "backend", "subtype": "backend",
        "file": "exec/src/main/java/com/wish/rd/exec/repair/security/model/ExecutionAllowlistPolicy.java",
        "gate": "mvn:ExecutionAllowlistPolicyTest",
        "old": "            if (c == '*') {\n                regex.append(\".*\");\n            } else {",
        "new": "            if (c == '*') {\n                regex.append(Pattern.quote(\"*\"));\n            } else {",
        "symptom": "通配符 glob 退化为字面量，分支/仓库模式匹配失效",
        "localization": "ExecutionAllowlistPolicy.globRegex 通配符处理",
        "covering_test": "ExecutionAllowlistPolicyTest.shouldMatchRepositoryAndBranchPatterns",
    },
    {
        "id": "B6-cost-field-missing", "category": "backend", "subtype": "backend",
        "file": "exec/src/main/java/com/wish/rd/exec/repair/docker/usage/ClaudeTokenUsageParser.java",
        "gate": "mvn:ClaudeTokenUsageParserTest",
        "old": "        for (String fieldName : new String[]{\"total_cost_usd\", \"total_cost\", \"cost_usd\"}) {",
        "new": "        for (String fieldName : new String[]{\"total_cost_usd\", \"cost_usd\"}) {",
        "symptom": "漏读 total_cost 字段，估算成本恒为 0",
        "localization": "ClaudeTokenUsageParser.firstCost 成本字段列表",
        "covering_test": "ClaudeTokenUsageParserTest.shouldAggregateUniqueAssistantUsageAndFinalCost",
    },
    # ---------------- 前端 3 个：纯函数逻辑（现有 node:test 应能检出） ----------------
    {
        "id": "FU1-tasklink-param", "category": "frontend", "subtype": "frontend_unit",
        "file": "frontend/src/pages/dashboard/dashboardPresentation.ts",
        "gate": "node",
        "old": "    searchParams.set(\"projectId\", normalizedProjectId);",
        "new": "    searchParams.set(\"project\", normalizedProjectId);",
        "symptom": "任务下钻链接的 projectId 查询参数键写错为 project",
        "localization": "dashboardPresentation.taskListHref projectId 参数",
        "covering_test": "dashboardPresentation.test.ts builds a task-list drill-down",
    },
    {
        "id": "FU2-ratio-precision", "category": "frontend", "subtype": "frontend_unit",
        "file": "frontend/src/pages/dashboard/dashboardPresentation.ts",
        "gate": "node",
        "old": "  return `${(Math.max(0, Math.min(1, ratio.value)) * 100).toFixed(1)}%`;",
        "new": "  return `${(Math.max(0, Math.min(1, ratio.value)) * 100).toFixed(0)}%`;",
        "symptom": "可用率百分比丢失一位小数（50.0% -> 50%）",
        "localization": "dashboardPresentation.formatAvailabilityRatio toFixed",
        "covering_test": "dashboardPresentation.test.ts renders unavailable ratios",
    },
    {
        "id": "FU3-stale-scope-guard", "category": "frontend", "subtype": "frontend_unit",
        "file": "frontend/src/pages/dashboard/dashboardPresentation.ts",
        "gate": "node",
        "old": "  return responseProjectId === selectedProjectId ? data : null;",
        "new": "  return data;",
        "symptom": "项目切换后陈旧响应防护失效，可能渲染上一个项目的数据",
        "localization": "dashboardPresentation.dashboardDataForScope 作用域守卫",
        "covering_test": "dashboardPresentation.test.ts does not render a previous project response",
    },
    # ---------------- 前端 6 个：渲染/交互（纯函数单测结构上看不见，需浏览器 E2E） -------------
    {
        "id": "FR1-metric-value-misbind", "category": "frontend", "subtype": "frontend_render",
        "file": "frontend/src/pages/DashboardPage.tsx",
        "gate": "node",
        "old": "      label: \"已完成交付\",\n      value: overview?.completedCount,",
        "new": "      label: \"已完成交付\",\n      value: overview?.blockedCount,",
        "symptom": "『已完成交付』指标卡错误绑定 blockedCount（显示失败/阻塞数）",
        "localization": "DashboardPage metrics 已完成交付 value 绑定",
        "e2e_check": "读『已完成交付』卡片数值，应等于 API completedCount(61) 而非 blockedCount(49)",
    },
    {
        "id": "FR2-status-tone-danger", "category": "frontend", "subtype": "frontend_render",
        "file": "frontend/src/pages/DashboardPage.tsx",
        "gate": "node",
        "old": "  if (status.includes(\"FAILED\") || status === \"REJECTED\" || status === \"DEAD_LETTERED\") {\n    return \"is-danger\";\n  }",
        "new": "  if (status.includes(\"FAILED\") || status === \"REJECTED\" || status === \"DEAD_LETTERED\" || status === \"COMPLETED\") {\n    return \"is-danger\";\n  }",
        "symptom": "COMPLETED 状态徽章错误呈现为危险红（is-danger）而非成功绿",
        "localization": "DashboardPage statusTone COMPLETED 归类",
        "e2e_check": "近期交付中 COMPLETED 任务的状态 chip class 应为 is-success 而非 is-danger",
    },
    {
        "id": "FR3-status-list-empty", "category": "frontend", "subtype": "frontend_render",
        "file": "frontend/src/pages/DashboardPage.tsx",
        "gate": "node",
        "old": "              {statusRows.length > 0 ? (",
        "new": "              {statusRows.length > 9999 ? (",
        "symptom": "任务状态分布恒显示空态，即使有 8 类状态数据",
        "localization": "DashboardPage 状态分布列表条件渲染",
        "e2e_check": "有数据时状态分布列表应存在（>0 行），不应显示『当前范围暂无任务状态』",
    },
    {
        "id": "FR4-duplicate-label", "category": "frontend", "subtype": "frontend_render",
        "file": "frontend/src/pages/DashboardPage.tsx",
        "gate": "node",
        "old": "      label: \"失败或阻塞\",\n      value: overview?.blockedCount,",
        "new": "      label: \"已完成交付\",\n      value: overview?.blockedCount,",
        "symptom": "指标卡标签错误：『失败或阻塞』被误标为『已完成交付』（重复标签）",
        "localization": "DashboardPage metrics 失败或阻塞 label",
        "e2e_check": "指标区『已完成交付』标签应仅出现 1 次；『失败或阻塞』应存在",
    },
    {
        "id": "FR5-drop-first-row", "category": "frontend", "subtype": "frontend_render",
        "file": "frontend/src/pages/DashboardPage.tsx",
        "gate": "node",
        "old": "                  {overview.recentDeliveries.map((task) => <RecentDeliveryRow key={task.taskId} task={task} />)}",
        "new": "                  {overview.recentDeliveries.slice(1).map((task) => <RecentDeliveryRow key={task.taskId} task={task} />)}",
        "symptom": "近期交付列表漏渲染首项（off-by-one）",
        "localization": "DashboardPage recentDeliveries 渲染 slice",
        "e2e_check": "近期交付渲染行数应等于 API recentDeliveries 长度(10)，缺陷时为 9",
    },
    {
        "id": "FR6-drilldown-wrong-type", "category": "frontend", "subtype": "frontend_render",
        "file": "frontend/src/pages/DashboardPage.tsx",
        "gate": "node",
        "old": "      href: taskListHref(projectScope.projectId, { taskType: \"BUG_FIX\" })",
        "new": "      href: taskListHref(projectScope.projectId, { taskType: \"REQUIREMENT\" })",
        "symptom": "『Bug 总数』卡片下钻跳转到 REQUIREMENT 过滤列表（错误目标）",
        "localization": "DashboardPage Bug 总数 href taskType",
        "e2e_check": "点击『Bug 总数』卡片，URL 查询 taskType 应为 BUG_FIX 而非 REQUIREMENT",
    },
]


def by_id(defect_id):
    for defect in DEFECTS:
        if defect["id"] == defect_id:
            return defect
    raise SystemExit(f"unknown defect id: {defect_id}")


def apply_defect(defect):
    path = ROOT / defect["file"]
    text = path.read_text()
    count = text.count(defect["old"])
    if count != 1:
        raise SystemExit(f"{defect['id']}: old-string occurs {count} times in {defect['file']} (need exactly 1)")
    path.write_text(text.replace(defect["old"], defect["new"], 1))


def restore_file(rel_file):
    subprocess.run(["git", "checkout", "--", rel_file], cwd=ROOT, check=True)


def run_gate(defect):
    """Returns (detected, tail). detected=True when the gate FAILS (i.e. catches the bug)."""
    gate = defect["gate"]
    if gate.startswith("mvn:"):
        test_class = gate.split(":", 1)[1]
        proc = subprocess.run(
            ["./mvnw", "-o", "-q", "-pl", "exec", "test", f"-Dtest={test_class}"],
            cwd=ROOT, capture_output=True, text=True,
        )
    elif gate == "node":
        proc = subprocess.run(
            ["node", "--test", "test/*.test.ts"],
            cwd=FRONTEND, capture_output=True, text=True,
        )
    else:
        raise SystemExit(f"unknown gate: {gate}")
    tail = (proc.stdout + proc.stderr).strip().splitlines()
    return proc.returncode != 0, "\n".join(tail[-6:])


def cmd_run_unit():
    results = {}
    for defect in DEFECTS:
        print(f"== {defect['id']} ({defect['subtype']}) via {defect['gate']} ==", flush=True)
        try:
            apply_defect(defect)
            detected, tail = run_gate(defect)
        finally:
            restore_file(defect["file"])
        results[defect["id"]] = {
            "subtype": defect["subtype"],
            "category": defect["category"],
            "gate": defect["gate"],
            "unit_detected": 1 if detected else 0,
            "tail": tail,
        }
        print(f"   unit_detected={1 if detected else 0}", flush=True)
    out = OUT / "unit_results.json"
    out.write_text(json.dumps(results, ensure_ascii=False, indent=2))
    print(f"\nwrote {out}")
    # sanity summary
    render_missed = [k for k, v in results.items()
                     if v["subtype"] == "frontend_render" and v["unit_detected"] == 0]
    print(f"render defects missed by unit (expected all 6): {len(render_missed)}")


def cmd_inject(defect_id):
    apply_defect(by_id(defect_id))
    print(f"injected {defect_id} -> {by_id(defect_id)['file']} (remember to restore)")


def cmd_restore(defect_id):
    restore_file(by_id(defect_id)["file"])
    print(f"restored {by_id(defect_id)['file']}")


def cmd_matrix():
    unit_path = OUT / "unit_results.json"
    e2e_path = OUT / "e2e_results.json"
    unit = json.loads(unit_path.read_text())
    e2e = json.loads(e2e_path.read_text()) if e2e_path.exists() else {}
    rows = []
    for defect in DEFECTS:
        did = defect["id"]
        unit_detected = unit[did]["unit_detected"]
        if defect["subtype"] == "frontend_render":
            full_detected = int(e2e.get(did, {}).get("detected", 0))
        else:
            full_detected = unit_detected
        rows.append((did, defect["category"], "unit_only", unit_detected))
        rows.append((did, defect["category"], "full", full_detected))
    out = OUT / "matrix.csv"
    with out.open("w", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(["defect_id", "category", "qa_profile", "detected"])
        writer.writerows(rows)
    print(f"wrote {out} ({len(rows)} rows)")


def cmd_corpus():
    out = OUT / "defects.json"
    out.write_text(json.dumps(DEFECTS, ensure_ascii=False, indent=2))
    print(f"wrote {out} ({len(DEFECTS)} defects)")


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else ""
    if mode == "run-unit":
        cmd_run_unit()
    elif mode == "inject":
        cmd_inject(sys.argv[2])
    elif mode == "restore":
        cmd_restore(sys.argv[2])
    elif mode == "matrix":
        cmd_matrix()
    elif mode == "corpus":
        cmd_corpus()
    else:
        raise SystemExit(__doc__)


if __name__ == "__main__":
    main()
