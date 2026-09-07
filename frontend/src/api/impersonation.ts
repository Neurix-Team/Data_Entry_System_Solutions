import type { TeamRef } from './types';

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

export const IMPERSONATE_HEADER = 'X-Impersonate-Team-Id';
