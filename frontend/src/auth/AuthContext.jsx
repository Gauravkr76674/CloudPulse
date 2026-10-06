import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';

import * as api from '../api/api';
import { getToken, setToken, UNAUTHORIZED_EVENT } from '../api/client';

const AuthContext = createContext(null);

/** Ranking so a check can be "OPERATOR or better" instead of listing roles everywhere. */
const RANK = { VIEWER: 1, OPERATOR: 2, ADMIN: 3 };

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null);
  const [loading, setLoading] = useState(Boolean(getToken()));

  // A token in localStorage is only a claim; ask the backend who it belongs to.
  useEffect(() => {
    if (!getToken()) {
      setLoading(false);
      return;
    }
    let cancelled = false;
    api
      .currentUser()
      .then((me) => {
        if (!cancelled) {
          setUser(me);
        }
      })
      .catch(() => {
        setToken(null);
        if (!cancelled) {
          setUser(null);
        }
      })
      .finally(() => {
        if (!cancelled) {
          setLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, []);

  // Any 401 from any request means the session is over.
  useEffect(() => {
    const onUnauthorized = () => setUser(null);
    window.addEventListener(UNAUTHORIZED_EVENT, onUnauthorized);
    return () => window.removeEventListener(UNAUTHORIZED_EVENT, onUnauthorized);
  }, []);

  const signIn = useCallback(async (username, password) => {
    const response = await api.login(username, password);
    setUser(response.user);
    return response.user;
  }, []);

  const signOut = useCallback(() => {
    setToken(null);
    setUser(null);
  }, []);

  const value = useMemo(
    () => ({
      user,
      loading,
      signIn,
      signOut,
      role: user ? user.role : null,
      isAdmin: Boolean(user && user.role === 'ADMIN'),
      atLeast: (role) => Boolean(user) && (RANK[user.role] || 0) >= (RANK[role] || 0),
    }),
    [user, loading, signIn, signOut],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used inside <AuthProvider>');
  }
  return context;
}
