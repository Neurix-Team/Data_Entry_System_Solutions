import axios from 'axios';

export const API_BASE = import.meta.env.VITE_API_BASE ?? '/api';

export const api = axios.create({
  baseURL: API_BASE,
  headers: { 'Content-Type': 'application/json' },
});

const TOKEN_KEY = 'dems.token';

export const tokenStore = {
  get: () => localStorage.getItem(TOKEN_KEY),
  set: (t: string) => localStorage.setItem(TOKEN_KEY, t),
  clear: () => localStorage.removeItem(TOKEN_KEY),
};

api.interceptors.request.use((config) => {
  const token = tokenStore.get();
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

let onUnauthorized: (() => void) | null = null;
export const setUnauthorizedHandler = (fn: () => void) => {
  onUnauthorized = fn;
};

api.interceptors.response.use(
  (r) => r,
  (err) => {
    if (err?.response?.status === 401 && onUnauthorized) {
      onUnauthorized();
    }
    return Promise.reject(err);
  }
);

export function extractError(err: unknown, fallback = 'Something went wrong'): string {
  const anyErr = err as { response?: { data?: { message?: string; details?: Record<string, string> } } };
  const data = anyErr?.response?.data;
  if (data?.details) {
    const first = Object.values(data.details)[0];
    if (first) return String(first);
  }
  return data?.message || fallback;
}
