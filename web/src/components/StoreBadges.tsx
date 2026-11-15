/** Android app download (the latest signed APK) and App Store (not published yet: "Tezliklə"). */
export const ANDROID_APK_URL = "https://github.com/BTB60/borcdefderi/releases/download/mobile-latest/HesabPro.apk";

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
  return (
    <div className={`flex flex-wrap gap-3 ${className}`}>
      <a href={ANDROID_APK_URL} className={`${badge} hover:border-white/50 hover:bg-black`} title="Android tətbiqini yüklə (APK)">
        <PlayIcon />
        <span className="leading-tight">
          <span className="block text-[10px] uppercase tracking-wider text-white/70">Android üçün yüklə</span>
          <span className="block text-lg font-semibold">Google Play</span>
        </span>
      </a>
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
