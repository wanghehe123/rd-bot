# RD 任务控制面增强验收报告

日期：2026-07-10

关联文档：

- `docs/superpowers/specs/2026-07-10-rd-task-control-plane-enhancements-design.md`
- `docs/qa/rd-task-control-plane-enhancements-acceptance-plan-2026-07-10.md`
- `docs/superpowers/plans/2026-07-10-project-alert-recipients-and-cny-implementation-plan.md`
- `docs/superpowers/plans/2026-07-10-rd-task-control-plane-enhancements-implementation-plan.md`
- `docs/superpowers/specs/2026-07-10-rd-task-control-plane-enhancements-lessons-spec.md`

## 1. 结论

| 范围 | 结论 | 说明 |
| --- | --- | --- |
| A Bug 四阶段记录 | PASS | 四角色、上下文包、INPUT/PROMPT/RESULT、失败重试 attempt、任务类型隔离均有测试和 HTTP 证据 |
| B 项目飞书告警 | PASS_WITH_EXTERNAL_GAP | 配置、独立群聊/用户收件人、CNY 阈值、路由、幂等、PENDING 重试、审计和脱敏通过；真实飞书投递未执行 |
| C 图片材料 | PASS | 原字节对象存储、hash、MIME、HTML attachment、同名文件、symlink、执行附件和浏览器预览通过 |
| D 模板与 AI 草稿 | PASS | PostgreSQL 双模板、重启读取、严格 JSON、无密钥降级、不自动建任务和响应式交互通过 |
| E 回归与安全 | PASS | Maven、前端、SQL 双执行、diff、secret 与 production-store policy 通过 |

唯一外部缺口为真实 Feishu 消息 B-07。没有使用伪造 `CHAT_ID/OPEN_ID` 产生外部副作用，也没有把该项记为通过。

## 2. 自动化证据

```bash
./mvnw -q test
cd frontend && npm run typecheck && npm run build
git diff --check
```

结果：

- Maven：892 tests，0 failures，0 errors，22 skipped。
- Frontend：TypeScript 与 Vite build exit 0；仅保留 bundle 大于 500 KiB 的非阻断 warning。
- 静态 bundle 已确认包含 `rd-task-drafts/complete`、`应用模板`、`AI 补全`。
- `git diff --check` 通过；新增 diff 未发现 secret-like 明文。

重点新增测试覆盖：

- `BugFixStageRecorderTest`、`RdBotFixEngineTest`、`RdTaskExecutionOverviewControllerTest`
- `ProjectAwareFeishuRepairAlertSinkTest`、`PostgresRdAlertDeliveryStoreTest`
- `RdTaskControllerTest`、`TaskMaterialAttachmentResolverTest`、`RepairWorkspaceFactoryTest`
- `RdProjectTaskTemplateServiceTest`、`TaskDraftEngineTest`、对应 controller/store tests

## 3. 真实 HTTP、DB 与对象证据

### Bug 阶段与图片

- Task：`7481166106292523008`
- Material：`7481166138022432768`
- 原图 SHA-256：`bee39f2427c752b0335427ed6dd697cb9425ab002c3fad8c909cb054cd5fc58a`
- 上传后下载 SHA-256：与原图一致。
- 伪 PNG 上传：HTTP 400，`upload filename extension does not match content type`。
- HTML 材料：HTTP 200，`Content-Disposition: attachment; filename="index.html"`。
- Execution overview：四个 Bug 角色均可见；执行中进度 `21/24`，前三阶段 `SUCCEEDED`、编码阶段 `RUNNING`。
- 受控 Docker provider 容器真实启动；验收结束后调用 stop 并确认容器退出，未把未完成 provider 执行写成成功。

### 项目告警与模板

- Project：`7479447343427883008`
- `PUT/GET /admin/projects/{projectId}/alert-config`：HTTP 200。
- 当前 GET/DB：1 个 `OPEN_ID` 收件人、6 个事件、CNY 预算阈值 `54.0000`、失败阈值 `3`。
- Bug/Requirement 模板分别 PUT/GET，DB 各 2 条验收标准。
- 重启 PostgreSQL 模式 bootstrap 后再次 GET，模板内容保持不变。
- 真实联调发现并修复 `RdProjectTaskTemplateMapper.find` 缺失 `@Param` 的 500 问题。

### AI 草稿

- `POST /admin/rd-task-drafts/complete`：HTTP 200，未配置 credential 时 `available=false`、`aiGenerated=false`。
- 调用前后 PostgreSQL `rd_tasks` 数量为 `97 -> 97`，证明草稿接口不创建、不提交任务。
- 合法结果严格要求单个 JSON object、任务类型必填字段、字符串数组和 `0..1` confidence；尾随 token/code fence 被拒绝。

### SQL

`p0_knowledge_productionization.sql` 对本地 PostgreSQL 连续执行，均 exit 0。

重复执行同时暴露并修复历史 `t_ingestion_pipeline_node.id/created_by/updated_by` 字段过窄问题；修复后 PostgreSQL 模式 bootstrap 可正常启动。

## 4. 浏览器证据

浏览器在 Spring Boot 静态 bundle 上执行，不是直接读取 React 源码：

- Desktop `1440x900`：项目列表、告警弹窗、模板弹窗均无页面横向溢出。
- Mobile `390x844`：新建任务弹窗 `scrollWidth=clientWidth=390`、`overlapCount=0`。
- 图片预览：`naturalWidth=2576`、`naturalHeight=1456`，非空。
- 模板交互：先填“用户已经填写，禁止覆盖”，应用项目模板后该值保持不变，空的期望表现被填为“填写修复后的表现”。
- Console：本轮首次打开新收件人编辑器时发现缺少 `TooltipProvider`，已修复；重新构建并同步静态资源后，重复打开、添加与删除收件人不会产生新的 console error。

### 项目告警收件人与人民币预算

- Spring Boot 静态 bundle 已重新构建并同步到实际服务资源目录；`http://127.0.0.1:18082/admin/projects` 的“飞书告警”弹窗可正常打开。
- 弹窗将收件人拆分为“群聊告警”和“个人用户告警”。两侧都有可访问的加号按钮；群聊加号只新增 `oc_xxx` 输入行，个人用户原有 `ou_xxx` 行可删除至空，未保存测试变更已取消。
- 宽度受限视口下，弹窗内容区域内部滚动，取消/保存按钮仍可用；预算字段显示“预算阈值（元）”，当前值为 `54`。
- `GET /admin/projects/7479447343427883008/alert-config` 返回 `budgetThresholdCny=54.0000`，不再返回公共 USD 阈值字段。
- SQL 在本地 PostgreSQL 连续执行两次，结果保持 `budget_threshold_usd=7.5000`、`budget_threshold_cny=54.0000`，证明存量数据没有重复换算。
- 未触发真实 Feishu 发送，外部投递仍维持 `BLOCKED_EXTERNAL/NOT_VERIFIED`。

### 新建任务图片、模板与 AI 开发链路

- 图片选择改为待上传缩略图卡片：支持追加、去重、逐张删除，前端限制 PNG/JPEG/WebP/GIF、单张 10 MiB、总数 10 张；删除后的文件不会进入创建后的上传循环。
- 新增 3 个图片状态测试，覆盖追加/去重、非法类型、超限文件、数量上限和单张删除；连同代理、通知宿主和收件人测试共 8 个 Node tests，全部通过。
- 根因 1：页面调用 `sonner.toast`，但 App 只挂载旧 `ToastHost`，所以“请先选择项目”和模板成功反馈都不可见。现已同时挂载 Sonner `Toaster` 和旧宿主。
- 根因 2：Vite 未配置 `/admin/rd-task-drafts`，POST 被 5173 自己处理为 404。新增代理后，`POST http://127.0.0.1:5174/admin/rd-task-drafts/complete` 返回 HTTP 200。
- 当前 AI 模型凭证未配置，HTTP 200 响应为 `available=false`、`reason=task draft model credential is not configured`；浏览器能显示该原因，不再显示 404。
- 模板接口经 Vite 返回 HTTP 200；浏览器确认未选项目时提示可见，选择 `Codex 外卖验收项目` 后显示应用成功并把实际现象填为 `填写用户可见现象`。
- 浏览器控制环境禁止自动注入本地文件，因此真实文件缩略图的最后一步保留为运行页面上的人工确认；选择状态、删除语义、构建与类型检查均已自动化验证。

## 5. gpt-5.6-luna CR

按 A/B/C/D/QA-E 五个独立范围完成两轮复审。第一轮发现并修复：

- SEARCHING 失败不能进入 `FAILED_RETRYABLE`、概览混入异类角色、缺上下文包和失败原因展示。
- 告警审计竞态、项目预算阈值、全局回退、错误脱敏和审计更新不确定性。
- 附件 target symlink、同名覆盖、MIME/HTML、原字节 hash 未复核。
- AI 无生产适配器、结构校验过松、旧响应覆盖新表单、静态 bundle 未同步。

所有 P0/P1 均修复后重跑自动化和实链。真实 Feishu/provider 外部成功项仍按 `BLOCKED_EXTERNAL/NOT_VERIFIED` 记录。

## 6. 残余风险与补测

1. 真实 Feishu：需要已授权收件人后执行一条脱敏消息，反查 `rd_alert_deliveries.provider_message_id`。
2. Feishu 跨实例并发：当前有分布式锁、PostgreSQL 唯一键和 provider `uuid`；仍建议在双实例预发环境压测。
3. 真实 AI 草稿 provider：需要设置对应环境变量后验证一次合法 JSON 和一次非法响应，不得在日志记录 credential。
4. 前端 bundle 较大：不影响本次功能，但后续可按页面拆分动态 import。

## 7. 可复跑入口

当前本地 PostgreSQL 模式管理端：`http://127.0.0.1:18082/admin/projects`。

真实 Feishu 补测必须先由操作者确认接收目标，随后执行验收计划 B-07，不允许使用仓库文本保存收件人或密钥。
