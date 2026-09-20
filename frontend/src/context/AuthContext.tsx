import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { authApi, mfaApi } from '../api/auth';
import { setUnauthorizedHandler, tokenStore } from '../api/client';
import type { User } from '../api/types';

/** What POST /auth/login came back with: a session, or a second-factor challenge. */
export type LoginOutcome =
  | { status: 'ok'; user: User }
  | { status: 'mfa'; ticket: string; periodSeconds: number };

interface AuthContextValue {
  user: User | null;
  loading: boolean;
  login: (username: string, password: string) => Promise<LoginOutcome>;
  /** Finishes a sign-in challenge with the ticket + a code; resolves with the user. */
  completeMfa: (ticket: string, code: string) => Promise<User>;
  /** Drops a pending challenge ticket without signing in. */
  cancelMfa: () => void;
  logout: () => void;
  refresh: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);

  const logout = useCallback(() => {
    authApi.logout().catch(() => undefined).finally(() => {
      tokenStore.clear();
      setUser(null);
    });
  }, []);

  useEffect(() => {
    setUnauthorizedHandler(() => {
      tokenStore.clear();
      setUser(null);
    });
  }, []);

  useEffect(() => {
    const ctrl = new AbortController();
    let mounted = true;
    authApi.me(ctrl.signal)
      .then((u) => { if (mounted) setUser(u); })
      .catch(() => { if (mounted) setUser(null); })
      .finally(() => { if (mounted) setLoading(false); });
    return () => {
      mounted = false;
      ctrl.abort();
    };
  }, []);

  const login = useCallback(async (username: string, password: string): Promise<LoginOutcome> => {
    const res = await authApi.login(username, password);
    if (res.mfaRequired && res.mfaTicket) {
      // The pending ticket rides along as the Bearer credential for the MFA steps only;
      // the backend refuses it everywhere else.
      tokenStore.set(res.mfaTicket);
      return { status: 'mfa', ticket: res.mfaTicket, periodSeconds: res.mfaPeriodSeconds ?? 30 };
    }
    if (res.token) tokenStore.set(res.token);
    if (res.user) setUser(res.user);
    return { status: 'ok', user: res.user };
  }, []);

  const completeMfa = useCallback(async (ticket: string, code: string): Promise<User> => {
    const res = await mfaApi.verify(ticket, code);
    if (!res.token || !res.user) throw new Error('Two-factor verification failed.');
    tokenStore.set(res.token);
    setUser(res.user);
    return res.user;
  }, []);

  const cancelMfa = useCallback(() => {
    tokenStore.clear();
  }, []);

  const refresh = useCallback(async () => {
    try {
      const me = await authApi.me();
      setUser(me);
    } catch {
    }
  }, []);

  const value = useMemo(
    () => ({ user, loading, login, completeMfa, cancelMfa, logout, refresh }),
    [user, loading, login, completeMfa, cancelMfa, logout, refresh]
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}
