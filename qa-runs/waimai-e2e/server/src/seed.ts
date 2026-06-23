import db from './database';
import bcrypt from 'bcryptjs';

function seedDatabase() {
  // Check if already seeded
  const userCount = db.prepare('SELECT COUNT(*) as count FROM users').get() as any;
  if (userCount.count > 0) {
    console.log('[seed] 数据库已有数据，跳过初始化');
    return;
  }

  console.log('[seed] 首次运行，正在生成演示数据...');
  const salt = bcrypt.genSaltSync(10);
  const defaultPassword = bcrypt.hashSync('123456', salt);

  const insertUser = db.prepare(`
    INSERT INTO users (username, password, nickname, role, phone, avatar, status)
    VALUES (?, ?, ?, ?, ?, ?, ?)
  `);

  const insertCategory = db.prepare(`
    INSERT INTO categories (merchant_id, name, sort_order) VALUES (?, ?, ?)
  `);

  const insertProduct = db.prepare(`
    INSERT INTO products (merchant_id, category_id, name, description, price, original_price, image, stock, sales, status, sort_order)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
  `);

  const insertMerchant = db.prepare(`
    INSERT INTO merchants (user_id, name, description, address, phone, logo, business_hours, delivery_fee, min_order, rating, monthly_sales, status, category, latitude, longitude)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
  `);

  const insertRider = db.prepare(`
    INSERT INTO riders (user_id, name, phone, status, current_lat, current_lng, total_orders, rating)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
  `);

  const insertOrder = db.prepare(`
    INSERT INTO orders (order_no, user_id, merchant_id, rider_id, status, total_amount, delivery_fee, packaging_fee, discount_amount, pay_amount, address_name, address_phone, address_detail, address_lat, address_lng, remark, created_at, updated_at, paid_at, accepted_at, cooking_at, ready_at, picked_at, delivered_at, completed_at, cancel_reason)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
  `);

  const insertOrderItem = db.prepare(`
    INSERT INTO order_items (order_id, product_id, product_name, product_image, price, quantity, subtotal)
    VALUES (?, ?, ?, ?, ?, ?, ?)
  `);

  const insertReview = db.prepare(`
    INSERT INTO reviews (order_id, user_id, merchant_id, rider_id, merchant_rating, rider_rating, content, images, created_at)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
  `);

  const seedAll = db.transaction(() => {
    // ===== 用户账号 =====
    // 管理员
    insertUser.run('admin', defaultPassword, '系统管理员', 'admin', '13800000000', '👨‍💼', 'active');
    // 用户
    insertUser.run('user1', defaultPassword, '张小明', 'user', '13800000001', '🧑', 'active');
    insertUser.run('user2', defaultPassword, '李小红', 'user', '13800000002', '👩', 'active');
    insertUser.run('user3', defaultPassword, '王大力', 'user', '13800000003', '🧔', 'active');
    // 商家
    insertUser.run('merchant1', defaultPassword, '黄焖鸡米饭老板', 'merchant', '13800000011', '👨‍🍳', 'active');
    insertUser.run('merchant2', defaultPassword, '麻辣烫老板', 'merchant', '13800000012', '👩‍🍳', 'active');
    insertUser.run('merchant3', defaultPassword, '奶茶店老板', 'merchant', '13800000013', '🧑‍🍳', 'active');
    insertUser.run('merchant4', defaultPassword, '披萨店老板', 'merchant', '13800000014', '🍕', 'active');
    // 骑手
    insertUser.run('rider1', defaultPassword, '赵骑手', 'rider', '13800000021', '🏍️', 'active');
    insertUser.run('rider2', defaultPassword, '钱骑手', 'rider', '13800000022', '🛵', 'active');
    insertUser.run('rider3', defaultPassword, '孙骑手', 'rider', '13800000023', '🚴', 'active');

    // ===== 商家信息 =====
    // merchant1: 黄焖鸡米饭 (user_id=5)
    insertMerchant.run(5, '黄焖鸡米饭（科技园店）', '正宗黄焖鸡，汤浓肉嫩，米饭免费续', '深圳市南山区科技园南路88号', '0755-26001001', '🍚', '10:00-22:00', 3, 15, 4.8, 3256, 'open', '中式快餐', 22.5405, 113.9605);
    // merchant2: 麻辣烫 (user_id=6)
    insertMerchant.run(6, '张记麻辣烫', '骨汤熬制，食材新鲜，可选辣度', '深圳市南山区深南大道9988号', '0755-26001002', '🌶️', '11:00-23:00', 4, 20, 4.6, 2180, 'open', '麻辣烫', 22.5380, 113.9580);
    // merchant3: 奶茶店 (user_id=7)
    insertMerchant.run(7, '茶百道（万象天地店）', '鲜果茶饮，真材实料', '深圳市南山区万象天地B1层', '0755-26001003', '🧋', '09:30-22:30', 5, 10, 4.9, 5680, 'open', '茶饮', 22.5350, 113.9550);
    // merchant4: 披萨 (user_id=8)
    insertMerchant.run(8, '老王披萨', '手工现做，真材实料，超大份量', '深圳市南山区后海滨路100号', '0755-26001004', '🍕', '10:30-21:30', 6, 30, 4.5, 890, 'open', '西式快餐', 22.5320, 113.9520);

    // ===== 商品分类 =====
    // 黄焖鸡分类
    insertCategory.run(1, '招牌推荐', 1);
    insertCategory.run(1, '黄焖鸡系列', 2);
    insertCategory.run(1, '配菜', 3);
    insertCategory.run(1, '饮品', 4);
    // 麻辣烫分类
    insertCategory.run(2, '招牌麻辣烫', 1);
    insertCategory.run(2, '拌面系列', 2);
    insertCategory.run(2, '小吃', 3);
    insertCategory.run(2, '饮品', 4);
    // 奶茶分类
    insertCategory.run(3, '鲜果茶', 1);
    insertCategory.run(3, '奶茶', 2);
    insertCategory.run(3, '纯茶', 3);
    insertCategory.run(3, '小料加购', 4);
    // 披萨分类
    insertCategory.run(4, '经典披萨', 1);
    insertCategory.run(4, '意面', 2);
    insertCategory.run(4, '小食', 3);
    insertCategory.run(4, '饮品', 4);

    // ===== 商品 =====
    // 黄焖鸡商品
    insertProduct.run(1, 1, '黄焖鸡米饭套餐', '招牌黄焖鸡+米饭+酸梅汤', 25, 32, '🍗', 200, 1856, 'on', 1);
    insertProduct.run(1, 1, '黄焖排骨米饭套餐', '秘制黄焖排骨+米饭+酸梅汤', 30, 38, '🍖', 150, 923, 'on', 2);
    insertProduct.run(1, 2, '黄焖鸡（大份）', '大份黄焖鸡，肉量翻倍', 32, 40, '🍗', 100, 654, 'on', 1);
    insertProduct.run(1, 2, '黄焖鸡（中份）', '中份黄焖鸡，一人食刚好', 22, 28, '🍗', 200, 1200, 'on', 2);
    insertProduct.run(1, 2, '黄焖鸡（小份）', '小份黄焖鸡，尝鲜价', 16, 20, '🍗', 300, 800, 'on', 3);
    insertProduct.run(1, 3, '卤蛋', '秘制卤蛋', 2, 3, '🥚', 500, 2100, 'on', 1);
    insertProduct.run(1, 3, '豆腐', '卤水老豆腐', 3, 4, '🧈', 300, 1500, 'on', 2);
    insertProduct.run(1, 3, '青菜', '时令蔬菜', 5, 6, '🥬', 200, 800, 'on', 3);
    insertProduct.run(1, 4, '酸梅汤', '自制酸梅汤', 5, 8, '🥤', 500, 3000, 'on', 1);
    insertProduct.run(1, 4, '可乐', '可口可乐330ml', 3, 0, '🥤', 1000, 4000, 'on', 2);

    // 麻辣烫商品
    insertProduct.run(2, 5, '招牌骨汤麻辣烫', '大骨浓汤，自选食材', 28, 35, '🍲', 150, 1200, 'on', 1);
    insertProduct.run(2, 5, '番茄麻辣烫', '酸甜番茄汤底', 26, 32, '🍅', 100, 800, 'on', 2);
    insertProduct.run(2, 5, '麻辣拌', '干拌麻辣，香辣过瘾', 25, 30, '🌶️', 100, 650, 'on', 3);
    insertProduct.run(2, 6, '麻酱拌面', '浓郁麻酱拌面', 18, 22, '🍜', 200, 900, 'on', 1);
    insertProduct.run(2, 6, '酸辣拌面', '酸辣开胃', 18, 22, '🍜', 200, 700, 'on', 2);
    insertProduct.run(2, 7, '炸鸡柳', '外酥里嫩', 12, 15, '🍗', 300, 1100, 'on', 1);
    insertProduct.run(2, 7, '烤肠', '脆皮烤肠', 5, 6, '🌭', 500, 2000, 'on', 2);
    insertProduct.run(2, 8, '王老吉', '凉茶', 5, 0, '🥤', 500, 1500, 'on', 1);

    // 奶茶商品
    insertProduct.run(3, 9, '杨枝甘露', '芒果+西柚+椰奶', 18, 22, '🥭', 300, 2800, 'on', 1);
    insertProduct.run(3, 9, '葡萄柠檬茶', '鲜葡萄+柠檬', 16, 20, '🍇', 300, 2200, 'on', 2);
    insertProduct.run(3, 9, '草莓大福', '草莓+糯米', 20, 25, '🍓', 200, 1800, 'on', 3);
    insertProduct.run(3, 10, '经典珍珠奶茶', 'Q弹珍珠+香浓奶茶', 12, 15, '🧋', 500, 4500, 'on', 1);
    insertProduct.run(3, 10, '芋泥啵啵奶茶', '香芋+啵啵', 16, 20, '🧋', 300, 3200, 'on', 2);
    insertProduct.run(3, 10, '红豆奶茶', '红豆+奶茶', 14, 18, '🧋', 300, 2100, 'on', 3);
    insertProduct.run(3, 11, '茉莉绿茶', '清香茉莉', 8, 10, '🍵', 500, 1500, 'on', 1);
    insertProduct.run(3, 11, '桂花乌龙', '桂花飘香', 10, 12, '🍵', 400, 1200, 'on', 2);
    insertProduct.run(3, 12, '珍珠', 'Q弹黑糖珍珠', 2, 0, '⚪', 1000, 5000, 'on', 1);
    insertProduct.run(3, 12, '椰果', '清爽椰果', 2, 0, '🟤', 1000, 3000, 'on', 2);

    // 披萨商品
    insertProduct.run(4, 13, '超级至尊披萨', '培根+火腿+蘑菇+青椒+芝士', 58, 78, '🍕', 50, 320, 'on', 1);
    insertProduct.run(4, 13, '夏威夷披萨', '菠萝+火腿+芝士', 48, 65, '🍕', 80, 280, 'on', 2);
    insertProduct.run(4, 13, '榴莲披萨', '金枕榴莲+芝士', 68, 88, '🍕', 30, 150, 'on', 3);
    insertProduct.run(4, 14, '番茄肉酱意面', '经典意式肉酱', 28, 35, '🍝', 100, 450, 'on', 1);
    insertProduct.run(4, 14, '奶油培根意面', '浓郁奶油+培根', 32, 40, '🍝', 80, 320, 'on', 2);
    insertProduct.run(4, 15, '烤鸡翅（4个）', '秘制烤翅', 18, 22, '🍗', 200, 600, 'on', 1);
    insertProduct.run(4, 15, '芝士薯条', '浓郁芝士酱薯条', 16, 20, '🍟', 200, 500, 'on', 2);
    insertProduct.run(4, 16, '可口可乐', '330ml', 5, 0, '🥤', 500, 1000, 'on', 1);

    // ===== 骑手信息 =====
    insertRider.run(9, '赵骑手', '13800000021', 'online', 22.5400, 113.9600, 1256, 4.9);
    insertRider.run(10, '钱骑手', '13800000022', 'online', 22.5350, 113.9550, 980, 4.7);
    insertRider.run(11, '孙骑手', '13800000023', 'offline', 22.5380, 113.9570, 650, 4.8);

    // ===== 示例订单 =====
    const now = Date.now();
    const hour = 3600000;
    const day = 86400000;

    // 已完成订单1 - user1 在黄焖鸡下单
    const t1 = now - 3 * day;
    insertOrder.run('WM202401010001', 2, 1, 1, 'completed', 55, 3, 2, 5, 55,
      '张小明', '13800000001', '深圳市南山区科技园1号楼101', 22.5410, 113.9610,
      '多加辣', t1, t1 + 2 * hour, t1, t1 + 5 * 60000, t1 + 10 * 60000, t1 + 25 * 60000, t1 + 30 * 60000, t1 + 50 * 60000, t1 + 55 * 60000, null);

    insertOrderItem.run(1, 1, '黄焖鸡米饭套餐', '🍗', 25, 1, 25);
    insertOrderItem.run(1, 2, '黄焖排骨米饭套餐', '🍖', 30, 1, 30);

    insertReview.run(1, 2, 1, 1, 5, 5, '味道很好，送餐也快！', '[]', t1 + 3 * hour);

    // 已完成订单2 - user2 在奶茶店下单
    const t2 = now - 2 * day;
    insertOrder.run('WM202401020001', 3, 3, 2, 'completed', 40, 5, 0, 0, 45,
      '李小红', '13800000002', '深圳市南山区科技园2号楼202', 22.5360, 113.9560,
      '少糖少冰', t2, t2 + hour, t2, t2 + 3 * 60000, t2 + 8 * 60000, t2 + 15 * 60000, t2 + 18 * 60000, t2 + 35 * 60000, t2 + 40 * 60000, null);

    insertOrderItem.run(2, 19, '杨枝甘露', '🥭', 18, 1, 18);
    insertOrderItem.run(2, 22, '经典珍珠奶茶', '🧋', 12, 1, 12);

    insertReview.run(2, 3, 3, 2, 5, 4, '奶茶很好喝，骑手小哥态度也好', '[]', t2 + 2 * hour);

    // 已完成订单3 - user1 在麻辣烫下单
    const t3 = now - day;
    insertOrder.run('WM202401030001', 2, 2, 1, 'completed', 46, 4, 2, 0, 52,
      '张小明', '13800000001', '深圳市南山区科技园1号楼101', 22.5410, 113.9610,
      '', t3, t3 + 1.5 * hour, t3, t3 + 8 * 60000, t3 + 15 * 60000, t3 + 35 * 60000, t3 + 38 * 60000, t3 + 55 * 60000, t3 + 60 * 60000, null);

    insertOrderItem.run(3, 11, '招牌骨汤麻辣烫', '🍲', 28, 1, 28);
    insertOrderItem.run(3, 16, '炸鸡柳', '🍗', 12, 1, 12);

    // 待评价订单 - user3 在披萨店
    const t4 = now - 4 * hour;
    insertOrder.run('WM202401040001', 4, 4, 2, 'delivered', 72, 6, 2, 0, 80,
      '王大力', '13800000003', '深圳市南山区科技园3号楼303', 22.5330, 113.9530,
      '披萨切8片', t4, t4 + 1.5 * hour, t4, t4 + 10 * 60000, t4 + 20 * 60000, t4 + 40 * 60000, t4 + 45 * 60000, t4 + 70 * 60000, null, null);

    insertOrderItem.run(4, 33, '超级至尊披萨', '🍕', 58, 1, 58);

    // 进行中的订单 - 正在配送
    const t5 = now - 30 * 60000;
    insertOrder.run('WM202401040002', 2, 1, 1, 'delivering', 53, 3, 2, 0, 58,
      '张小明', '13800000001', '深圳市南山区科技园1号楼101', 22.5410, 113.9610,
      '不要香菜', t5, t5 + 25 * 60000, t5, t5 + 3 * 60000, t5 + 8 * 60000, t5 + 20 * 60000, t5 + 22 * 60000, null, null, null);

    insertOrderItem.run(5, 1, '黄焖鸡米饭套餐', '🍗', 25, 1, 25);
    insertOrderItem.run(5, 9, '酸梅汤', '🥤', 5, 1, 5);

    // 商家已接单，待制作
    const t6 = now - 15 * 60000;
    insertOrder.run('WM202401040003', 3, 3, null, 'accepted', 36, 5, 0, 0, 41,
      '李小红', '13800000002', '深圳市南山区科技园2号楼202', 22.5360, 113.9560,
      '三分糖', t6, t6 + 3 * 60000, t6, t6 + 3 * 60000, null, null, null, null, null, null);

    insertOrderItem.run(6, 22, '经典珍珠奶茶', '🧋', 12, 2, 24);

    // 待接单
    const t7 = now - 5 * 60000;
    insertOrder.run('WM202401040004', 4, 2, null, 'pending', 30, 4, 2, 0, 36,
      '王大力', '13800000003', '深圳市南山区科技园3号楼303', 22.5330, 113.9530,
      '多放辣', t7, t7, null, null, null, null, null, null, null, null);

    insertOrderItem.run(7, 13, '番茄麻辣烫', '🍅', 26, 1, 26);

    // 已取消订单
    const t8 = now - 5 * day;
    insertOrder.run('WM202401000001', 2, 1, null, 'cancelled', 25, 3, 2, 0, 30,
      '张小明', '13800000001', '深圳市南山区科技园1号楼101', 22.5410, 113.9610,
      '', t8, t8 + 2 * 60000, t8, null, null, null, null, null, t8 + 2 * 60000, '不想要了');

    insertOrderItem.run(8, 4, '黄焖鸡（中份）', '🍗', 22, 1, 22);
  });

  seedAll();
  console.log('[seed] ✅ 演示数据生成完成');
  console.log('[seed] 📋 演示账号（密码均为 123456）:');
  console.log('  - 管理员: admin');
  console.log('  - 用户: user1 / user2 / user3');
  console.log('  - 商家: merchant1 / merchant2 / merchant3 / merchant4');
  console.log('  - 骑手: rider1 / rider2 / rider3');
}

export default seedDatabase;
