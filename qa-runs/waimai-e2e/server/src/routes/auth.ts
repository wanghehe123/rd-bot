import { Router, Request, Response } from 'express';
import bcrypt from 'bcryptjs';
import jwt from 'jsonwebtoken';
import db from '../database';
import { secret as JWT_SECRET } from '../middleware/auth';

const router = Router();

// Input validation helpers
const isValidUsername = (u: string) => /^[a-zA-Z0-9_]{3,30}$/.test(u);
const isValidPassword = (p: string) => p.length >= 6 && p.length <= 100;

// Login
router.post('/login', (req: Request, res: Response) => {
  const { username, password } = req.body;
  if (!username || !password) {
    return res.status(400).json({ error: '请输入用户名和密码' });
  }

  if (typeof username !== 'string' || typeof password !== 'string') {
    return res.status(400).json({ error: '参数格式错误' });
  }

  const user = db.prepare('SELECT * FROM users WHERE username = ?').get(username) as any;
  if (!user) {
    return res.status(401).json({ error: '用户名或密码错误' });
  }

  const valid = bcrypt.compareSync(password, user.password_hash);
  if (!valid) {
    return res.status(401).json({ error: '用户名或密码错误' });
  }

  const token = jwt.sign(
    { id: user.id, username: user.username, role: user.role },
    JWT_SECRET,
    { expiresIn: '24h' }
  );

  res.json({
    token,
    user: {
      id: user.id,
      username: user.username,
      name: user.name,
      role: user.role,
      phone: user.phone,
      avatar_url: user.avatar_url,
    }
  });
});

// Get current user profile
router.get('/me', (req: Request, res: Response) => {
  const authReq = req as any;
  const userId = authReq.user.id;
  const user = db.prepare(
    'SELECT id, username, name, role, phone, avatar_url, created_at FROM users WHERE id = ?'
  ).get(userId) as any;

  if (!user) {
    return res.status(404).json({ error: '用户不存在' });
  }

  res.json(user);
});

// Change password
router.put('/password', (req: Request, res: Response) => {
  const authReq = req as any;
  const userId = authReq.user.id;
  const { oldPassword, newPassword } = req.body;

  if (!oldPassword || !newPassword) {
    return res.status(400).json({ error: '请输入旧密码和新密码' });
  }

  if (typeof newPassword !== 'string' || !isValidPassword(newPassword)) {
    return res.status(400).json({ error: '新密码长度需要 6-100 个字符' });
  }

  const user = db.prepare('SELECT * FROM users WHERE id = ?').get(userId) as any;
  if (!user || !bcrypt.compareSync(oldPassword, user.password_hash)) {
    return res.status(401).json({ error: '旧密码错误' });
  }

  const hash = bcrypt.hashSync(newPassword, 10);
  db.prepare('UPDATE users SET password_hash = ? WHERE id = ?').run(hash, userId);

  res.json({ message: '密码修改成功' });
});

export default router;
