import { Router, Request, Response } from 'express';
import db from '../database';
import { authMiddleware, requireRole } from '../middleware/auth';

const router = Router();
router.use(authMiddleware);

// GET /api/riders/dashboard
router.get('/dashboard', (req: Request, res: Response) => {
  const authReq = req as any;
  const riderId = authReq.user.id;

  const riderProfile = db.prepare('SELECT * FROM riders WHERE user_id = ?').get(riderId) as any;
  if (!riderProfile) {
    return res.status(404).json({ error: '骑手信息不存在' });
  }

  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const todayTimestamp = Math.floor(today.getTime() / 1000);

  const todayOrders = db.prepare(`
    SELECT COUNT(*) as count, COALESCE(SUM(delivery_fee), 0) as income
    FROM orders WHERE rider_id = ? AND status = 'delivered' AND delivered_at >= ?
  `).get(riderId, todayTimestamp) as any;

  const activeOrders = db.prepare(`
    SELECT COUNT(*) as count FROM orders
    WHERE rider_id = ? AND status IN ('picked_up', 'delivering')
  `).get(riderId) as any;

  res.json({
    is_online: riderProfile.is_online,
    today_completed: todayOrders.count,
    today_income: todayOrders.income,
    active_orders: activeOrders.count,
    total_completed: riderProfile.total_orders,
    total_income: riderProfile.total_income,
    rating: riderProfile.rating,
  });
});

// PUT /api/riders/online
router.put('/online', (req: Request, res: Response) => {
  const authReq = req as any;
  const riderId = authReq.user.id;
  const { is_online } = req.body;

  const riderProfile = db.prepare('SELECT * FROM riders WHERE user_id = ?').get(riderId) as any;
  if (!riderProfile) {
    return res.status(404).json({ error: '骑手信息不存在' });
  }

  db.prepare('UPDATE riders SET is_online = ? WHERE user_id = ?').run(is_online ? 1 : 0, riderId);
  res.json({ message: is_online ? '已上线' : '已下线', is_online });
});

// GET /api/riders/available-orders
router.get('/available-orders', (req: Request, res: Response) => {
  const authReq = req as any;
  const riderId = authReq.user.id;

  const riderProfile = db.prepare('SELECT * FROM riders WHERE user_id = ?').get(riderId) as any;
  if (!riderProfile || !riderProfile.is_online) {
    return res.status(400).json({ error: '请先上线接单' });
  }

  const orders = db.prepare(`
    SELECT o.*, m.name as merchant_name, m.address as merchant_address,
           m.phone as merchant_phone
    FROM orders o
    JOIN merchants m ON o.merchant_id = m.id
    WHERE o.status = 'ready' AND o.rider_id IS NULL
    ORDER BY o.created_at ASC
    LIMIT 20
  `).all();

  // Parse delivery_address JSON
  const result = orders.map((o: any) => ({
    ...o,
    delivery_address: JSON.parse(o.delivery_address),
  }));

  res.json(result);
});

// POST /api/riders/accept/:orderId
router.post('/accept/:orderId', (req: Request, res: Response) => {
  const authReq = req as any;
  const riderId = authReq.user.id;
  const orderId = parseInt(req.params.orderId);

  const riderProfile = db.prepare('SELECT * FROM riders WHERE user_id = ?').get(riderId) as any;
  if (!riderProfile || !riderProfile.is_online) {
    return res.status(400).json({ error: '请先上线接单' });
  }

  const order = db.prepare('SELECT * FROM orders WHERE id = ?').get(orderId) as any;
  if (!order) {
    return res.status(404).json({ error: '订单不存在' });
  }
  if (order.status !== 'ready' || order.rider_id) {
    return res.status(400).json({ error: '该订单已被接取' });
  }

  db.prepare(`
    UPDATE orders SET rider_id = ?, status = 'picked_up', picked_up_at = ? WHERE id = ?
  `).run(riderId, Math.floor(Date.now() / 1000), orderId);

  res.json({ message: '接单成功' });
});

// GET /api/riders/my-orders
router.get('/my-orders', (req: Request, res: Response) => {
  const authReq = req as any;
  const riderId = authReq.user.id;
  const { status, page = 1, limit = 20 } = req.query;

  let sql = `
    SELECT o.*, m.name as merchant_name, m.address as merchant_address
    FROM orders o
    JOIN merchants m ON o.merchant_id = m.id
    WHERE o.rider_id = ?
  `;
  const params: any[] = [riderId];

  if (status) {
    sql += ' AND o.status = ?';
    params.push(status);
  }

  sql += ' ORDER BY o.created_at DESC';
  const offset = (Number(page) - 1) * Number(limit);
  sql += ` LIMIT ? OFFSET ?`;
  params.push(Number(limit), offset);

  const orders = db.prepare(sql).all(...params);
  const result = orders.map((o: any) => ({
    ...o,
    delivery_address: JSON.parse(o.delivery_address),
  }));

  const total = (db.prepare(
    'SELECT COUNT(*) as count FROM orders WHERE rider_id = ?' + (status ? ' AND status = ?' : '')
  ).get(...(status ? [riderId, status] : [riderId])) as any).count;

  res.json({ data: result, total, page: Number(page), limit: Number(limit) });
});

// POST /api/riders/deliver/:orderId
router.post('/deliver/:orderId', (req: Request, res: Response) => {
  const authReq = req as any;
  const riderId = authReq.user.id;
  const orderId = parseInt(req.params.orderId);

  const order = db.prepare('SELECT * FROM orders WHERE id = ? AND rider_id = ?').get(orderId, riderId) as any;
  if (!order) {
    return res.status(404).json({ error: '订单不存在' });
  }
  if (!['picked_up', 'delivering'].includes(order.status)) {
    return res.status(400).json({ error: '订单状态不允许完成配送' });
  }

  const now = Math.floor(Date.now() / 1000);
  db.prepare(`
    UPDATE orders SET status = 'delivered', delivered_at = ?, updated_at = ? WHERE id = ?
  `).run(now, now, orderId);

  // Update rider stats
  db.prepare(`
    UPDATE riders SET total_orders = total_orders + 1, total_income = total_income + ? WHERE user_id = ?
  `).run(order.delivery_fee || 5, riderId);

  res.json({ message: '配送完成' });
});

export default router;
