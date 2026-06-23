# Waimai 数据库设计文档

## 1. 数据库概览

数据库使用 SQLite，schema 文件位于 `server/config/schema.sql`。

运行时初始化由 `server/src/database.ts` 完成：

- 数据文件：`server/data/waimai.db`
- Schema：`server/config/schema.sql`
- PRAGMA：
  - `journal_mode=WAL`
  - `foreign_keys=ON`

## 2. 表结构

### 2.1 users

用户账号表。

字段：

- `id`: 主键，自增。
- `username`: 唯一用户名。
- `password_hash`: 密码哈希。
- `role`: `customer`、`merchant`、`rider`、`admin`。
- `name`: 展示名。
- `phone`: 手机号。
- `address`: 地址。
- `avatar_url`: 头像 URL。
- `status`: `active` 或 `disabled`。
- `created_at`: 创建时间。
- `updated_at`: 更新时间。

索引：

- `idx_users_role`
- `idx_users_username`

### 2.2 merchants

商家表。

字段：

- `id`: 主键，自增。
- `user_id`: 关联用户 ID，唯一。
- `name`: 商家名称。
- `description`: 描述。
- `address`: 地址。
- `phone`: 手机。
- `image_url`: 商家图片。
- `category`: 分类。
- `rating`: 评分。
- `rating_count`: 评分次数。
- `monthly_sales`: 月销量。
- `is_open`: 是否营业，整数布尔值。
- `delivery_fee`: 配送费。
- `min_order`: 起送价。
- `created_at`: 创建时间。
- `updated_at`: 更新时间。

外键：

- `user_id -> users.id`

索引：

- `idx_merchants_user_id`
- `idx_merchants_category`
- `idx_merchants_is_open`

### 2.3 categories

商品分类表。

字段：

- `id`: 主键，自增。
- `merchant_id`: 商家 ID。
- `name`: 分类名。
- `sort_order`: 排序值。

外键：

- `merchant_id -> merchants.id ON DELETE CASCADE`

索引：

- `idx_categories_merchant_id`

### 2.4 products

商品表。

字段：

- `id`: 主键，自增。
- `merchant_id`: 商家 ID。
- `category_id`: 分类 ID。
- `name`: 商品名。
- `description`: 描述。
- `price`: 售价。
- `image_url`: 图片 URL。
- `stock`: 库存。
- `is_available`: 是否可售。
- `sales_count`: 销量。
- `created_at`: 创建时间。
- `updated_at`: 更新时间。

外键：

- `merchant_id -> merchants.id`
- `category_id -> categories.id`

索引：

- `idx_products_merchant_id`
- `idx_products_category_id`
- `idx_products_is_available`

### 2.5 orders

订单主表。

字段：

- `id`: 主键，自增。
- `order_no`: 唯一订单号。
- `customer_id`: 顾客用户 ID。
- `merchant_id`: 商家 ID。
- `rider_id`: 骑手用户 ID，可为空。
- `status`: 订单状态。
- `total_amount`: 总金额。
- `delivery_fee`: 配送费。
- `address`: 配送地址。
- `phone`: 联系电话。
- `customer_name`: 收货人姓名。
- `note`: 顾客备注。
- `merchant_note`: 商家备注。
- `rating_merchant`: 商家评分。
- `rating_rider`: 骑手评分。
- `review`: 评价内容。
- `reject_reason`: 拒单原因。
- `cancel_reason`: 取消原因。
- `created_at`: 创建时间。
- `updated_at`: 更新时间。
- `accepted_at`: 接单时间。
- `preparing_at`: 制作时间。
- `ready_at`: 出餐时间。
- `assigned_at`: 分配时间。
- `picked_up_at`: 取餐时间。
- `delivering_at`: 配送时间。
- `delivered_at`: 送达时间。
- `completed_at`: 完成时间。
- `cancelled_at`: 取消时间。

外键：

- `customer_id -> users.id`
- `merchant_id -> merchants.id`
- `rider_id -> users.id`

索引：

- `idx_orders_customer_id`
- `idx_orders_merchant_id`
- `idx_orders_rider_id`
- `idx_orders_status`
- `idx_orders_created_at`
- `idx_orders_order_no`

### 2.6 order_items

订单明细表。

字段：

- `id`: 主键，自增。
- `order_id`: 订单 ID。
- `product_id`: 商品 ID。
- `product_name`: 下单时商品名快照。
- `product_price`: 下单时价格快照。
- `quantity`: 数量。
- `subtotal`: 小计。

外键：

- `order_id -> orders.id ON DELETE CASCADE`
- `product_id -> products.id`

索引：

- `idx_order_items_order_id`

### 2.7 order_status_logs

订单状态流转日志表。

字段：

- `id`: 主键，自增。
- `order_id`: 订单 ID。
- `status`: 状态。
- `operator_id`: 操作人用户 ID。
- `note`: 备注。
- `created_at`: 创建时间。

外键：

- `order_id -> orders.id ON DELETE CASCADE`
- `operator_id -> users.id`

索引：

- `idx_order_status_logs_order_id`

### 2.8 rider_locations

骑手位置表。

字段：

- `id`: 主键，自增。
- `rider_id`: 骑手用户 ID，唯一。
- `latitude`: 纬度。
- `longitude`: 经度。
- `updated_at`: 更新时间。

外键：

- `rider_id -> users.id`

索引：

- `idx_rider_locations_rider_id`

## 3. 状态枚举

### 3.1 用户角色

Schema 允许：

- `customer`
- `merchant`
- `rider`
- `admin`

当前 seed 使用了 `user`，这会违反 CHECK 约束。

### 3.2 用户状态

- `active`
- `disabled`

### 3.3 订单状态

- `pending`
- `accepted`
- `preparing`
- `ready`
- `assigned`
- `picked_up`
- `delivering`
- `delivered`
- `completed`
- `cancelled`
- `rejected`

## 4. 与代码不一致的点

### 4.1 seed.ts 与 schema 不一致

`seed.ts` 使用了 schema 不存在的字段：

- `users.nickname`
- `users.avatar`
- `merchants.logo`
- `merchants.business_hours`
- `merchants.status`
- `merchants.latitude`
- `merchants.longitude`
- `products.original_price`
- `products.image`
- `products.sales`
- `products.status`
- `products.sort_order`
- `orders.user_id`
- `orders.packaging_fee`
- `orders.discount_amount`
- `orders.pay_amount`
- `orders.address_name`
- `orders.address_phone`
- `orders.address_detail`
- `orders.address_lat`
- `orders.address_lng`
- `orders.remark`
- `orders.paid_at`
- `orders.cooking_at`
- `orders.picked_at`
- `reviews` 表
- `riders` 表

### 4.2 routes 与 schema 不一致

后端路由使用 schema 不存在的表或字段：

- `reviews`
- `menu_items`
- `riders`
- `orders.delivery_address`
- `orders.rejected_at`
- `users.password`
- `users.is_online`
- `users.total_deliveries`

### 4.3 前端与后端不一致

前端 API 请求路径和请求体与后端不匹配：

- 前端注册：`/auth/register`，后端未实现。
- 前端 profile：`/auth/profile` 或 `/auth/me`，后端 `/auth/me` 未挂认证。
- 前端商品：`/merchants/my/products`，后端 `/merchants/manage/items`。
- 前端订单列表：`/orders/mine`，后端 `GET /orders`。
- 前端骑手：`/riders/my/*` 和 `/riders/deliveries/*`，后端为 `/riders/dashboard`、`/available-orders`、`/my-orders` 等。

## 5. 建议的 schema 对齐方向

建议选择一种数据模型作为唯一事实来源。

推荐以 `schema.sql` 为基准修复代码：

- 将 seed 的 `nickname` 改为 `name`。
- 将 seed 的 `avatar` 改为 `avatar_url`。
- 将 seed 的 `user` 角色改为 `customer`。
- 将路由中的 `menu_items` 改为 `products`。
- 将商家营业状态统一为 `is_open`。
- 如确实需要评价表和骑手档案表，应在 schema 中显式增加 `reviews`、`riders`，并同步 seed 和路由。
- 将订单地址字段统一为 `address`、`phone`、`customer_name`。
- 将 JWT payload 统一为 `{ userId, username, role }` 或统一改代码读取 `id`。
