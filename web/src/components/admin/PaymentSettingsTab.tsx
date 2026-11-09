"use client";

import { useEffect, useState, type FormEvent } from "react";
import { Button, Card, Field, Input, Spinner, Textarea } from "@/components/ui";
import { ApiError, api } from "@/lib/api";
import { DEFAULT_WHATSAPP_TEMPLATE } from "@/lib/plans";
import type { Contact } from "@/lib/types";

type Props = { onError: (m: string | null) => void; onNotice?: (m: string | null) => void };

type Form = Omit<Contact, "whatsapp">;

const KEYS: (keyof Form)[] = [
  "phone", "m10Account", "m10Recipient", "paymentNote", "cardNumber", "cardHolder", "cardBank", "cardNote",
  "extraPhone", "extraContact", "whatsappTemplate", "paymentInstructions", "afterPaymentText",
];

const toForm = (c: Contact): Record<keyof Form, string> =>
  Object.fromEntries(KEYS.map((k) => [k, c[k] ?? ""])) as Record<keyof Form, string>;

/**
 * SUPER_ADMIN: M10, bank card, WhatsApp / contact numbers and message texts shown on the store payment page.
 * Everything is stored in the database — nothing is hard-coded in the source.
 */
export function PaymentSettingsTab({ onError, onNotice }: Props) {
  const [form, setForm] = useState<Record<keyof Form, string> | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api<Contact>("/api/public/contact").then((c) => setForm(toForm(c))).catch((e) => onError(e instanceof ApiError ? e.message : "Ayarlar yüklənmədi"));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function save(e: FormEvent) {
    e.preventDefault();
    if (!form) return;
    setBusy(true);
    onError(null);
    try {
      const saved = await api<Contact>("/api/admin/settings/contact", { method: "PUT", json: form });
      setForm(toForm(saved));
      onNotice?.("Ödəniş və əlaqə parametrləri yadda saxlanıldı — mağazalar yeni məlumatları dərhal görəcək");
    } catch (err) {
      onError(err instanceof ApiError ? err.message : "Yadda saxlanılmadı");
    } finally {
      setBusy(false);
    }
  }

  if (!form) return <Spinner />;
  const input = (k: keyof Form, props: Record<string, unknown> = {}) => (
    <Input value={form[k]} onChange={(e) => setForm({ ...form, [k]: e.target.value })} {...props} />
  );
  const area = (k: keyof Form, placeholder?: string) => (
    <Textarea value={form[k]} maxLength={1000} placeholder={placeholder} onChange={(e) => setForm({ ...form, [k]: e.target.value })} />
  );

  return (
    <form onSubmit={save} className="space-y-5">
      <div className="grid gap-5 lg:grid-cols-2">
        <Card>
          <h2 className="mb-1 font-serif text-lg font-semibold text-navy-800">M10 məlumatları</h2>
          <p className="mb-4 text-sm text-slate-500">Boş qalsa, M10 ödəniş üsulu göstərilmir.</p>
          <div className="space-y-3">
            <Field label="M10 nömrəsi">{input("m10Account", { maxLength: 60, placeholder: "050 123 45 67", inputMode: "tel" })}</Field>
            <Field label="Hesab sahibinin adı">{input("m10Recipient", { maxLength: 120 })}</Field>
            <Field label="M10 ödəniş təlimatı" hint="Məs: təyinat hissəsinə mağaza adını yazın">{input("paymentNote", { maxLength: 500 })}</Field>
          </div>
        </Card>

        <Card>
          <h2 className="mb-1 font-serif text-lg font-semibold text-navy-800">Bank kartı</h2>
          <p className="mb-4 text-sm text-slate-500">Kart məlumatı yalnız daxil olmuş mağazalara göstərilir.</p>
          <div className="space-y-3">
            <Field label="Kart nömrəsi">{input("cardNumber", { maxLength: 30, placeholder: "4169 0000 0000 0000", inputMode: "numeric" })}</Field>
            <Field label="Kart sahibinin adı">{input("cardHolder", { maxLength: 120 })}</Field>
            <Field label="Bankın adı" hint="İstəyə bağlı">{input("cardBank", { maxLength: 120 })}</Field>
            <Field label="Kartla ödəniş təlimatı">{input("cardNote", { maxLength: 500 })}</Field>
          </div>
        </Card>

        <Card>
          <h2 className="mb-1 font-serif text-lg font-semibold text-navy-800">Əlaqə</h2>
          <p className="mb-4 text-sm text-slate-500">“WhatsApp ilə əlaqə saxla” düyməsi bu nömrəyə yazır.</p>
          <div className="space-y-3">
            <Field label="WhatsApp nömrəsi" hint="Məs: 050 123 45 67 — avtomatik +994 formatına çevrilir">{input("phone", { maxLength: 30, inputMode: "tel" })}</Field>
            <Field label="Əlavə əlaqə nömrəsi">{input("extraPhone", { maxLength: 30, inputMode: "tel" })}</Field>
            <Field label="Əlavə əlaqə məlumatı" hint="Məs: iş saatları">{input("extraContact", { maxLength: 300 })}</Field>
          </div>
        </Card>

        <Card>
          <h2 className="mb-1 font-serif text-lg font-semibold text-navy-800">Mesajlar</h2>
          <p className="mb-4 text-sm text-slate-500">
            Şablonda istifadə edin: <code className="text-xs">[Mağaza adı]</code> <code className="text-xs">[Paket adı]</code> <code className="text-xs">[Müddət]</code> <code className="text-xs">[Məbləğ]</code>
          </p>
          <div className="space-y-3">
            <Field label="WhatsApp mesaj şablonu" hint="Boş qalsa, standart mətn istifadə olunur">{area("whatsappTemplate", DEFAULT_WHATSAPP_TEMPLATE)}</Field>
            <Field label="Ödəniş təlimatı" hint="Ödəniş səhifəsinin yuxarısında göstərilir">{area("paymentInstructions")}</Field>
            <Field label="Ödənişdən sonra göstəriləcək mətn">{area("afterPaymentText", "Sorğunuz qəbul edildi. Ödəniş yoxlanılandan sonra hesabınız aktivləşəcək.")}</Field>
          </div>
        </Card>
      </div>
      <div className="flex justify-end">
        <Button type="submit" variant="gold" className="px-8 py-3" disabled={busy}>{busy ? "Saxlanılır…" : "Yadda saxla"}</Button>
      </div>
    </form>
  );
}
