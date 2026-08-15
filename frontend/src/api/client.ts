import axios from 'axios';

export const API_BASE = import.meta.env.VITE_API_BASE ?? '/api';

export const api = axios.create({
  baseURL: API_BASE,
  headers: { 'Content-Type': 'application/json' },
  // Send/receive the auth cookie on cross-origin requests. Backend must set
  // Access-Control-Allow-Credentials + a specific origin (not *) for this to work.
  withCredentials: true,
});

// In-memory token — used only if some caller still passes a token from a previous session
// (backward-compat migration).  New logins do NOT write here; the browser gets the auth via
// an httpOnly cookie the server sets.
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
  // Read the locale from localStorage on every request so the backend can pick the
  // right side of bilingual (name_en / name_ar) fields.  Written by i18n/index.tsx.
  const lang = localStorage.getItem(LANG_KEY);
  config.headers['Accept-Language'] = lang === 'ar' ? 'ar' : 'en';
  // Multipart uploads (FormData) must be sent with the browser-generated boundary in the
  // Content-Type header. Our axios instance defaults to application/json for JSON APIs;
  // strip that default for FormData so the browser can inject "multipart/form-data;
  // boundary=…" itself — otherwise Spring rejects the request with "Content-Type
  // 'application/json' is not supported".
  if (typeof FormData !== 'undefined' && config.data instanceof FormData) {
    if (config.headers && typeof (config.headers as { delete?: (k: string) => void }).delete === 'function') {
      (config.headers as { delete: (k: string) => void }).delete('Content-Type');
    } else if (config.headers) {
      delete (config.headers as Record<string, unknown>)['Content-Type'];
    }
  }
  return config;
});

// One-time cleanup of legacy localStorage token — it's now handled via cookies. Doing this
// on module load means users who had a stored token get it purged after this deploy without
// needing to log out manually.
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
