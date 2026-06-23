# Waimai API 文档

## 1. 通用约定

基础路径：

- 后端：`http://localhost:3000`
- 前端代理：`/api`

认证：

- Header: `Authorization: Bearer <jwt>`
- 受保护接口需用户存在且角色满足要求。

错误响应：

```json
{
  "error": "错误说明"
}
```

## 2. Health

### GET /api/health

说明：健康检查。

响应：

```json
{
  "status": "ok",
  "timestamp": "2026-06-22T00:00:00.000Z"
}
```

## 3. Auth

### POST /api/auth/login

说明：登录。

请求：

```json
{
  "username": "admin",
  "password": "123456"
}
```

成功响应：

```json
{
  "token": "<jwt>",
  "user": {
    "id": 1,
    "username": "admin",
    "name": "系统管理员",
    "role": "admin",
    "phone": "13800000000",
    "avatar_url": ""
  }
}
```

当前实现问题：

- JWT payload 使用 `id`，认证中间件读取 `userId`。

### GET /api/auth/me

说明：获取当前用户。

认证：需要，但当前代码未挂 `authMiddleware`。

成功响应：

```json
{
  "id": 1,
  "username": "admin",
  "name": "系统管理员",
  "role": "admin",
  "phone": "13800000000",
  "avatar_url": "",
  "created_at": "2026-06-22 00:00:00"
}
```

### PUT /api/auth/password

说明：修改密码。

认证：需要，但当前代码未挂 `authMiddleware`。

请求：

```json
{
  "oldPassword": "123456",
  "newPassword": "654321"
}
```

## 4. Merchants

### GET /api/merchants

说明：公开商家列表。

Query：

- `search`: 商家名称模糊搜索。
- `category`: 商家分类。
- `sort`: `rating` 或 `orders`。

当前实现问题：

- 查询 `reviews` 表和 `m.status = 'open'`，schema 中不存在 `reviews` 和 `status`。

### GET /api/merchants/categories

说明：公开商家分类列表。

当前实现问题：

- 查询 `m.status = 'open'`，schema 中使用 `is_open`。

### GET /api/merchants/{id}

说明：商家详情。

当前实现问题：

- Join `reviews`，schema 中没有该表。

### GET /api/merchants/{id}/menu

说明：商家菜单，按分类分组。

当前实现问题：

- 查询 `menu_items`，schema 中表名是 `products`。

### GET /api/merchants/{id}/reviews

说明：商家评价。

当前实现问题：

- 查询 `reviews`，schema 中没有该表。

### GET /api/merchants/manage/info

说明：商家获取自己的店铺信息。

认证：merchant。

### PATCH /api/merchants/manage/status

说明：商家修改营业状态。

认证：merchant。

请求：

```json
{
  "status": "open"
}
```

当前实现问题：

- schema 使用 `is_open INTEGER`，不是 `status`。

### GET /api/merchants/manage/categories

说明：商家分类列表。

认证：merchant。

### POST /api/merchants/manage/categories

说明：新增分类。

认证：merchant。

请求：

```json
{
  "name": "招牌",
  "sort_order": 1
}
```

### DELETE /api/merchants/manage/categories/{id}

说明：删除分类。

认证：merchant。

当前实现问题：

- 检查 `menu_items`，schema 中表名是 `products`。

### GET /api/merchants/manage/items

说明：商家商品列表。

认证：merchant。

当前实现问题：

- 查询 `menu_items`，schema 中表名是 `products`。

### POST /api/merchants/manage/items

说明：创建商品。

认证：merchant。

请求：

```json
{
  "category_id": 1,
  "name": "黄焖鸡米饭",
  "description": "招牌套餐",
  "price": 25,
  "image_url": "",
  "stock": 999
}
```

当前实现问题：

- 写入 `menu_items`，schema 中表名是 `products`。

### PATCH /api/merchants/manage/items/{id}

说明：更新商品。

认证：merchant。

### DELETE /api/merchants/manage/items/{id}

说明：删除商品。

认证：merchant。

## 5. Orders

### POST /api/orders

说明：顾客创建订单。

认证：customer。

请求：

```json
{
  "merchant_id": 1,
  "items": [
    {
      "product_id": 1,
      "quantity": 2
    }
  ],
  "address": "深圳市南山区科技园",
  "phone": "13800000001",
  "customer_name": "张小明",
  "note": "少辣"
}
```

成功响应：

```json
{
  "order": {
    "id": 1,
    "order_no": "ORD202606221200000001",
    "status": "pending"
  }
}
```

### GET /api/orders

说明：当前顾客订单列表。

认证：已登录。

Query：

- `status`: 可选订单状态。

### GET /api/orders/{id}

说明：订单详情。

认证：已登录。

### PUT /api/orders/{id}/status

说明：按角色推进订单状态。

认证：已登录。

请求：

```json
{
  "status": "accepted",
  "note": "已接单"
}
```

当前实现问题：

- 设置 `rejected_at`，schema 中没有此字段。

### POST /api/orders/{id}/rate

说明：顾客评价已完成订单。

认证：customer。

请求：

```json
{
  "rating_merchant": 5,
  "rating_rider": 5,
  "review": "很好"
}
```

## 6. Riders

### GET /api/riders/dashboard

说明：骑手 dashboard。

认证：rider。

当前实现问题：

- 查询 `riders` 表，schema 中没有该表。

### PUT /api/riders/online

说明：骑手上线或下线。

认证：rider。

请求：

```json
{
  "is_online": true
}
```

当前实现问题：

- 查询和更新 `riders` 表，schema 中没有该表。

### GET /api/riders/available-orders

说明：骑手可接订单。

认证：rider。

当前实现问题：

- 查询 `riders` 表和 `orders.delivery_address` 字段，schema 不匹配。

### POST /api/riders/accept/{orderId}

说明：骑手接单。

认证：rider。

### GET /api/riders/my-orders

说明：骑手自己的订单。

认证：rider。

Query：

- `status`
- `page`
- `limit`

### POST /api/riders/deliver/{orderId}

说明：骑手完成配送。

认证：rider。

## 7. Admin

### GET /api/admin/dashboard

说明：管理员 dashboard。

认证：admin。

### GET /api/admin/orders

说明：订单列表。

认证：admin。

Query：

- `status`
- `page`
- `limit`

### PUT /api/admin/orders/{id}/status

说明：管理员更新订单状态。

认证：admin。

### GET /api/admin/merchants

说明：商家列表。

认证：admin。

### PUT /api/admin/merchants/{id}/status

说明：更新商家状态。

认证：admin。

当前实现问题：

- schema 中商家营业字段是 `is_open`，不是 `status`。

### POST /api/admin/merchants

说明：管理员创建商家。

认证：admin。

当前实现问题：

- 写入 `users.password`，schema 字段是 `password_hash`。

### GET /api/admin/riders

说明：骑手列表。

认证：admin。

当前实现问题：

- 查询 `users.is_online`、`users.total_deliveries`，schema 无此字段。

### PUT /api/admin/riders/{id}/status

说明：更新骑手状态。

认证：admin。

当前实现问题：

- 更新 `users.is_online`，schema 无此字段。

### POST /api/admin/riders

说明：创建骑手。

认证：admin。

当前实现问题：

- 写入 `users.password`、`users.is_online`，schema 字段不匹配。

### GET /api/admin/accounts

说明：账号列表。

认证：admin。

当前实现问题：

- 查询 `users.is_online`、`users.total_deliveries`，schema 无此字段。

### POST /api/admin/accounts

说明：创建账号。

认证：admin。

当前实现问题：

- 写入 `users.password`，schema 字段是 `password_hash`。

## 8. 前端声明但后端未实现的接口

来自 `client/src/api.ts`：

- `POST /api/auth/register`
- `GET /api/auth/profile`
- `GET /api/merchants/my/products`
- `POST /api/merchants/my/products`
- `PATCH /api/merchants/my/products/{id}`
- `DELETE /api/merchants/my/products/{id}`
- `GET /api/merchants/my/categories`
- `POST /api/merchants/my/categories`
- `PATCH /api/merchants/my/profile`
- `GET /api/orders/mine`
- `POST /api/orders/{id}/cancel`
- `POST /api/orders/{id}/confirm`
- `GET /api/orders/merchant`
- `POST /api/orders/{id}/accept`
- `POST /api/orders/{id}/reject`
- `POST /api/orders/{id}/prepare`
- `POST /api/orders/{id}/ready`
- `GET /api/riders/my/profile`
- `PATCH /api/riders/my/status`
- `GET /api/riders/my/deliveries`
- `POST /api/riders/deliveries/{id}/accept`
- `POST /api/riders/deliveries/{id}/arrive`
- `POST /api/riders/deliveries/{id}/pickup`
- `POST /api/riders/deliveries/{id}/deliver`
- `POST /api/riders/deliveries/{id}/complete`
- `GET /api/admin/stats`
- `GET /api/admin/stats/trend`
- `GET /api/admin/orders/abnormal`
- `PATCH /api/admin/merchants/{id}`
- `PATCH /api/admin/riders/{id}`
