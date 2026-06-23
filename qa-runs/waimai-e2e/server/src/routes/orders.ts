import { Router, Request, Response } from 'express';
import db from '../database';
import { authMiddleware, requireRole } from '../middleware/auth';

const router = Router();
router.use(authMiddleware);

// Generate order number
function generateOrderNo(): string {
  const now = new Date();
  const date = now.toISOString().replace(/[-T:\.Z]/g, '').slice(0, 14);
  const rand = Math.floor(Math.random() * 10000).toString().padStart(4, '0');
  return `ORD${date}${rand}`;
}

// POST /api/orders - 创建订单（顾客）
router.post('/', requireRole('customer'), (req: Request, res: Response) => {
  const authReq = req as any;
  const { merchant_id, items, address, phone, customer_name, note } = req.body;

  if (!merchant_id || !items || !items.length || !address || !phone || !customer_name) {
    return res.status(400).json({ error: '请填写完整的订单信息' });
  }

  // Validate items
  for (const item of items) {
    if (!item.product_id || !item.quantity || item.quantity < 1) {
      return res.status(400).json({ error: '订单商品信息格式错误' });
    }
  }

  const merchant = db.prepare('SELECT * FROM merchants WHERE id = ? AND is_open = 1').get(merchant_id) as any;
  if (!merchant) {
    return res.status(400).json({ error: '商家不存在或已休息' });
  }

  let totalAmount = 0;
  const orderItems: any[] = [];

  for (const item of items) {
    const product = db.prepare('SELECT * FROM products WHERE id = ? AND merchant_id = ? AND is_available = 1').get(item.product_id, merchant_id) as any;
    if (!product) {
      return res.status(400).json({ error: `商品 ${item.product_id} 不可用` });
    }
    if (product.stock < item.quantity) {
      return res.status(400).json({ error: `${product.name} 库存不足` });
    }
    const subtotal = product.price * item.quantity;
    totalAmount += subtotal;
    orderItems.push({
      product_id: product.id,
      product_name: product.name,
      product_price: product.price,
      quantity: item.quantity,
      subtotal,
    });
  }

  if (totalAmount < merchant.min_order) {
    return res.status(400).json({ error: `未达到最低消费 ${merchant.min_order} 元` });
  }

  totalAmount += merchant.delivery_fee;
  const orderNo = generateOrderNo();

  try {
    const insertOrder = db.prepare(`
      INSERT INTO orders (order_no, customer_id, merchant_id, status, total_amount, delivery_fee, address, phone, customer_name, note)
      VALUES (?, ?, ?, 'pending', ?, ?, ?, ?, ?, ?)
    `);

    const result = insertOrder.run(orderNo, authReq.user.id, merchant_id, totalAmount, merchant.delivery_fee, address, phone, customer_name, note || '');

    const insertItem = db.prepare(`
      INSERT INTO order_items (order_id, product_id, product_name, product_price, quantity, subtotal)
      VALUES (?, ?, ?, ?, ?, ?)
    `);

    for (const item of orderItems) {
      insertItem.run(result.lastInsertRowid, item.product_id, item.product_name, item.product_price, item.quantity, item.subtotal);
    }

    const order = db.prepare('SELECT * FROM orders WHERE id = ?').get(result.lastInsertRowid);
    res.status(201).json({ order });
  } catch (error) {
    res.status(500).json({ error: '创建订单失败' });
  }
});

// GET /api/orders - 获取订单列表
router.get('/', (req: Request, res: Response) => {
  const authReq = req as any;
  try {
    const { status } = req.query;
    const query = status
      ? 'SELECT * FROM orders WHERE customer_id = ? AND status = ? ORDER BY created_at DESC'
      : 'SELECT * FROM orders WHERE customer_id = ? ORDER BY created_at DESC';
    const rows = status
      ? db.prepare(query).all(authReq.user.id, status)
      : db.prepare(query).all(authReq.user.id);
    res.json(rows);
  } catch (error) {
    res.status(500).json({ error: '获取订单列表失败' });
  }
});

// GET /api/orders/:id - 获取订单详情
router.get('/:id', (req: Request, res: Response) => {
  const authReq = req as any;
  try {
    const order = db.prepare('SELECT * FROM orders WHERE id = ?').get(req.params.id) as any;
    if (!order) return res.status(404).json({ error: '订单不存在' });

    if (authReq.user.role === 'customer' && order.customer_id !== authReq.user.id) {
      return res.status(403).json({ error: '无权限查看' });
    }

    const items = db.prepare('SELECT * FROM order_items WHERE order_id = ?').all(order.id);
    res.json({ ...order, items });
  } catch (error) {
    res.status(500).json({ error: '获取订单详情失败' });
  }
});

// PUT /api/orders/:id/status - 更新订单状态
router.put('/:id/status', (req: Request, res: Response) => {
  const authReq = req as any;
  const { status, note, reject_reason, cancel_reason } = req.body;
  const order = db.prepare('SELECT * FROM orders WHERE id = ?').get(req.params.id) as any;

  if (!order) return res.status(404).json({ error: '订单不存在' });

  const validTransitions: Record<string, Record<string, string[]>> = {
    customer: {
      pending: ['cancelled'],
      delivered: ['completed'],
    },
    merchant: {
      pending: ['accepted', 'rejected'],
      accepted: ['preparing'],
      preparing: ['ready'],
    },
    rider: {
      ready: ['picked_up'],
      picked_up: ['delivering'],
      delivering: ['delivered'],
    },
    admin: {},
  };

  const allowed = validTransitions[authReq.user.role]?.[order.status] || [];
  if (!allowed.includes(status)) {
    return res.status(400).json({ error: `不能从 ${order.status} 变为 ${status}` });
  }

  let updateFields = 'status = ?, updated_at = datetime("now","localtime")';
  const params: any[] = [status];

  if (status === 'cancelled') { updateFields += ', cancelled_at = datetime("now","localtime"), cancel_reason = ?'; params.push(cancel_reason || ''); }
  if (status === 'rejected') { updateFields += ', rejected_at = datetime("now","localtime"), reject_reason = ?'; params.push(reject_reason || ''); }
  if (status === 'accepted') { updateFields += ', accepted_at = datetime("now","localtime")'; }
  if (status === 'preparing') { updateFields += ', preparing_at = datetime("now","localtime")'; }
  if (status === 'ready') { updateFields += ', ready_at = datetime("now","localtime")'; }
  if (status === 'picked_up') { updateFields += ', picked_up_at = datetime("now","localtime")'; }
  if (status === 'delivering') { updateFields += ', delivering_at = datetime("now","localtime")'; }
  if (status === 'delivered') { updateFields += ', delivered_at = datetime("now","localtime")'; }
  if (status === 'completed') { updateFields += ', completed_at = datetime("now","localtime")'; }

  params.push(req.params.id);
  db.prepare(`UPDATE orders SET ${updateFields} WHERE id = ?`).run(...params);

  db.prepare('INSERT INTO order_status_logs (order_id, status, operator_id, note) VALUES (?, ?, ?, ?)')
    .run(order.id, status, authReq.user.id, note || '');

  res.json({ message: '状态更新成功' });
});

// POST /api/orders/:id/rate - 评价订单
router.post('/:id/rate', requireRole('customer'), (req: Request, res: Response) => {
  const authReq = req as any;
  const { rating_merchant, rating_rider, review } = req.body;
  const order = db.prepare('SELECT * FROM orders WHERE id = ? AND customer_id = ? AND status = ?')
    .get(req.params.id, authReq.user.id, 'completed') as any;

  if (!order) return res.status(400).json({ error: '订单不存在或未完成' });
  if (order.rating_merchant) return res.status(400).json({ error: '已评价' });

  db.prepare('UPDATE orders SET rating_merchant = ?, rating_rider = ?, review = ? WHERE id = ?')
    .run(rating_merchant || 5, rating_rider || 5, review || '', order.id);

  if (rating_merchant) {
    const merchant = db.prepare('SELECT rating, rating_count FROM merchants WHERE id = ?').get(order.merchant_id) as any;
    const newCount = merchant.rating_count + 1;
    const newRating = ((merchant.rating * merchant.rating_count) + rating_merchant) / newCount;
    db.prepare('UPDATE merchants SET rating = ?, rating_count = ? WHERE id = ?')
      .run(Math.round(newRating * 10) / 10, newCount, order.merchant_id);
  }

  res.json({ message: '评价成功' });
});

export default router;
