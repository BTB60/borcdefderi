"use client";

import Image from "next/image";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { PlanCard } from "@/components/PlanCard";
import { StoreBadges } from "@/components/StoreBadges";
import { api, getToken } from "@/lib/api";
import type { Contact, PeriodCode, Plan } from "@/lib/types";

const FEATURES: { title: string; text: string; icon: string }[] = [
  { title: "Müştəri borcları", text: "Kimin nə qədər borcu var, son ödəmə tarixi, tarixçə və hesab çıxarışı — bir kartda.", icon: "M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8Zm-7 8a7 7 0 0 1 14 0" },
  { title: "Sürətli satış kassası", text: "Barkod skaneri, F9 ilə ödəniş, Boşluqla satış və 80 mm çek. Nağd, kart, qarışıq və nisyə.", icon: "M3 4h2l2.4 11h11.2L21 8H6.2M9 20a1 1 0 1 0 0-2 1 1 0 0 0 0 2Zm9 0a1 1 0 1 0 0-2 1 1 0 0 0 0 2Z" },
  { title: "Avtomatik xatırlatma", text: "Son ödəmə tarixi yaxınlaşanda proqram özü tapır; hazır mesajı bir toxunuşla WhatsApp və ya SMS ilə göndərin.", icon: "M6 8a6 6 0 1 1 12 0c0 7 3 9 3 9H3s3-2 3-9Zm4.3 13a1.94 1.94 0 0 0 3.4 0" },
  { title: "Anbar və mal qəbulu", text: "Qalıqlar, az qalan mallar, sayım, təchizatçıdan mal qəbulu — alış qiyməti və borc bir sənəddə.", icon: "M3 9l9-6 9 6v11H3V9Zm6 11v-6h6v6" },
  { title: "Topdan və pərakəndə", text: "Hər məhsula Top 1 (topdan) və Top 2 (pərakəndə) qiyməti; kassir bir düymə ilə seçir.", icon: "M7 7h10v10H7z M4 4h16v16H4z" },
  { title: "Endirim və bonus", text: "Daimi müştəriyə endirim faizi, satışdan bonus yığımı və bonusla ödəniş.", icon: "M20 12v8H4v-8M2 7h20v5H2zM12 20V7M12 7H7.5a2.5 2.5 0 0 1 0-5C11 2 12 7 12 7Zm0 0h4.5a2.5 2.5 0 0 0 0-5C13 2 12 7 12 7Z" },
  { title: "Kassa və bank", text: "Nağd və kart hesabları, mədaxil-məxaric, köçürmə və gün sonu hesabatı.", icon: "M3 7h18v12H3V7Zm0 4h18M7 3h10v4H7V3Z" },
  { title: "Gəlir və mənfəət", text: "Satış, maya dəyəri, xərclər və xalis nəticə — rəqəmlər birbaşa bazadan.", icon: "M4 19h16M6 16l4-5 3 3 5-7" },
  { title: "İşçilər və icazələr", text: "Kassir, anbardar, mühasib — hər işçi yalnız öz bölmələrini görür; fəaliyyət jurnalı.", icon: "M12 12a3 3 0 1 0 0-6 3 3 0 0 0 0 6Zm-6 8a6 6 0 0 1 12 0" },
  { title: "Telefon tətbiqi", text: "Android tətbiqi: kamera ilə barkod, satış və borclar. İnternet olmasa da satış dayanmır.", icon: "M7 2h10v20H7zM11 18h2" },
  { title: "Excel və PDF", text: "Məhsul, müştəri və şirkətləri Excel-dən idxal edin; hesabatları Excel və PDF-ə çıxarın.", icon: "M7 3h7l5 5v13H7V3Zm7 0v5h5M10 13h6M10 17h6" },
  { title: "Təhlükəsiz və ayrı", text: "Hər mağazanın məlumatı yalnız özünə aiddir; hər əməliyyat tarixçədə qalır.", icon: "M12 3l8 4v5c0 5-3.5 8-8 9-4.5-1-8-4-8-9V7l8-4Z" },
];

const FAQ: [string, string][] = [
  ["Proqramı quraşdırmaq lazımdır?", "Xeyr. Brauzerdə işləyir — kompüter, planşet və telefonda. Android üçün ayrıca tətbiq də var."],
  ["İnternet kəsiləndə nə olur?", "Telefon tətbiqində satış davam edir və internet gələndə özü göndərilir."],
  ["Məlumatlarım təhlükəsizdir?", "Hər mağazanın məlumatı ayrıca saxlanılır, işçilər yalnız icazə verilən bölmələri görür."],
  ["Köhnə proqramdan keçə bilərəm?", "Bəli. Məhsulları, müştəriləri və şirkətləri Excel faylından bir dəfəyə idxal edə bilərsiniz."],
];

const PERIOD_NAME: Record<string, string> = { MONTHLY: "Aylıq", QUARTERLY: "3 aylıq", SEMI_ANNUAL: "6 aylıq", YEARLY: "İllik", CUSTOM: "Xüsusi" };

function Icon({ d }: { d: string }) {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.7} strokeLinecap="round" strokeLinejoin="round" className="h-6 w-6" aria-hidden>
      <path d={d} />
    </svg>
  );
}

/** Public home page: what HesabPro does, the packages, the video and how to get in touch. */
export default function Home() {
  const router = useRouter();
  const [signedIn, setSignedIn] = useState(false);
  const [plans, setPlans] = useState<Plan[] | null>(null);
  const [period, setPeriod] = useState<PeriodCode>("MONTHLY");
  const [contact, setContact] = useState<Contact | null>(null);

  useEffect(() => {
    if (window.hesabDesktop) { router.replace("/satis"); return; } // the Windows till opens straight on the till
    setSignedIn(!!getToken());
    api<Plan[]>("/api/public/plans")
      .then((l) => setPlans(l.filter((p) => p.active && !p.archived).sort((a, b) => a.sortOrder - b.sortOrder)))
      .catch(() => setPlans([]));
    api<Contact>("/api/public/contact").then(setContact).catch(() => setContact(null));
  }, [router]);

  const periods = Array.from(new Set((plans ?? []).flatMap((p) => p.options.map((o) => o.period))));
  const wa = contact?.whatsapp ? `https://wa.me/${contact.whatsapp}?text=${encodeURIComponent("Salam! HesabPro haqqında məlumat almaq istəyirəm.")}` : null;
  const start = signedIn ? "/dashboard" : "/register";
  const startLabel = signedIn ? "Panelə keç" : "Pulsuz başla";

  return (
    <div className="min-h-screen bg-canvas text-navy-800">
      <header className="sticky top-0 z-30 border-b border-slate-200/70 bg-white/85 backdrop-blur">
        <div className="mx-auto flex max-w-6xl items-center justify-between gap-3 px-4 py-3">
          <Link href="/" className="flex items-center gap-2.5">
            <span className="flex h-9 w-9 items-center justify-center rounded-xl bg-navy-800 p-1.5"><Image src="/logo-mark.png" alt="" width={48} height={48} className="h-full w-full object-contain" /></span>
            <span className="font-serif text-lg font-semibold">HesabPro</span>
          </Link>
          <nav className="hidden items-center gap-6 text-sm text-slate-600 md:flex">
            <a href="#funksiyalar" className="hover:text-navy-800">Funksiyalar</a>
            <a href="#video" className="hover:text-navy-800">Video</a>
            <a href="#qiymetler" className="hover:text-navy-800">Qiymətlər</a>
            <a href="#elaqe" className="hover:text-navy-800">Əlaqə</a>
          </nav>
          <div className="flex items-center gap-2">
            {!signedIn && <Link href="/login" className="rounded-xl px-3 py-2 text-sm font-medium text-navy-800 hover:bg-slate-100">Daxil ol</Link>}
            <Link href={start} className="rounded-xl bg-gold-500 px-4 py-2 text-sm font-semibold text-navy-900 hover:bg-gold-600 hover:text-white">{startLabel}</Link>
          </div>
        </div>
      </header>

      <section className="relative overflow-hidden bg-navy-800 text-white">
        <div className="absolute -right-32 -top-32 h-[28rem] w-[28rem] rounded-full bg-gold-500/15 blur-3xl" />
        <div className="absolute -bottom-40 -left-24 h-[26rem] w-[26rem] rounded-full bg-sky-500/10 blur-3xl" />
        <div className="relative mx-auto grid max-w-6xl items-center gap-10 px-4 py-16 md:grid-cols-[1.15fr_0.85fr] md:py-24">
          <div>
            <p className="mb-4 inline-flex rounded-full bg-white/10 px-3 py-1 text-xs font-semibold uppercase tracking-wider text-gold-300">Mağazalar üçün hesab proqramı</p>
            <h1 className="font-serif text-4xl font-semibold leading-tight md:text-6xl">Borc, satış, anbar və kassa — hamısı bir yerdə.</h1>
            <p className="mt-5 max-w-xl text-lg text-white/75">
              HesabPro mağazanızın borc dəftərini, satış kassasını, anbarını və pulunu bir proqramda idarə edir. Kompüterdə, telefonda, istənilən yerdə.
            </p>
            <div className="mt-8 flex flex-wrap gap-3">
              <Link href={start} className="rounded-2xl bg-gold-500 px-6 py-3.5 text-base font-semibold text-navy-900 shadow-lg shadow-gold-500/20 hover:bg-gold-400">{startLabel}</Link>
              <a href="#video" className="rounded-2xl border border-white/25 px-6 py-3.5 text-base font-medium text-white hover:border-white/60">Videoya bax</a>
            </div>
            <ul className="mt-8 flex flex-wrap gap-x-6 gap-y-2 text-sm text-white/70">
              <li>✓ Quraşdırma lazım deyil</li><li>✓ Android tətbiqi</li><li>✓ Azərbaycan dilində</li>
            </ul>
          </div>
          <div id="video" className="mx-auto w-full max-w-[300px] scroll-mt-24">
            <div className="rounded-[2.2rem] bg-black p-2.5 shadow-2xl ring-1 ring-white/15">
              <video className="aspect-[9/16] w-full rounded-[1.7rem] bg-navy-900 object-cover" src="/video/hesabpro.mp4" poster="/video/hesabpro-poster.jpg"
                controls playsInline preload="none" />
            </div>
            <p className="mt-3 text-center text-xs text-white/50">1 dəqiqəlik təqdimat · səsli</p>
          </div>
        </div>
      </section>

      <section id="funksiyalar" className="mx-auto max-w-6xl scroll-mt-20 px-4 py-16 md:py-20">
        <h2 className="text-center font-serif text-3xl font-semibold md:text-4xl">Mağazanıza lazım olan hər şey</h2>
        <p className="mx-auto mt-3 max-w-2xl text-center text-slate-500">Kağız dəftər və ayrı-ayrı proqramlar əvəzinə bir proqram.</p>
        <div className="mt-10 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {FEATURES.map((f) => (
            <div key={f.title} className="rounded-2xl bg-white p-5 shadow-card ring-1 ring-slate-200/70 transition hover:-translate-y-0.5 hover:shadow-lg">
              <span className="mb-3 inline-flex h-11 w-11 items-center justify-center rounded-xl bg-gold-500/15 text-gold-600"><Icon d={f.icon} /></span>
              <h3 className="font-semibold text-navy-800">{f.title}</h3>
              <p className="mt-1.5 text-sm leading-relaxed text-slate-600">{f.text}</p>
            </div>
          ))}
        </div>
      </section>

      <section id="qiymetler" className="scroll-mt-20 bg-white py-16 md:py-20">
        <div className="mx-auto max-w-6xl px-4">
          <h2 className="text-center font-serif text-3xl font-semibold md:text-4xl">Abunə paketləri</h2>
          <p className="mx-auto mt-3 max-w-2xl text-center text-slate-500">Mağazanızın ölçüsünə uyğun paketi seçin. Uzun müddətə daha sərfəli.</p>
          {periods.length > 1 && (
            <div className="mt-6 flex justify-center">
              <div className="inline-flex flex-wrap justify-center gap-1 rounded-xl bg-slate-100 p-1">
                {periods.map((p) => (
                  <button key={p} type="button" onClick={() => setPeriod(p)}
                    className={`rounded-lg px-4 py-1.5 text-sm font-medium ${period === p ? "bg-white text-navy-800 shadow" : "text-slate-500"}`}>
                    {PERIOD_NAME[p] ?? p}
                  </button>
                ))}
              </div>
            </div>
          )}
          {!plans ? (
            <p className="mt-10 text-center text-sm text-slate-400">Paketlər yüklənir…</p>
          ) : plans.length === 0 ? (
            <p className="mt-10 text-center text-sm text-slate-500">Qiymətlər üçün bizimlə əlaqə saxlayın.</p>
          ) : (
            <div className="mt-10 grid gap-5 md:grid-cols-2 lg:grid-cols-3">
              {plans.map((p) => (
                <PlanCard key={p.id} plan={p} period={p.options.some((o) => o.period === period) ? period : p.options[0]?.period ?? null}
                  action={{ label: signedIn ? "Paketi seç" : "Başla", onClick: () => router.push(signedIn ? "/abunelik" : "/register") }} />
              ))}
            </div>
          )}
        </div>
      </section>

      <section className="mx-auto grid max-w-6xl gap-10 px-4 py-16 md:grid-cols-2 md:py-20">
        <div className="rounded-3xl bg-navy-800 p-8 text-white">
          <h2 className="font-serif text-2xl font-semibold">Telefonda da yanınızda</h2>
          <p className="mt-2 text-white/70">Android tətbiqi ilə kamera ilə barkod oxudun, satış edin, borclara baxın. İnternet kəsiləndə də satış davam edir.</p>
          <StoreBadges className="mt-6" />
        </div>
        <div>
          <h2 className="font-serif text-2xl font-semibold">Tez-tez verilən suallar</h2>
          <div className="mt-4 divide-y divide-slate-200 rounded-2xl bg-white ring-1 ring-slate-200/70">
            {FAQ.map(([q, a]) => (
              <details key={q} className="group px-5 py-4">
                <summary className="flex cursor-pointer list-none items-center justify-between font-medium text-navy-800">
                  {q}<span className="ml-3 text-gold-600 transition group-open:rotate-45">+</span>
                </summary>
                <p className="mt-2 text-sm text-slate-600">{a}</p>
              </details>
            ))}
          </div>
        </div>
      </section>

      <section id="elaqe" className="scroll-mt-20 border-t border-slate-200 bg-white py-14">
        <div className="mx-auto flex max-w-6xl flex-col items-center gap-4 px-4 text-center">
          <h2 className="font-serif text-3xl font-semibold">Sualınız var?</h2>
          <p className="text-slate-500">Yazın, proqramı birlikdə qurub mağazanıza uyğunlaşdıraq.</p>
          <div className="flex flex-wrap justify-center gap-3">
            {wa && <a href={wa} target="_blank" rel="noreferrer" className="rounded-2xl bg-emerald-600 px-6 py-3 font-semibold text-white hover:bg-emerald-700">WhatsApp ilə yazın</a>}
            {contact?.phone && <a href={`tel:${contact.phone.replace(/[^+0-9]/g, "")}`} className="rounded-2xl border border-slate-300 px-6 py-3 font-medium text-navy-800 hover:border-gold-500">{contact.phone}</a>}
            <Link href={start} className="rounded-2xl bg-gold-500 px-6 py-3 font-semibold text-navy-900 hover:bg-gold-600 hover:text-white">{startLabel}</Link>
          </div>
        </div>
      </section>

      <footer className="bg-navy-900 py-6 text-center text-xs text-white/50">
        © {new Date().getFullYear()} HesabPro · Borc, satış, anbar və kassa proqramı
      </footer>
    </div>
  );
}
