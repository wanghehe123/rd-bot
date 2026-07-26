#!/usr/bin/env python3
"""四题 django 代理评测：复刻官方 SWE-bench harness 判定流程。

流程（与官方 run_evaluation 一致）：
  1. 按 base_commit 物化干净源码树
  2. git apply 模型补丁（rd-bot 臂 patch.diff；无补丁 = 直接 unresolved）
  3. 将 test_patch 涉及的测试文件重置回 base（官方会丢弃模型对测试文件的修改）
  4. git apply 官方 test_patch
  5. 容器内跑 FAIL_TO_PASS + PASS_TO_PASS，全绿 = resolved

输出 sb-report（resolved_ids + per-instance 详情），供 compute_summary.py 消费。
"""

import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
EVAL_DATA = ROOT / "qa-runs/swebench-lite-10/eval-data-django4.json"
E1 = ROOT / "qa-runs/benchmarks-metrics/e1"
WORK = Path("/tmp/proxy-eval")
IMAGE = "rd-bot/claude-code:local"

BASE_TREES = {
    "django__django-11019": ("/Volumes/WishDisk/codes/swe/repositories/django__django", None),
    "django__django-11001": (str(ROOT / "private-repos/swebench-lite-django__django-11001"), "swebench/django-11001-baseline"),
    "django__django-10914": (str(ROOT / "private-repos/swebench-lite-django__django-10914"), "swebench/django-10914-baseline"),
    "django__django-10924": (str(ROOT / "private-repos/swebench-lite-django__django-10924"), "swebench/django-10924-baseline"),
}


def sh(cmd, cwd=None, check=True, timeout=None):
    r = subprocess.run(cmd, shell=True, cwd=cwd, capture_output=True, text=True, timeout=timeout)
    if check and r.returncode != 0:
        raise RuntimeError(f"cmd failed ({r.returncode}): {cmd}\n{r.stderr[-1500:]}")
    return r


def to_label(entry: str) -> str:
    # "test_x (module.Class)" -> "module.Class.test_x"；已是 label 的原样返回
    m = re.match(r"^(\S+)\s+\((\S+?)\)$", entry.strip())
    return f"{m.group(2)}.{m.group(1)}" if m else entry.strip()


def test_files_of(patch_text: str):
    return re.findall(r"^diff --git a/(\S+)", patch_text, re.M)


def evaluate(inst: str, data: dict, patch_file: Path):
    src, ref = BASE_TREES[inst]
    base_commit = data["base_commit"]
    wd = WORK / inst
    sh(f"rm -rf {wd} && mkdir -p {wd}")
    tree_ref = ref or base_commit
    sh(f"git -C {src} archive --format=tar {tree_ref} | tar -x -C {wd}")
    sh("git init -q && git add -A && git -c user.email=e@v.al -c user.name=eval commit -qm base", cwd=wd)

    detail = {"instance_id": inst, "resolved": False}
    if not patch_file.exists() or patch_file.stat().st_size == 0:
        detail["status"] = "no_patch"
        return detail
    # 2. 模型补丁
    r = sh(f"git apply --whitespace=nowarn {patch_file}", cwd=wd, check=False)
    if r.returncode != 0:
        detail["status"] = "model_patch_apply_failed"
        detail["error"] = r.stderr[-500:]
        return detail
    # 3. 官方口径：test_patch 涉及的测试文件重置回 base
    for f in test_files_of(data["test_patch"]):
        sh(f"git checkout HEAD -- {f} 2>/dev/null || rm -f {f}", cwd=wd, check=False)
    # 4. 官方 test_patch
    tp = wd / "official-test.patch"
    tp.write_text(data["test_patch"])
    r = sh("git apply --whitespace=nowarn official-test.patch", cwd=wd, check=False)
    if r.returncode != 0:
        detail["status"] = "test_patch_apply_failed"
        detail["error"] = r.stderr[-500:]
        return detail
    # 5. 跑测试：模块级（test_patch 涉及的测试模块整体跑，与官方 harness 一致；
    #    个别 F2P/P2P 条目是 docstring 描述而非测试 ID，无法作为 label 传入）
    modules = []
    for f in test_files_of(data["test_patch"]):
        if f.startswith("tests/") and f.endswith(".py"):
            modules.append(f[len("tests/"):-3].replace("/", "."))
    labels = list(dict.fromkeys(modules))
    argv = [
        "docker", "run", "--rm", "--entrypoint", "python3",
        "-e", "PYTHONPATH=/work/repo",
        "-v", f"{wd}:/work/repo", "-w", "/work/repo/tests", IMAGE,
        "runtests.py", "--verbosity", "1", "--parallel", "1", *labels,
    ]
    try:
        r = subprocess.run(argv, capture_output=True, text=True, timeout=1800)
    except subprocess.TimeoutExpired:
        detail["status"] = "timeout"
        return detail
    tail = (r.stderr + r.stdout)[-3000:]
    full = r.stderr + r.stdout
    detail["exit"] = r.returncode
    detail["output_tail"] = tail[-1200:]
    m = re.search(r"Ran (\d+) tests?", tail)
    detail["ran"] = int(m.group(1)) if m else None
    detail["resolved"] = r.returncode == 0 and bool(re.search(r"\nOK", tail))
    detail["status"] = "resolved" if detail["resolved"] else "tests_failed"
    fails = re.findall(r"(?:FAIL|ERROR): (.+)", full)
    detail["failing"] = sorted(set(f.strip() for f in fails))
    return detail


def main():
    data = json.loads(EVAL_DATA.read_text())
    arm = sys.argv[1] if len(sys.argv) > 1 else "rd-bot"
    report, resolved = {}, []
    for inst, d in data.items():
        if arm == "gold":
            patch = WORK / f"gold-{inst}.patch"
            WORK.mkdir(parents=True, exist_ok=True)
            patch.write_text(d["gold_patch"])
        else:
            patch = E1 / inst / arm / "patch.diff"
        print(f"== {inst} ({arm}) ==", flush=True)
        detail = evaluate(inst, d, patch)
        report[inst] = detail
        if detail["resolved"]:
            resolved.append(inst)
        print(f"   -> {detail['status']}", flush=True)
    out = ROOT / f"qa-runs/swebench-lite-10/sb-report-{arm}.json"
    out.write_text(json.dumps({"resolved_ids": resolved, "instances": report}, indent=1, ensure_ascii=False))
    print(f"report -> {out}\nresolved: {len(resolved)}/{len(report)}")


if __name__ == "__main__":
    main()
