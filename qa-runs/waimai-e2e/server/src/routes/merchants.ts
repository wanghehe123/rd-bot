import { Router, Request, Response } from 'express';
import db from '../database';
import { authMiddleware, requireRole } from '../middleware/auth';

const router = Router();

// ===== Public: 用户端浏览商家 =====

// GET /api/merchants - 用户浏览商家列表
router.get('/', (req: Request, res: Response) => {
  try {
    const { search, category, sort } = req.query;
    let sql = `SELECT m.*, COALESCE(AVG(r.rating), 0) as avg_rating, COUNT(DISTINCT r.id) as review_count
      FROM merchants m
      LEFT JOIN reviews r ON r.merchant_id = m.id
      WHERE m.status = 'open'`;
    const params: any[] = [];

    if (search) {
      sql += ` AND m.name LIKE ?`;
      params.push(`%${search}%`);
    }
    if (category) {
      sql += ` AND m.category = ?`;
      params.push(category);
    }
    sql += ` GROUP BY m.id`;

    if (sort === 'rating') {
      sql += ` ORDER BY avg_rating DESC`;
    } else if (sort === 'orders') {
      sql += ` ORDER BY review_count DESC`;
    } else {
      sql += ` ORDER BY m.id ASC`;
    }

    const merchants = db.prepare(sql).all(...params);
    res.json(merchants);
  } catch (error: any) {
    res.status(500).json({ error: error.message });
  }
});

// GET /api/merchants/categories - 获取所有商家分类
router.get('/categories', (req: Request, res: Response) => {
  try {
    const categories = db.prepare(`SELECT DISTINCT category FROM merchants WHERE status = 'open' ORDER BY category`).all();
    res.json(categories.map((c: any) => c.category));
  } catch (error: any) {
    res.status(500).json({ error: error.message });
  }
});

// GET /api/merchants/:id - 获取商家详情
router.get('/:id', (req: Request, res: Response) => {
  try {
    const merchant = db.prepare(`SELECT m.*, COALESCE(AVG(r.rating), 0) as avg_rating, COUNT(DISTINCT r.id) as review_count
      FROM merchants m LEFT JOIN reviews r ON r.merchant_id = m.id
      WHERE m.id = ? GROUP BY m.id`).get(req.params.id);
    if (!merchant) return res.status(404).json({ error: '商家不存在' });
    res.json(merchant);
  } catch (error: any) {
    res.status(500).json({ error: error.message });
  }
});

// GET /api/merchants/:id/menu - 获取商家菜单（按分类分组）
router.get('/:id/menu', (req: Request, res: Response) => {
  try {
    const categories = db.prepare(`SELECT * FROM categories WHERE merchant_id = ? ORDER BY sort_order`).all(req.params.id);
    const items = db.prepare(`SELECT * FROM menu_items WHERE merchant_id = ? AND is_available = 1`).all(req.params.id);
    const result = categories.map((cat: any) => ({
      ...cat,
      items: items.filter((item: any) => item.category_id === cat.id)
    }));
    res.json(result);
  } catch (error: any) {
    res.status(500).json({ error: error.message });
  }
});

// GET /api/merchants/:id/reviews - 获取商家评价
router.get('/:id/reviews', (req: Request, res: Response) => {
  try {
    const reviews = db.prepare(`
      SELECT r.*, u.username as user_name
      FROM reviews r JOIN users u ON r.user_id = u.id
      WHERE r.merchant_id = ? ORDER BY r.created_at DESC LIMIT 50
    `).all(req.params.id);
    res.json(reviews);
  } catch (error: any) {
    res.status(500).json({ error: error.message });
  }
});

// ===== 商家端管理接口 =====

router.use(authMiddleware);
router.use(requireRole('merchant'));

// GET /api/merchants/manage/info - 商家获取自己的信息
router.get('/manage/info', (req: Request, res: Response) => {
  try {
    const merchant = db.prepare(`SELECT * FROM merchants WHERE user_id = ?`).get(req.userId);
    if (!merchant) return res.status(404).json({ error: '商家信息不存在' });
    res.json(merchant);
  } catch (error: any) {
    res.status(500).json({ error: error.message });
  }
});

// PATCH /api/merchants/manage/status - 切换营业状态
router.patch('/manage/status', (req: Request, res: Response) => {
  try {
    const { status } = req.body;
    if (!['open', 'closed', 'paused'].includes(status)) {
      return res.status(400).json({ error: '无效的营业状态' });
    }
    const merchant = db.prepare(`SELECT * FROM merchants WHERE user_id = ?`).get(req.userId) as any;
    if (!merchant) return res.status(404).json({ error: '商家信息不存在' });
    db.prepare(`UPDATE merchants SET status = ? WHERE id = ?`).run(status, merchant.id);
    res.json({ message: '营业状态已更新', status });
  } catch (error: any) {
    res.status(500).json({ error: error.message });
  }
});

// GET /api/merchants/manage/categories - 商家获取自己的分类
router.get('/manage/categories', (req: Request, res: Response) => {
  try {
    const merchant = db.prepare(`SELECT * FROM merchants WHERE user_id = ?`).get(req.userId) as any;
    if (!merchant) return res.status(404).json({ error: '商家信息不存在' });
    const categories = db.prepare(`SELECT * FROM categories WHERE merchant_id = ? ORDER BY sort_order`).all(merchant.id);
    res.json(categories);
  } catch (error: any) {
    res.status(500).json({ error: error.message });
  }
});

// POST /api/merchants/manage/categories - 创建分类
router.post('/manage/categories', (req: Request, res: Response) => {
  try {
    const merchant = db.prepare(`SELECT * FROM merchants WHERE user_id = ?`).get(req.userId) as any;
    if (!merchant) return res.status(404).json({ error: '商家信息不存在' });
    const { name, sort_order } = req.body;
    if (!name) return res.status(400).json({ error: '分类名称不能为空' });
    const maxOrder = db.prepare(`SELECT MAX(sort_order) as max_order FROM categories WHERE merchant_id = ?`).get(merchant.id) as any;
    const result = db.prepare(`INSERT INTO categories (merchant_id, name, sort_order) VALUES (?, ?, ?)`).run(
      merchant.id, name, sort_order ?? ((maxOrder?.max_order || 0) + 1)
    );
    res.json({ id: result.lastInsertRowid, name, sort_order: sort_order ?? ((maxOrder?.max_order || 0) + 1) });
  } catch (error: any) {
    res.status(500).json({ error: error.message });
  }
});

// DELETE /api/merchants/manage/categories/:id - 删除分类
router.delete('/manage/categories/:id', (req: Request, res: Response) => {
  try {
    const merchant = db.prepare(`SELECT * FROM merchants WHERE user_id = ?`).get(req.userId) as any;
    if (!merchant) return res.status(404).json({ error: '商家信息不存在' });
    const itemsCount = db.prepare(`SELECT COUNT(*) as cnt FROM menu_items WHERE category_id = ?`).get(req.params.id) as any;
    if (itemsCount.cnt > 0) {
      return res.status(400).json({ error: '该分类下还有商品，请先移除商品' });
    }
    db.prepare(`DELETE FROM categories WHERE id = ? AND merchant_id = ?`).run(req.params.id, merchant.id);
    res.json({ message: '分类已删除' });
  } catch (error: any) {
    res.status(500).json({ error: error.message });
  }
});

// GET /api/merchants/manage/items - 商家获取所有商品
router.get('/manage/items', (req: Request, res: Response) => {
  try {
    const merchant = db.prepare(`SELECT * FROM merchants WHERE user_id = ?`).get(req.userId) as any;
    if (!merchant) return res.status(404).json({ error: '商家信息不存在' });
    const items = db.prepare(`
      SELECT mi.*, c.name as category_name
      FROM menu_items mi
      LEFT JOIN categories c ON mi.category_id = c.id
      WHERE mi.merchant_id = ? ORDER BY mi.category_id, mi.id
    `).all(merchant.id);
    res.json(items);
  } catch (error: any) {
    res.status(500).json({ error: error.message });
  }
});

// POST /api/merchants/manage/items - 创建商品
router.post('/manage/items', (req: Request, res: Response) => {
  try {
    const merchant = db.prepare(`SELECT * FROM merchants WHERE user_id = ?`).get(req.userId) as any;
    if (!merchant) return res.status(404).json({ error: '商家信息不存在' });
    const { category_id, name, description, price, image_url, stock } = req.body;
    if (!name || !price) return res.status(400).json({ error: '商品名称和价格不能为空' });
    const result = db.prepare(
      `INSERT INTO menu_items (merchant_id, category_id, name, description, price, image_url, stock, is_available) VALUES (?, ?, ?, ?, ?, ?, ?, 1)`
    ).run(merchant.id, category_id, name, description || '', price, image_url || '', stock ?? 999);
    res.json({ id: result.lastInsertRowid, message: '商品已创建' });
  } catch (error: any) {
    res.status(500).json({ error: error.message });
  }
});

// PATCH /api/merchants/manage/items/:id - 更新商品
router.patch('/manage/items/:id', (req: Request, res: Response) => {
  try {
    const merchant = db.prepare(`SELECT * FROM merchants WHERE user_id = ?`).get(req.userId) as any;
    if (!merchant) return res.status(404).json({ error: '商家信息不存在' });
    const item = db.prepare(`SELECT * FROM menu_items WHERE id = ? AND merchant_id = ?`).get(req.params.id, merchant.id) as any;
    if (!item) return res.status(404).json({ error: '商品不存在' });
    const { name, description, price, image_url, stock, is_available, category_id } = req.body;
    db.prepare(`UPDATE menu_items SET name = ?, description = ?, price = ?, image_url = ?, stock = ?, is_available = ?, category_id = ? WHERE id = ?`).run(
      name ?? item.name, description ?? item.description, price ?? item.price,
      image_url ?? item.image_url, stock ?? item.stock,
      is_available !== undefined ? (is_available ? 1 : 0) : item.is_available,
      category_id ?? item.category_id, item.id
    );
    res.json({ message: '商品已更新' });
  } catch (error: any) {
    res.status(500).json({ error: error.message });
  }
});

// DELETE /api/merchants/manage/items/:id - 删除商品
router.delete('/manage/items/:id', (req: Request, res: Response) => {
  try {
    const merchant = db.prepare(`SELECT * FROM merchants WHERE user_id = ?`).get(req.userId) as any;
    if (!merchant) return res.status(404).json({ error: '商家信息不存在' });
    db.prepare(`DELETE FROM menu_items WHERE id = ? AND merchant_id = ?`).run(req.params.id, merchant.id);
    res.json({ message: '商品已删除' });
  } catch (error: any) {
    res.status(500).json({ error: error.message });
  }
});

export default router;
