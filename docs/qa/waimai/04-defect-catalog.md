# Waimai 缺陷目录

## 1. 验证环境

- 项目副本：`/Users/wish233/Documents/RD-Bot/qa-runs/waimai-e2e`
- 原始项目：`/Volumes/WishDisk/test/ima/waimai`
- Node.js：v24.12.0
- npm：11.7.0
- 后端依赖安装：成功，npm audit 报 1 个 moderate vulnerability。
- 前端依赖安装：成功，npm audit 报 1 个 moderate + 1 个 high vulnerability。

## 2. P0 缺陷：后端无法启动

### 现象

执行：

```bash
cd server
npm run dev
```

失败：

```text
ReferenceError: __dirname is not defined in ES module scope
```

### 根因

`server/package.json` 设置 `"type": "module"`，但 `server/src/database.ts` 使用 CommonJS 的 `__dirname`。

### 影响

后端无法启动，所有 HTTP API 均不可用。

### 建议修复

在 ESM 中用 `fileURLToPath(import.meta.url)` 和 `path.dirname` 计算当前文件目录。

## 3. P0 缺陷：前端无法构建

### 现象

执行：

```bash
cd client
npm run build
```

失败：

```text
error TS1127: Invalid character.
```

### 根因

多个 TSX 文件包含真实占位文本 `…[omitted ...]`，不是合法 TypeScript/JSX。

受影响文件包括：

- `client/src/layouts/CustomerLayout.tsx`
- `client/src/layouts/MerchantLayout.tsx`
- `client/src/pages/Login.tsx`
- `client/src/pages/customer/Home.tsx`
- `client/src/pages/customer/Orders.tsx`
- `client/src/pages/merchant/Dashboard.tsx`
- `client/src/pages/merchant/Menu.tsx`
- `client/src/pages/merchant/Orders.tsx`
- `client/src/pages/rider/Available.tsx`
- `client/src/pages/rider/MyDeliveries.tsx`
- `client/src/pages/admin/Dashboard.tsx`
- `client/src/pages/admin/Merchants.tsx`
- `client/src/pages/admin/Orders.tsx`
- `client/src/pages/admin/Riders.tsx`
- `client/src/pages/admin/Accounts.tsx`

### 影响

前端产物无法构建，Vite 生产构建不可用。

## 4. P0 缺陷：seed 与 schema 不一致

### 现象

执行：

```bash
cd server
npm run seed
```

当前首先被 ESM `__dirname` 问题阻断。静态检查显示即使修复 ESM 问题，seed 仍会继续失败。

### 根因

`server/src/seed.ts` 写入大量 schema 中不存在的表和字段。

典型例子：

- `users.nickname`、`users.avatar`，schema 为 `users.name`、`users.avatar_url`。
- seed 使用角色 `user`，schema 只允许 `customer`。
- `merchants.status`、`merchants.logo`、`merchants.business_hours`，schema 不存在。
- `products.original_price`、`products.image`、`products.sales`，schema 不存在。
- seed 写入 `riders`、`reviews` 表，schema 未定义。

### 影响

演示账号无法初始化，README 中测试账号不可用。

## 5. P0 缺陷：JWT payload 不一致

### 现象

登录接口签发：

```ts
{ id: user.id, username: user.username, role: user.role }
```

认证中间件读取：

```ts
decoded.userId
```

### 影响

登录成功后，访问受保护接口时会用 `undefined` 查询用户，导致认证失败。

### 建议修复

统一为：

```ts
{ userId: user.id, username: user.username, role: user.role }
```

或者认证中间件改读 `decoded.id`。

## 6. P1 缺陷：后端路由与 schema 大面积不一致

### 现象

路由使用了 schema 不存在的表和字段：

- `reviews`
- `menu_items`
- `riders`
- `orders.delivery_address`
- `orders.rejected_at`
- `users.password`
- `users.is_online`
- `users.total_deliveries`

### 影响

即使后端能启动，商家列表、菜单、骑手、管理员账号等接口仍会运行时报错。

## 7. P1 缺陷：前端 API 与后端 API 不一致

### 现象

前端 `client/src/api.ts` 声明了大量后端未实现路径，例如：

- `/auth/register`
- `/auth/profile`
- `/merchants/my/products`
- `/orders/mine`
- `/orders/{id}/cancel`
- `/riders/my/profile`
- `/admin/stats`

### 影响

前端即使构建通过，也无法按预期调用后端。

## 8. P1 缺陷：AuthContext 调用不存在的 API client 方法

### 现象

`client/src/context/AuthContext.tsx` 调用：

```ts
api.get('/auth/me')
api.post('/auth/login', ...)
```

但 `client/src/api.ts` 中 `ApiClient` 没有公开 `get` 或 `post` 方法。

### 影响

修复前端占位符后，TypeScript 仍会继续报错。

## 9. 自动修复推荐目标

本次最适合作为自动修复系统输入的真实 bug：

> Waimai 后端在 Node ESM 环境下无法启动，`server/src/database.ts` 使用 `__dirname` 导致 `npm run dev` 和 `npm run seed` 立即失败。请修复 ESM 路径解析，并尽量让 seed 能进入 schema 初始化后的真实数据写入阶段。

选择理由：

- 缺陷真实且可由命令稳定复现。
- 修复范围小，主要集中在 `server/src/database.ts`。
- 有明确验收命令：`npm run seed` 不应再因 `__dirname` 报错。
- 即使后续暴露 seed/schema 不一致，也能证明自动修复至少推进了执行边界。
