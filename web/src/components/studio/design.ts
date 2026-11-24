import { CHARACTERS, characterById } from "./characters";

/** One bonus card look: everything the studio draws, saved as JSON per store. */
export type Spec = {
  name: string;
  category: CategoryId;
  background: Background;
  color1: string; color2: string; accent: string; textColor: string;
  font: FontId;
  layout: Layout;
  icon: IconId;
  title: string;
  tagline: string;
  /** A library character, or "ai" for {@link Spec.characterSvg}, or "none". */
  character: string;
  characterSvg?: string | null;
  characterScale: number;
  characterSide: "left" | "right";
  qrFront: "none" | "left" | "right";
  qrBack: "left" | "center" | "right";
  backNote: string;
  source: "ai" | "local" | "manual";
};

/** The real card a design is shown with (or a sample). */
export type CardData = { name: string; number: string; balance: string; validUntil: string; qrText: string };

export const SAMPLE: CardData = { name: "Ad Soyad", number: "HP-000000", balance: "0,00 ₼", validUntil: "12/2027", qrText: "HP-000000" };

export type CategoryId = "funny" | "gift" | "vip" | "luxury" | "color3d" | "market" | "cafe" | "kids" | "minimal";
export const CATEGORIES: { id: CategoryId; label: string; emoji: string; prompt: string; chars: string[] }[] = [
  { id: "funny", label: "Gülməli personajlar", emoji: "😂", prompt: "funny cartoon characters", chars: ["bag", "star", "robot", "apple", "cart"] },
  { id: "gift", label: "Hədiyyə və alış-veriş", emoji: "🎁", prompt: "gifts and shopping", chars: ["gift", "bag", "bear"] },
  { id: "vip", label: "Premium və VIP", emoji: "😎", prompt: "premium VIP members", chars: ["crown", "cat", "star"] },
  { id: "luxury", label: "Qara-qızılı lüks", emoji: "💎", prompt: "black and gold luxury", chars: ["diamond", "crown", "cat"] },
  { id: "color3d", label: "Rəngarəng 3D", emoji: "🎨", prompt: "colourful 3D", chars: ["star", "donut", "robot", "gift"] },
  { id: "market", label: "Market və mağaza", emoji: "🛒", prompt: "grocery market and shop", chars: ["cart", "apple", "bag"] },
  { id: "cafe", label: "Kafe və restoran", emoji: "☕", prompt: "café and restaurant", chars: ["cup", "donut"] },
  { id: "kids", label: "Uşaq və əyləncə", emoji: "🧸", prompt: "kids and fun", chars: ["bear", "donut", "apple", "gift"] },
  { id: "minimal", label: "Minimalist və modern", emoji: "◻️", prompt: "minimalist modern", chars: ["robot", "diamond", "none"] },
];

export type Background = "gradient" | "radial" | "stripes" | "dots" | "waves" | "confetti" | "mesh" | "sunburst" | "grid";
export const BACKGROUNDS: { id: Background; label: string }[] = [
  { id: "gradient", label: "Qradiyent" }, { id: "radial", label: "Parıltı" }, { id: "stripes", label: "Zolaqlar" },
  { id: "dots", label: "Nöqtələr" }, { id: "waves", label: "Dalğalar" }, { id: "confetti", label: "Konfeti" },
  { id: "mesh", label: "Rəng ləkələri" }, { id: "sunburst", label: "Günəş şüaları" }, { id: "grid", label: "Tor" },
];

export type FontId = "rounded" | "serif" | "bold" | "mono" | "playful" | "elegant";
/** System fonts only: a card drawn as SVG and exported to PNG / PDF cannot load web fonts. */
export const FONTS: { id: FontId; label: string; stack: string; weight: number }[] = [
  { id: "rounded", label: "Yumru", stack: "'Trebuchet MS', 'Arial Rounded MT Bold', Verdana, sans-serif", weight: 800 },
  { id: "bold", label: "Qalın", stack: "'Arial Black', Impact, 'Segoe UI', sans-serif", weight: 900 },
  { id: "serif", label: "Klassik", stack: "Georgia, 'Times New Roman', serif", weight: 700 },
  { id: "elegant", label: "Zərif", stack: "'Palatino Linotype', 'Book Antiqua', Palatino, serif", weight: 700 },
  { id: "playful", label: "Oynaq", stack: "'Comic Sans MS', 'Chalkboard SE', 'Segoe Print', cursive", weight: 700 },
  { id: "mono", label: "Texno", stack: "'Courier New', Consolas, monospace", weight: 700 },
];

export type Layout = "classic" | "split" | "centered" | "badge" | "diagonal";
export const LAYOUTS: { id: Layout; label: string }[] = [
  { id: "classic", label: "Klassik" }, { id: "split", label: "İki hissə" }, { id: "centered", label: "Mərkəz" },
  { id: "badge", label: "Nişan" }, { id: "diagonal", label: "Diaqonal" },
];

export type IconId = "star" | "gift" | "crown" | "heart" | "cup" | "cart" | "sparkle" | "diamond" | "smile" | "leaf" | "none";
export const ICONS: Record<Exclude<IconId, "none">, string> = {
  star: "M12 2l3 6.9 7.5.6-5.7 4.9 1.8 7.3L12 17.8 5.4 21.7l1.8-7.3L1.5 9.5 9 8.9z",
  gift: "M3 9h18v4H3zM5 13h14v8H5zM12 9v12M12 9C9 3 5 6 8 9M12 9c3-6 7-3 4 0",
  crown: "M3 18L5 7l5 5 2-7 2 7 5-5 2 11z",
  heart: "M12 21C5 15 2 11.5 2 8a5 5 0 0 1 10-1 5 5 0 0 1 10 1c0 3.5-3 7-10 13z",
  cup: "M4 7h13v6a6 6 0 0 1-6 6h-1a6 6 0 0 1-6-6zM17 9h2a2 2 0 0 1 0 4h-2",
  cart: "M2 3h3l2.5 12h11L21 7H6M9 20a1.5 1.5 0 1 0 0 .1M17 20a1.5 1.5 0 1 0 0 .1",
  sparkle: "M12 2l2.2 6.8L21 11l-6.8 2.2L12 20l-2.2-6.8L3 11l6.8-2.2z",
  diamond: "M6 3h12l4 6-10 12L2 9z",
  smile: "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zM8 14s1.5 2 4 2 4-2 4-2M9 9h.01M15 9h.01",
  leaf: "M5 21c0-9 6-15 15-16-1 9-7 15-16 16zM5 21l8-8",
};

/** Hand-picked palettes per category: [color1, color2, accent, text]. */
const PALETTES: Record<CategoryId, string[][]> = {
  funny: [["#FF6B6B", "#FFD93D", "#4D96FF", "#1B1B3A"], ["#00C2FF", "#7B61FF", "#FFE45E", "#FFFFFF"], ["#FF9F1C", "#FF4D6D", "#2EC4B6", "#FFFFFF"]],
  gift: [["#E11D48", "#FB7185", "#FDE047", "#FFFFFF"], ["#7C3AED", "#EC4899", "#FDE68A", "#FFFFFF"], ["#0EA5E9", "#22D3EE", "#F43F5E", "#06283D"]],
  vip: [["#111827", "#4C1D95", "#F5D67B", "#F5D67B"], ["#0F172A", "#1E3A8A", "#FBBF24", "#FFFFFF"], ["#3B0764", "#9D174D", "#FDE68A", "#FFFFFF"]],
  luxury: [["#0B0B0F", "#2A2A35", "#D4A537", "#F5D67B"], ["#111111", "#3D2B00", "#E9C46A", "#F7E3A1"], ["#0D0D0D", "#1F2937", "#C9A227", "#EAD18F"]],
  color3d: [["#F72585", "#7209B7", "#4CC9F0", "#FFFFFF"], ["#06D6A0", "#118AB2", "#FFD166", "#FFFFFF"], ["#FF5F6D", "#FFC371", "#5F0F40", "#FFFFFF"]],
  market: [["#16A34A", "#84CC16", "#FACC15", "#FFFFFF"], ["#0EA5E9", "#22C55E", "#F97316", "#FFFFFF"], ["#F97316", "#FACC15", "#16A34A", "#1C1917"]],
  cafe: [["#3E2723", "#8D6E63", "#FFCC80", "#FFF8E1"], ["#6D4C41", "#D7CCC8", "#FF7043", "#3E2723"], ["#1B4332", "#A68A64", "#F4D35E", "#FFFFFF"]],
  kids: [["#FFB5E8", "#B28DFF", "#FFF5BA", "#3A0CA3"], ["#85E3FF", "#BFFCC6", "#FF9CEE", "#1D3557"], ["#FFD166", "#FF6392", "#5BC0EB", "#2B2D42"]],
  minimal: [["#F8FAFC", "#E2E8F0", "#0F172A", "#0F172A"], ["#111827", "#374151", "#F9FAFB", "#F9FAFB"], ["#FAFAF9", "#D6D3D1", "#EA580C", "#1C1917"]],
};

const FONT_BY: Record<CategoryId, FontId[]> = {
  funny: ["playful", "rounded", "bold"], gift: ["rounded", "playful"], vip: ["elegant", "serif", "bold"], luxury: ["elegant", "serif"],
  color3d: ["bold", "rounded"], market: ["rounded", "bold"], cafe: ["serif", "elegant", "rounded"], kids: ["playful", "rounded"],
  minimal: ["mono", "bold", "rounded"],
};
const BG_BY: Record<CategoryId, Background[]> = {
  funny: ["confetti", "sunburst", "dots", "waves"], gift: ["confetti", "radial", "stripes"], vip: ["radial", "grid", "mesh"],
  luxury: ["grid", "radial", "stripes"], color3d: ["mesh", "waves", "sunburst"], market: ["waves", "dots", "gradient"],
  cafe: ["gradient", "dots", "radial"], kids: ["confetti", "dots", "waves"], minimal: ["gradient", "grid", "stripes"],
};
const ICON_BY: Record<CategoryId, IconId[]> = {
  funny: ["smile", "star", "sparkle"], gift: ["gift", "heart", "star"], vip: ["crown", "diamond", "star"], luxury: ["diamond", "crown"],
  color3d: ["sparkle", "star"], market: ["cart", "leaf", "star"], cafe: ["cup", "heart"], kids: ["heart", "star", "smile"],
  minimal: ["none", "sparkle", "diamond"],
};
const TAGLINES: Record<CategoryId, string[]> = {
  funny: ["Gül, yığ, qazan!", "Hər alış bir təbəssüm", "Bonuslar səni gözləyir"],
  gift: ["Hər alışa hədiyyə", "Sürprizlər burada", "Bonus = hədiyyə"],
  vip: ["Siz xüsusisiniz", "VIP müştəri", "Ən yaxşısı sizin üçün"],
  luxury: ["Exclusive üzv", "Lüks bonuslar", "Qızıl müştəri"],
  color3d: ["Rəngli bonuslar", "Hər gün yeni bonus", "Parlaq alış-veriş"],
  market: ["Təzə, sərfəli, bonuslu", "Hər səbətə bonus", "Alış-veriş qazandırır"],
  cafe: ["Hər fincana bonus", "Dadlı bonuslar", "10-cu qəhvə bizdən"],
  kids: ["Kiçik dostlar üçün", "Oyna, yığ, sevin!", "Ən şən kart"],
  minimal: ["", "Sadə. Sərfəli.", "Bonus kart"],
};

const pick = <T,>(a: T[]): T => a[Math.floor(Math.random() * a.length)];

/** The built-in generator: a new combination every time (palette, background, layout, character, icon, font). */
export function generateLocal(category: CategoryId, previous?: Spec | null): Spec {
  const cat = CATEGORIES.find((c) => c.id === category) ?? CATEGORIES[0];
  for (let tries = 0; tries < 6; tries++) {
    const [c1, c2, accent, text] = pick(PALETTES[cat.id]);
    const flip = Math.random() < 0.35;
    const s: Spec = {
      name: `${cat.label} ${Math.floor(100 + Math.random() * 900)}`,
      category: cat.id,
      background: pick(BG_BY[cat.id]),
      color1: flip ? c2 : c1, color2: flip ? c1 : c2, accent, textColor: text,
      font: pick(FONT_BY[cat.id]),
      layout: pick(LAYOUTS).id,
      icon: pick(ICON_BY[cat.id]),
      title: "BONUS KART",
      tagline: pick(TAGLINES[cat.id]),
      character: pick(cat.chars),
      characterScale: 0.85 + Math.random() * 0.3,
      characterSide: Math.random() < 0.75 ? "right" : "left",
      qrFront: "none",
      qrBack: pick(["left", "center", "right"] as const),
      backNote: "Kassada kartı göstərin — hər alışdan bonus qazanın.",
      source: "local",
    };
    if (!previous || s.character !== previous.character || s.background !== previous.background || s.layout !== previous.layout) return s;
  }
  return generateLocal(category, null);
}

// ---------------------------------------------------------------- drawing

export const CARD_W = 856, CARD_H = 540;
/** 3 mm bleed at 10 units per mm. */
export const BLEED = 30;

const esc = (s: string) => s.replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]!));
const b64 = (s: string) => (typeof window === "undefined" ? Buffer.from(s, "utf-8").toString("base64") : btoa(unescape(encodeURIComponent(s))));
const svgImage = (inner: string, x: number, y: number, size: number, viewBox = "0 0 240 240") =>
  `<image x="${x}" y="${y}" width="${size}" height="${size}" href="data:image/svg+xml;base64,${b64(`<svg xmlns="http://www.w3.org/2000/svg" viewBox="${viewBox}">${inner}</svg>`)}"/>`;

function backgroundLayer(s: Spec, w: number, h: number): string {
  const a = s.accent, t = s.textColor;
  const base = `<defs><linearGradient id="bg" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="${s.color1}"/><stop offset="1" stop-color="${s.color2}"/></linearGradient>
    <radialGradient id="glow" cx="75%" cy="25%" r="70%"><stop offset="0" stop-color="#fff" stop-opacity=".35"/><stop offset="1" stop-color="#fff" stop-opacity="0"/></radialGradient></defs>
    <rect width="${w}" height="${h}" fill="url(#bg)"/>`;
  const op = (v: number) => `opacity="${v}"`;
  let p = "";
  switch (s.background) {
    case "radial": p = `<rect width="${w}" height="${h}" fill="url(#glow)"/>`; break;
    case "stripes": for (let x = -h; x < w; x += 46) p += `<path d="M${x} ${h} L${x + h} 0" stroke="${t}" stroke-width="14" ${op(0.07)}/>`; break;
    case "dots": for (let y = 18; y < h; y += 34) for (let x = 18 + ((y / 34) % 2) * 17; x < w; x += 34) p += `<circle cx="${x}" cy="${y}" r="4" fill="${t}" ${op(0.12)}/>`; break;
    case "waves": for (let i = 0; i < 6; i++) { const y = h * (0.45 + i * 0.1); p += `<path d="M0 ${y} C ${w * 0.25} ${y - 40}, ${w * 0.5} ${y + 40}, ${w} ${y - 10} L ${w} ${h} L 0 ${h} Z" fill="${i % 2 ? a : t}" ${op(0.07)}/>`; } break;
    case "confetti": {
      let seed = 7; const rnd = () => ((seed = (seed * 9301 + 49297) % 233280) / 233280);
      for (let i = 0; i < 60; i++) { const x = rnd() * w, y = rnd() * h, c = [a, t, s.color2][i % 3]; p += `<rect x="${x}" y="${y}" width="12" height="5" rx="2" fill="${c}" ${op(0.45)} transform="rotate(${rnd() * 180} ${x} ${y})"/>`; }
      break;
    }
    case "mesh": p = `<circle cx="${w * 0.2}" cy="${h * 0.9}" r="${h * 0.6}" fill="${a}" ${op(0.35)}/><circle cx="${w * 0.95}" cy="${h * 0.1}" r="${h * 0.55}" fill="${s.color2}" ${op(0.5)}/><circle cx="${w * 0.6}" cy="${h * 0.6}" r="${h * 0.35}" fill="#fff" ${op(0.12)}/>`; break;
    case "sunburst": for (let i = 0; i < 24; i++) { const ang = (i / 24) * Math.PI * 2, ang2 = ang + Math.PI / 24, cx = w * 0.78, cy = h * 0.4, R = w; p += `<path d="M${cx} ${cy} L${cx + R * Math.cos(ang)} ${cy + R * Math.sin(ang)} L${cx + R * Math.cos(ang2)} ${cy + R * Math.sin(ang2)} Z" fill="${t}" ${op(0.06)}/>`; } break;
    case "grid": for (let x = 0; x < w; x += 40) p += `<path d="M${x} 0 V${h}" stroke="${a}" ${op(0.15)}/>`; for (let y = 0; y < h; y += 40) p += `<path d="M0 ${y} H${w}" stroke="${a}" ${op(0.15)}/>`; break;
    default: p = `<rect width="${w}" height="${h}" fill="url(#glow)" ${op(0.5)}/>`;
  }
  return base + p;
}

function icon(s: Spec, x: number, y: number, size: number): string {
  if (s.icon === "none") return "";
  const k = size / 24;
  return `<g transform="translate(${x} ${y}) scale(${k})"><path d="${ICONS[s.icon]}" fill="${s.accent}" stroke="${s.accent}" stroke-width="1.2" stroke-linejoin="round" stroke-linecap="round"/></g>`;
}

/** The character's box: at most 42% of the card's width, so the text always keeps its side. */
const charSize = (s: Spec, w: number, h: number) => Math.min(Math.round(h * 0.82 * s.characterScale), Math.round(w * 0.42));
const hasChar = (s: Spec) => s.character !== "none" && !!(s.character === "ai" ? s.characterSvg : characterById(s.character));

function character(s: Spec, w: number, h: number): string {
  const inner = s.character === "ai" ? s.characterSvg : characterById(s.character)?.svg;
  if (!inner || s.character === "none") return "";
  const size = charSize(s, w, h);
  const x = s.characterSide === "right" ? w - size - 10 : 10;
  const y = h - size + 6;
  return `<ellipse cx="${x + size / 2}" cy="${h - 20}" rx="${size * 0.36}" ry="14" fill="#000" opacity=".12"/>` + svgImage(inner, x, y, size);
}

/** Front of the card (856×540 units, or more with bleed: the picture grows, the text keeps its safe area). */
export function renderFront(s: Spec, d: CardData, opts: { bleed?: boolean; qr?: string | null; textless?: boolean } = {}): string {
  const bl = opts.bleed ? BLEED : 0, W = CARD_W + 2 * bl, H = CARD_H + 2 * bl;
  const f = FONTS.find((x) => x.id === s.font) ?? FONTS[0];
  const t = s.textColor, left = s.characterSide === "left";
  const tx = (left && hasChar(s) ? charSize(s, CARD_W, CARD_H) + 40 : 56) + bl, ty = 56 + bl;
  const qrSize = 150;
  let text = "";
  if (!opts.textless) {
    const title = esc(s.title || "BONUS KART");
    const align = s.layout === "centered" ? `text-anchor="middle"` : "";
    const cx = s.layout === "centered" ? W / 2 : tx;
    text += `<g font-family="${f.stack}" fill="${t}">`;
    if (s.layout === "badge") text += `<rect x="${tx - 18}" y="${ty - 6}" width="${Math.min(470, title.length * 34 + 40)}" height="78" rx="39" fill="${s.accent}" opacity=".95"/>`;
    if (s.layout === "split") text += `<rect x="${bl}" y="${bl + CARD_H * 0.62}" width="${CARD_W}" height="${CARD_H * 0.38}" fill="#000" opacity=".22"/>`;
    if (s.layout === "diagonal") text += `<path d="M${bl} ${bl} H${bl + CARD_W * 0.62} L${bl + CARD_W * 0.42} ${bl + CARD_H} H${bl} Z" fill="#000" opacity=".16"/>`;
    text += `<text x="${cx}" y="${ty + 52}" ${align} font-size="56" font-weight="${f.weight}" letter-spacing="2" ${s.layout === "badge" ? `fill="${pickContrast(s.accent)}"` : ""}>${title}</text>`;
    if (s.tagline) text += `<text x="${cx}" y="${ty + 100}" ${align} font-size="26" opacity=".9">${esc(s.tagline)}</text>`;
    text += icon(s, s.layout === "centered" ? W / 2 - 22 : tx, ty + 120, 44);
    const by = bl + CARD_H - 150;
    text += `<text x="${tx}" y="${by}" font-size="18" letter-spacing="4" opacity=".75">BONUS BALANSI</text>`;
    text += `<text x="${tx}" y="${by + 50}" font-size="48" font-weight="${f.weight}">${esc(d.balance)}</text>`;
    text += `<text x="${tx}" y="${by + 96}" font-size="24" font-weight="600">${esc(d.name)}</text>`;
    text += `<text x="${tx}" y="${by + 126}" font-size="20" letter-spacing="3" opacity=".85">${esc(d.number)}  ·  ${esc(d.validUntil)}</text>`;
    text += `</g>`;
    if (s.qrFront !== "none" && opts.qr) {
      const qx = s.qrFront === "right" ? bl + CARD_W - qrSize - 40 : bl + 40, qy = bl + 40;
      text += `<rect x="${qx - 8}" y="${qy - 8}" width="${qrSize + 16}" height="${qrSize + 16}" rx="14" fill="#fff"/><image x="${qx}" y="${qy}" width="${qrSize}" height="${qrSize}" href="${opts.qr}"/>`;
    }
  }
  const art = `<g transform="translate(${bl} ${bl})">${character(s, CARD_W, CARD_H)}</g>`;
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${W} ${H}" width="${W}" height="${H}">${backgroundLayer(s, W, H)}${art}${text}</svg>`;
}

/** Back: a big QR for the till, the card number and a short note. */
export function renderBack(s: Spec, d: CardData, opts: { bleed?: boolean; qr?: string | null } = {}): string {
  const bl = opts.bleed ? BLEED : 0, W = CARD_W + 2 * bl, H = CARD_H + 2 * bl;
  const f = FONTS.find((x) => x.id === s.font) ?? FONTS[0];
  const q = 280, qy = bl + 70;
  const qx = s.qrBack === "left" ? bl + 60 : s.qrBack === "right" ? bl + CARD_W - q - 60 : bl + (CARD_W - q) / 2;
  const textX = s.qrBack === "left" ? qx + q + 50 : s.qrBack === "right" ? bl + 60 : W / 2;
  const anchor = s.qrBack === "center" ? `text-anchor="middle"` : "";
  const textY = s.qrBack === "center" ? qy + q + 70 : qy + 70;
  const panel = `<rect x="${qx - 16}" y="${qy - 16}" width="${q + 32}" height="${q + 32}" rx="24" fill="#fff"/>`;
  const qr = opts.qr ? `<image x="${qx}" y="${qy}" width="${q}" height="${q}" href="${opts.qr}"/>` : `<rect x="${qx}" y="${qy}" width="${q}" height="${q}" fill="#e5e7eb"/>`;
  const note = s.qrBack === "center" ? "" : wrap(s.backNote, 26).map((l, i) => `<tspan x="${textX}" dy="${i ? 30 : 0}">${esc(l)}</tspan>`).join("");
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${W} ${H}" width="${W}" height="${H}">
    ${backgroundLayer({ ...s, background: s.background === "confetti" ? "dots" : s.background }, W, H)}
    <rect width="${W}" height="${H}" fill="#000" opacity=".18"/>${panel}${qr}
    <g font-family="${f.stack}" fill="${s.textColor}">
      <text x="${textX}" y="${textY}" ${anchor} font-size="40" font-weight="${f.weight}" letter-spacing="4">${esc(d.number)}</text>
      <text x="${textX}" y="${textY + 44}" ${anchor} font-size="24">${esc(d.name)}</text>
      ${note ? `<text x="${textX}" y="${textY + 100}" font-size="22" opacity=".85">${note}</text>` : ""}
      <text x="${textX}" y="${s.qrBack === "center" ? textY + 84 : bl + CARD_H - 50}" ${anchor} font-size="20" opacity=".75">Etibarlıdır: ${esc(d.validUntil)}</text>
    </g></svg>`;
}

function wrap(text: string, n: number): string[] {
  const out: string[] = [];
  let line = "";
  for (const w of text.split(/\s+/)) {
    if ((line + " " + w).trim().length > n) { if (line) out.push(line); line = w; } else line = (line + " " + w).trim();
  }
  if (line) out.push(line);
  return out.slice(0, 5);
}

function pickContrast(hex: string): string {
  const n = parseInt(hex.slice(1), 16), r = n >> 16, g = (n >> 8) & 255, b = n & 255;
  return (r * 299 + g * 587 + b * 114) / 1000 > 150 ? "#111111" : "#ffffff";
}

export const toDataUrl = (svg: string) => `data:image/svg+xml;base64,${b64(svg)}`;

/** Draws an SVG string onto a canvas (for PNG / JPEG / PDF) at the given pixel size. */
export function rasterize(svg: string, width: number, height: number, type: "image/png" | "image/jpeg", quality = 0.95): Promise<string> {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => {
      // nested pictures inside the SVG need a moment after load
      setTimeout(() => {
        const c = document.createElement("canvas");
        c.width = width; c.height = height;
        const g = c.getContext("2d")!;
        if (type === "image/jpeg") { g.fillStyle = "#fff"; g.fillRect(0, 0, width, height); }
        g.drawImage(img, 0, 0, width, height);
        resolve(c.toDataURL(type, quality));
      }, 60);
    };
    img.onerror = () => reject(new Error("Şəkil çəkilmədi"));
    img.src = toDataUrl(svg);
  });
}

export const CHARACTER_LIBRARY = CHARACTERS;
