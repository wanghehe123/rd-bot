# Waimai 系统设计文档

## 1. 项目定位

Waimai 是一个外卖平台 Demo，目标是覆盖顾客、商家、骑手、管理员四类角色的基础业务闭环。

核心业务目标：

- 顾客：浏览商家、浏览菜单、创建订单、查看订单、评价订单。
- 商家：维护店铺、维护商品分类和商品、处理订单状态。
- 骑手：上线、查看可接单、接单、配送、完成配送。
- 管理员：查看平台数据、管理商家、骑手、订单和账号。

## 2. 技术架构

系统采用前后端分离架构。

- 前端：React 18 + TypeScript + Vite + Ant Design + React Router。
- 后端：Node.js + Express + TypeScript。
- 数据库：SQLite，运行时使用 better-sqlite3。
- 鉴权：JWT bearer token。
- 本地启动：根目录 `start.sh` 或分别启动 `server` 与 `client`。

运行时请求链路：

1. 浏览器访问 Vite 前端。
2. 前端 `client/src/api.ts` 通过 `/api` 前缀调用后端。
3. 后端 `server/src/index.ts` 挂载 Express 路由。
4. 路由通过 `server/src/database.ts` 访问 SQLite。
5. 需要鉴权的接口通过 `server/src/middleware/auth.ts` 验证 JWT。

## 3. 后端模块设计

### 3.1 入口层

文件：`server/src/index.ts`

职责：

- 创建 Express app。
- 注册 CORS 和 JSON body parser。
- 挂载路由：
  - `/api/auth`
  - `/api/merchants`
  - `/api/orders`
  - `/api/riders`
  - `/api/admin`
- 暴露 `/api/health`。
- 注册全局错误处理器。

### 3.2 数据访问层

文件：`server/src/database.ts`

职责：

- 打开 `server/data/waimai.db`。
- 启用 WAL 和 foreign keys。
- 执行 `server/config/schema.sql` 初始化 schema。

当前风险：

- 项目设置 `"type": "module"`，但 `database.ts` 使用 CommonJS 的 `__dirname`。在 Node 24 + tsx 下启动会直接失败。

### 3.3 鉴权中间件

文件：`server/src/middleware/auth.ts`

职责：

- 从 `Authorization: Bearer <token>` 读取 JWT。
- 验证 token。
- 查询用户是否存在。
- 写入 `req.user` 与 `req.userId`。
- `requireRole(...)` 按角色限制访问。

当前风险：

- 登录接口签发 payload 字段为 `id`，但中间件读取 `decoded.userId`。
- `AuthRequest.user` 类型声明要求 `{ id, username, role }`，但实际写入 decoded 对象 `{ userId, username, role }`。

### 3.4 认证路由

文件：`server/src/routes/auth.ts`

职责：

- `POST /api/auth/login` 登录。
- `GET /api/auth/me` 获取当前用户。
- `PUT /api/auth/password` 修改密码。

当前风险：

- README 和前端声明了注册接口，但后端没有实现 `POST /api/auth/register`。
- `/me` 和 `/password` 未挂载 `authMiddleware`，访问时依赖 `req.user` 会失败。

### 3.5 商家路由

文件：`server/src/routes/merchants.ts`

职责：

- 公共商家列表、分类、详情、菜单、评价。
- 商家端店铺状态、分类和商品管理。

当前风险：

- 路由使用 `reviews`、`menu_items` 表，但 schema 定义的是 `products`，且没有 `reviews` 表。
- 路由使用 `m.status = 'open'`，schema 定义的是 `is_open INTEGER`。
- README API 命名为 `/manage/menu`，实际代码为 `/manage/items`、`/manage/categories`。

### 3.6 订单路由

文件：`server/src/routes/orders.ts`

职责：

- 顾客创建订单。
- 用户查询订单列表和详情。
- 更新订单状态。
- 评价订单。

当前风险：

- 前端 API 使用 `/orders/mine`、`/orders/:id/cancel`、`/orders/:id/confirm`、`/orders/merchant` 等路径，后端未实现。
- 更新状态时引用 `rejected_at` 字段，但 schema 没有该字段。
- 创建订单请求要求 `address`、`phone`、`customer_name`，前端发送的是 `delivery_address`、`remark`。

### 3.7 骑手路由

文件：`server/src/routes/riders.ts`

职责：

- 骑手 dashboard。
- 上线/下线。
- 可接订单。
- 接单。
- 我的订单。
- 完成配送。

当前风险：

- 路由使用 `riders` 表，schema 没有 `riders` 表，只有 `rider_locations`。
- 路由使用 `orders.delivery_address`，schema 定义的是 `address`。
- README API 命名为 `/available`、`/deliveries`，代码实际为 `/available-orders`、`/my-orders`、`/deliver/:orderId`。

### 3.8 管理员路由

文件：`server/src/routes/admin.ts`

职责：

- 管理员 dashboard。
- 管理订单、商家、骑手、账号。

当前风险：

- 创建用户时写入 `users.password`，schema 字段是 `password_hash`。
- 查询骑手时读取 `users.is_online`、`users.total_deliveries`，schema 没有这些字段。
- 前端 API 使用 `/admin/stats`、`/admin/stats/trend`、`PATCH /admin/merchants/:id` 等，后端实际接口不同。

## 4. 前端模块设计

### 4.1 路由与角色入口

文件：`client/src/App.tsx`

职责：

- 使用 React Router 配置 `/login`、`/customer`、`/merchant`、`/rider`、`/admin`。
- `ProtectedRoute` 根据 token 和 role 拦截路由。

### 4.2 API Client

文件：`client/src/api.ts`

职责：

- 维护 token。
- 统一发起 fetch 请求。
- 封装认证、商家、订单、骑手、管理员 API。

当前风险：

- 只实现 `request`、`setToken`、`getToken` 和业务方法，没有 `api.get`、`api.post` 方法。
- `AuthContext.tsx` 调用了 `api.get`、`api.post`，会导致 TypeScript 失败。
- 多数前端 API 路径与后端实际路由不一致。

### 4.3 Auth Context

文件：`client/src/context/AuthContext.tsx`

职责：

- 从 localStorage 恢复 token。
- 获取当前用户。
- 登录、注册、登出。

当前风险：

- 调用不存在的 `api.get`、`api.post`。
- 用户字段使用 `real_name`，后端返回 `name`。

### 4.4 页面和布局

布局：

- `CustomerLayout.tsx`
- `MerchantLayout.tsx`
- `RiderLayout.tsx`
- `AdminLayout.tsx`

页面：

- 登录：`Login.tsx`
- 顾客：首页、订单
- 商家：Dashboard、菜单、订单
- 骑手：可接单、我的配送
- 管理员：Dashboard、商家、订单、骑手、账号

当前风险：

- 多个 TSX 文件包含 `…[omitted ...]` 占位文本，导致 TypeScript 词法解析失败。

## 5. 关键数据流

### 5.1 登录

1. 前端提交用户名密码。
2. 后端 `/api/auth/login` 查询 users。
3. bcrypt 校验密码。
4. 后端签发 JWT。
5. 前端保存 token。

缺口：

- JWT payload 字段与认证中间件不一致，导致后续受保护接口无法通过用户查询。

### 5.2 下单

1. 顾客选择商家与商品。
2. 前端提交订单。
3. 后端检查商家营业状态、商品可用性、库存、起送价。
4. 写入 orders 与 order_items。
5. 商家、骑手、管理员按订单状态处理。

缺口：

- 前端请求体字段与后端要求不一致。
- seed/schema/路由的订单字段不一致。

### 5.3 商家商品管理

1. 商家登录。
2. 后端根据 `req.userId` 查找商家。
3. 商家管理分类和商品。

缺口：

- 路由使用 `menu_items`，schema 使用 `products`。

### 5.4 骑手配送

1. 骑手上线。
2. 查询可接订单。
3. 接单并推进状态。
4. 完成配送并累计收入。

缺口：

- 路由使用 `riders` 独立表，schema 未定义。

## 6. 系统边界和建议修复顺序

建议优先按以下顺序修复：

1. 修复 Node ESM 启动问题，让后端能启动。
2. 统一 schema、seed、后端路由的字段和表名。
3. 统一 JWT payload 和 `AuthRequest` 类型。
4. 修复前端源码占位符污染。
5. 统一前端 API client 与后端路由路径。
6. 增加最小端到端测试：登录、商家列表、下单、商家接单、骑手配送、管理员 dashboard。
