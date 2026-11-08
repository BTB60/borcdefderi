"use client";

import { useEffect, useState } from "react";
import { API_URL, getToken } from "@/lib/api";
import { cx } from "./ui";

/** Cache of object URLs per avatar version, shared by every <Avatar> on the page. */
const cache = new Map<string, Promise<string | null>>();

function loadAvatar(version: number): Promise<string | null> {
  const key = String(version);
  let p = cache.get(key);
  if (!p) {
    const headers = new Headers();
    const token = getToken();
    if (token) headers.set("Authorization", `Bearer ${token}`);
    p = fetch(`${API_URL}/api/profile/avatar`, { headers })
      .then(async (res) => (res.ok ? URL.createObjectURL(await res.blob()) : null))
      .catch(() => null);
    cache.set(key, p);
  }
  return p;
}

/** The signed-in user's profile picture, or their initial when there is none. */
export function Avatar({ name, version, size = 36, className }: { name: string | null | undefined; version: number | null | undefined; size?: number; className?: string }) {
  const [src, setSrc] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    if (version) {
      loadAvatar(version).then((url) => alive && setSrc(url));
    } else {
      setSrc(null);
    }
    return () => {
      alive = false;
    };
  }, [version]);

  const style = { width: size, height: size, fontSize: Math.round(size * 0.4) };
  if (src) {
    // eslint-disable-next-line @next/next/no-img-element -- blob URL of a protected image
    return <img src={src} alt={name ?? "Profil şəkli"} style={style} className={cx("shrink-0 rounded-full object-cover ring-2 ring-gold-500/60", className)} />;
  }
  return (
    <div style={style} className={cx("flex shrink-0 items-center justify-center rounded-full bg-gold-500/90 font-semibold text-navy-900", className)} aria-hidden="true">
      {(name ?? "?").charAt(0).toLocaleUpperCase("az")}
    </div>
  );
}
