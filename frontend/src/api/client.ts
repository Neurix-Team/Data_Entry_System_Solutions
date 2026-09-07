import axios from 'axios';
import { IMPERSONATE_HEADER, impersonation } from './impersonation';

export const API_BASE = import.meta.env.VITE_API_BASE ?? '/api';

export const api = axios.create({
  baseURL: API_BASE,
  withCredentials: true,
});

let memoryToken: string | null = null;

export const tokenStore = {
  get: () => memoryToken,
  set: (t: string) => { memoryToken = t; },
  clear: () => { memoryToken = null; },
};

const LANG_KEY = 'dems.lang';

api.interceptors.request.use((config) => {
  const token = tokenStore.get();
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  const lang = localStorage.getItem(LANG_KEY);
  config.headers['Accept-Language'] = lang === 'ar' ? 'ar' : 'en';

  const url = config.url || '';
  if (!url.startsWith('/super') && !url.startsWith('super')) {
    const imp = impersonation.current();
    if (imp) {
      config.headers[IMPERSONATE_HEADER] = String(imp.team.id);
    }
  }
  return config;
});

try { localStorage.removeItem('dems.token'); } catch { /* SSR / private mode */ }

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
