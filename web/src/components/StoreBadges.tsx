"use client";

import { useEffect, useState } from "react";
import { Modal } from "@/components/ui";

/** Android app download (the latest signed APK) and iPhone install (the web app added to the Home Screen from Safari,
 * until the App Store version is published). */
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

type Platform = "ios" | "android" | "other";

/** Which phone the page is open on, and whether it already runs as an installed (home-screen) app. */
function usePlatform(): { platform: Platform; installed: boolean; safari: boolean } {
  const [state, setState] = useState<{ platform: Platform; installed: boolean; safari: boolean }>({ platform: "other", installed: false, safari: false });
  useEffect(() => {
    const ua = navigator.userAgent;
    // iPadOS reports itself as a Mac; a touch screen gives it away
    const ios = /iPhone|iPad|iPod/.test(ua) || (/Macintosh/.test(ua) && navigator.maxTouchPoints > 1);
    const android = /Android/.test(ua);
    const installed = window.matchMedia("(display-mode: standalone)").matches || (navigator as Navigator & { standalone?: boolean }).standalone === true;
    const safari = ios && !/CriOS|FxiOS|EdgiOS|OPiOS|YaBrowser|GSA|Instagram|FBAN|FBAV|Telegram|WhatsApp/.test(ua);
    setState({ platform: ios ? "ios" : android ? "android" : "other", installed, safari });
  }, []);
  return state;
}

function ShareIcon() {
  return (
    <svg viewBox="0 0 24 24" className="inline h-5 w-5 align-[-4px] text-sky-600" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="M12 3v12M7.5 7.5 12 3l4.5 4.5" />
      <path d="M8 11H6a2 2 0 0 0-2 2v6a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-6a2 2 0 0 0-2-2h-2" />
    </svg>
  );
}

function AddBoxIcon() {
  return (
    <svg viewBox="0 0 24 24" className="inline h-5 w-5 align-[-4px] text-slate-700" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" aria-hidden>
      <rect x="3.5" y="3.5" width="17" height="17" rx="4" />
      <path d="M12 8v8M8 12h8" />
    </svg>
  );
}

/** Step-by-step: install HesabPro on an iPhone from Safari (Share → Add to Home Screen). */
function IosInstallModal({ open, onClose, platform, safari }: { open: boolean; onClose: () => void; platform: Platform; safari: boolean }) {
  const [copied, setCopied] = useState(false);
  const url = typeof window !== "undefined" ? `${window.location.origin}/login` : "https://hesabpro.cloud/login";
  const step = "flex gap-3 rounded-xl bg-slate-50 p-3 text-sm text-slate-700";
  const num = "flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-navy-800 text-xs font-semibold text-white";
  return (
    <Modal open={open} onClose={onClose} title="iPhone-a quraşdırın">
      {platform !== "ios" ? (
        <div className="space-y-3 text-sm text-slate-600">
          <p>Bu səhifəni <b>iPhone-da Safari</b> ilə açın və <b>“iPhone üçün quraşdır”</b> düyməsinə toxunun.</p>
          <p className="break-all rounded-xl bg-slate-50 p-3 font-mono text-navy-800">{url}</p>
        </div>
      ) : (
        <div className="space-y-3">
          {!safari && (
            <div className="rounded-xl border border-amber-200 bg-amber-50 p-3 text-sm text-amber-800">
              Quraşdırma yalnız <b>Safari</b>-də işləyir. Linki kopyalayıb Safari-də açın.
              <button
                type="button"
                className="mt-2 block w-full rounded-lg bg-amber-600 py-2 font-semibold text-white"
                onClick={() => { void navigator.clipboard?.writeText(url).then(() => setCopied(true)); }}
              >
                {copied ? "Kopyalandı ✓" : "Linki kopyala"}
              </button>
            </div>
          )}
          <div className={step}><span className={num}>1</span><span>Aşağıdakı <b>Paylaş</b> <ShareIcon /> düyməsinə toxunun (iPad-də yuxarı sağda).</span></div>
          <div className={step}><span className={num}>2</span><span>Siyahını aşağı sürüşdürüb <b>“Ana ekrana əlavə et”</b> <AddBoxIcon /> seçin <span className="text-slate-400">(“На экран «Домой»” / “Add to Home Screen”)</span>.</span></div>
          <div className={step}><span className={num}>3</span><span>Yuxarı sağda <b>“Əlavə et”</b> düyməsinə toxunun.</span></div>
          <p className="pt-1 text-sm text-slate-500">Ana ekranda <b>HesabPro</b> ikonu yaranacaq — tətbiq kimi tam ekranda açılır, yeniləmələr avtomatik gəlir.</p>
        </div>
      )}
    </Modal>
  );
}

export function StoreBadges({ className = "" }: { className?: string }) {
  const badge = "flex items-center gap-3 rounded-xl border border-white/20 bg-black/80 px-4 py-2.5 text-left text-white transition";
  const apk = useLatestApk();
  const { platform, installed, safari } = usePlatform();
  const [iosHelp, setIosHelp] = useState(false);
  const d = apk?.date ? new Date(apk.date) : null;
  const built = d ? `${String(d.getDate()).padStart(2, "0")}.${String(d.getMonth() + 1).padStart(2, "0")}.${d.getFullYear()}` : null;
  // already opened from the Home Screen icon: nothing to install
  if (installed && platform === "ios") return null;
  const androidBadge = (
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
  );
  const iosBadge = (
    <button type="button" onClick={() => setIosHelp(true)} className={`${badge} hover:border-white/50 hover:bg-black`} title="iPhone-a quraşdır (Safari → Ana ekrana əlavə et)">
      <AppleIcon />
      <span className="leading-tight">
        <span className="block text-[10px] uppercase tracking-wider text-white/70">iPhone üçün quraşdır</span>
        <span className="block text-lg font-semibold">App Store</span>
      </span>
    </button>
  );
  return (
    <div className={`flex flex-wrap items-end gap-3 ${className}`}>
      {/* the button for the phone in hand comes first; on a computer both are shown */}
      {platform === "ios" ? <>{iosBadge}{androidBadge}</> : <>{androidBadge}{iosBadge}</>}
      <IosInstallModal open={iosHelp} onClose={() => setIosHelp(false)} platform={platform} safari={safari} />
    </div>
  );
}
