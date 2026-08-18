import type { TeamRef } from './types';

/**
 * Client-side memory of which team a SUPER_ADMIN has "entered". While a value is set, every
 * API request the axios client makes carries {@code X-Impersonate-Team-Id: <id>}. The backend
 * ({@code JwtAuthFilter}) recognises the header, drops the super admin's cross-team scope
 * for that request, and behaves as if the caller were an ADMIN of the target team.
 *
 * <p>Persisted in localStorage so a page refresh keeps the operator inside the same team —
 * losing the scope on every reload would be jarring and error-prone (they'd suddenly see
 * cross-team data when they thought they were focused).
 */
const KEY = 'dems.impersonate';

export interface ImpersonationState {
  team: TeamRef;
  enteredAt: number;
}

type Listener = (state: ImpersonationState | null) => void;
const listeners = new Set<Listener>();

function read(): ImpersonationState | null {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as ImpersonationState;
    if (!parsed?.team?.id) return null;
    return parsed;
  } catch {
    return null;
  }
}

function write(next: ImpersonationState | null) {
  try {
    if (next) localStorage.setItem(KEY, JSON.stringify(next));
    else localStorage.removeItem(KEY);
  } catch { /* private mode / quota — non-fatal */ }
  listeners.forEach((l) => l(next));
}

export const impersonation = {
  current: (): ImpersonationState | null => read(),
  enter: (team: TeamRef) => write({ team, enteredAt: Date.now() }),
  exit: () => write(null),
  subscribe: (fn: Listener): (() => void) => {
    listeners.add(fn);
    return () => listeners.delete(fn);
  },
};

/** Header name — kept in sync with backend {@code JwtAuthFilter.IMPERSONATE_HEADER}. */
export const IMPERSONATE_HEADER = 'X-Impersonate-Team-Id';
