"use client";

import { useEffect, useState } from "react";
import { formatMoney } from "@/lib/format";

type CartMsg = { kind: "cart"; store: string; subtotal: number; discount: number; total: number; lines: { name: string; quantity: number; price: number; total: number }[] };
type DoneMsg = { kind: "done"; store: string; total: number; change: number };

/**
 * Müştəri ekranı: opened from the till onto a second monitor facing the customer. It shows the goods being rung up
 * and the total, then "Təşəkkür edirik" with the change. Data comes from the till window in the same browser.
 */
export default function CustomerDisplayPage() {
  const [msg, setMsg] = useState<CartMsg | DoneMsg | null>(null);

  useEffect(() => {
    if (typeof BroadcastChannel === "undefined") return;
    const ch = new BroadcastChannel("hesabpro-customer-display");
    ch.onmessage = (e) => setMsg(e.data as CartMsg | DoneMsg);
    return () => ch.close();
  }, []);

  const store = msg?.store ?? "";
  if (!msg || (msg.kind === "cart" && msg.lines.length === 0)) {
    return (
      <main className="flex min-h-screen flex-col items-center justify-center bg-gradient-to-br from-navy-800 to-navy-600 p-8 text-center text-white">
        <p className="font-serif text-5xl font-semibold">{store || "Xoş gəlmisiniz!"}</p>
        {store && <p className="mt-4 text-2xl text-white/70">Xoş gəlmisiniz!</p>}
        <p className="fixed bottom-6 text-sm text-white/40">HesabPro</p>
      </main>
    );
  }
  if (msg.kind === "done") {
    return (
      <main className="flex min-h-screen flex-col items-center justify-center bg-emerald-600 p-8 text-center text-white">
        <p className="text-7xl">✓</p>
        <p className="mt-4 font-serif text-5xl font-semibold">Təşəkkür edirik!</p>
        <p className="mt-6 text-3xl">Ödəniş: {formatMoney(msg.total)}</p>
        {msg.change > 0 && <p className="mt-3 text-4xl font-bold">Qalıq: {formatMoney(msg.change)}</p>}
        <p className="fixed bottom-6 text-sm text-white/60">{store}</p>
      </main>
    );
  }
  const last = msg.lines.slice(-12);
  return (
    <main className="flex min-h-screen flex-col bg-canvas">
      <header className="bg-navy-800 px-8 py-4 text-white"><p className="font-serif text-2xl font-semibold">{store}</p></header>
      <section className="flex-1 overflow-hidden px-8 py-6">
        <table className="w-full text-2xl">
          <tbody className="divide-y divide-slate-200">
            {last.map((l, i) => (
              <tr key={i} className={i === last.length - 1 ? "bg-gold-500/10" : ""}>
                <td className="py-3 text-navy-800">{l.name}</td>
                <td className="py-3 text-right text-slate-500">{Number(l.quantity)} × {formatMoney(l.price)}</td>
                <td className="py-3 pl-6 text-right font-semibold tabular-nums text-navy-800">{formatMoney(l.total)}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {msg.lines.length > last.length && <p className="mt-2 text-lg text-slate-400">… və daha {msg.lines.length - last.length} mal</p>}
      </section>
      <footer className="bg-white px-8 py-6 shadow-[0_-8px_30px_rgba(0,0,0,0.06)]">
        {msg.discount > 0 && (
          <div className="flex justify-between text-2xl text-slate-600"><span>Endirim</span><span className="tabular-nums">−{formatMoney(msg.discount)}</span></div>
        )}
        <div className="mt-1 flex items-baseline justify-between">
          <span className="text-3xl font-semibold text-navy-800">Ödəniləcək</span>
          <span className="font-serif text-7xl font-semibold tabular-nums text-navy-800">{formatMoney(msg.total)}</span>
        </div>
      </footer>
    </main>
  );
}
