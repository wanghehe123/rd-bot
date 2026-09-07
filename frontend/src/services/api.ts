import axios, { AxiosError, type AxiosInstance, type AxiosRequestConfig } from "axios";
import { toast } from "sonner";

/**
 * 全局 axios 实例。
 *
 * RD-Bot 后端 admin 接口当前不要求鉴权，baseURL 留空走 vite dev proxy；
 * 响应错误统一通过 sonner 弹出提示。
 */
const instance: AxiosInstance = axios.create({
  baseURL: (import.meta as unknown as { env?: { VITE_API_BASE_URL?: string } }).env?.VITE_API_BASE_URL || "",
  timeout: 30000
});

// 各 service 均以 `api.get<T, T>(...)` 形式调用并直接按 `T` 消费返回值，
// 因此成功响应统一解包为 `response.data`，使运行时与类型签名一致。
instance.interceptors.response.use(
  (response) => response.data,
  (error: AxiosError<{ message?: string }>) => {
    const status = error.response?.status;
    const message =
      error.response?.data?.message ||
      error.message ||
      (status ? `请求失败 (${status})` : "网络异常，请稍后重试");
    // 4xx/5xx 弹错；业务页面通常会再自行 catch 处理，这里只兜底
    if (status === undefined || status >= 500) {
      toast.error(message);
    }
    return Promise.reject(error);
  }
);

export const api = {
  get: <T, R = T>(url: string, config?: AxiosRequestConfig) =>
    instance.get<T, R>(url, config),
  post: <T, R = T>(url: string, data?: unknown, config?: AxiosRequestConfig) =>
    instance.post<T, R>(url, data, config),
  put: <T, R = T>(url: string, data?: unknown, config?: AxiosRequestConfig) =>
    instance.put<T, R>(url, data, config),
  patch: <T, R = T>(url: string, data?: unknown, config?: AxiosRequestConfig) =>
    instance.patch<T, R>(url, data, config),
  delete: <T, R = T>(url: string, config?: AxiosRequestConfig) =>
    instance.delete<T, R>(url, config)
};

export default instance;
