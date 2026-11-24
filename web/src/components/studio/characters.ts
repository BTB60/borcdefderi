/**
 * Card Studio's own characters: original cartoon figures drawn as SVG (viewBox 0 0 240 240) with gradient shading so
 * they read as 3D. Gradient ids carry the character's id so two of them never clash on one card.
 */
export type Character = { id: string; name: string; tags: string[]; svg: string };

const eyes = (x1: number, x2: number, y: number, r = 11) => `
  <ellipse cx="${x1}" cy="${y}" rx="${r}" ry="${r * 1.15}" fill="#fff"/><ellipse cx="${x2}" cy="${y}" rx="${r}" ry="${r * 1.15}" fill="#fff"/>
  <circle cx="${x1 + 2}" cy="${y + 2}" r="${r * 0.55}" fill="#1f1235"/><circle cx="${x2 + 2}" cy="${y + 2}" r="${r * 0.55}" fill="#1f1235"/>
  <circle cx="${x1 + 4}" cy="${y - 2}" r="${r * 0.2}" fill="#fff"/><circle cx="${x2 + 4}" cy="${y - 2}" r="${r * 0.2}" fill="#fff"/>`;
const shadow = (cx = 120, cy = 222, rx = 70) => `<ellipse cx="${cx}" cy="${cy}" rx="${rx}" ry="10" fill="#000" opacity=".18"/>`;
const shine = (id: string, a: string, b: string) =>
  `<radialGradient id="${id}" cx="35%" cy="30%" r="75%"><stop offset="0" stop-color="${a}"/><stop offset="1" stop-color="${b}"/></radialGradient>`;

export const CHARACTERS: Character[] = [
  {
    id: "bag", name: "Şən çanta", tags: ["shop", "market", "fun"],
    svg: `<defs>${shine("cbag", "#ffd36b", "#f08a00")}${shine("cbagh", "#ff7a59", "#c2410c")}</defs>${shadow()}
      <path d="M86 70 C86 30 154 30 154 70" fill="none" stroke="url(#cbagh)" stroke-width="12" stroke-linecap="round"/>
      <path d="M52 70 H188 L176 210 Q120 222 64 210 Z" fill="url(#cbag)" stroke="#b45309" stroke-width="4"/>
      <path d="M60 78 H180 L178 96 H62 Z" fill="#fff" opacity=".25"/>
      ${eyes(98, 142, 120)}
      <path d="M96 152 Q120 178 144 152" fill="#7c2d12" stroke="#7c2d12" stroke-width="4" stroke-linecap="round"/>
      <path d="M106 160 Q120 170 134 160" fill="#fb7185"/>
      <circle cx="80" cy="146" r="9" fill="#fb7185" opacity=".55"/><circle cx="160" cy="146" r="9" fill="#fb7185" opacity=".55"/>
      <path d="M52 140 Q20 120 28 96" fill="none" stroke="#f08a00" stroke-width="10" stroke-linecap="round"/><circle cx="28" cy="92" r="9" fill="#ffd36b"/>
      <path d="M188 140 Q220 150 214 182" fill="none" stroke="#f08a00" stroke-width="10" stroke-linecap="round"/><circle cx="214" cy="186" r="9" fill="#ffd36b"/>`,
  },
  {
    id: "gift", name: "Hədiyyə qutusu", tags: ["gift", "fun", "kids"],
    svg: `<defs>${shine("cgift", "#ff8fb1", "#db2777")}${shine("cgiftr", "#fde68a", "#eab308")}</defs>${shadow()}
      <rect x="44" y="96" width="152" height="118" rx="16" fill="url(#cgift)" stroke="#9d174d" stroke-width="4"/>
      <rect x="34" y="76" width="172" height="36" rx="12" fill="#f472b6" stroke="#9d174d" stroke-width="4"/>
      <rect x="108" y="76" width="24" height="138" fill="url(#cgiftr)"/>
      <path d="M120 76 C90 40 62 56 80 74 Z M120 76 C150 40 178 56 160 74 Z" fill="url(#cgiftr)" stroke="#a16207" stroke-width="3"/>
      ${eyes(84, 156, 146, 12)}
      <path d="M92 178 Q120 200 148 178" fill="none" stroke="#831843" stroke-width="6" stroke-linecap="round"/>
      <circle cx="66" cy="172" r="9" fill="#fff" opacity=".35"/><circle cx="174" cy="172" r="9" fill="#fff" opacity=".35"/>`,
  },
  {
    id: "crown", name: "VIP tac", tags: ["vip", "luxury"],
    svg: `<defs>${shine("ccrown", "#fff3b0", "#c08a00")}${shine("ccrownj", "#a78bfa", "#5b21b6")}</defs>${shadow(120, 220, 64)}
      <path d="M40 190 L50 82 L90 124 L120 62 L150 124 L190 82 L200 190 Z" fill="url(#ccrown)" stroke="#8a6100" stroke-width="5" stroke-linejoin="round"/>
      <rect x="40" y="180" width="160" height="30" rx="10" fill="#e8b923" stroke="#8a6100" stroke-width="5"/>
      <circle cx="50" cy="80" r="11" fill="url(#ccrownj)"/><circle cx="120" cy="58" r="13" fill="url(#ccrownj)"/><circle cx="190" cy="80" r="11" fill="url(#ccrownj)"/>
      <path d="M86 138 q10 -10 20 0 M134 138 q10 -10 20 0" fill="none" stroke="#3b2a05" stroke-width="5" stroke-linecap="round"/>
      <path d="M100 158 Q120 172 140 158" fill="none" stroke="#3b2a05" stroke-width="5" stroke-linecap="round"/>
      <path d="M150 150 l14 -2 l-4 12 z" fill="#fff" opacity=".7"/>`,
  },
  {
    id: "diamond", name: "Brilyant", tags: ["luxury", "vip", "minimal"],
    svg: `<defs><linearGradient id="cdia" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#e0f7ff"/><stop offset=".5" stop-color="#67e8f9"/><stop offset="1" stop-color="#0e7490"/></linearGradient></defs>${shadow(120, 222, 58)}
      <path d="M60 80 L90 46 H150 L180 80 L120 206 Z" fill="url(#cdia)" stroke="#155e75" stroke-width="5" stroke-linejoin="round"/>
      <path d="M60 80 H180 M90 46 L104 80 L120 206 L136 80 L150 46" fill="none" stroke="#155e75" stroke-width="3" opacity=".6"/>
      ${eyes(102, 138, 112, 9)}
      <path d="M106 138 Q120 150 134 138" fill="none" stroke="#164e63" stroke-width="5" stroke-linecap="round"/>
      <path d="M70 76 l10 -12 l10 12" fill="#fff" opacity=".8"/>`,
  },
  {
    id: "cup", name: "Eynəkli fincan", tags: ["cafe"],
    svg: `<defs>${shine("ccup", "#ffffff", "#d6d3d1")}${shine("ccof", "#a16207", "#451a03")}</defs>${shadow()}
      <path d="M58 86 H168 L158 198 Q113 214 68 198 Z" fill="url(#ccup)" stroke="#78716c" stroke-width="4"/>
      <path d="M168 110 Q206 112 200 146 Q194 178 160 172" fill="none" stroke="#a8a29e" stroke-width="12" stroke-linecap="round"/>
      <ellipse cx="113" cy="86" rx="55" ry="12" fill="url(#ccof)"/>
      <path d="M92 58 q-10 -14 0 -28 M116 58 q-10 -14 0 -28 M140 58 q-10 -14 0 -28" fill="none" stroke="#d6d3d1" stroke-width="5" stroke-linecap="round"/>
      <rect x="70" y="112" width="38" height="24" rx="10" fill="#1f2937"/><rect x="118" y="112" width="38" height="24" rx="10" fill="#1f2937"/>
      <path d="M108 122 H118" stroke="#1f2937" stroke-width="5"/><path d="M76 116 l10 0" stroke="#fff" stroke-width="3" opacity=".6"/>
      <path d="M88 160 Q113 180 138 160" fill="none" stroke="#57534e" stroke-width="6" stroke-linecap="round"/>`,
  },
  {
    id: "bear", name: "Ulduzlu ayı", tags: ["kids", "fun", "gift"],
    svg: `<defs>${shine("cbear", "#e7b98a", "#9a5b2b")}${shine("cstar", "#fff59d", "#f59e0b")}</defs>${shadow()}
      <circle cx="70" cy="62" r="24" fill="url(#cbear)"/><circle cx="170" cy="62" r="24" fill="url(#cbear)"/>
      <circle cx="70" cy="62" r="11" fill="#f9c9a8"/><circle cx="170" cy="62" r="11" fill="#f9c9a8"/>
      <ellipse cx="120" cy="164" rx="64" ry="54" fill="url(#cbear)"/>
      <circle cx="120" cy="104" r="62" fill="url(#cbear)" stroke="#7c4a20" stroke-width="3"/>
      <ellipse cx="120" cy="124" rx="28" ry="20" fill="#f9dcc4"/><ellipse cx="120" cy="114" rx="10" ry="7" fill="#3b1f0e"/>
      ${eyes(96, 144, 92, 9)}
      <path d="M108 132 Q120 142 132 132" fill="none" stroke="#3b1f0e" stroke-width="4" stroke-linecap="round"/>
      <path d="M190 150 l8 18 l20 2 l-15 13 l5 20 l-18 -10 l-18 10 l5 -20 l-15 -13 l20 -2 z" fill="url(#cstar)" stroke="#b45309" stroke-width="3"/>`,
  },
  {
    id: "cart", name: "Qaçan araba", tags: ["market", "shop"],
    svg: `<defs>${shine("ccart", "#93c5fd", "#1d4ed8")}</defs>${shadow(120, 222, 76)}
      <path d="M22 58 H52 L72 168 H186" fill="none" stroke="#1e3a8a" stroke-width="9" stroke-linecap="round" stroke-linejoin="round"/>
      <path d="M58 80 H210 L194 150 H72 Z" fill="url(#ccart)" stroke="#1e3a8a" stroke-width="5" stroke-linejoin="round"/>
      <circle cx="86" cy="198" r="16" fill="#1f2937"/><circle cx="170" cy="198" r="16" fill="#1f2937"/><circle cx="86" cy="198" r="6" fill="#9ca3af"/><circle cx="170" cy="198" r="6" fill="#9ca3af"/>
      ${eyes(112, 160, 108, 10)}
      <path d="M118 132 Q136 146 154 132" fill="none" stroke="#1e3a8a" stroke-width="5" stroke-linecap="round"/>
      <circle cx="96" cy="74" r="14" fill="#ef4444"/><circle cx="124" cy="70" r="14" fill="#22c55e"/><circle cx="150" cy="72" r="13" fill="#f59e0b"/>
      <path d="M0 120 h30 M6 140 h26 M12 100 h22" stroke="#60a5fa" stroke-width="5" stroke-linecap="round" opacity=".7"/>`,
  },
  {
    id: "star", name: "Ulduz dostu", tags: ["fun", "minimal", "vip"],
    svg: `<defs>${shine("cst", "#fff7ae", "#f59e0b")}</defs>${shadow(120, 222, 60)}
      <path d="M120 20 L148 86 L218 92 L164 138 L180 208 L120 172 L60 208 L76 138 L22 92 L92 86 Z" fill="url(#cst)" stroke="#b45309" stroke-width="6" stroke-linejoin="round"/>
      ${eyes(102, 138, 112, 10)}
      <path d="M104 140 Q120 156 136 140" fill="#b45309" stroke="#b45309" stroke-width="4" stroke-linecap="round"/>
      <circle cx="86" cy="134" r="8" fill="#fb7185" opacity=".6"/><circle cx="154" cy="134" r="8" fill="#fb7185" opacity=".6"/>`,
  },
  {
    id: "donut", name: "Pişik donut", tags: ["cafe", "kids", "fun"],
    svg: `<defs>${shine("cdon", "#f5c98b", "#c2791f")}${shine("cicing", "#ffb3d1", "#ec4899")}</defs>${shadow()}
      <path d="M60 66 L74 30 L96 58 Z M180 66 L166 30 L144 58 Z" fill="#ec4899" stroke="#9d174d" stroke-width="3"/>
      <circle cx="120" cy="124" r="88" fill="url(#cdon)" stroke="#92400e" stroke-width="4"/>
      <path d="M40 120 Q46 52 120 44 Q194 52 200 120 Q190 150 170 132 Q150 160 130 138 Q110 162 92 136 Q70 158 52 134 Q42 140 40 120 Z" fill="url(#cicing)"/>
      <circle cx="120" cy="128" r="22" fill="#fde7c7" stroke="#92400e" stroke-width="3"/>
      <path d="M70 84 l8 4 M150 70 l10 -2 M100 66 l4 8 M170 104 l8 6 M64 110 l10 -2" stroke="#fff" stroke-width="5" stroke-linecap="round"/>
      ${eyes(88, 152, 96, 9)}
      <path d="M112 176 Q120 184 128 176" fill="none" stroke="#7c2d12" stroke-width="4" stroke-linecap="round"/>
      <path d="M58 170 l-26 -4 M58 180 l-24 6 M182 170 l26 -4 M182 180 l24 6" stroke="#7c2d12" stroke-width="3" stroke-linecap="round"/>`,
  },
  {
    id: "apple", name: "Gülən alma", tags: ["market", "kids"],
    svg: `<defs>${shine("capp", "#ff8a80", "#b91c1c")}${shine("cleaf", "#86efac", "#15803d")}</defs>${shadow()}
      <path d="M120 66 C72 40 28 82 40 140 C50 196 92 222 120 206 C148 222 190 196 200 140 C212 82 168 40 120 66 Z" fill="url(#capp)" stroke="#7f1d1d" stroke-width="4"/>
      <path d="M120 66 Q122 40 132 26" fill="none" stroke="#713f12" stroke-width="7" stroke-linecap="round"/>
      <path d="M128 44 Q160 18 182 40 Q156 62 128 44 Z" fill="url(#cleaf)"/>
      <path d="M66 96 Q72 78 88 74" fill="none" stroke="#fff" stroke-width="7" stroke-linecap="round" opacity=".6"/>
      ${eyes(96, 144, 120, 12)}
      <path d="M94 158 Q120 186 146 158" fill="#7f1d1d"/><path d="M106 168 Q120 178 134 168" fill="#fda4af"/>`,
  },
  {
    id: "robot", name: "Mini robot", tags: ["minimal", "fun"],
    svg: `<defs>${shine("crob", "#e2e8f0", "#64748b")}${shine("crobs", "#a5f3fc", "#0891b2")}</defs>${shadow(120, 222, 60)}
      <line x1="120" y1="40" x2="120" y2="62" stroke="#475569" stroke-width="6"/><circle cx="120" cy="34" r="10" fill="#f43f5e"/>
      <rect x="54" y="62" width="132" height="104" rx="30" fill="url(#crob)" stroke="#334155" stroke-width="5"/>
      <rect x="70" y="82" width="100" height="56" rx="20" fill="url(#crobs)"/>
      <circle cx="98" cy="110" r="10" fill="#0f172a"/><circle cx="142" cy="110" r="10" fill="#0f172a"/><circle cx="101" cy="106" r="3" fill="#fff"/><circle cx="145" cy="106" r="3" fill="#fff"/>
      <path d="M104 126 Q120 136 136 126" fill="none" stroke="#0f172a" stroke-width="4" stroke-linecap="round"/>
      <rect x="80" y="170" width="80" height="40" rx="14" fill="url(#crob)" stroke="#334155" stroke-width="4"/>
      <circle cx="120" cy="190" r="8" fill="#22c55e"/>`,
  },
  {
    id: "cat", name: "Tacılı pişik", tags: ["vip", "kids", "fun"],
    svg: `<defs>${shine("ccat", "#4b5563", "#111827")}${shine("ccatc", "#fde68a", "#d97706")}</defs>${shadow()}
      <path d="M60 92 L70 34 L104 70 Z M180 92 L170 34 L136 70 Z" fill="url(#ccat)"/>
      <ellipse cx="120" cy="170" rx="66" ry="48" fill="url(#ccat)"/>
      <circle cx="120" cy="112" r="64" fill="url(#ccat)"/>
      <path d="M92 52 L100 30 L112 46 L120 24 L128 46 L140 30 L148 52 Z" fill="url(#ccatc)" stroke="#92400e" stroke-width="3"/>
      <ellipse cx="96" cy="108" rx="13" ry="15" fill="#a3e635"/><ellipse cx="144" cy="108" rx="13" ry="15" fill="#a3e635"/>
      <ellipse cx="96" cy="110" rx="4" ry="11" fill="#111827"/><ellipse cx="144" cy="110" rx="4" ry="11" fill="#111827"/>
      <path d="M114 132 L126 132 L120 140 Z" fill="#f9a8d4"/><path d="M106 146 Q120 156 134 146" fill="none" stroke="#e5e7eb" stroke-width="4" stroke-linecap="round"/>
      <path d="M70 134 l-30 -6 M70 144 l-28 6 M170 134 l30 -6 M170 144 l28 6" stroke="#e5e7eb" stroke-width="3" stroke-linecap="round"/>`,
  },
  {
    id: "snowman", name: "Şən qardan adam", tags: ["newyear"],
    svg: `<defs>${shine("csnow", "#ffffff", "#cbd5e1")}${shine("cscarf", "#f87171", "#b91c1c")}${shine("chat", "#334155", "#0f172a")}</defs>${shadow(120, 224, 64)}
      <circle cx="120" cy="170" r="54" fill="url(#csnow)" stroke="#94a3b8" stroke-width="3"/>
      <circle cx="120" cy="92" r="42" fill="url(#csnow)" stroke="#94a3b8" stroke-width="3"/>
      <rect x="86" y="36" width="68" height="16" rx="4" fill="url(#chat)"/><rect x="96" y="6" width="48" height="36" rx="6" fill="url(#chat)"/>
      <rect x="96" y="30" width="48" height="8" fill="#dc2626"/>
      ${eyes(104, 136, 86, 8)}
      <path d="M120 98 L156 106 L120 110 Z" fill="#f97316"/>
      <path d="M104 116 Q120 126 136 116" fill="none" stroke="#334155" stroke-width="4" stroke-linecap="round"/>
      <path d="M80 128 Q120 146 160 128 L156 140 Q120 156 84 140 Z" fill="url(#cscarf)"/><path d="M140 138 l10 36 l-16 2 z" fill="#dc2626"/>
      <circle cx="120" cy="160" r="5" fill="#334155"/><circle cx="120" cy="182" r="5" fill="#334155"/>
      <path d="M68 160 L30 132 M38 138 l-8 -14 M172 160 L210 132 M202 138 l8 -14" stroke="#78350f" stroke-width="6" stroke-linecap="round"/>`,
  },
  {
    id: "tree", name: "Bayram ağacı", tags: ["newyear"],
    svg: `<defs><linearGradient id="ctree" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#4ade80"/><stop offset="1" stop-color="#15803d"/></linearGradient>${shine("cts", "#fff7ae", "#f59e0b")}</defs>${shadow(120, 224, 60)}
      <rect x="106" y="190" width="28" height="28" rx="4" fill="#92400e"/>
      <path d="M120 40 L190 196 H50 Z" fill="url(#ctree)" stroke="#14532d" stroke-width="4" stroke-linejoin="round"/>
      <path d="M78 140 Q120 160 162 132 M66 172 Q120 196 176 164 M94 100 Q120 116 146 96" fill="none" stroke="#fde047" stroke-width="5" stroke-linecap="round"/>
      <circle cx="96" cy="150" r="8" fill="#ef4444"/><circle cx="148" cy="122" r="8" fill="#3b82f6"/><circle cx="132" cy="176" r="8" fill="#a855f7"/><circle cx="84" cy="184" r="7" fill="#f59e0b"/><circle cx="160" cy="178" r="7" fill="#ef4444"/>
      ${eyes(106, 134, 128, 8)}
      <path d="M110 150 Q120 158 130 150" fill="none" stroke="#14532d" stroke-width="4" stroke-linecap="round"/>
      <path d="M120 14 l8 18 l20 2 l-15 13 l5 20 l-18 -10 l-18 10 l5 -20 l-15 -13 l20 -2 z" fill="url(#cts)" stroke="#b45309" stroke-width="3"/>`,
  },
  {
    id: "ornament", name: "Bayram şarı", tags: ["newyear"],
    svg: `<defs>${shine("corn", "#93c5fd", "#1d4ed8")}${shine("corncap", "#fde68a", "#b45309")}</defs>${shadow(120, 224, 56)}
      <path d="M120 6 V40" stroke="#b45309" stroke-width="4"/>
      <rect x="100" y="36" width="40" height="22" rx="6" fill="url(#corncap)"/>
      <circle cx="120" cy="134" r="80" fill="url(#corn)" stroke="#1e3a8a" stroke-width="4"/>
      <path d="M44 120 Q120 150 196 120" fill="none" stroke="#fde68a" stroke-width="8" opacity=".9"/>
      <path d="M60 170 l8 -8 l8 8 l8 -8 l8 8 l8 -8 l8 8 l8 -8 l8 8 l8 -8 l8 8 l8 -8 l8 8" fill="none" stroke="#fff" stroke-width="4" opacity=".8"/>
      ${eyes(100, 140, 104, 10)}
      <path d="M104 132 Q120 146 136 132" fill="none" stroke="#1e3a8a" stroke-width="5" stroke-linecap="round"/>
      <ellipse cx="82" cy="88" rx="14" ry="22" fill="#fff" opacity=".35" transform="rotate(30 82 88)"/>`,
  },
  {
    id: "semeni", name: "Səməni", tags: ["novruz"],
    svg: `<defs>${shine("cplate", "#fde68a", "#d97706")}<linearGradient id="cgrass" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#86efac"/><stop offset="1" stop-color="#16a34a"/></linearGradient></defs>${shadow(120, 222, 76)}
      <path d="M58 150 C56 90 70 40 76 30 M74 150 C74 80 88 36 96 24 M92 150 C94 90 104 40 112 18 M110 150 C112 80 120 30 124 16 M128 150 C130 86 138 40 146 22 M146 150 C148 90 156 46 166 28 M162 150 C164 100 174 56 182 40" fill="none" stroke="url(#cgrass)" stroke-width="7" stroke-linecap="round"/>
      <path d="M60 104 Q120 92 180 104" fill="none" stroke="#dc2626" stroke-width="10"/>
      <path d="M112 100 q-14 -20 -2 -26 q10 4 8 22 M128 100 q14 -20 2 -26 q-10 4 -8 22" fill="#dc2626"/>
      <ellipse cx="120" cy="164" rx="92" ry="34" fill="url(#cplate)" stroke="#92400e" stroke-width="4"/>
      <ellipse cx="120" cy="152" rx="70" ry="14" fill="#fef3c7" opacity=".5"/>
      ${eyes(98, 142, 166, 9)}
      <path d="M108 184 Q120 192 132 184" fill="none" stroke="#92400e" stroke-width="4" stroke-linecap="round"/>`,
  },
  {
    id: "bonfire", name: "Tonqal", tags: ["novruz"],
    svg: `<defs><radialGradient id="cfire" cx="50%" cy="75%" r="70%"><stop offset="0" stop-color="#fef08a"/><stop offset=".45" stop-color="#fb923c"/><stop offset="1" stop-color="#dc2626"/></radialGradient></defs>${shadow(120, 224, 74)}
      <path d="M48 214 L192 180 M48 180 L192 214" stroke="#78350f" stroke-width="16" stroke-linecap="round"/>
      <path d="M120 22 C150 70 196 96 178 156 C168 190 140 204 120 204 C100 204 72 190 62 156 C46 104 96 92 92 52 C110 70 112 84 120 22 Z" fill="url(#cfire)" stroke="#b91c1c" stroke-width="4"/>
      <path d="M120 96 C134 120 150 134 142 164 C136 182 104 182 98 164 C92 140 112 132 120 96 Z" fill="#fef9c3" opacity=".8"/>
      ${eyes(104, 136, 140, 9)}
      <path d="M108 166 Q120 176 132 166" fill="none" stroke="#7c2d12" stroke-width="4" stroke-linecap="round"/>
      <circle cx="60" cy="60" r="4" fill="#fb923c"/><circle cx="184" cy="48" r="3" fill="#fde047"/><circle cx="170" cy="86" r="3" fill="#fb923c"/>`,
  },
  {
    id: "pastry", name: "Şəkərbura", tags: ["novruz", "cafe"],
    svg: `<defs>${shine("cdough", "#fff1c1", "#e2a64b")}</defs>${shadow(120, 222, 80)}
      <path d="M24 150 C24 92 70 62 120 62 C170 62 216 92 216 150 Q120 176 24 150 Z" fill="url(#cdough)" stroke="#b45309" stroke-width="4"/>
      <path d="M44 122 l12 -10 l12 10 l12 -10 l12 10 l12 -10 l12 10 l12 -10 l12 10 l12 -10 l12 10 l12 -10 l12 10" fill="none" stroke="#b45309" stroke-width="4" stroke-linejoin="round"/>
      <path d="M70 100 l10 -8 M100 90 l10 -8 M130 90 l10 -8 M160 100 l10 -8" stroke="#c2410c" stroke-width="3" stroke-linecap="round"/>
      ${eyes(98, 142, 138, 9)}
      <path d="M108 158 Q120 166 132 158" fill="none" stroke="#92400e" stroke-width="4" stroke-linecap="round"/>
      <circle cx="78" cy="152" r="8" fill="#fb7185" opacity=".5"/><circle cx="162" cy="152" r="8" fill="#fb7185" opacity=".5"/>`,
  },
];

export const characterById = (id?: string | null) => CHARACTERS.find((c) => c.id === id) ?? null;
