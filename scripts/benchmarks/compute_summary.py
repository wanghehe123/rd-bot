#!/usr/bin/env python3
"""简历指标汇总计算器：所有【待填写】数字的唯一计算出口（公式见 spec 文档第 3 章）。

用法::

    python3 scripts/benchmarks/compute_summary.py \
        --e1-metrics qa-runs/benchmarks-metrics/e1/metrics.jsonl \
        --sb-report-claude qa-runs/swebench-lite-10/sb-report-claude-code.json \
        --sb-report-rdbot qa-runs/swebench-lite-10/sb-report-rd-bot.json \
        --e2-dir qa-runs/interrupt-recovery/20260726 \
        --e4-matrix qa-runs/qa-detection-rate/20260727/matrix.csv \
        --e5-metrics qa-runs/benchmarks-metrics/e5/metrics.jsonl \
        --out qa-runs/benchmarks-metrics/summary.md

所有输入均可选：给什么算什么，缺的实验在报告中标 N/A。
sb-report 为 sb-cli / 官方 harness 的 report.json（含 resolved_ids 或 per-instance resolved 布尔）。
"""

import argparse
import csv
import json
from pathlib import Path


def load_jsonl(path):
    if not path or not Path(path).exists():
        return []
    return [json.loads(l) for l in Path(path).read_text().splitlines() if l.strip()]


def resolved_ids(report_path):
    if not report_path or not Path(report_path).exists():
        return None
    report = json.loads(Path(report_path).read_text())
    if "resolved_ids" in report:
        return set(report["resolved_ids"])
    if "resolved" in report and isinstance(report["resolved"], list):
        return set(report["resolved"])
    # sb-cli per-instance 结构：{instance_id: {"resolved": bool}}
    return {k for k, v in report.items() if isinstance(v, dict) and v.get("resolved")}


def pct(numerator, denominator):
    return "N/A" if not denominator else f"{100.0 * numerator / denominator:.0f}%"


def mean(values):
    values = [v for v in values if v is not None]
    return None if not values else round(sum(values) / len(values), 1)


def section_e1(rows, resolved_cc, resolved_rd, lines):
    lines.append("## E1 SWE-bench Lite 配对基准测试\n")
    instances = sorted({r["instance"] for r in rows})
    if not instances:
        lines.append("N/A（无 metrics.jsonl 数据）\n")
        return
    by_key = {(r["instance"], r["arm"]): r for r in rows}
    n = len(instances)
    cc_resolved = rd_resolved = 0
    table = ["| instance | resolved(CC) | resolved(RD) | CC 分钟 | RD 分钟(有效) | RD attempts | 门禁拦截 |",
             "|---|---|---|---|---|---|---|"]
    quadrant = {"both": 0, "cc_only": 0, "rd_only": 0, "neither": 0}
    for instance in instances:
        cc = by_key.get((instance, "claude-code"), {})
        rd = by_key.get((instance, "rd-bot"), {})
        cc_ok = resolved_cc is not None and instance in resolved_cc
        rd_ok = resolved_rd is not None and instance in resolved_rd
        cc_resolved += cc_ok
        rd_resolved += rd_ok
        key = ("both" if cc_ok and rd_ok else "cc_only" if cc_ok
               else "rd_only" if rd_ok else "neither")
        quadrant[key] += 1
        table.append(
            f"| {instance} | {'✅' if cc_ok else '❌'} | {'✅' if rd_ok else '❌'} "
            f"| {cc.get('wallClockMinutes', 'N/A')} | {rd.get('effectiveMinutes', 'N/A')} "
            f"| {rd.get('totalAttempts', 'N/A')} | {'是' if rd.get('gateHit') else '否'} |")
    lines.extend(table)
    lines.append("")
    if resolved_cc is not None and resolved_rd is not None:
        lines.append(f"- **简历口径 resolved：RD-Bot {rd_resolved}/{n} vs Claude Code {cc_resolved}/{n}**")
        lines.append(f"- 四象限：共同成功 {quadrant['both']} / 仅 CC {quadrant['cc_only']} "
                     f"/ 仅 RD-Bot {quadrant['rd_only']} / 都失败 {quadrant['neither']}")
    else:
        lines.append("- resolved：待 sb-cli 报告（--sb-report-*）")
    cc_minutes = mean([by_key.get((i, "claude-code"), {}).get("wallClockMinutes") for i in instances])
    rd_minutes = mean([by_key.get((i, "rd-bot"), {}).get("effectiveMinutes") for i in instances])
    lines.append(f"- **平均耗时：RD-Bot {rd_minutes} 分钟（有效口径） vs Claude Code {cc_minutes} 分钟**")
    gate_hits = sum(1 for i in instances if by_key.get((i, "rd-bot"), {}).get("gateHit"))
    lines.append(f"- 门禁拦截：{gate_hits}/{n}（F1 修复后预期为 0）\n")


def section_e3(rows, lines):
    lines.append("## E3 交付完整率（三件套：补丁 + 回归测试 + 验收证据）\n")
    for arm, label in (("rd-bot", "流水线（RD-Bot）"), ("claude-code", "单次直出（Claude Code）")):
        arm_rows = [r for r in rows if r["arm"] == arm]
        if not arm_rows:
            lines.append(f"- {label}：N/A")
            continue
        complete = sum(1 for r in arm_rows if r.get("deliveryComplete"))
        detail = (f"补丁 {sum(1 for r in arm_rows if r.get('hasPatch'))}"
                  f" / 回归测试 {sum(1 for r in arm_rows if r.get('hasRegressionTest'))}"
                  f" / 验收证据 {sum(1 for r in arm_rows if r.get('hasAcceptanceEvidence'))}")
        lines.append(f"- **{label}：{pct(complete, len(arm_rows))}（{complete}/{len(arm_rows)}）**（{detail}）")
    lines.append("")


def section_e2(e2_dir, lines):
    lines.append("## E2 强制中断恢复实验\n")
    cases = []
    if e2_dir and Path(e2_dir).exists():
        for path in sorted(Path(e2_dir).glob("case-*.json")):
            if path.name.endswith(("-pre.json", "-post.json", "-zero-rerun.json")):
                continue
            cases.append(json.loads(path.read_text()))
    if not cases:
        lines.append("N/A（无 case 数据）\n")
        return
    n = len(cases)
    resumed = sum(1 for c in cases if c.get("resumeSuccess"))
    zero = sum(1 for c in cases if c.get("zeroRerun"))
    latency = mean([c.get("recoverySeconds") for c in cases])
    lines.append(f"- **{n} 次强制中断，断点续跑成功率 {pct(resumed, n)}（{resumed}/{n}），对照组 Claude Code 无状态模式 0%**")
    lines.append(f"- 已成功阶段零重跑率 {pct(zero, n)}（{zero}/{n}）；平均恢复时延 {latency} 秒")
    by_scenario = {}
    for c in cases:
        by_scenario.setdefault(c.get("scenario"), []).append(c)
    for scenario, group in sorted(by_scenario.items()):
        ok = sum(1 for c in group if c.get("resumeSuccess"))
        lines.append(f"  - {scenario}: {ok}/{len(group)}")
    lines.append("")


def section_e4(matrix_path, lines):
    lines.append("## E4 QA 检出率（缺陷注入）\n")
    if not matrix_path or not Path(matrix_path).exists():
        lines.append("N/A（无 matrix.csv 数据）\n")
        return
    # matrix.csv 列：defect_id,category(backend|frontend),qa_profile(unit_only|full),detected(0|1)
    rows = list(csv.DictReader(Path(matrix_path).open()))
    for profile, label in (("unit_only", "A 组·仅单测"), ("full", "B 组·单测+Playwright")):
        group = [r for r in rows if r["qa_profile"] == profile]
        detected = sum(int(r["detected"]) for r in group)
        frontend = [r for r in group if r["category"] == "frontend"]
        fe_missed = sum(1 for r in frontend if r["detected"] == "0")
        lines.append(f"- **{label}：检出率 {pct(detected, len(group))}（{detected}/{len(group)}）**，"
                     f"前端缺陷漏检 {fe_missed}/{len(frontend)}")
    unit_fe = [r for r in rows if r["qa_profile"] == "unit_only" and r["category"] == "frontend"]
    full_fe = [r for r in rows if r["qa_profile"] == "full" and r["category"] == "frontend"]
    if unit_fe and full_fe:
        miss_a = 100.0 * sum(1 for r in unit_fe if r["detected"] == "0") / len(unit_fe)
        miss_b = 100.0 * sum(1 for r in full_fe if r["detected"] == "0") / len(full_fe)
        lines.append(f"- **前端渲染缺陷漏检率下降 {miss_a - miss_b:.0f} 个百分点（{miss_a:.0f}% → {miss_b:.0f}%）**")
    lines.append("")


def section_e5(rows, lines):
    lines.append("## E5 Mini Jira 自建 Java 场景\n")
    if not rows:
        lines.append("N/A（无 metrics 数据）\n")
        return
    n = len(rows)
    ok = sum(1 for r in rows if r.get("finalStatus") == "COMPLETED" and r.get("deliveryComplete"))
    minutes = mean([r.get("effectiveMinutes") for r in rows])
    lines.append(f"- **{n} 题，成功率 {pct(ok, n)}（{ok}/{n}），平均耗时 {minutes} 分钟（有效口径）**\n")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--e1-metrics")
    parser.add_argument("--sb-report-claude")
    parser.add_argument("--sb-report-rdbot")
    parser.add_argument("--e2-dir")
    parser.add_argument("--e4-matrix")
    parser.add_argument("--e5-metrics")
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    lines = ["# 简历指标汇总（自动生成，勿手改）",
             "", "> 生成命令与输入文件路径即证据链；每个数字可回溯到落盘产物。", ""]
    e1_rows = load_jsonl(args.e1_metrics)
    section_e1(e1_rows, resolved_ids(args.sb_report_claude), resolved_ids(args.sb_report_rdbot), lines)
    section_e2(args.e2_dir, lines)
    section_e3(e1_rows, lines)
    section_e4(args.e4_matrix, lines)
    section_e5(load_jsonl(args.e5_metrics), lines)

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(lines) + "\n")
    print(f"summary written -> {out}")


if __name__ == "__main__":
    main()
