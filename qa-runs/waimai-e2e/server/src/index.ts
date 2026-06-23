import express, { Request, Response, NextFunction } from 'express';
import cors from 'cors';
import db from './database';
import authRoutes from './routes/auth';
import merchantRoutes from './routes/merchants';
import orderRoutes from './routes/orders';
import riderRoutes from './routes/riders';
import adminRoutes from './routes/admin';

const app = express();
const PORT = parseInt(process.env.PORT || '3000', 10);

// 中间件
app.use(cors());
app.use(express.json());

// 路由
app.use('/api/auth', authRoutes);
app.use('/api/merchants', merchantRoutes);
app.use('/api/orders', orderRoutes);
app.use('/api/riders', riderRoutes);
app.use('/api/admin', adminRoutes);

// 健康检查
app.get('/api/health', (_req, res) => {
  res.json({ status: 'ok', timestamp: new Date().toISOString() });
});

// 全局错误处理中间件
app.use((err: Error, _req: Request, res: Response, _next: NextFunction) => {
  console.error('[Server Error]', err.stack || err.message);
  res.status(500).json({ error: '服务器内部错误' });
});

app.listen(PORT, () => {
  console.log(`🚀 外卖平台后端运行在 http://localhost:${PORT}`);
});
