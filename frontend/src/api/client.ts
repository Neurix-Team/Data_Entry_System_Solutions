import axios from 'axios';
import { IMPERSONATE_HEADER, impersonation } from './impersonation';
import type { LoginResponse } from './types';

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

api.interceptors.request.use(async (config) => {
  const token = tokenStore.get();
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  if (!token && !['get', 'head', 'options'].includes((config.method || 'get').toLowerCase())) {
    const csrf = await axios.get<{ token: string }>(`${API_BASE}/auth/csrf`, { withCredentials: true });
    config.headers['X-XSRF-TOKEN'] = csrf.data.token;
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

// On a 401, try one silent /auth/refresh before giving up. The auth cookie keeps the
// session alive across reloads, so an expired Bearer token can usually be exchanged
// for a fresh one without forcing the user back to the login page.
let refreshing: Promise<boolean> | null = null;

async function tryRefresh(): Promise<boolean> {
  if (!refreshing) {
    refreshing = axios
      .post<LoginResponse>(`${API_BASE}/auth/refresh`, null, { withCredentials: true })
      .then((r) => {
        if (r.data.token) {
          tokenStore.set(r.data.token);
          return true;
        }
        return false;
      })
      .catch(() => false)
      .finally(() => {
        refreshing = null;
      });
  }
  return refreshing;
}

api.interceptors.response.use(
  (r) => r,
  async (err) => {
    const status = err?.response?.status;
    const url: string = err?.config?.url ?? '';
    const alreadyRefreshed = Boolean(err?.config?._retriedAfterRefresh);
    // MFA-step failures (a wrong code) are expected input errors — they must not
    // trigger a silent refresh or a global sign-out.
    if (status === 401 && !alreadyRefreshed && !url.includes('/auth/refresh')
        && !url.includes('/auth/login') && !url.includes('/auth/mfa/') && await tryRefresh()) {
      const config = err.config;
      config._retriedAfterRefresh = true;
      const token = tokenStore.get();
      if (token) config.headers = { ...config.headers, Authorization: `Bearer ${token}` };
      return api.request(config);
    }
    if (status === 401 && onUnauthorized) {
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
