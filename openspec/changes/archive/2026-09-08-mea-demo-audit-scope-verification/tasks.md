## 1. 两项修复

- [x] 1.1 增加历史 QA 与异轮/无来源 head 记录的失败回归；实现明确任务 scope、来源显示及 Attempt 问题摘要隔离。覆盖 PENDING/BLOCKED/UNTRUSTED 及只有任务阻断的展示。
- [x] 1.2 增加错误 HTTP 状态反向回归；修复正例 200、负例 404、请求身份与错误/SKIP 区分。真实分页及路由合同回归补充后 Python HTTP 回归 9/9，错误状态与错误身份均不再误 PASS。
- [x] 1.4 修正真机发现的 CANCELLED 卡片图标误标，验证实际已取消阶段；不扩展取消状态机。5a3 包部署后，旧 07l Coding/QA 卡片均真实显示已取消。
- [x] 1.5 补齐默认产物页 QA evidence 加载与选中 Attempt 完整结果解析，覆盖截断 JSON、读取失败及过期响应；以同一真实已完成任务复验。
- [x] 1.3 运行相关测试、前端全量测试/typecheck/build、OpenSpec 严格校验，并记录结果。前端 247/247、typecheck/build 通过，OpenSpec 21/0。

## 2. 组合构建与真实业务验证

- [x] 2.1 生成包含全部已有及本轮修复的新 jar，记录源码差异与构建 hash，验证 jar 内前端资源和关键后端类。最终 jar d707cdafa1d470a21f193131fef49323098bc3650459b53efd4243f1f0c56de4，50 个静态资源一致，当前 NEED_INFO 优先协议类已打包。
- [x] 2.2 核对真实环境和实验占用，使用同一候选验证真实业务读取及桌面页面；不得中断他人实验。
- [x] 2.3 执行一条普通真实需求，记录 taskId、实际状态、证据及交付产物；失败不得伪装通过。7502920392433078272 COMPLETED，PR #40 OPEN，两个 AC 经 QA 与宿主审计完成，23 条 QA 证据；四张桌面/移动端截图已保存。
- [x] 2.4 保存 QA 报告，明确通过与未验证范围；不自动 commit、push 或 archive。见 docs/superpowers/qa/2026-09-08-mea-demo-closeout-and-live-verification.md；业务通过，最终 d707 包的默认证据/全文读取、Attempt 切换、MEA 与 PR 入口均已真机复验。
