import { Request, Response, NextFunction } from 'express';
import jwt from 'jsonwebtoken';
import db from '../database';

// 生产环境必须设置 JWT_SECRET 环境变量
const JWT_SECRET = process.env.JWT_SECRET;
if (!JWT_SECRET) {
  if (process.env.NODE_ENV === 'production') {
    throw new Error('JWT_SECRET environment variable is required in production');
  }
  console.warn('⚠️  JWT_SECRET not set, using development default. Set JWT_SECRET in production!');
}

const secret = JWT_SECRET || 'waimai-dev-secret-key-2024';

export interface AuthRequest extends Request {
  user?: { id: string; username: string; role: string };
  userId?: number;
}

export const authMiddleware = (req: AuthRequest, res: Response, next: NextFunction) => {
  const authHeader = req.headers.authorization;
  if (!authHeader || !authHeader.startsWith('Bearer ')) {
    return res.status(401).json({ error: '未提供认证令牌' });
  }

  const token = authHeader.split(' ')[1];

  try {
    const decoded = jwt.verify(token, secret) as { userId: number; username: string; role: string };

    const user = db.prepare('SELECT id, username, role FROM users WHERE id = ?').get(decoded.userId);
    if (!user) {
      return res.status(401).json({ error: '用户不存在' });
    }

    req.user = decoded;
    req.userId = decoded.userId;
    next();
  } catch (error) {
    return res.status(401).json({ error: '令牌无效或已过期' });
  }
};

export const requireRole = (...roles: string[]) => {
  return (req: AuthRequest, res: Response, next: NextFunction) => {
    if (!req.user || !roles.includes(req.user.role)) {
      return res.status(403).json({ error: '无权限访问' });
    }
    next();
  };
};

export { secret };
