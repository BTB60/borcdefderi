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

export function paymentConfirmationText(store: string, party: string, paid: number, remaining: number): string {
  const rest = remaining > 0 ? `Qalıq borcunuz: ${formatMoney(remaining)}.` : "Borcunuz tam ödənilib.";
  return `Hörmətli ${party}, "${store}" mağazasına ${formatMoney(paid)} ödənişiniz qəbul edildi. ${rest} Təşəkkür edirik!`;
}
