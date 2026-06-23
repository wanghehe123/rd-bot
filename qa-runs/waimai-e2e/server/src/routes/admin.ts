import { Router, Request, Response } from 'express';
import bcrypt from 'bcryptjs';
import db from '../database';
import { authMiddleware, requireRole } from '../middleware/auth';

const router = Router();
router.use(authMiddleware);

// GET /api/admin/dashboard
router.get('/dashboard', requireRole('admin'), (req: Request, res: Response) => {
  try {
    const totalMerchants = db.prepare("SELECT COUNT(*) as count FROM merchants").get() as any;
    const totalRiders = db.prepare("SELECT COUNT(*) as count FROM users WHERE role = 'rider'").get() as any;
    const totalOrders = db.prepare("SELECT COUNT(*) as count FROM orders").get() as any;
    const totalCustomers = db.prepare("SELECT COUNT(*) as count FROM users WHERE role = 'customer'").get() as any;
    const pendingOrders = db.prepare("SELECT COUNT(*) as count FROM orders WHERE status = 'pending'").get() as any;
    const completedOrders = db.prepare("SELECT COUNT(*) as count FROM orders WHERE status = 'delivered'").get() as any;
    const totalRevenue = db.prepare("SELECT COALESCE(SUM(total_amount), 0) as total FROM orders WHERE status = 'delivered'").get() as any;

    const recentOrders = db.prepare(`
      SELECT o.*, u.username as customer_name, m.name as merchant_name
      FROM orders o
      LEFT JOIN users u ON o.customer_id = u.id
      LEFT JOIN merchants m ON o.merchant_id = m.id
      ORDER BY o.created_at DESC LIMIT 10
    `).all();

    res.json({
      totalMerchants: totalMerchants.count,
      totalRiders: totalRiders.count,
      totalOrders: totalOrders.count,
      totalCustomers: totalCustomers.count,
      pendingOrders: pendingOrders.count,
      completedOrders: completedOrders.count,
      totalRevenue: totalRevenue.total,
      recentOrders
    });
  } catch (error) {
    res.status(500).json({ error: '获取仪表盘数据失败' });
  }
});

// GET /api/admin/orders
router.get('/orders', requireRole('admin'), (req: Request, res: Response) => {
  try {
    const { status, page = 1, limit = 20 } = req.query;
    const offset = (Number(page) - 1) * Number(limit);

    let query = `
      SELECT o.*, u.username as customer_name, m.name as merchant_name,
             r.username as rider_name
      FROM orders o
      LEFT JOIN users u ON o.customer_id = u.id
      LEFT JOIN merchants m ON o.merchant_id = m.id
      LEFT JOIN users r ON o.rider_id = r.id
    `;
    const params: any[] = [];

    if (status) {
      query += ' WHERE o.status = ?';
      params.push(status);
    }

    query += ' ORDER BY o.created_at DESC LIMIT ? OFFSET ?';
    params.push(Number(limit), offset);

    const rows = db.prepare(query).all(...params);
    res.json(rows);
  } catch (error) {
    res.status(500).json({ error: '获取订单列表失败' });
  }
});

// PUT /api/admin/orders/:id/status
router.put('/orders/:id/status', requireRole('admin'), (req: Request, res: Response) => {
  try {
    const { status } = req.body;
    const order = db.prepare('SELECT * FROM orders WHERE id = ?').get(req.params.id) as any;

    if (!order) {
      return res.status(404).json({ error: '订单不存在' });
    }

    const validStatuses = ['pending', 'accepted', 'rejected', 'preparing', 'ready', 'picked_up', 'delivering', 'delivered', 'completed', 'cancelled'];
    if (!validStatuses.includes(status)) {
      return res.status(400).json({ error: '无效的订单状态' });
    }

    db.prepare('UPDATE orders SET status = ?, updated_at = datetime("now","localtime") WHERE id = ?')
      .run(status, req.params.id);

    res.json({ message: '订单状态更新成功' });
  } catch (error) {
    res.status(500).json({ error: '更新订单状态失败' });
  }
});

// GET /api/admin/merchants
router.get('/merchants', requireRole('admin'), (req: Request, res: Response) => {
  try {
    const rows = db.prepare(`
      SELECT m.*, u.username as owner_name
      FROM merchants m
      LEFT JOIN users u ON m.user_id = u.id
      ORDER BY m.created_at DESC
    `).all();
    res.json(rows);
  } catch (error) {
    res.status(500).json({ error: '获取商家列表失败' });
  }
});

// PUT /api/admin/merchants/:id/status
router.put('/merchants/:id/status', requireRole('admin'), (req: Request, res: Response) => {
  try {
    const { status } = req.body;
    const merchant = db.prepare('SELECT * FROM merchants WHERE id = ?').get(req.params.id);

    if (!merchant) {
      return res.status(404).json({ error: '商家不存在' });
    }

    db.prepare('UPDATE merchants SET status = ?, updated_at = datetime("now","localtime") WHERE id = ?')
      .run(status, req.params.id);

    res.json({ message: '商家状态更新成功' });
  } catch (error) {
    res.status(500).json({ error: '更新商家状态失败' });
  }
});

// POST /api/admin/merchants - 创建商家（管理员）
router.post('/merchants', requireRole('admin'), (req: Request, res: Response) => {
  try {
    const { username, password, name, address, phone, description, delivery_fee, min_order } = req.body;

    if (!username || !password || !name) {
      return res.status(400).json({ error: '用户名、密码和商家名称不能为空' });
    }

    const existing = db.prepare('SELECT id FROM users WHERE username = ?').get(username);
    if (existing) {
      return res.status(400).json({ error: '用户名已存在' });
    }

    const hashedPassword = bcrypt.hashSync(password, 10);
    const userResult = db.prepare(
      "INSERT INTO users (username, password, phone, role) VALUES (?, ?, ?, 'merchant')"
    ).run(username, hashedPassword, phone || '');

    const merchantResult = db.prepare(
      'INSERT INTO merchants (user_id, name, address, phone, description, delivery_fee, min_order) VALUES (?, ?, ?, ?, ?, ?, ?)'
    ).run(userResult.lastInsertRowid, name, address || '', phone || '', description || '', delivery_fee || 5, min_order || 20);

    res.status(201).json({
      id: merchantResult.lastInsertRowid,
      user_id: userResult.lastInsertRowid,
      name
    });
  } catch (error) {
    res.status(500).json({ error: '创建商家失败' });
  }
});

// GET /api/admin/riders
router.get('/riders', requireRole('admin'), (req: Request, res: Response) => {
  try {
    const rows = db.prepare(`
      SELECT id, username, phone, is_online, total_deliveries, created_at
      FROM users WHERE role = 'rider'
      ORDER BY created_at DESC
    `).all();
    res.json(rows);
  } catch (error) {
    res.status(500).json({ error: '获取骑手列表失败' });
  }
});

// PUT /api/admin/riders/:id/status
router.put('/riders/:id/status', requireRole('admin'), (req: Request, res: Response) => {
  try {
    const { is_online } = req.body;
    const rider = db.prepare("SELECT id FROM users WHERE id = ? AND role = 'rider'").get(req.params.id);

    if (!rider) {
      return res.status(404).json({ error: '骑手不存在' });
    }

    db.prepare('UPDATE users SET is_online = ? WHERE id = ?').run(is_online ? 1 : 0, req.params.id);
    res.json({ message: '骑手状态更新成功' });
  } catch (error) {
    res.status(500).json({ error: '更新骑手状态失败' });
  }
});

// POST /api/admin/riders - 创建骑手（管理员）
router.post('/riders', requireRole('admin'), (req: Request, res: Response) => {
  try {
    const { username, password, phone } = req.body;

    if (!username || !password) {
      return res.status(400).json({ error: '用户名和密码不能为空' });
    }

    const existing = db.prepare('SELECT id FROM users WHERE username = ?').get(username);
    if (existing) {
      return res.status(400).json({ error: '用户名已存在' });
    }

    const hashedPassword = bcrypt.hashSync(password, 10);
    const result = db.prepare(
      "INSERT INTO users (username, password, phone, role, is_online) VALUES (?, ?, ?, 'rider', 0)"
    ).run(username, hashedPassword, phone || '');

    res.status(201).json({ id: result.lastInsertRowid });
  } catch (error) {
    res.status(500).json({ error: '创建骑手失败' });
  }
});

// GET /api/admin/accounts
router.get('/accounts', requireRole('admin'), (req: Request, res: Response) => {
  try {
    const { role } = req.query;
    let query = 'SELECT id, username, phone, role, is_online, total_deliveries, created_at FROM users';
    const params: any[] = [];

    if (role) {
      query += ' WHERE role = ?';
      params.push(role);
    }

    query += ' ORDER BY created_at DESC';
    const accounts = db.prepare(query).all(...params);
    res.json(accounts);
  } catch (error) {
    res.status(500).json({ error: '获取账户列表失败' });
  }
});

// POST /api/admin/accounts
router.post('/accounts', requireRole('admin'), (req: Request, res: Response) => {
  try {
    const { username, password, phone, role } = req.body;

    if (!username || !password) {
      return res.status(400).json({ error: '用户名和密码不能为空' });
    }

    const existing = db.prepare('SELECT id FROM users WHERE username = ?').get(username);
    if (existing) {
      return res.status(400).json({ error: '用户名已存在' });
    }

    const hashedPassword = bcrypt.hashSync(password, 10);
    const result = db.prepare(
      'INSERT INTO users (username, password, phone, role) VALUES (?, ?, ?, ?)'
    ).run(username, hashedPassword, phone || '', role || 'customer');

    res.status(201).json({ id: result.lastInsertRowid });
  } catch (error) {
    res.status(500).json({ error: '创建账户失败' });
  }
});

export default router;
