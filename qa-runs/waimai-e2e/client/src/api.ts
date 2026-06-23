const BASE_URL = '/api';

interface RequestOptions {
  method?: string;
  body?: any;
  token?: string;
}

class ApiClient {
  private token: string | null = localStorage.getItem('token');

  setToken(token: string | null) {
    this.token = token;
    if (token) {
      localStorage.setItem('token', token);
    } else {
      localStorage.removeItem('token');
    }
  }

  getToken(): string | null {
    return this.token;
  }

  private async request<T = any>(path: string, options: RequestOptions = {}): Promise<T> {
    const { method = 'GET', body, token } = options;
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
    };
    const authToken = token || this.token;
    if (authToken) {
      headers['Authorization'] = `Bearer ${authToken}`;
    }

    const res = await fetch(`${BASE_URL}${path}`, {
      method,
      headers,
      body: body ? JSON.stringify(body) : undefined,
    });

    const data = await res.json();
    if (!res.ok) {
      throw new Error(data.error || data.message || `请求失败 (${res.status})`);
    }
    return data;
  }

  // Auth
  async register(data: { username: string; password: string; role: string; nickname?: string; phone?: string }) {
    return this.request('/auth/register', { method: 'POST', body: data });
  }

  async login(data: { username: string; password: string }) {
    return this.request('/auth/login', { method: 'POST', body: data });
  }

  async getProfile() {
    return this.request('/auth/profile');
  }

  // User - Merchants (public)
  async getMerchantList(params?: { category?: string; keyword?: string; sort?: string }) {
    const qs = new URLSearchParams();
    if (params?.category) qs.set('category', params.category);
    if (params?.keyword) qs.set('keyword', params.keyword);
    if (params?.sort) qs.set('sort', params.sort);
    const q = qs.toString();
    return this.request(`/merchants${q ? '?' + q : ''}`);
  }

  async getMerchantCategories() {
    return this.request('/merchants/categories');
  }

  async getMerchantDetail(id: number) {
    return this.request(`/merchants/${id}`);
  }

  async getMerchantMenu(merchantId: number) {
    return this.request(`/merchants/${merchantId}/menu`);
  }

  async getMerchantReviews(merchantId: number) {
    return this.request(`/merchants/${merchantId}/reviews`);
  }

  // Merchant - Products
  async getMyProducts() {
    return this.request('/merchants/my/products');
  }

  async createProduct(data: any) {
    return this.request('/merchants/my/products', { method: 'POST', body: data });
  }

  async updateProduct(id: number, data: any) {
    return this.request(`/merchants/my/products/${id}`, { method: 'PATCH', body: data });
  }

  async deleteProduct(id: number) {
    return this.request(`/merchants/my/products/${id}`, { method: 'DELETE' });
  }

  async getMyCategories() {
    return this.request('/merchants/my/categories');
  }

  async createCategory(data: { name: string; sort_order?: number }) {
    return this.request('/merchants/my/categories', { method: 'POST', body: data });
  }

  async updateMerchantProfile(data: any) {
    return this.request('/merchants/my/profile', { method: 'PATCH', body: data });
  }

  // Orders
  async createOrder(data: {
    merchant_id: number;
    items: { product_id: number; quantity: number }[];
    delivery_address: string;
    remark?: string;
  }) {
    return this.request('/orders', { method: 'POST', body: data });
  }

  async getMyOrders(status?: string) {
    const q = status ? `?status=${status}` : '';
    return this.request(`/orders/mine${q}`);
  }

  async getOrderDetail(id: number) {
    return this.request(`/orders/${id}`);
  }

  async cancelOrder(id: number) {
    return this.request(`/orders/${id}/cancel`, { method: 'POST' });
  }

  async confirmOrder(id: number) {
    return this.request(`/orders/${id}/confirm`, { method: 'POST' });
  }

  async rateOrder(id: number, data: { merchant_rating?: number; rider_rating?: number; comment?: string }) {
    return this.request(`/orders/${id}/rate`, { method: 'POST', body: data });
  }

  // Merchant - Orders
  async getMerchantOrders(status?: string) {
    const q = status ? `?status=${status}` : '';
    return this.request(`/orders/merchant${q}`);
  }

  async acceptOrder(id: number) {
    return this.request(`/orders/${id}/accept`, { method: 'POST' });
  }

  async rejectOrder(id: number, reason?: string) {
    return this.request(`/orders/${id}/reject`, { method: 'POST', body: { reason } });
  }

  async startPreparing(id: number) {
    return this.request(`/orders/${id}/prepare`, { method: 'POST' });
  }

  async completePreparing(id: number) {
    return this.request(`/orders/${id}/ready`, { method: 'POST' });
  }

  // Rider
  async getRiderProfile() {
    return this.request('/riders/my/profile');
  }

  async updateRiderStatus(status: string) {
    return this.request('/riders/my/status', { method: 'PATCH', body: { status } });
  }

  async getRiderDeliveries(status?: string) {
    const q = status ? `?status=${status}` : '';
    return this.request(`/riders/my/deliveries${q}`);
  }

  async riderAcceptOrder(orderId: number) {
    return this.request(`/riders/deliveries/${orderId}/accept`, { method: 'POST' });
  }

  async riderArriveMerchant(orderId: number) {
    return this.request(`/riders/deliveries/${orderId}/arrive`, { method: 'POST' });
  }

  async riderPickup(orderId: number) {
    return this.request(`/riders/deliveries/${orderId}/pickup`, { method: 'POST' });
  }

  async riderStartDelivery(orderId: number) {
    return this.request(`/riders/deliveries/${orderId}/deliver`, { method: 'POST' });
  }

  async riderCompleteDelivery(orderId: number) {
    return this.request(`/riders/deliveries/${orderId}/complete`, { method: 'POST' });
  }

  // Admin
  async getDashboardStats() {
    return this.request('/admin/stats');
  }

  async getOrderTrend(days?: number) {
    const q = days ? `?days=${days}` : '';
    return this.request(`/admin/stats/trend${q}`);
  }

  async getAllOrders(params?: { status?: string; page?: number; page_size?: number }) {
    const qs = new URLSearchParams();
    if (params?.status) qs.set('status', params.status);
    if (params?.page) qs.set('page', String(params.page));
    if (params?.page_size) qs.set('page_size', String(params.page_size));
    const q = qs.toString();
    return this.request(`/admin/orders${q ? '?' + q : ''}`);
  }

  async getAbnormalOrders() {
    return this.request('/admin/orders/abnormal');
  }

  async getAllMerchants(params?: { status?: string; keyword?: string }) {
    const qs = new URLSearchParams();
    if (params?.status) qs.set('status', params.status);
    if (params?.keyword) qs.set('keyword', params.keyword);
    const q = qs.toString();
    return this.request(`/admin/merchants${q ? '?' + q : ''}`);
  }

  async adminUpdateMerchant(id: number, data: any) {
    return this.request(`/admin/merchants/${id}`, { method: 'PATCH', body: data });
  }

  async getAllRiders(params?: { status?: string; keyword?: string }) {
    const qs = new URLSearchParams();
    if (params?.status) qs.set('status', params.status);
    if (params?.keyword) qs.set('keyword', params.keyword);
    const q = qs.toString();
    return this.request(`/admin/riders${q ? '?' + q : ''}`);
  }

  async adminUpdateRider(id: number, data: any) {
    return this.request(`/admin/riders/${id}`, { method: 'PATCH', body: data });
  }

  async adminCreateAccount(data: any) {
    return this.request('/admin/accounts', { method: 'POST', body: data });
  }
}

export const api = new ApiClient();
export default api;
