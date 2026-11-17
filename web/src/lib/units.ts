/** How a product is counted at the till: by piece (ədəd), by weight (kq / qram) or anything else (litr, metr…). */
export type UnitKind = "piece" | "kg" | "g" | "other";

const KG = ["kq", "kg", "kilo", "kiloqram", "kilogram"];
const G = ["q", "qr", "qram", "gr", "g", "gram"];
const PIECE = ["", "ədəd", "əd", "eded", "ed", "dənə", "dene", "ad", "pcs", "şt"];

export function unitKind(unit: string | null | undefined): UnitKind {
  const u = (unit ?? "").trim().toLowerCase().replace(/\.$/, "");
  if (KG.includes(u)) return "kg";
  if (G.includes(u)) return "g";
  if (PIECE.includes(u)) return "piece";
  return "other";
}

export const isWeighed = (unit: string | null | undefined) => {
  const k = unitKind(unit);
  return k === "kg" || k === "g";
};

/** Units offered in the product form (any other text still works). */
export const UNIT_SUGGESTIONS = ["ədəd", "kq", "qram", "litr", "metr", "paket", "qutu"];

const num3 = (n: number) => n.toLocaleString("az-AZ", { maximumFractionDigits: 3 });

/** "2 ədəd", "1,25 kq", "0,35 kq (350 q)", "350 q". */
export function qtyWithUnit(q: number, unit: string | null | undefined): string {
  const k = unitKind(unit);
  if (k === "kg") return q > 0 && q < 1 ? `${num3(q)} kq (${Math.round(q * 1000)} q)` : `${num3(q)} kq`;
  if (k === "g") return `${num3(q)} q`;
  return `${num3(q)} ${unit?.trim() || "ədəd"}`;
}

/** Quantity in the product's own unit from grams typed at the till (3 decimals at most). */
export function fromGrams(grams: number, unit: string | null | undefined): number {
  return unitKind(unit) === "g" ? Math.round(grams) : Math.round(grams) / 1000;
}
