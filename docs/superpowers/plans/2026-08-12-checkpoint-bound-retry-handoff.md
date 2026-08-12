# 2026-08-12 检查点绑定重试交接（已归档）

> **状态：已被后续实现与真实 GitHub 冒烟取代。** 不要按本文第 3 节继续 POST retry，也不要把 version/fence 数字当作当前真值。
>
> 现行护栏与 2026-08-13 验证证据：
> `docs/superpowers/specs/2026-08-13-requirement-publication-preflight-and-retry-provenance-spec.md`
>
> 冻结实现蓝图仍是：
> `docs/superpowers/plans/2026-08-11-checkpoint-bound-retry-dispatch-implementation.md`
> （主路径已通；全量 HTTP/concurrency 矩阵仍未宣称完成）

## 当时阻断（历史）

任务 `7493233919224057856` 在旧运行时耗尽后没有匹配 provenance，`failure-recovery` 409。后来补了耗尽/发布 provenance，又在发布预检把 GitHub 422 误判为 `UNKNOWN_REMOTE_RESULT`。该任务不要再重试发布。

## 后来实际完成的路径

新建等价 docs-only 任务 `7493354884037742592`（同一 GitHub 项目 `7487468535443230720`）一次 submit 后 COMPLETED，PR #9。根因与护栏只维护在 2026-08-13 spec，避免与本文旧时间线重复。
