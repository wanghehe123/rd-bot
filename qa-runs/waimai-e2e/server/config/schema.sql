PRAGMA journal_mode=WAL;
PRAGMA foreign_keys=ON;

CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,
    role TEXT NOT NULL CHECK(role IN ('customer','merchant','rider','admin')),
    name TEXT NOT NULL,
    phone TEXT DEFAULT '',
    address TEXT DEFAULT '',
    avatar_url TEXT DEFAULT '',
    status TEXT DEFAULT 'active' CHECK(status IN ('active','disabled')),
    created_at DATETIME DEFAULT (datetime('now','localtime')),
    updated_at DATETIME DEFAULT (datetime('now','localtime'))
);
CREATE INDEX IF NOT EXISTS idx_users_role ON users(role);
CREATE INDEX IF NOT EXISTS idx_users_username ON users(username);

CREATE TABLE IF NOT EXISTS merchants (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL UNIQUE,
    name TEXT NOT NULL,
    description TEXT DEFAULT '',
    address TEXT DEFAULT '',
    phone TEXT DEFAULT '',
    image_url TEXT DEFAULT '',
    category TEXT DEFAULT '其他',
    rating REAL DEFAULT 5.0,
    rating_count INTEGER DEFAULT 0,
    monthly_sales INTEGER DEFAULT 0,
    is_open INTEGER DEFAULT 1,
    delivery_fee REAL DEFAULT 3.0,
    min_order REAL DEFAULT 15.0,
    created_at DATETIME DEFAULT (datetime('now','localtime')),
    updated_at DATETIME DEFAULT (datetime('now','localtime')),
    FOREIGN KEY (user_id) REFERENCES users(id)
);
CREATE INDEX IF NOT EXISTS idx_merchants_user_id ON merchants(user_id);
CREATE INDEX IF NOT EXISTS idx_merchants_category ON merchants(category);
CREATE INDEX IF NOT EXISTS idx_merchants_is_open ON merchants(is_open);

CREATE TABLE IF NOT EXISTS categories (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    merchant_id INTEGER NOT NULL,
    name TEXT NOT NULL,
    sort_order INTEGER DEFAULT 0,
    FOREIGN KEY (merchant_id) REFERENCES merchants(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_categories_merchant_id ON categories(merchant_id);

CREATE TABLE IF NOT EXISTS products (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    merchant_id INTEGER NOT NULL,
    category_id INTEGER NOT NULL,
    name TEXT NOT NULL,
    description TEXT DEFAULT '',
    price REAL NOT NULL,
    image_url TEXT DEFAULT '',
    stock INTEGER DEFAULT 999,
    is_available INTEGER DEFAULT 1,
    sales_count INTEGER DEFAULT 0,
    created_at DATETIME DEFAULT (datetime('now','localtime')),
    updated_at DATETIME DEFAULT (datetime('now','localtime')),
    FOREIGN KEY (merchant_id) REFERENCES merchants(id),
    FOREIGN KEY (category_id) REFERENCES categories(id)
);
CREATE INDEX IF NOT EXISTS idx_products_merchant_id ON products(merchant_id);
CREATE INDEX IF NOT EXISTS idx_products_category_id ON products(category_id);
CREATE INDEX IF NOT EXISTS idx_products_is_available ON products(is_available);

CREATE TABLE IF NOT EXISTS orders (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    order_no TEXT UNIQUE NOT NULL,
    customer_id INTEGER NOT NULL,
    merchant_id INTEGER NOT NULL,
    rider_id INTEGER,
    status TEXT NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','accepted','preparing','ready','assigned','picked_up','delivering','delivered','completed','cancelled','rejected')),
    total_amount REAL NOT NULL,
    delivery_fee REAL DEFAULT 0,
    address TEXT NOT NULL,
    phone TEXT NOT NULL,
    customer_name TEXT NOT NULL,
    note TEXT DEFAULT '',
    merchant_note TEXT DEFAULT '',
    rating_merchant INTEGER,
    rating_rider INTEGER,
    review TEXT DEFAULT '',
    reject_reason TEXT DEFAULT '',
    cancel_reason TEXT DEFAULT '',
    created_at DATETIME DEFAULT (datetime('now','localtime')),
    updated_at DATETIME DEFAULT (datetime('now','localtime')),
    accepted_at DATETIME,
    preparing_at DATETIME,
    ready_at DATETIME,
    assigned_at DATETIME,
    picked_up_at DATETIME,
    delivering_at DATETIME,
    delivered_at DATETIME,
    completed_at DATETIME,
    cancelled_at DATETIME,
    FOREIGN KEY (customer_id) REFERENCES users(id),
    FOREIGN KEY (merchant_id) REFERENCES merchants(id),
    FOREIGN KEY (rider_id) REFERENCES users(id)
);
CREATE INDEX IF NOT EXISTS idx_orders_customer_id ON orders(customer_id);
CREATE INDEX IF NOT EXISTS idx_orders_merchant_id ON orders(merchant_id);
CREATE INDEX IF NOT EXISTS idx_orders_rider_id ON orders(rider_id);
CREATE INDEX IF NOT EXISTS idx_orders_status ON orders(status);
CREATE INDEX IF NOT EXISTS idx_orders_created_at ON orders(created_at);
CREATE INDEX IF NOT EXISTS idx_orders_order_no ON orders(order_no);

CREATE TABLE IF NOT EXISTS order_items (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    order_id INTEGER NOT NULL,
    product_id INTEGER NOT NULL,
    product_name TEXT NOT NULL,
    product_price REAL NOT NULL,
    quantity INTEGER NOT NULL DEFAULT 1,
    subtotal REAL NOT NULL,
    FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE,
    FOREIGN KEY (product_id) REFERENCES products(id)
);
CREATE INDEX IF NOT EXISTS idx_order_items_order_id ON order_items(order_id);

CREATE TABLE IF NOT EXISTS order_status_logs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    order_id INTEGER NOT NULL,
    status TEXT NOT NULL,
    operator_id INTEGER,
    note TEXT DEFAULT '',
    created_at DATETIME DEFAULT (datetime('now','localtime')),
    FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE,
    FOREIGN KEY (operator_id) REFERENCES users(id)
);
CREATE INDEX IF NOT EXISTS idx_order_status_logs_order_id ON order_status_logs(order_id);

CREATE TABLE IF NOT EXISTS rider_locations (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    rider_id INTEGER NOT NULL UNIQUE,
    latitude REAL DEFAULT 39.9042,
    longitude REAL DEFAULT 116.4074,
    updated_at DATETIME DEFAULT (datetime('now','localtime')),
    FOREIGN KEY (rider_id) REFERENCES users(id)
);
CREATE INDEX IF NOT EXISTS idx_rider_locations_rider_id ON rider_locations(rider_id);
