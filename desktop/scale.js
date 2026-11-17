// Kabellə qoşulan tərəzi (RS-232 / USB-COM): reads the weight from the scale for the till.
// The COM port is read by Windows' own PowerShell (.NET SerialPort), so the app needs no native module.
// The bytes come here as hex and are understood by the protocol the store picked in Parametrlər.
const { spawn, execFile } = require("child_process");
const fs = require("fs");
const path = require("path");

/**
 * Models / protocols. `req` = bytes sent before each reading ("|" = short pause between parts), "" = the scale
 * sends on its own. `format` = data bits, parity, stop bits.
 */
const PROTOCOLS = [
  { id: "auto", label: "Avtomatik — tərəzi çəkini özü göndərir (CAS davamlı rejim, ACOM, Mercury, Shtrih, Vesta, Çin tərəziləri)", req: "", baud: 9600, format: "8N1", parse: "text" },
  { id: "cas", label: "CAS ECR protokolu (PD-II, ER Plus, ER Jr, AP-1, PR-II, DB-II)", req: "05|11", baud: 9600, format: "8N1", parse: "text" },
  { id: "mettler", label: "Mettler Toledo MT-SICS (Ariva, BBA, ICS, PS)", req: "53490D0A", baud: 9600, format: "8N1", parse: "text" },
  { id: "toledo8217", label: "Toledo 8217 — skaner-tərəzilər (Datalogic Magellan, Zebex, Honeywell, Mettler 8217)", req: "57", baud: 9600, format: "7E1", parse: "toledo" },
  { id: "ohaus", label: "Ohaus (IP sorğusu)", req: "49500D0A", baud: 9600, format: "8N1", parse: "text" },
  { id: "massak", label: "Massa-K (VT, MK, TB — “E” sorğusu)", req: "45", baud: 4800, format: "8E1", parse: "massak" },
  { id: "custom", label: "Digər — öz sorğu əmrim", req: null, baud: 9600, format: "8N1", parse: "text" },
];

const protocolOf = (id) => PROTOCOLS.find((p) => p.id === id) || PROTOCOLS[0];

/** "\r\n", "\x05", "<ENQ>" written by hand → hex for the reader. */
function customToHex(text) {
  const names = { NUL: 0, SOH: 1, STX: 2, ETX: 3, EOT: 4, ENQ: 5, ACK: 6, CR: 13, LF: 10, DC1: 17, ESC: 27 };
  const bytes = [];
  const s = String(text || "");
  for (let i = 0; i < s.length; i++) {
    const c = s[i];
    if (c === "\\" && s[i + 1] === "r") { bytes.push(13); i++; }
    else if (c === "\\" && s[i + 1] === "n") { bytes.push(10); i++; }
    else if (c === "\\" && s[i + 1] === "x" && /^[0-9a-f]{2}$/i.test(s.slice(i + 2, i + 4))) { bytes.push(parseInt(s.slice(i + 2, i + 4), 16)); i += 3; }
    else if (c === "<" && s.indexOf(">", i) > i && names[s.slice(i + 1, s.indexOf(">", i)).toUpperCase()] !== undefined) {
      const end = s.indexOf(">", i);
      bytes.push(names[s.slice(i + 1, end).toUpperCase()]);
      i = end;
    } else bytes.push(c.charCodeAt(0) & 0xff);
  }
  return bytes.map((b) => b.toString(16).padStart(2, "0")).join("");
}

function toKg(value, unit, cfgUnit, hasPoint) {
  const u = (cfgUnit && cfgUnit !== "auto" ? cfgUnit : unit || (hasPoint ? "kg" : "g")).toLowerCase();
  if (u === "g") return value / 1000;
  if (u === "lb") return value * 0.45359237;
  if (u === "oz") return value * 0.028349523;
  return value;
}

/** One text line from a scale → { kg, stable } or null. */
function parseText(line, cfgUnit) {
  const raw = String(line);
  if (/OL|OVER|ERR/i.test(raw)) return null;
  // CAS ECR: SOH STX <S|U|F> sign weight unit
  const cas = raw.indexOf("\x01\x02");
  let stable = true;
  if (cas >= 0) {
    const st = raw[cas + 2];
    if (st === "F") return null;
    stable = st !== "U";
  }
  const text = raw.replace(/[\x00-\x1f\x7f-\xff]/g, " ");
  if (/(^|[\s,])(US|D|M|MO)([\s,]|$)/.test(text)) stable = false;
  const re = /([-+]?)\s*(\d+(?:[.,]\d+)?)\s*(kg|g|lb|oz)?(?![a-z])/gi;
  const found = [];
  for (let m; (m = re.exec(text));) found.push({ sign: m[1], num: m[2], unit: m[3] ? m[3].toLowerCase() : null });
  if (!found.length) return null;
  // the number with a unit wins (a scale may also send a device id or a tare); otherwise the longest number
  const withUnit = found.filter((c) => c.unit);
  const best = withUnit.length ? withUnit[withUnit.length - 1] : found.reduce((a, c) => (c.num.length >= a.num.length ? c : a));
  const value = Number(best.num.replace(",", "."));
  if (!Number.isFinite(value)) return null;
  const kg = toKg(value, best.unit, cfgUnit, /[.,]/.test(best.num)) * (best.sign === "-" ? -1 : 1);
  return { kg: Math.round(kg * 1000) / 1000, stable };
}

/** Toledo 8217: STX + 5 digits (grams, or a number with a point) + CR; "?" + status = still moving. */
function parseToledo(line, cfgUnit) {
  const s = String(line).replace(/[\x00-\x1f]/g, "");
  if (s.includes("?")) return { kg: null, stable: false };
  const m = /(\d+(?:\.\d+)?)/.exec(s);
  if (!m) return null;
  const hasPoint = m[1].includes(".");
  const v = Number(m[1]);
  const kg = cfgUnit && cfgUnit !== "auto" ? toKg(v, null, cfgUnit, hasPoint) : hasPoint ? v : v / 1000;
  return { kg: Math.round(kg * 1000) / 1000, stable: true };
}

/** Massa-K: two bytes, low first, weight in grams (signed). */
function parseMassaK(bytes) {
  if (bytes.length < 2) return null;
  const b = bytes.slice(-2);
  let g = b[0] | (b[1] << 8);
  if (g & 0x8000) g -= 0x10000;
  return { kg: g / 1000, stable: true };
}

/** Feeds bytes in, returns the newest reading (or null) and keeps any half line for next time. */
function createParser(cfg) {
  const proto = protocolOf(cfg.protocol);
  let buf = "";
  return (bytes) => {
    if (proto.parse === "massak") return parseMassaK(bytes);
    buf += Buffer.from(bytes).toString("latin1");
    const parts = buf.split(/[\r\n\x03\x04]+/);
    buf = parts.pop() || "";
    if (buf.length > 200) buf = buf.slice(-200);
    let last = null;
    for (const p of parts) {
      const r = proto.parse === "toledo" ? parseToledo(p, cfg.unit) : parseText(p, cfg.unit);
      if (r) last = r;
    }
    return last;
  };
}

const READER_PS = String.raw`param([string]$Port, [int]$Baud = 9600, [int]$Bits = 8, [string]$Parity = 'None', [string]$Stop = 'One', [string]$Req = '', [int]$Every = 300, [int]$Parent = 0)
try {
  $p = New-Object System.IO.Ports.SerialPort $Port, $Baud, ([System.IO.Ports.Parity]$Parity), $Bits, ([System.IO.Ports.StopBits]$Stop)
  $p.ReadTimeout = 200; $p.WriteTimeout = 500; $p.DtrEnable = $true; $p.RtsEnable = $true
  $p.Open()
} catch { [Console]::Out.WriteLine('ERR ' + $_.Exception.Message); [Console]::Out.Flush(); exit 2 }
[Console]::Out.WriteLine('OPEN'); [Console]::Out.Flush()
$chunks = New-Object System.Collections.ArrayList
foreach ($h in ($Req -split '\|')) {
  if ($h.Length -ge 2) {
    $b = New-Object byte[] ([int]($h.Length / 2))
    for ($i = 0; $i -lt $b.Length; $i++) { $b[$i] = [Convert]::ToByte($h.Substring($i * 2, 2), 16) }
    [void]$chunks.Add($b)
  }
}
$n = 0
while ($true) {
  try {
    foreach ($c in $chunks) { $p.Write($c, 0, $c.Length); Start-Sleep -Milliseconds 80 }
    Start-Sleep -Milliseconds $Every
    $k = $p.BytesToRead
    if ($k -gt 0) {
      $d = New-Object byte[] $k
      [void]$p.Read($d, 0, $k)
      [Console]::Out.WriteLine('D ' + [BitConverter]::ToString($d)); [Console]::Out.Flush()
    }
  } catch { [Console]::Out.WriteLine('ERR ' + $_.Exception.Message); [Console]::Out.Flush(); Start-Sleep -Milliseconds 1000 }
  $n++
  if ($Parent -gt 0 -and $n % 10 -eq 0 -and -not (Get-Process -Id $Parent -ErrorAction SilentlyContinue)) { $p.Close(); exit 0 }
}
`;

const PARITY = { N: "None", E: "Even", O: "Odd" };
let child = null;

function stopScale() {
  if (child) {
    try { child.kill(); } catch { /* ignore */ }
    child = null;
  }
}

/** Starts reading the scale; onReading gets { kg, stable } or { error }. */
function startScale(cfg, dataDir, onReading) {
  stopScale();
  if (!cfg || !cfg.port) return;
  const proto = protocolOf(cfg.protocol);
  const format = String(cfg.format || proto.format);
  const req = proto.id === "custom" ? customToHex(cfg.command) : proto.req;
  const script = path.join(dataDir, "scale.ps1");
  try { fs.writeFileSync(script, READER_PS); } catch { onReading({ error: "Proqram faylı yazıla bilmədi" }); return; }
  const args = ["-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script,
    "-Port", String(cfg.port), "-Baud", String(Number(cfg.baud) || proto.baud),
    "-Bits", format[0] === "7" ? "7" : "8", "-Parity", PARITY[format[1]] || "None", "-Stop", format[2] === "2" ? "Two" : "One",
    "-Every", String(Number(cfg.every) || 300), "-Parent", String(process.pid)];
  if (req) args.push("-Req", req);
  const parse = createParser(cfg);
  const me = spawn("powershell.exe", args, { windowsHide: true });
  child = me;
  let rest = "";
  let open = false;
  me.stdout.on("data", (chunk) => {
    rest += chunk.toString();
    const lines = rest.split(/\r?\n/);
    rest = lines.pop() || "";
    for (const line of lines) {
      if (line.startsWith("OPEN")) { open = true; onReading({ kg: null, stable: false, open: true }); }
      else if (line.startsWith("ERR ")) {
        onReading({ error: open ? "Tərəzidən oxunmadı — kabeli yoxlayın" : `${cfg.port} portu açılmadı — kabeli, port nömrəsini yoxlayın və ya başqa proqram bu portu tutub` });
      }
      else if (line.startsWith("D ")) {
        const bytes = line.slice(2).trim().split("-").map((h) => parseInt(h, 16)).filter((n) => Number.isFinite(n));
        const r = parse(bytes);
        if (r) onReading(r);
      }
    }
  });
  me.on("exit", () => {
    if (child === me) {
      child = null;
      if (open) onReading({ error: "Tərəzi ilə əlaqə kəsildi" });
    }
  });
}

/** COM ports Windows sees (USB-COM adapters included). */
function listPorts() {
  return new Promise((resolve) => {
    execFile("powershell.exe", ["-NoProfile", "-NonInteractive", "-Command", "[System.IO.Ports.SerialPort]::GetPortNames() -join ','"],
      { windowsHide: true, timeout: 10000 }, (err, out) => resolve(err ? [] : [...new Set(String(out).trim().split(",").filter(Boolean))].sort()));
  });
}

module.exports = { PROTOCOLS, startScale, stopScale, listPorts, createParser, customToHex, parseText, parseToledo };
