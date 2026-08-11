import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { authApi } from '../api/auth';
import { setUnauthorizedHandler, tokenStore } from '../api/client';
import type { User } from '../api/types';

interface AuthContextValue {
  user: User | null;
  loading: boolean;
  login: (username: string, password: string) => Promise<User>;
  logout: () => void;
  /** Re-fetch the current user from the server. Called after profile mutations
   *  (avatar upload, display name change) so the UI reflects the new state. */
  refresh: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);

  const logout = useCallback(() => {
    // Tell the server to clear the httpOnly cookie; then wipe local state.  Even if the
    // request fails (network down), we still clear local state so the UI reflects logout.
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
    // Cookie-based session — we can't read the httpOnly cookie from JS, so probe /auth/me
    // to see if the browser already has a valid session.  401 → not logged in.
    authApi.me()
      .then(setUser)
      .catch(() => setUser(null))
      .finally(() => setLoading(false));
  }, []);

  const login = useCallback(async (username: string, password: string) => {
    const res = await authApi.login(username, password);
    // Server sets the httpOnly cookie in the response; we no longer store the token in JS.
    setUser(res.user);
    return res.user;
  }, []);

  const refresh = useCallback(async () => {
    try {
      const me = await authApi.me();
      setUser(me);
    } catch {
      // If refresh fails (session expired etc.), the standard 401 handler will clear state.
    }
  }, []);

  const value = useMemo(
    () => ({ user, loading, login, logout, refresh }),
    [user, loading, login, logout, refresh]
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}
