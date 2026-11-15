"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { api, getToken, setToken } from "./api";
import type { AuthResponse, Me, Permission } from "./types";

// the desktop till remembers who is signed in, so it opens (and sells) without internet too
const ME_CACHE = "hp_me_cache";
const isDesktop = () => typeof window !== "undefined" && !!window.hesabDesktop;
function rememberMe(me: Me | null) {
  if (!isDesktop()) return;
  try {
    if (me) window.localStorage.setItem(ME_CACHE, JSON.stringify(me));
    else window.localStorage.removeItem(ME_CACHE);
  } catch { /* storage blocked */ }
}

interface AuthContextValue {
  me: Me | null;
  loading: boolean;
  signIn: (response: AuthResponse) => void;
  signOut: () => void;
  refresh: () => Promise<void>;
  can: (permission: Permission) => boolean;
  /** The store's package does not include this module. */
  isLocked: (module: string) => boolean;
  isOwner: boolean;
  /** The owner may change / delete anything; an employee only what they recorded themselves. */
  mayChange: (recordedBy: string | null | undefined) => boolean;
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
      const fresh = await api<Me>("/api/auth/me");
      rememberMe(fresh);
      setMe(fresh);
    } catch (e) {
      if (isDesktop() && e instanceof TypeError) {
        try {
          const raw = window.localStorage.getItem(ME_CACHE);
          if (raw) return setMe(JSON.parse(raw) as Me);
        } catch { /* storage blocked */ }
      }
      setMe(null);
    }
  }, []);

  useEffect(() => {
    refresh().finally(() => setLoading(false));
  }, [refresh]);

  const signIn = useCallback((response: AuthResponse) => {
    setToken(response.token);
    rememberMe(response.me);
    setMe(response.me);
  }, []);

  const signOut = useCallback(() => {
    setToken(null);
    rememberMe(null);
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
      mayChange: (by: string | null | undefined) => isOwner || (!!by && by === me?.userId),
      isSuperAdmin: me?.platformRole === "SUPER_ADMIN",
      // a module missing from the package is closed even for the owner
      can: (permission: Permission) =>
        !(me?.lockedModules ?? []).includes(permission as never) && (isOwner || (me?.permissions ?? []).includes(permission)),
      isLocked: (module: string) => (me?.lockedModules ?? []).includes(module as never),
    };
  }, [me, loading, signIn, signOut, refresh]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth AuthProvider daxilində istifadə olunmalıdır");
  return ctx;
}
