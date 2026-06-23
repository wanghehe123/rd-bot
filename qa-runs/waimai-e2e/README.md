# 外卖平台 (WaiMai Platform)

一个全栈外卖平台 Demo，支持顾客、商家、骑手、管理员四种角色。

## 技术栈

- **后端**: Node.js + Express + TypeScript + SQLite (better-sqlite3)
- **前端**: React 18 + TypeScript + Vite + Ant Design + React Router

## 快速启动

```bash
# 一键启动（安装依赖 + 启动前后端）
chmod +x start.sh
./start.sh
```

或手动启动：

```bash
# 安装后端依赖
cd server && npm install && cd ..

# 安装前端依赖
cd client && npm install && cd ..

# 启动后端（端口 3000）
cd server && npm run dev &

# 启动前端（端口 5173）
cd client && npm run dev
```

## 项目结构

```
waimai/
├── server/                 # 后端
│   ├── config/
│   │   └── schema.sql      # 数据库 Schema
│   ├── src/
│   │   ├── database.ts     # 数据库连接
│   │   ├── seed.ts         # 种子数据
│   │   ├── index.ts        # 入口文件
│   │   ├── middleware/
│   │   │   └── auth.ts     # JWT 认证中间件
│   │   └── routes/
│   │       ├── auth.ts     # 注册/登录
│   │       ├── merchants.ts # 商家 API
│   │       ├── orders.ts   # 订单 API
│   │       ├── riders.ts   # 骑手 API
│   │       └── admin.ts    # 管理员 API
│   ├── package.json
│   └── tsconfig.json
├── client/                 # 前端
│   ├── src/
│   │   ├── api.ts          # API 客户端
│   │   ├── App.tsx         # 路由配置
│   │   ├── main.tsx        # 入口文件
│   │   ├── context/
│   │   │   └── AuthContext.tsx
│   │   ├── layouts/        # 布局组件
│   │   │   ├── CustomerLayout.tsx
│   │   │   ├── MerchantLayout.tsx
│   │   │   ├── RiderLayout.tsx
│   │   │   └── AdminLayout.tsx
│   │   └── pages/          # 页面组件
│   │       ├── Login.tsx
│   │       ├── customer/
│   │       ├── merchant/
│   │       ├── rider/
│   │       └── admin/
│   ├── package.json
│   ├── vite.config.ts
│   └── tsconfig.json
├── start.sh                # 一键启动脚本
└── README.md
```

## 测试账号

种子数据中预设了以下账号（密码均为 `123456`）：

| 角色 | 用户名 | 说明 |
|------|--------|------|
| 管理员 | admin | 平台管理员 |
| 商家 | merchant1 | 商家账号 |
| 骑手 | rider1 | 骑手账号 |
| 顾客 | customer1 | 顾客账号 |

## API 端点

### 认证
- `POST /api/auth/register` - 注册
- `POST /api/auth/login` - 登录

### 商家
- `GET /api/merchants` - 获取商家列表
- `GET /api/merchants/:id` - 获取商家详情
- `GET /api/merchants/:id/menu` - 获取商家菜单
- `GET /api/merchants/manage/info` - 商家获取自己的信息
- `PUT /api/merchants/manage/info` - 商家更新信息
- `GET /api/merchants/manage/menu` - 商家获取菜单
- `POST /api/merchants/manage/menu` - 添加菜品
- `PUT /api/merchants/manage/menu/:id` - 更新菜品
- `DELETE /api/merchants/manage/menu/:id` - 删除菜品
- `GET /api/merchants/manage/orders` - 商家获取订单
- `PUT /api/merchants/manage/orders/:id/status` - 商家更新订单状态

### 订单
- `POST /api/orders` - 创建订单
- `GET /api/orders` - 获取用户订单
- `GET /api/orders/:id` - 获取订单详情

### 骑手
- `GET /api/riders/available` - 获取可接订单
- `POST /api/riders/accept/:id` - 接单
- `GET /api/riders/deliveries` - 获取配送中订单
- `PUT /api/riders/deliveries/:id/status` - 更新配送状态

### 管理员
- `GET /api/admin/dashboard` - 仪表盘数据
- `GET /api/admin/merchants` - 商家列表
- `PUT /api/admin/merchants/:id/status` - 审核商家
- `GET /api/admin/orders` - 所有订单
- `GET /api/admin/riders` - 骑手列表
- `PUT /api/admin/riders/:id/status` - 审核骑手
- `GET /api/admin/accounts` - 账户列表
- `PUT /api/admin/accounts/:id/role` - 修改角色

## 功能说明

### 顾客端
- 浏览商家列表和菜单
- 加入购物车并下单
- 查看订单状态

### 商家端
- 管理店铺信息
- 管理菜品（增删改）
- 处理订单（接单/出餐）

### 骑手端
- 查看可接订单
- 接单配送
- 更新配送状态

### 管理端
- 平台数据仪表盘
- 商家审核
- 骑手审核
- 订单管理
- 账户管理
