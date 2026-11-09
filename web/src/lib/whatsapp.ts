import { formatMoney } from "./format";

/**
 * Azerbaijani phone → international digits for wa.me links.
 * "050 123 45 67" → "994501234567", "+994 50 123 45 67" → "994501234567", "501234567" → "994501234567".
 * Returns null when the number cannot be a mobile number.
 */
export function waNumber(phone: string | null | undefined): string | null {
  if (!phone) return null;
  let d = phone.replace(/\D/g, "");
  if (d.startsWith("00")) d = d.slice(2);
  if (d.startsWith("994")) return d.length === 12 ? d : null;
  if (d.startsWith("0") && d.length === 10) return "994" + d.slice(1);
  if (d.length === 9) return "994" + d;
  return d.length >= 10 && d.length <= 15 ? d : null;
}

/** Click-to-chat link; opens WhatsApp with the message ready to send (the store sends it itself). */
export function waLink(phone: string | null | undefined, text: string): string | null {
  const n = waNumber(phone);
  return n ? `https://wa.me/${n}?text=${encodeURIComponent(text)}` : null;
}

export function debtReminderText(store: string, party: string, remaining: number, dueDate?: string | null): string {
  const due = dueDate ? ` Son ödəmə tarixi: ${dueDate.split("-").reverse().join(".")}.` : "";
  return `Hörmətli ${party}, "${store}" mağazasına qalıq borcunuz ${formatMoney(remaining)} təşkil edir.${due} Təşəkkür edirik!`;
}

/** Payment confirmation; with a receipt link the customer can open the PDF receipt (çek) from WhatsApp. */
export function paymentConfirmationText(store: string, party: string, paid: number, remaining: number, receiptUrl?: string | null): string {
  const rest = remaining > 0 ? `Qalıq borcunuz: ${formatMoney(remaining)}.` : "Borcunuz tam ödənilib.";
  const receipt = receiptUrl ? `\nÖdəniş çeki: ${receiptUrl}` : "";
  return `Hörmətli ${party}, "${store}" mağazasına ${formatMoney(paid)} ödənişiniz qəbul edildi. ${rest} Təşəkkür edirik!${receipt}`;
}

/** Message used when (re)sending a receipt from the payments list. */
export function receiptMessageText(store: string, party: string, paid: number, receiptUrl: string): string {
  return `Hörmətli ${party}, "${store}" mağazasına ${formatMoney(paid)} ödənişinizin çeki: ${receiptUrl}`;
}

/** WhatsApp Web (for computers without the WhatsApp app). */
export function waWebLink(phone: string | null | undefined, text: string): string | null {
  const n = waNumber(phone);
  return n ? `https://web.whatsapp.com/send?phone=${n}&text=${encodeURIComponent(text)}` : null;
}

export type TemplateKey = "reminder" | "payment" | "balance" | "thanks" | "custom";

export const TEMPLATE_LABEL: Record<TemplateKey, string> = {
  reminder: "Borc xatırlatması",
  payment: "Ödəniş təsdiqi",
  balance: "Qalıq borc məlumatı",
  thanks: "Təşəkkür mesajı",
  custom: "Fərdi mesaj",
};

/** Editable starting texts for the customer card's WhatsApp button. */
export function templateText(
  key: TemplateKey,
  v: { store: string; name: string; total: number; paid: number; remaining: number; lastPayment?: number | null },
): string {
  switch (key) {
    case "reminder":
      return `Salam, ${v.name}.\n"${v.store}" mağazasına qalıq borcunuz ${formatMoney(v.remaining)} təşkil edir. Zəhmət olmasa, ödənişi yaxın vaxtda edin.\nTəşəkkür edirik!`;
    case "payment":
      return `Salam, ${v.name}.\n${v.lastPayment ? formatMoney(v.lastPayment) + " məbləğində " : ""}ödənişiniz qəbul edildi. Qalıq borcunuz: ${formatMoney(v.remaining)}.\nTəşəkkür edirik!`;
    case "balance":
      return `Salam, ${v.name}.\nHesabPro sistemində borc məlumatınız:\nÜmumi borc: ${formatMoney(v.total)}\nÖdənilmiş: ${formatMoney(v.paid)}\nQalıq borc: ${formatMoney(v.remaining)}\nƏlavə məlumat üçün bizimlə əlaqə saxlaya bilərsiniz.\n${v.store}`;
    case "thanks":
      return `Salam, ${v.name}.\nBizi seçdiyiniz üçün təşəkkür edirik! Hər zaman xidmətinizdəyik.\n${v.store}`;
    default:
      return `Salam, ${v.name}.\n`;
  }
}
