"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { Suspense, useEffect, useMemo, useState, type FormEvent } from "react";
import { Alert, Button, Card, Field, Input, PageHeader, Spinner, Textarea } from "@/components/ui";
import { ApiError, api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { formatMoney, todayBaku } from "@/lib/format";
import { contrastText, fillWhatsappTemplate, findOption, formatCard, optionLabel, periodText } from "@/lib/plans";
import type { Contact, Plan, SubscriptionRecord } from "@/lib/types";

export default function PaymentPage() {
  return (
    <Suspense fallback={<Spinner />}>
      <Payment />
    </Suspense>
  );
}

const MAX_RECEIPT_BYTES = 5 * 1024 * 1024;
type Method = "M10" | "CARD";

/** The store's payment page for one package + period: where to pay, WhatsApp contact and "Ödəniş etdim". */
function Payment() {
  const params = useSearchParams();
  const { me, isOwner } = useAuth();
  const [plans, setPlans] = useState<Plan[] | null>(null);
  const [contact, setContact] = useState<Contact | null>(null);
  const [pending, setPending] = useState<SubscriptionRecord | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [method, setMethod] = useState<Method>("M10");
  const [copied, setCopied] = useState<string | null>(null);

  useEffect(() => {
    Promise.all([
      api<Plan[]>("/api/public/plans"),
      api<Contact>("/api/public/contact"),
      api<SubscriptionRecord[]>("/api/subscriptions/history"),
    ])
      .then(([p, c, h]) => {
        setPlans(p);
        setContact(c);
        setPending(h.find((x) => x.status === "PENDING") ?? null);
        if (!c.m10Account && c.cardNumber) setMethod("CARD");
      })
      .catch((e) => setError(e instanceof ApiError ? e.message : "Məlumat yüklənmədi"));
  }, []);

  const plan = plans?.find((p) => p.code === params.get("plan"));
  const option = plan ? findOption(plan, params.get("period")) : undefined;

  async function copy(value: string, key: string) {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(key);
      setTimeout(() => setCopied((k) => (k === key ? null : k)), 2000);
    } catch {
      setCopied(null);
    }
  }

  const waHref = useMemo(() => {
    if (!contact?.whatsapp || !plan || !option) return null;
    const text = fillWhatsappTemplate(contact.whatsappTemplate, {
      store: me?.storeName ?? "",
      plan: plan.name,
      period: periodText(option),
      amount: option.price,
    });
    return `https://wa.me/${contact.whatsapp}?text=${encodeURIComponent(text)}`;
  }, [contact, plan, option, me?.storeName]);

  if (error) return <Alert>{error}</Alert>;
  if (!plans || !contact) return <Spinner />;
  if (!plan || !option) {
    return (
      <>
        <PageHeader title="Ödəniş" />
        <Alert>Seçilmiş paket artıq satışda deyil. <Link className="underline" href="/abunelik">Paketlərə qayıt</Link></Alert>
      </>
    );
  }

  const hasM10 = !!contact.m10Account;
  const hasCard = !!contact.cardNumber;

  return (
    <>
      <PageHeader
        title="Ödəniş"
        subtitle="Ödənişi edin, WhatsApp ilə əlaqə saxlayın və “Ödəniş etdim” sorğusunu göndərin"
        actions={<Link href="/abunelik" className="rounded-xl border border-slate-300 px-4 py-2.5 text-sm font-medium text-slate-700 hover:border-slate-400">← Paketlər</Link>}
      />

      <div className="grid gap-6 lg:grid-cols-[1fr_380px]">
        <div className="space-y-5">
          <div className="overflow-hidden rounded-2xl border-2 p-5 shadow-card" style={{ backgroundColor: plan.backgroundColor, color: plan.textColor, borderColor: plan.primaryColor }}>
            <p className="text-xs font-semibold uppercase tracking-widest opacity-70">Seçilmiş paket</p>
            <div className="mt-1 flex flex-wrap items-baseline justify-between gap-3">
              <p className="font-serif text-2xl font-semibold">{plan.name}</p>
              <p className="font-serif text-3xl font-semibold">{formatMoney(option.price)}</p>
            </div>
            <p className="mt-1 text-sm opacity-80">Müddət: {optionLabel(option)} — {periodText(option)}</p>
            {plan.options.length > 1 && (
              <div className="mt-3 flex flex-wrap gap-1.5">
                {plan.options.map((o) => (
                  <Link
                    key={o.period}
                    href={`/abunelik/odenis?plan=${encodeURIComponent(plan.code)}&period=${o.period}`}
                    replace
                    className="rounded-full border px-3 py-1 text-xs font-medium"
                    style={o.period === option.period
                      ? { backgroundColor: plan.primaryColor, borderColor: plan.primaryColor, color: contrastText(plan.primaryColor) }
                      : { borderColor: `${plan.textColor}33` }}
                  >
                    {optionLabel(o)} · {formatMoney(o.price)}
                  </Link>
                ))}
              </div>
            )}
          </div>

          {contact.paymentInstructions && <Alert kind="info"><span className="whitespace-pre-line">{contact.paymentInstructions}</span></Alert>}

          <Card>
            <h2 className="mb-3 font-serif text-lg font-semibold text-navy-800">1. Ödəniş üsulunu seçin</h2>
            {!hasM10 && !hasCard ? (
              <Alert kind="info">Ödəniş məlumatları hələ əlavə edilməyib. WhatsApp ilə administratorla əlaqə saxlayın.</Alert>
            ) : (
              <>
                <div className="mb-4 grid grid-cols-2 gap-2" role="radiogroup" aria-label="Ödəniş üsulu">
                  {([["M10", "M10", hasM10], ["CARD", "Bank kartı", hasCard]] as const).map(([key, label, enabled]) => (
                    <button
                      key={key}
                      type="button"
                      role="radio"
                      aria-checked={method === key}
                      disabled={!enabled}
                      onClick={() => setMethod(key)}
                      className={`rounded-xl border px-4 py-3 text-sm font-semibold transition disabled:cursor-not-allowed disabled:opacity-40 ${method === key ? "border-gold-500 bg-gold-500/10 text-navy-800 shadow-glow" : "border-slate-300 text-slate-600 hover:border-slate-400"}`}
                    >
                      {label}
                    </button>
                  ))}
                </div>
                {method === "M10" && hasM10 && (
                  <PayDetails
                    rows={[
                      { label: "M10 nömrəsi", value: contact.m10Account!, copyKey: "m10", mono: true },
                      { label: "Hesab sahibi", value: contact.m10Recipient },
                      { label: "Məbləğ", value: formatMoney(option.price), copyValue: option.price.toFixed(2), copyKey: "amount" },
                    ]}
                    note={contact.paymentNote}
                    copied={copied}
                    onCopy={copy}
                  />
                )}
                {method === "CARD" && hasCard && (
                  <PayDetails
                    rows={[
                      { label: "Kart nömrəsi", value: formatCard(contact.cardNumber), copyValue: contact.cardNumber!.replace(/\D/g, ""), copyKey: "card", mono: true },
                      { label: "Kart sahibi", value: contact.cardHolder },
                      { label: "Bank", value: contact.cardBank },
                      { label: "Məbləğ", value: formatMoney(option.price), copyValue: option.price.toFixed(2), copyKey: "amount" },
                    ]}
                    note={contact.cardNote}
                    copied={copied}
                    onCopy={copy}
                  />
                )}
              </>
            )}
          </Card>

          <Card>
            <h2 className="mb-3 font-serif text-lg font-semibold text-navy-800">2. “Ödəniş etdim” — təsdiq sorğusu</h2>
            {isOwner ? (
              <ClaimForm plan={plan} period={option.period} amount={option.price} method={method} pending={pending} afterText={contact.afterPaymentText} />
            ) : (
              <Alert kind="info">Ödəniş sorğusunu yalnız mağaza sahibi göndərə bilər.</Alert>
            )}
          </Card>
        </div>

        <aside className="space-y-4 lg:sticky lg:top-6 lg:self-start">
          <Card>
            <h2 className="mb-2 font-serif text-lg font-semibold text-navy-800">Əlaqə</h2>
            <p className="mb-4 text-sm text-slate-500">Sualınız varsa və ya çeki göndərmək istəyirsinizsə, administratora yazın. Mesaj hazır olacaq — WhatsApp-da özünüz göndərirsiniz.</p>
            {waHref ? (
              <a href={waHref} target="_blank" rel="noopener noreferrer" className="flex items-center justify-center gap-2 rounded-xl bg-emerald-600 px-4 py-3 text-sm font-semibold text-white transition hover:bg-emerald-500">
                <svg aria-hidden="true" viewBox="0 0 24 24" className="h-5 w-5 fill-current"><path d="M12 2a10 10 0 0 0-8.6 15.1L2 22l5-1.3A10 10 0 1 0 12 2Zm0 18.2a8.2 8.2 0 0 1-4.2-1.1l-.3-.2-3 .8.8-2.9-.2-.3A8.2 8.2 0 1 1 12 20.2Zm4.5-6.1c-.2-.1-1.5-.7-1.7-.8s-.4-.1-.6.1-.7.8-.8 1-.3.2-.5.1a6.7 6.7 0 0 1-3.3-2.9c-.3-.4.3-.4.7-1.3.1-.2 0-.3 0-.4l-.8-1.8c-.2-.5-.4-.4-.6-.4h-.5a1 1 0 0 0-.7.3 3 3 0 0 0-.9 2.2 5.2 5.2 0 0 0 1.1 2.7 11.8 11.8 0 0 0 4.5 4c1.7.7 2.3.8 3.2.6a2.7 2.7 0 0 0 1.8-1.3 2.2 2.2 0 0 0 .2-1.3c-.1-.1-.3-.2-.5-.3Z" /></svg>
                WhatsApp ilə əlaqə saxla
              </a>
            ) : (
              <Alert kind="info">WhatsApp nömrəsi hələ əlavə edilməyib.</Alert>
            )}
            {contact.phone && <p className="mt-3 text-center text-sm text-slate-600">WhatsApp: <a className="font-medium text-navy-800" href={`tel:${contact.phone.replace(/[^+\d]/g, "")}`}>{contact.phone}</a></p>}
            {contact.extraPhone && <p className="mt-1 text-center text-sm text-slate-600">Əlavə nömrə: <a className="font-medium text-navy-800" href={`tel:${contact.extraPhone.replace(/[^+\d]/g, "")}`}>{contact.extraPhone}</a></p>}
            {contact.extraContact && <p className="mt-1 text-center text-xs text-slate-500">{contact.extraContact}</p>}
          </Card>
          <p className="px-1 text-xs text-slate-400">Ödəniş administrator tərəfindən yoxlanılıb təsdiqlənməyənə qədər abunəlik aktivləşmir. Məbləğ paketin qiymətinə görə serverdə hesablanır.</p>
        </aside>
      </div>
    </>
  );
}

function PayDetails({ rows, note, copied, onCopy }: {
  rows: { label: string; value: string | null | undefined; copyKey?: string; copyValue?: string; mono?: boolean }[];
  note: string | null;
  copied: string | null;
  onCopy: (v: string, k: string) => void;
}) {
  return (
    <div className="rounded-xl border border-gold-500/40 bg-gold-500/5 px-4 py-3">
      {rows.filter((r) => r.value).map((r) => (
        <div key={r.label} className="flex flex-wrap items-center justify-between gap-2 border-b border-gold-500/15 py-2 last:border-0">
          <span className="text-sm text-slate-500">{r.label}</span>
          <span className="flex items-center gap-2">
            <span className={`text-navy-800 ${r.mono ? "font-mono text-lg font-semibold tracking-wide" : "font-semibold"}`}>{r.value}</span>
            {r.copyKey && (
              <button type="button" onClick={() => onCopy(r.copyValue ?? r.value ?? "", r.copyKey!)} className="rounded-full bg-white px-2.5 py-0.5 text-xs font-medium text-slate-600 ring-1 ring-slate-200 hover:bg-slate-50">
                {copied === r.copyKey ? "Kopyalandı ✓" : "Kopyala"}
              </button>
            )}
          </span>
        </div>
      ))}
      {note && <p className="mt-2 whitespace-pre-line text-xs text-slate-600">{note}</p>}
    </div>
  );
}

function ClaimForm({ plan, period, amount, method, pending, afterText }: {
  plan: Plan;
  period: string;
  amount: number;
  method: Method;
  pending: SubscriptionRecord | null;
  afterText: string | null;
}) {
  const { me } = useAuth();
  const [payerName, setPayerName] = useState(me?.fullName ?? "");
  const [paid, setPaid] = useState(amount.toFixed(2));
  const [date, setDate] = useState(todayBaku());
  const [reference, setReference] = useState("");
  const [note, setNote] = useState("");
  const [receipt, setReceipt] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [sent, setSent] = useState(false);

  useEffect(() => setPaid(amount.toFixed(2)), [amount]);

  async function send(e: FormEvent) {
    e.preventDefault();
    setError(null);
    if (receipt && receipt.size > MAX_RECEIPT_BYTES) {
      setError("Faylın ölçüsü 5 MB-dan çox ola bilməz");
      return;
    }
    const form = new FormData();
    form.append("planCode", plan.code);
    form.append("period", period);
    form.append("paymentMethod", method);
    if (payerName.trim()) form.append("payerName", payerName.trim());
    if (paid.trim()) form.append("paidAmount", paid.trim().replace(",", "."));
    if (date) form.append("paymentDate", date);
    if (reference.trim()) form.append("payerReference", reference.trim());
    if (note.trim()) form.append("note", note.trim());
    if (receipt) form.append("receipt", receipt);
    setBusy(true);
    try {
      await api("/api/subscriptions", { method: "POST", body: form });
      setSent(true);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Sorğu göndərilmədi");
    } finally {
      setBusy(false);
    }
  }

  if (sent) {
    return (
      <div className="space-y-3">
        <Alert kind="success">
          <span className="whitespace-pre-line">{afterText?.trim() || "Sorğunuz göndərildi. Administrator ödənişi yoxlayıb təsdiqləyəndən sonra abunəliyiniz avtomatik aktivləşəcək."}</span>
        </Alert>
        <Link href="/abunelik" className="block rounded-xl bg-navy-800 px-4 py-3 text-center text-sm font-medium text-white hover:bg-navy-700">Ödəniş sorğularıma bax</Link>
      </div>
    );
  }

  return (
    <form onSubmit={send} className="space-y-3">
      {pending && <Alert kind="info">Yoxlanılan sorğunuz var ({pending.planName}). Yenidən göndərsəniz, həmin sorğu yenilənəcək.</Alert>}
      {error && <Alert>{error}</Alert>}
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="Ödəniş üsulu"><Input value={method === "M10" ? "M10" : "Bank kartı"} readOnly className="bg-slate-50" /></Field>
        <Field label="Ödəniş edən şəxsin adı"><Input maxLength={120} value={payerName} onChange={(e) => setPayerName(e.target.value)} /></Field>
        <Field label="Ödəniş məbləği (₼)" hint={`Paketin qiyməti: ${formatMoney(amount)}`}>
          <Input type="number" min="0" step="0.01" value={paid} onChange={(e) => setPaid(e.target.value)} />
        </Field>
        <Field label="Ödəniş tarixi"><Input type="date" max={todayBaku()} value={date} onChange={(e) => setDate(e.target.value)} /></Field>
      </div>
      <Field label="Əməliyyat nömrəsi" hint="Çekdəki əməliyyat / istinad nömrəsi (istəyə bağlı)">
        <Input maxLength={120} value={reference} onChange={(e) => setReference(e.target.value)} />
      </Field>
      <Field label="Çek və ya qəbz şəkli" hint="PNG, JPG və ya PDF · maksimum 5 MB">
        <input
          type="file"
          accept="image/png,image/jpeg,application/pdf"
          onChange={(e) => setReceipt(e.target.files?.[0] ?? null)}
          className="block w-full text-sm text-slate-600 file:mr-4 file:rounded-xl file:border-0 file:bg-navy-800 file:px-4 file:py-2.5 file:text-sm file:font-medium file:text-white hover:file:bg-navy-700"
        />
      </Field>
      <Field label="Əlavə qeyd"><Textarea maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} /></Field>
      <Button type="submit" variant="gold" className="w-full py-3" disabled={busy}>{busy ? "Göndərilir…" : "Ödəniş etdim — təsdiq sorğusu göndər"}</Button>
    </form>
  );
}
