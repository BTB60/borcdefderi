"use client";

import { useEffect, useState } from "react";

/** Android app download (the latest signed APK) and App Store (not published yet: "Tezliklə"). */
export const ANDROID_APK_URL = "https://github.com/BTB60/borcdefderi/releases/download/mobile-latest/HesabPro.apk";
const APK_RELEASE_API = "https://api.github.com/repos/BTB60/borcdefderi/releases/tags/mobile-latest";
const APK_INFO_KEY = "hp_apk_release";

type ApkInfo = { version: string | null; date: string | null };

/** The newest APK's version ("HesabPro APK v0.2.39") and when it was built; refreshed with every new APK. */
function useLatestApk(): ApkInfo | null {
  const [info, setInfo] = useState<ApkInfo | null>(null);
  useEffect(() => {
    try {
      const saved = JSON.parse(window.sessionStorage.getItem(APK_INFO_KEY) ?? "null") as ApkInfo | null;
      if (saved) { setInfo(saved); return; }
    } catch { /* storage blocked */ }
    fetch(APK_RELEASE_API, { headers: { Accept: "application/vnd.github+json" } })
      .then((r) => (r.ok ? r.json() : null))
      .then((r: { name?: string; assets?: { name: string; updated_at: string }[] } | null) => {
        if (!r) return;
        const apk = r.assets?.find((a) => a.name.endsWith(".apk"));
        const next = { version: /v(\d+(?:\.\d+)+)/.exec(r.name ?? "")?.[1] ?? null, date: apk?.updated_at ?? null };
        setInfo(next);
        try { window.sessionStorage.setItem(APK_INFO_KEY, JSON.stringify(next)); } catch { /* storage blocked */ }
      })
      .catch(() => undefined);
  }, []);
  return info;
}

function PlayIcon() {
  return (
    <svg viewBox="0 0 24 26" className="h-7 w-7 shrink-0" aria-hidden>
      <path d="M1.2.6 13.4 13 1.2 25.4c-.4-.2-.7-.7-.7-1.3V1.9c0-.6.3-1.1.7-1.3Z" fill="#00D7FE" />
      <path d="M17.5 8.9 13.4 13 1.2.6c.2-.1.5-.2.8-.2.3 0 .6.1.9.3l14.6 8.2Z" fill="#00F076" />
      <path d="M17.5 17.1 2.9 25.3c-.3.2-.6.3-.9.3-.3 0-.6-.1-.8-.2L13.4 13l4.1 4.1Z" fill="#F83B4E" />
      <path d="m22.4 11.7-4.9-2.8L13.4 13l4.1 4.1 4.9-2.8c.5-.3.8-.8.8-1.3s-.3-1-.8-1.3Z" fill="#FFCE00" />
    </svg>
  );
}

function AppleIcon() {
  return (
    <svg viewBox="0 0 24 28" className="h-7 w-7 shrink-0" fill="currentColor" aria-hidden>
      <path d="M19.7 14.9c0-3.3 2.7-4.9 2.8-5-1.5-2.2-3.9-2.5-4.7-2.6-2-.2-3.9 1.2-4.9 1.2-1 0-2.6-1.2-4.2-1.1-2.2 0-4.2 1.3-5.3 3.2-2.3 3.9-.6 9.7 1.6 12.9 1.1 1.6 2.4 3.3 4.1 3.2 1.6-.1 2.3-1 4.2-1 2 0 2.5 1 4.2 1 1.8 0 2.9-1.6 3.9-3.2 1.2-1.8 1.7-3.6 1.8-3.7-.1 0-3.4-1.3-3.5-5.2ZM16.5 5.2c.9-1.1 1.5-2.6 1.3-4.1-1.3.1-2.9.9-3.8 2-.8.9-1.6 2.5-1.4 4 1.5.1 3-.8 3.9-1.9Z" />
    </svg>
  );
}

export function StoreBadges({ className = "" }: { className?: string }) {
  const badge = "flex items-center gap-3 rounded-xl border border-white/20 bg-black/80 px-4 py-2.5 text-left text-white transition";
  const apk = useLatestApk();
  const built = apk?.date ? new Date(apk.date).toLocaleDateString("az-AZ", { day: "2-digit", month: "2-digit", year: "numeric" }) : null;
  return (
    <div className={`flex flex-wrap items-end gap-3 ${className}`}>
      <div>
        {(apk?.version || built) && (
          <p className="mb-1.5 inline-flex items-center gap-1.5 rounded-full bg-gold-500 px-2.5 py-0.5 text-[11px] font-semibold text-navy-900">
            <span className="h-1.5 w-1.5 rounded-full bg-emerald-600" />
            Yeni versiya{apk?.version ? ` ${apk.version}` : ""}{built ? ` · ${built}` : ""}
          </p>
        )}
        <a href={ANDROID_APK_URL} className={`${badge} hover:border-white/50 hover:bg-black`} title="Android tətbiqini yüklə (APK)">
          <PlayIcon />
          <span className="leading-tight">
            <span className="block text-[10px] uppercase tracking-wider text-white/70">Android üçün yüklə</span>
            <span className="block text-lg font-semibold">Google Play</span>
          </span>
        </a>
      </div>
      <div className={`${badge} cursor-default opacity-80`} title="iPhone tətbiqi tezliklə App Store-da" aria-disabled>
        <AppleIcon />
        <span className="leading-tight">
          <span className="block text-[10px] uppercase tracking-wider text-white/70">Tezliklə</span>
          <span className="block text-lg font-semibold">App Store</span>
        </span>
      </div>
    </div>
  );
}
