# Provider Credential Console — Frontend Handoff

> 给**另一个前端 agent**的独立实施说明。不要改交付编排、不要跑 Maven 业务测试除非你动了 Vite proxy 合同。后端 API 以本文契约为准；后端尚未合并时先写类型、空态和合同测试，再对接真数据。

**Goal:** 在侧栏「设置」增加「供应商配置」页：按供应商维护路由元数据，并写入 API key。页面只显示是否已配置，永不回显明文。保存后需求评审不再依赖启动前 `export OPENCODE_API_KEY`。

**Do not:**

- 不要把密钥画进系统设置 JSON 预览，也不要复用只读的 `/rag/settings`。
- 不要把密钥字段加到 `ModelProviderProfile` 类型上当可读字段。
- 不要改 Agent 策略页的绑定语义；它继续只选 `providerId`。
- 不要手改 `bootstrap/src/main/resources/static/admin/**`；走 Vite 构建产物。
- 不要做删除供应商（用启用开关）。不要做 OpenViking / MinIO 密钥。
- 不要在 GET 响应里找不到 key 时“帮忙”把用户刚输入的 key 再显示出来。

**Backend change:** `openspec/changes/provider-credential-console/`  
**Backend implementation plan:** `docs/superpowers/plans/2026-08-17-provider-credential-console.md`（你不需要实施它）

**Tech:** React + Vite 6 + React Router + 现有 shadcn/`@/components/ui` + `sonner` + `frontend/test/*.test.ts`（`node --experimental-strip-types --test`）。

---

## 1. 用户能看到什么

侧栏「设置」组，系统设置上方：

```
设置
  用户管理
  供应商配置    ← 新
  系统设置
```

页面 `/admin/model-providers`：

```
供应商配置
  [操作令牌输入，复用 Agent 策略页同一套：X-RD-Agent-Runtime-Token，本地默认 local-agent-runtime]
  [新建供应商]

表格
  显示名 | providerId | 协议 | 模型 | 环境变量名 | 密钥 | 启用 | 操作
  OpenCode Go | opencode-go | OPENAI_CHAT_COMPLETIONS | deepseek-v4-flash | OPENCODE_API_KEY | 已配置 / 未配置 | 开 | 编辑元数据 · 设置密钥
```

- **未配置**：红色/琥珀色徽章「未配置」。这就是截图里 `missing API key env: OPENCODE_API_KEY` 的对应状态。
- **已配置**：绿色「已配置」，不显示任何 key 字符。
- **设置密钥**对话框：password 输入 + 显示/隐藏；提交后清空输入框。提供「清除已保存密钥」。
- **编辑元数据**：providerId（新建时可填，编辑时只读）、显示名、协议、baseUrl、modelId、credentialEnvironmentVariable、authHeader、enabled。
- 空表：说明文案「还没有供应商。新建一条（例如 providerId=`opencode-go`，环境变量=`OPENCODE_API_KEY`），再设置密钥。」

多个供应商共用同一环境变量名时，在行上提示「该环境变量名被多个供应商共用，执行时使用最近一次保存的密钥」。

---

## 2. HTTP 契约（后端会提供）

基路径已由 `/admin/model-provider-profiles` 代理到 Spring Boot。页面路由是 `/admin/model-providers`，**不要**把 SPA 路径做成 API 前缀。

写接口请求头：`X-RD-Agent-Runtime-Token`（与 `projectService.ts` 里策略页相同）。

### 2.1 列表

`GET /admin/model-provider-profiles`

```ts
export type ModelProviderProtocol =
  | "ANTHROPIC_COMPATIBLE"
  | "ANTHROPIC_MESSAGES"
  | "OPENAI_CHAT_COMPLETIONS"
  | "OPENAI_COMPLETIONS"
  | "OPENAI_RESPONSES"
  | "GOOGLE_GENERATIVE_AI";

export interface ModelProviderProfile {
  providerId: string;
  displayName: string;
  protocol: ModelProviderProtocol;
  baseUrl: string;
  modelId: string;
  credentialEnvironmentVariable: string;
  authHeader?: boolean;
  enabled: boolean;
  version: number;
  credentialConfigured: boolean;
  credentialUpdatedAt: number | null; // epoch millis; 后端也可能省略
}
```

响应是数组。`credentialConfigured` 在后端未合并前可能缺失：前端视为 `false`，不要 crash。

### 2.2 创建/更新元数据

`PUT /admin/model-provider-profiles/{providerId}`

```ts
export interface ModelProviderMetadataInput {
  displayName: string;
  protocol: ModelProviderProtocol;
  baseUrl: string;
  modelId: string;
  credentialEnvironmentVariable: string;
  authHeader: boolean;
  enabled: boolean;
  version: number;
}
```

**禁止**在该 body 里带 `apiKey`。版本：新建传 `1`；更新传当前 `version`。

### 2.3 写密钥

`PUT /admin/model-provider-profiles/{providerId}/credential`

```ts
export interface ModelProviderCredentialInput {
  apiKey: string; // 空白字符串 = 清除
}
```

成功后立刻再 GET list。不要把 `apiKey` 写入 React state（提交后 `setApiKey("")`）。

403 → 「操作令牌无效」。503 → 「后端未配置 mutation token」。400 → 展示后端消息。

---

## 3. 文件地图

**Create**

- `frontend/src/pages/admin/provider/ModelProviderPage.tsx`
- `frontend/src/pages/admin/provider/modelProviderForm.ts`（空校验、env 名正则）
- `frontend/src/services/modelProviderService.ts`（也可扩 `projectService.ts`；优先新文件以免策略页服务膨胀）
- `frontend/test/modelProviderContract.test.ts`

**Modify**

- `frontend/src/App.tsx` — lazy route `model-providers`
- `frontend/src/components/AdminLayout.tsx` — 菜单 + `breadcrumbMap.["model-providers"] = "供应商配置"`
- `frontend/src/services/projectService.ts` — `ModelProviderProfile` 增加可选 `credentialConfigured?`（策略页不依赖）
- `frontend/vite.config.ts` — **仅当**你把 API 改到新前缀时才改。保持 API 为 `/admin/model-provider-profiles` 则现有 proxy 测试已覆盖。
- `frontend/test/viteProxy.test.ts` — 若新增 SPA 路径被误代理，补一条「`/admin/model-providers` 不得整段代理成 API」。

图标：`Cpu` 或 `KeyRound`（lucide-react，AdminLayout 已有同类用法）。

---

## 4. 任务（按顺序，TDD 能测的先测）

### Task F1: 类型与服务合同

- [ ] **Step 1: 写失败测试** `frontend/test/modelProviderContract.test.ts`

```ts
import assert from "node:assert/strict";
import test from "node:test";
import { readFileSync } from "node:fs";

test("model provider service never types a readable apiKey on the profile", () => {
  const service = readFileSync(new URL("../src/services/modelProviderService.ts", import.meta.url), "utf8");
  assert.match(service, /credentialConfigured/);
  assert.doesNotMatch(service, /apiKey\s*\?:/);
  assert.match(service, /X-RD-Agent-Runtime-Token/);
  assert.match(service, /\/admin\/model-provider-profiles/);
});
```

（实现服务后再让它通过。先写测试、再写服务。）

- [ ] **Step 2: 实现 `modelProviderService.ts`**

```ts
import { api } from "@/services/api";

export async function listModelProviders(): Promise<ModelProviderProfile[]> { ... }

export async function upsertModelProvider(
  providerId: string,
  body: ModelProviderMetadataInput,
  mutationToken: string
) {
  return api.put(`/admin/model-provider-profiles/${encodeURIComponent(providerId)}`, body, {
    headers: { "X-RD-Agent-Runtime-Token": mutationToken }
  });
}

export async function putModelProviderCredential(
  providerId: string,
  apiKey: string,
  mutationToken: string
) {
  return api.put(
    `/admin/model-provider-profiles/${encodeURIComponent(providerId)}/credential`,
    { apiKey },
    { headers: { "X-RD-Agent-Runtime-Token": mutationToken } }
  );
}
```

用项目里 `api` 的实际 `put` 签名（对照 `projectService.ts` 的 `updateAgentStrategy`）。不要发明第二种 HTTP 客户端。

- [ ] **Step 3: 跑测试**

```bash
cd frontend && node --experimental-strip-types --test test/modelProviderContract.test.ts
```

Expected: PASS.

- [ ] **Step 4: Commit** `feat(frontend): model provider credential API client`

### Task F2: 路由与导航

- [ ] **Step 1: `App.tsx` 增加**

```tsx
const ModelProviderPage = lazy(() =>
  import("@/pages/admin/provider/ModelProviderPage").then((module) => ({
    default: module.ModelProviderPage
  }))
);
// inside /admin routes:
<Route path="model-providers" element={<ModelProviderPage />} />
```

- [ ] **Step 2: `AdminLayout` 设置组插入** `{ path: "/admin/model-providers", label: "供应商配置", icon: Cpu }`，放在「系统设置」之前。

- [ ] **Step 3: Vite** — 确认 HTML 打开 `http://localhost:5173/admin/model-providers` 返回 SPA 而不是被代理到后端 404。现有 proxy **没有** `/admin/model-providers` 前缀，一般无需改。若你加了前缀代理，必须 `bypass` HTML navigation（抄 `isHtmlNavigation`）。

- [ ] **Step 4: 补 proxy 测试**（仅在你改了 vite.config 时）：

```ts
test("model-providers SPA is not proxied as an API prefix", () => {
  const proxy = viteConfig.server?.proxy as Record<string, unknown> | undefined;
  assert.equal(proxy?.["/admin/model-providers"], undefined);
});
```

```bash
cd frontend && node --experimental-strip-types --test test/viteProxy.test.ts
```

- [ ] **Step 5: Commit** `feat(frontend): add 供应商配置 nav and route`

### Task F3: 页面

对照 `SkillHubPage.tsx` 的表格 + Dialog，对照 `AgentStrategyPage.tsx` 的令牌条（KeyRound、显示/隐藏、本地默认按钮）。

`modelProviderForm.ts`：

```ts
const ENV = /^[A-Z_][A-Z0-9_]*$/;

export function metadataError(input: ModelProviderMetadataInput & { providerId: string }): string {
  if (!input.providerId.trim()) return "请填写 providerId";
  if (!input.displayName.trim()) return "请填写显示名";
  if (!input.baseUrl.trim()) return "请填写 baseUrl";
  if (!input.modelId.trim()) return "请填写模型";
  if (!ENV.test(input.credentialEnvironmentVariable.trim())) {
    return "环境变量名必须是大写字母、数字和下划线，不能填写密钥本身";
  }
  return "";
}
```

页面行为：

1. mount 时 `listModelProviders()`；失败 toast。
2. 令牌空时禁用保存/写密钥，提示先填令牌。
3. 提交密钥后清空对话框 state，再 refresh 列表。
4. `credentialEnvironmentVariable` 重复时显示共用提示。

不要引入新的 UI 库。不要改 globals 主题。

- [ ] **Step 1: 实现页面与 form helper**
- [ ] **Step 2: typecheck**

```bash
cd frontend && npm run typecheck
```

Expected: PASS.

- [ ] **Step 3: Commit** `feat(frontend): provider credential console page`

### Task F4: 浏览器验收（有前后端时）

后端需已执行 p16 且 Java 已接线。本地 mutation token：`local-agent-runtime`。

1. 打开 http://localhost:5173/admin/model-providers
2. 填令牌 → 本地默认
3. 若列表无 `opencode-go`：新建  
   providerId=`opencode-go`，协议=`OPENAI_CHAT_COMPLETIONS`，  
   baseUrl=`https://opencode.ai/zen/go/v1`，model 与本地 yaml 一致，  
   环境变量=`OPENCODE_API_KEY`，启用
4. 设置密钥（真实 OpenCode key）→ 行变为「已配置」→ 刷新页面仍是「已配置」，看不到明文
5. 回到失败任务，从失败阶段重试：不应再出现 `missing API key env: OPENCODE_API_KEY`
6. 缩小到移动端宽度：侧栏与表格不横向撑破

桌面和窄屏都要点一遍。只截一张图不算验收。

---

## 5. 与策略页的关系

`AgentStrategyPage` 已 `getModelProviderProfiles()`。多返回的 `credentialConfigured` 可忽略。不要在策略页再做一套密钥表单（用户明确要独立导航页）。

可选（非必须）：策略页下拉里对未配置密钥的供应商加「未配置密钥」标记，点过去链到 `/admin/model-providers`。不要在本计划未完成独立页之前做这个。
