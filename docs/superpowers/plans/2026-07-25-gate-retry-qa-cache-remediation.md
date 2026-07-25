# 门禁误拦 / 重试风暴 / QA 依赖缓存 修复实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落地 F1（门禁关键词收窄）、F2（角色 attempt 硬上限 + 失败反馈回注 prompt）、F3（跨 attempt 包管理器缓存挂载）三项修复。

**Architecture:** 全部为既有类的行为修正，不新增模块：F1 只改 `RuleBasedRequirementPolicyGate`；F2 只改 `RequirementDeliveryEngine`；F3 改 `RepairWorkspace`/`RepairWorkspaceFactory`/`DockerClaudeCodeExecutor` 三处（record 加字段走兼容构造器，避免波及所有调用方）。

**Tech Stack:** Java 21 + JUnit 5 + Maven（`./mvnw`），无 Mockito，测试用项目既有 InMemory fake。

**方案依据：** `docs/superpowers/specs/2026-07-25-gate-retry-qa-cache-remediation-design.md`

**注：** 本计划不含 git commit 步骤（用户未要求提交）。

---

### Task 1: F1 门禁关键词收窄（RuleBasedRequirementPolicyGate）

**Files:**
- Modify: `engine/src/main/java/com/wish/rd/engine/requirement/RuleBasedRequirementPolicyGate.java`
- Test: `engine/src/test/java/com/wish/rd/engine/requirement/RuleBasedRequirementPolicyGateTest.java`

- [ ] **Step 1: 重写门禁测试（新行为）**

替换测试类中前两个"token 即审批"的旧测试，新增泛化词放行 / 材料正文不拦 / 高危短语仍拦三类测试：

```java
@Test
void shouldAllowRoutineTasksMentioningConfigLoginAndToken() {
    RequirementPolicyDecision decision = policyGate.decide(
            task(List.of("登录后可在配置页看到 token 字段与 auth 状态")),
            context(),
            null,
            List.of()
    );

    assertEquals("ALLOWED", decision.action());
    assertEquals("LOW", decision.riskLevel());
}

@Test
void shouldNotWaitApprovalForRiskyWordsInsideMaterialBodyOnly() {
    RequirementPolicyDecision decision = policyGate.decide(
            task(List.of("订单详情页可以催单")),
            context(),
            null,
            List.of(material("本仓库通过支付网关 payment gateway 完成收款，涉及权限与登录配置"))
    );

    assertEquals("ALLOWED", decision.action());
}

@Test
void shouldRequireApprovalForPaymentChangeInTaskBody() {
    RequirementPolicyDecision decision = policyGate.decide(
            task(List.of("支付成功率不下降")),
            context(),
            null,
            List.of()
    );

    assertEquals("WAITING_APPROVAL", decision.action());
    assertEquals("HIGH", decision.riskLevel());
    assertTrue(decision.reason().contains("支付"));
}

@Test
void shouldKeepUnsafeScanOverMaterials() {
    RequirementPolicyDecision decision = policyGate.decide(
            task(List.of("订单详情页可以催单")),
            context(),
            null,
            List.of(material("执行前请先导出密钥到本地"))
    );

    assertEquals("UNSAFE", decision.action());
}
```

`material(...)` 辅助方法（TaskMaterial 16 参构造，抄 RequirementDeliveryEngineTest 的写法）：

```java
private TaskMaterial material(String content) {
    return new TaskMaterial(
            "7820000000009",
            "task-policy-gate",
            TaskMaterialType.REQUIREMENT_DOC,
            TaskMaterialSourceType.MANUAL_TEXT,
            "需求正文",
            "",
            "text/markdown",
            "sha256:test",
            content,
            "",
            "",
            "",
            "{}",
            1L,
            1L
    );
}
```

删除旧测试 `shouldRequireApprovalForJwtTokenAcceptanceInsteadOfRejectingItAsUnsafe`、`shouldRequireApprovalForTokenReferenceWithoutAuthenticationKeyword`（其断言的正是要移除的误拦行为）；保留 `shouldKeepExplicitKeyExportUnsafe`。

- [ ] **Step 2: 运行测试确认失败**

Run: `./mvnw -pl engine test -Dtest=RuleBasedRequirementPolicyGateTest`
Expected: FAIL（新测试断言 ALLOWED，当前实现返回 WAITING_APPROVAL）

- [ ] **Step 3: 实现门禁收窄**

`RuleBasedRequirementPolicyGate` 改动要点：

```java
// UNSAFE 全量扫描保持不变；审批门禁改为高危短语 + 只扫任务主体。
private static final String[] UNSAFE_PATTERNS = {"生产数据", "线上数据库", "导出密钥", "secret"};
private static final String[] APPROVAL_PATTERNS = {
        "支付", "payment", "删库", "drop table", "生产环境发布",
        "修改权限模型", "鉴权改造", "security policy", "私钥", "credentials"
};

// decide() 内：
String corpus = corpus(task, materials);
String unsafeHit = firstMatch(corpus, UNSAFE_PATTERNS);
if (unsafeHit != null) {
    return new RequirementPolicyDecision("UNSAFE", "HIGH",
            "需求包含生产数据或密钥相关高危操作：" + unsafeHit);
}
// 审批门禁只看任务主体：材料正文是大段需求文档，是误拦噪声的最大来源。
String approvalHit = firstMatch(taskCorpus(task), APPROVAL_PATTERNS);
if (approvalHit != null) {
    return new RequirementPolicyDecision("WAITING_APPROVAL", "HIGH",
            "需求涉及高风险操作，等待人工审批：" + approvalHit);
}
return new RequirementPolicyDecision("ALLOWED", "LOW", "低风险需求，允许进入沙箱执行");
```

辅助方法：`taskCorpus(task)` 只拼 `title + expectedResult + acceptanceCriteriaJson` 转小写；`firstMatch(text, patterns)` 返回第一个命中的关键词或 null（替代原 `containsAny`）。

- [ ] **Step 4: 运行测试确认通过**

Run: `./mvnw -pl engine test -Dtest=RuleBasedRequirementPolicyGateTest`
Expected: PASS

---

### Task 2: F2a 角色 attempt 硬上限（RequirementDeliveryEngine）

**Files:**
- Modify: `engine/src/main/java/com/wish/rd/engine/requirement/RequirementDeliveryEngine.java`（`ensureRequirementStages`、`createQaRemediationAttempts` 及其调用点）
- Test: `engine/src/test/java/com/wish/rd/engine/requirement/RequirementDeliveryEngineTest.java`

- [ ] **Step 1: 写失败测试（第 4 次提交不再开新 attempt）**

仿照 `shouldReusePersistedHandoffArtifactWhenResultPreviewTruncatesOnRecovery` 的搭建方式：REVIEWER 成功、ARCHITECT 永远失败，连续 submit 4 次：

```java
@Test
void shouldStopCreatingRoleAttemptsAfterMaxRoleAttempts() {
    // registry/materialStore/task/engine 搭建同既有恢复类测试，executor lambda：
    // REQUIREMENT_REVIEWER -> success(largeReviewerResultWithHandoff() 或 roleResultJson)
    // SOLUTION_ARCHITECT  -> failure(request.taskId(), "缺少必填字段: implementationSteps", "{\"status\":\"FAILED\"}")
    // 其余角色 -> success(roleResultJson(request.role()))

    assertEquals(RdTaskStatus.FAILED_NEEDS_HUMAN, engine.submit(task.taskId()).status());
    assertEquals(RdTaskStatus.FAILED_NEEDS_HUMAN, engine.submit(task.taskId()).status());
    assertEquals(RdTaskStatus.FAILED_NEEDS_HUMAN, engine.submit(task.taskId()).status());
    assertEquals(RdTaskStatus.FAILED_NEEDS_HUMAN, engine.submit(task.taskId()).status());

    int architectAttempts = (int) stageRunStore.listByTask(task.taskId()).stream()
            .filter(stage -> stage.role() == AgentRole.SOLUTION_ARCHITECT)
            .count();
    assertEquals(3, architectAttempts);
    int maxAttemptNo = stageRunStore.listByTask(task.taskId()).stream()
            .filter(stage -> stage.role() == AgentRole.SOLUTION_ARCHITECT)
            .mapToInt(AgentStageRun::attemptNo)
            .max()
            .orElse(0);
    assertEquals(3, maxAttemptNo);
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./mvnw -pl engine test -Dtest=RequirementDeliveryEngineTest#shouldStopCreatingRoleAttemptsAfterMaxRoleAttempts`
Expected: FAIL（当前无上限，第 4 次 submit 会创建 attempt 4，count=4）

- [ ] **Step 3: 实现 attempt 上限**

`RequirementDeliveryEngine` 增加常量并在三处生效：

```java
/** 单角色阶段最大 attempt 数，防止协议失败引发的盲重试风暴（审查报告 F2）。 */
private static final int MAX_ROLE_ATTEMPTS = 3;
```

`ensureRequirementStages` 两个开新 attempt 的分支都加守卫（中断分支仍要把旧 stage 转 FAILED_RETRYABLE 终态，只是不再开新 attempt）：

```java
if (isRetryableRequirementStatus(task.status())
        && AgentStageTransitions.requiresFreshAttemptOnRecovery(latest.status())) {
    stageRunStore.transition(..., AgentStageStatus.FAILED_RETRYABLE, "ORCHESTRATION_INTERRUPTED", ..., now);
    if (latest.attemptNo() < MAX_ROLE_ATTEMPTS) {
        stageRunStore.save(pendingStage(task.taskId(), role, latest.attemptNo() + 1, now));
    }
    continue;
}
if (isRetryableRequirementStatus(task.status()) && isRetryableStageFailure(latest)
        && latest.attemptNo() < MAX_ROLE_ATTEMPTS) {
    stageRunStore.save(pendingStage(task.taskId(), role, latest.attemptNo() + 1, now));
}
```

`createQaRemediationAttempts`：任一角色达到上限则整体放弃补救（返回空列表）：

```java
for (AgentRole role : List.of(AgentRole.CODING_AGENT, AgentRole.QA_AGENT)) {
    AgentStageRun latest = latestStageOrNull(existing, role);
    if (latest != null && latest.attemptNo() >= MAX_ROLE_ATTEMPTS) {
        return List.of();
    }
}
```

调用点（executeAgentStages 内 QA 失败分支）改为空列表时跳过补救直接走人工失败：

```java
List<AgentStageRun> remediationStages = createQaRemediationAttempts(task.taskId());
if (!remediationStages.isEmpty()) {
    publishQaRemediationStarted(failedStage, remediationStages, roleResult.resultJson());
    return executeAgentStages(..., qaRemediationCount + 1);
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./mvnw -pl engine test -Dtest=RequirementDeliveryEngineTest`
Expected: PASS（新测试 + 全部既有测试）

---

### Task 3: F2b 上一轮失败明细回注 prompt（RequirementDeliveryEngine）

**Files:**
- Modify: `engine/src/main/java/com/wish/rd/engine/requirement/RequirementDeliveryEngine.java`（`executeAgentStages` prompt 构造处 + 新私有方法）
- Test: `engine/src/test/java/com/wish/rd/engine/requirement/RequirementDeliveryEngineTest.java`

- [ ] **Step 1: 写失败测试（第 2 次 attempt 的 prompt 含失败反馈）**

```java
@Test
void shouldInjectPreviousFailureFeedbackIntoRetryPrompt() {
    // 搭建同 Task 2 Step 1：ARCHITECT 永远 failure("缺少必填字段: implementationSteps", ...)
    assertEquals(RdTaskStatus.FAILED_NEEDS_HUMAN, engine.submit(task.taskId()).status());
    assertEquals(RdTaskStatus.FAILED_NEEDS_HUMAN, engine.submit(task.taskId()).status());

    List<RequirementExecutionRequest> architectRequests = captured.stream()
            .filter(request -> request.role() == AgentRole.SOLUTION_ARCHITECT)
            .toList();
    assertEquals(2, architectRequests.size());
    assertFalse(architectRequests.get(0).prompt().contains("上一轮失败反馈"));
    assertTrue(architectRequests.get(1).prompt().contains("上一轮失败反馈"));
    assertTrue(architectRequests.get(1).prompt().contains("缺少必填字段: implementationSteps"));
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./mvnw -pl engine test -Dtest=RequirementDeliveryEngineTest#shouldInjectPreviousFailureFeedbackIntoRetryPrompt`
Expected: FAIL（当前 retry prompt 与首轮相同）

- [ ] **Step 3: 实现失败反馈段落**

新私有方法（放在 `recoveryPromptSection` 附近）：

```java
/** 单次回注的失败明细上限，防止巨型校验错误把 prompt 撑爆。 */
private static final int MAX_FAILURE_FEEDBACK_CHARS = 4_000;

/**
 * 生成上一轮失败反馈 prompt 段落，让重试 attempt 针对性自纠错而不是盲重跑。
 * 无历史失败 attempt 时返回空字符串（首轮 prompt 不变）。
 */
private String previousFailureFeedbackSection(String taskId, AgentRole role, int currentAttemptNo) {
    AgentStageRun previousFailure = stageRunStore.listByTask(taskId).stream()
            .filter(stage -> stage.role() == role)
            .filter(stage -> stage.attemptNo() < currentAttemptNo)
            .filter(stage -> stage.status() == AgentStageStatus.FAILED_RETRYABLE
                    || stage.status() == AgentStageStatus.FAILED_NEEDS_HUMAN)
            .max(STAGE_RUN_RECENCY)
            .orElse(null);
    if (previousFailure == null || previousFailure.errorMessage().isBlank()) {
        return "";
    }
    String detail = previousFailure.errorMessage();
    if (detail.length() > MAX_FAILURE_FEEDBACK_CHARS) {
        detail = detail.substring(0, MAX_FAILURE_FEEDBACK_CHARS) + "...(truncated)";
    }
    return """
            # 上一轮失败反馈
            上一次 %s 尝试（attempt %d）失败，错误分类：%s。
            失败明细：
            %s
            请针对以上明细修正本轮输出（逐条补齐缺失或非法的结果字段），不要原样重复上一轮输出。
            """.formatted(
            role.name(),
            previousFailure.attemptNo(),
            previousFailure.errorCategory().isBlank() ? "UNKNOWN" : previousFailure.errorCategory(),
            detail
    ).strip();
}

private static String joinPromptSections(String first, String second) {
    if (first.isBlank()) {
        return second;
    }
    if (second.isBlank()) {
        return first;
    }
    return first + "\n\n" + second;
}
```

`executeAgentStages` 中 `buildAgentPrompt(...)` 的 recovery 参数改为：

```java
joinPromptSections(
        recoveryPromptSection(activeRetry, role, recoveryEvidenceMaterials),
        previousFailureFeedbackSection(task.taskId(), role, stage.attemptNo())
)
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./mvnw -pl engine test -Dtest=RequirementDeliveryEngineTest`
Expected: PASS

---

### Task 4: F3a 工作区缓存目录（RepairWorkspace + RepairWorkspaceFactory）

**Files:**
- Modify: `exec/src/main/java/com/wish/rd/exec/repair/docker/model/RepairWorkspace.java`
- Modify: `exec/src/main/java/com/wish/rd/exec/repair/docker/RepairWorkspaceFactory.java`
- Test: `exec/src/test/java/com/wish/rd/exec/repair/docker/RepairWorkspaceFactoryTest.java`

- [ ] **Step 1: 写失败测试**

```java
@Test
void shouldCreatePersistentCacheDirectoryBesideRepo() throws IOException {
    RepairWorkspaceFactory factory = new RepairWorkspaceFactory(temporaryDirectory, "{}");

    RepairWorkspace workspace = factory.create(command("task-cache"));

    assertEquals(workspace.root().resolve("cache"), workspace.cacheDirectory());
    assertTrue(Files.isDirectory(workspace.cacheDirectory()));
}
```

（`command(...)` 辅助方法沿用该测试类既有写法。）

- [ ] **Step 2: 运行测试确认失败**

Run: `./mvnw -pl exec test -Dtest=RepairWorkspaceFactoryTest`
Expected: 编译失败（`cacheDirectory()` 不存在）

- [ ] **Step 3: 实现 record 字段 + 工厂创建**

`RepairWorkspace` 加字段并保留 5 参兼容构造器（既有调用方零改动）：

```java
public record RepairWorkspace(
        Path root,
        Path inputDirectory,
        Path repoDirectory,
        Path outputDirectory,
        Path cacheDirectory,
        RepairWorkspaceFiles files
) {

    /** 兼容旧调用方：缓存目录默认为工作区根下 cache/。 */
    public RepairWorkspace(
            Path root,
            Path inputDirectory,
            Path repoDirectory,
            Path outputDirectory,
            RepairWorkspaceFiles files
    ) {
        this(root, inputDirectory, repoDirectory, outputDirectory,
                root == null ? null : root.resolve("cache"), files);
    }
}
```

`RepairWorkspaceFactory.create()` 在 output 目录旁增加 cache 目录（同样做 symlink/逃逸校验），并走 6 参构造：

```java
Path cacheDirectory = taskRoot.resolve("cache");
rejectSymlink(cacheDirectory);
Files.createDirectories(cacheDirectory);
ensureRealPathInsideWorkspaceRoot(cacheDirectory);
// ...
return new RepairWorkspace(taskRoot, inputDirectory, repoDirectory, outputDirectory, cacheDirectory, files);
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./mvnw -pl exec test -Dtest=RepairWorkspaceFactoryTest`
Expected: PASS

---

### Task 5: F3b 缓存挂载与环境变量（DockerClaudeCodeExecutor）

**Files:**
- Modify: `exec/src/main/java/com/wish/rd/exec/repair/docker/impl/DockerClaudeCodeExecutor.java`（常量区 + mounts/env 构造处）
- Test: `exec/src/test/java/com/wish/rd/exec/repair/docker/DockerClaudeCodeExecutorTest.java`

- [ ] **Step 1: 写失败测试**

```java
@Test
void shouldMountPersistentPackageManagerCache() {
    CapturingRunner runner = CapturingRunner.withResult(validResultJson("SUCCESS"));
    DockerClaudeCodeExecutor executor = executor(runner);

    executor.execute(command());

    assertEquals(
            "/work/cache",
            runner.request().mounts().get(temporaryDirectory.resolve("task-1001/cache").toString())
    );
    assertEquals("/work/cache/npm", runner.request().env().get("npm_config_cache"));
    assertEquals("/work/cache/pip", runner.request().env().get("PIP_CACHE_DIR"));
    assertEquals("/work/cache/yarn", runner.request().env().get("YARN_CACHE_FOLDER"));
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./mvnw -pl exec test -Dtest=DockerClaudeCodeExecutorTest#shouldMountPersistentPackageManagerCache`
Expected: FAIL（无 cache 挂载与 env）

- [ ] **Step 3: 实现挂载与环境变量**

常量区（L63-67 附近）：

```java
private static final String CONTAINER_CACHE_DIRECTORY = "/work/cache";
```

mounts 构造处（L814-817 之后）：

```java
mounts.put(workspace.cacheDirectory().toString(), CONTAINER_CACHE_DIRECTORY);
```

env 构造处（`Map<String, String> env = new LinkedHashMap<>(provider.env());` 之后）：

```java
// 跨 attempt 持久的包管理器缓存：同任务重试不再全量重新下载依赖（审查报告 F3）。
env.put("npm_config_cache", CONTAINER_CACHE_DIRECTORY + "/npm");
env.put("PIP_CACHE_DIR", CONTAINER_CACHE_DIRECTORY + "/pip");
env.put("YARN_CACHE_FOLDER", CONTAINER_CACHE_DIRECTORY + "/yarn");
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./mvnw -pl exec test -Dtest=DockerClaudeCodeExecutorTest`
Expected: PASS（新测试 + 全部既有测试；既有测试若断言 mounts 数量需同步 +1）

---

### Task 6: 全量回归

- [ ] **Step 1: 全模块测试**

Run: `./mvnw test`
Expected: engine / exec / bootstrap / rag / skill 全部 PASS。若 bootstrap 有依赖门禁旧行为（"配置/token 即审批"）的用例，按新行为修正断言（新行为是设计决策，见方案 F1）。

- [ ] **Step 2: 汇报**

输出：改动文件清单、测试结果、行为变化摘要（门禁放行范围、attempt 上限、缓存挂载）。
