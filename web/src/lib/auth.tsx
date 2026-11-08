"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { api, getToken, setToken } from "./api";
import type { AuthResponse, Me, Permission } from "./types";

interface AuthContextValue {
  me: Me | null;
  loading: boolean;
  signIn: (response: AuthResponse) => void;
  signOut: () => void;
  refresh: () => Promise<void>;
  can: (permission: Permission) => boolean;
  isOwner: boolean;
  isSuperAdmin: boolean;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<Me | null>(null);
  const [loading, setLoading] = useState(true);

  const refresh = useCallback(async () => {
    if (!getToken()) {
      setMe(null);
      return;
    }
    try {
      setMe(await api<Me>("/api/auth/me"));
    } catch {
      setMe(null);
    }
  }, []);

  useEffect(() => {
    refresh().finally(() => setLoading(false));
  }, [refresh]);

  const signIn = useCallback((response: AuthResponse) => {
    setToken(response.token);
    setMe(response.me);
  }, []);

  const signOut = useCallback(() => {
    setToken(null);
    setMe(null);
    window.location.href = "/login";
  }, []);

  const value = useMemo<AuthContextValue>(() => {
    const isOwner = me?.role === "STORE_OWNER";
    return {
      me,
      loading,
      signIn,
      signOut,
      refresh,
      isOwner,
      isSuperAdmin: me?.platformRole === "SUPER_ADMIN",
      can: (permission: Permission) => isOwner || (me?.permissions ?? []).includes(permission),
    };
  }, [me, loading, signIn, signOut, refresh]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth AuthProvider daxilində istifadə olunmalıdır");
  return ctx;
}
