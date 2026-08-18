import axios from 'axios';
import { IMPERSONATE_HEADER, impersonation } from './impersonation';

export const API_BASE = import.meta.env.VITE_API_BASE ?? '/api';

// No default Content-Type. axios v1 auto-sets it correctly for each payload:
//   • plain object / string / URLSearchParams → application/json
//   • FormData / Blob / File                  → multipart/form-data with boundary
// Setting a hard-coded default (as we used to) breaks the FormData path because the
// browser can no longer inject its own boundary — Spring then rejects the upload with
// "Content-Type 'application/json' is not supported".
export const api = axios.create({
  baseURL: API_BASE,
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

  // When a SUPER_ADMIN has "entered" a team, add the impersonation header so the backend
  // scopes admin/user endpoints to that team. Never send it on /api/super/* — the super
  // surface is intentionally cross-team and the header would confuse the backend into
  // thinking it should scope those too.
  const url = config.url || '';
  if (!url.startsWith('/super') && !url.startsWith('super')) {
    const imp = impersonation.current();
    if (imp) {
      config.headers[IMPERSONATE_HEADER] = String(imp.team.id);
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
