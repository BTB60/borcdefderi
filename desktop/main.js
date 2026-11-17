// HesabPro Kassa — Windows app around the HesabPro web till (/satis).
// Adds what a browser can't: silent receipt printing, selling without internet (the site is kept on disk and
// sales are queued and sent later) and a full-screen till that opens with Windows.
const { app, BrowserWindow, ipcMain, protocol, net, shell, Menu, dialog, screen } = require("electron");
const { execFile } = require("child_process");
const path = require("path");
const fs = require("fs");
const crypto = require("crypto");

const APP_URL = (process.env.HESABPRO_URL || "https://hesabpro.cloud").replace(/\/$/, "");
const ORIGIN = new URL(APP_URL).origin;
const DATA = app.getPath("userData");
const CACHE_DIR = path.join(DATA, "site-cache");
const SETTINGS_FILE = path.join(DATA, "settings.json");
const QUEUE_FILE = path.join(DATA, "offline-sales.json");

let win = null;

// ---------- small JSON stores ----------

function readJson(file, fallback) {
  try {
    return JSON.parse(fs.readFileSync(file, "utf8"));
  } catch {
    return fallback;
  }
}

function writeJson(file, value) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const tmp = file + ".tmp";
  fs.writeFileSync(tmp, JSON.stringify(value));
  fs.renameSync(tmp, file);
}

const settings = () => readJson(SETTINGS_FILE, {});
const saveSettings = (patch) => writeJson(SETTINGS_FILE, { ...settings(), ...patch });

// ---------- the site kept on disk (so the till opens without internet) ----------

function cacheKey(request) {
  // Next.js asks for the same URL as HTML or as RSC data: keep them apart
  const kind = request.headers.get("rsc") ? "rsc" : "doc";
  return crypto.createHash("sha1").update(kind + " " + request.url).digest("hex");
}

async function fromNetwork(request) {
  return net.fetch(request, { bypassCustomProtocolHandlers: true });
}

async function siteHandler(request) {
  const url = new URL(request.url);
  if (url.origin !== ORIGIN || request.method !== "GET") return fromNetwork(request);
  const key = cacheKey(request);
  const body = path.join(CACHE_DIR, key + ".bin");
  const meta = path.join(CACHE_DIR, key + ".json");
  try {
    const res = await fromNetwork(request);
    if (res.ok) {
      const buf = Buffer.from(await res.arrayBuffer());
      try {
        fs.mkdirSync(CACHE_DIR, { recursive: true });
        fs.writeFileSync(body, buf);
        writeJson(meta, { type: res.headers.get("content-type") || "application/octet-stream" });
      } catch { /* disk full etc.: still serve the page */ }
      return new Response(buf, { status: res.status, headers: res.headers });
    }
    return res;
  } catch {
    // offline: the copy saved the last time, or for a page the till itself
    let file = body, info = readJson(meta, null);
    if (!info && request.headers.get("accept")?.includes("text/html")) {
      const k = crypto.createHash("sha1").update("doc " + APP_URL + "/satis").digest("hex");
      file = path.join(CACHE_DIR, k + ".bin");
      info = readJson(path.join(CACHE_DIR, k + ".json"), null);
    }
    if (info && fs.existsSync(file)) {
      return new Response(fs.readFileSync(file), { status: 200, headers: { "content-type": info.type } });
    }
    if (request.headers.get("accept")?.includes("text/html")) {
      const page = fs.readFileSync(path.join(__dirname, "offline.html"));
      return new Response(page, { status: 200, headers: { "content-type": "text/html; charset=utf-8" } });
    }
    return Response.error();
  }
}

// ---------- daily backup of the sales not sent yet ----------
// Documents\HesabPro Kassa\ehtiyat\satislar-YYYY-MM-DD.json (last 30 days), so nothing is lost if this
// computer's app data is damaged before the internet comes back.

function backupQueue() {
  try {
    const items = queue();
    const dir = path.join(app.getPath("documents"), "HesabPro Kassa", "ehtiyat");
    fs.mkdirSync(dir, { recursive: true });
    if (items.length > 0) {
      const day = new Date().toISOString().slice(0, 10);
      // the sign-in token stays out of the backup file
      const sales = items.map(({ token: _t, ...rest }) => rest);
      writeJson(path.join(dir, `satislar-${day}.json`), { savedAt: new Date().toISOString(), count: sales.length, sales });
    }
    const cutoff = Date.now() - 30 * 86_400_000;
    for (const f of fs.readdirSync(dir)) {
      const full = path.join(dir, f);
      if (f.startsWith("satislar-") && fs.statSync(full).mtimeMs < cutoff) fs.rmSync(full, { force: true });
    }
  } catch { /* backup is best effort */ }
}

// ---------- cash drawer ----------
// Most receipt printers open the drawer plugged into them on the ESC/POS "pulse" command. Windows has no raw
// print API in Electron, so a tiny PowerShell script sends the bytes to the printer through winspool.

const DRAWER_PS = `
param([string]$Printer)
$src = @"
using System; using System.Runtime.InteropServices;
public class RawPrint {
  [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)] public class DOCINFO { public string pDocName; public string pOutputFile; public string pDataType; }
  [DllImport("winspool.drv", CharSet=CharSet.Unicode, SetLastError=true)] public static extern bool OpenPrinter(string n, out IntPtr h, IntPtr d);
  [DllImport("winspool.drv", SetLastError=true)] public static extern bool ClosePrinter(IntPtr h);
  [DllImport("winspool.drv", CharSet=CharSet.Unicode, SetLastError=true)] public static extern bool StartDocPrinter(IntPtr h, int l, DOCINFO d);
  [DllImport("winspool.drv", SetLastError=true)] public static extern bool EndDocPrinter(IntPtr h);
  [DllImport("winspool.drv", SetLastError=true)] public static extern bool StartPagePrinter(IntPtr h);
  [DllImport("winspool.drv", SetLastError=true)] public static extern bool EndPagePrinter(IntPtr h);
  [DllImport("winspool.drv", SetLastError=true)] public static extern bool WritePrinter(IntPtr h, byte[] b, int c, out int w);
  public static bool Send(string printer, byte[] data) {
    IntPtr h; if (!OpenPrinter(printer, out h, IntPtr.Zero)) return false;
    var di = new DOCINFO { pDocName = "HesabPro drawer", pDataType = "RAW" };
    bool ok = StartDocPrinter(h, 1, di) && StartPagePrinter(h);
    int w = 0; if (ok) ok = WritePrinter(h, data, data.Length, out w);
    EndPagePrinter(h); EndDocPrinter(h); ClosePrinter(h); return ok;
  }
}
"@
Add-Type -TypeDefinition $src
if (-not $Printer) { $Printer = (Get-CimInstance Win32_Printer | Where-Object Default).Name }
$bytes = [byte[]](0x1B, 0x70, 0x00, 0x19, 0xFA)
if ([RawPrint]::Send($Printer, $bytes)) { "ok" } else { "fail" }
`;

function openDrawer() {
  return new Promise((resolve) => {
    const script = path.join(DATA, "drawer.ps1");
    try { fs.writeFileSync(script, DRAWER_PS); } catch { return resolve(false); }
    const args = ["-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script];
    const printer = settings().printer;
    if (printer) args.push("-Printer", printer);
    execFile("powershell.exe", args, { windowsHide: true, timeout: 15000 }, (err, out) => resolve(!err && String(out).includes("ok")));
  });
}

// ---------- customer display (second monitor) ----------

let display = null;
function openCustomerDisplay() {
  if (display && !display.isDestroyed()) {
    display.focus();
    return true;
  }
  const main = screen.getPrimaryDisplay();
  const other = screen.getAllDisplays().find((d) => d.id !== main.id);
  const area = (other || main).bounds;
  display = new BrowserWindow({
    x: area.x, y: area.y, width: other ? area.width : 1024, height: other ? area.height : 700,
    title: "HesabPro — Müştəri ekranı",
    icon: path.join(__dirname, "build", "icon.png"),
    backgroundColor: "#101C35",
    autoHideMenuBar: true,
    fullscreen: !!other,
    webPreferences: { contextIsolation: true, sandbox: true },
  });
  display.setMenu(null);
  display.loadURL(APP_URL + "/musteri-ekrani");
  display.on("closed", () => { display = null; });
  return !!other;
}

// ---------- app updates ----------
// A new version downloads by itself in the background; when it is ready the till shows a "Yenilə" button
// (state goes to the page through "update-status"). Checked at start and every 4 hours.

let updater = null;
let updateStatus = { state: "idle", version: null, percent: 0 };
function setUpdate(next) {
  updateStatus = { ...updateStatus, ...next };
  win?.webContents.send("update-status", updateStatus);
}
function startUpdater() {
  try {
    ({ autoUpdater: updater } = require("electron-updater"));
  } catch {
    return; // not packaged (development)
  }
  updater.autoDownload = true;
  updater.autoInstallOnAppQuit = true;
  updater.on("update-available", (i) => setUpdate({ state: "downloading", version: i.version, percent: 0 }));
  updater.on("download-progress", (p) => setUpdate({ state: "downloading", percent: Math.round(p.percent) }));
  updater.on("update-downloaded", (i) => setUpdate({ state: "ready", version: i.version, percent: 100 }));
  updater.on("error", () => { if (updateStatus.state !== "ready") setUpdate({ state: "idle" }); });
  const check = () => updater.checkForUpdates().catch(() => undefined);
  check();
  setInterval(check, 4 * 60 * 60 * 1000);
}

// ---------- sales made without internet ----------

const queue = () => readJson(QUEUE_FILE, []);
const pendingCount = () => queue().length;
const notifyPending = () => win?.webContents.send("pending-changed", pendingCount());

let syncing = false;
async function sync() {
  if (syncing) return 0;
  syncing = true;
  let sent = 0;
  try {
    for (const item of queue()) {
      let res;
      try {
        res = await net.fetch(item.apiUrl + "/api/sales", {
          method: "POST",
          headers: { "Content-Type": "application/json", Authorization: "Bearer " + item.token },
          body: JSON.stringify(item.payload),
        });
      } catch {
        break; // still offline
      }
      const rest = queue().filter((q) => q.id !== item.id);
      if (res.ok) {
        writeJson(QUEUE_FILE, rest);
        sent++;
      } else if (res.status === 503 || res.status === 502) {
        break; // server waking up: try again later
      } else {
        let message = "Server qəbul etmədi (" + res.status + ")";
        try { message = (await res.json()).message || message; } catch { /* not JSON */ }
        writeJson(QUEUE_FILE, [...rest, { ...item, error: message }].sort((a, b) => a.savedAt.localeCompare(b.savedAt)));
        if (res.status !== 401) continue;
        break; // signed out: wait for the next sign-in
      }
    }
  } finally {
    syncing = false;
    notifyPending();
  }
  return sent;
}

// ---------- printing ----------

/** Prints HTML silently: receipts to the receipt printer; labels to the label printer at the label's size. */
async function printHtml(html, opts = {}) {
  const printer = opts.printer ?? (settings().printer || "");
  const pw = new BrowserWindow({ show: false, webPreferences: { sandbox: true } });
  try {
    await pw.loadURL("data:text/html;charset=utf-8," + encodeURIComponent(html));
    return await new Promise((resolve) => {
      pw.webContents.print(
        { silent: true, deviceName: printer, printBackground: true, margins: { marginType: "none" },
          ...(opts.pageSize ? { pageSize: opts.pageSize } : {}) },
        (ok, reason) => {
          if (!ok) console.error("print failed:", reason);
          resolve(ok);
        },
      );
    });
  } finally {
    setTimeout(() => pw.destroy(), 2000);
  }
}

// ---------- window ----------

function createWindow() {
  win = new BrowserWindow({
    width: 1366,
    height: 860,
    minWidth: 1024,
    minHeight: 640,
    title: "HesabPro Kassa",
    icon: path.join(__dirname, "build", "icon.png"),
    backgroundColor: "#f5f7fb",
    autoHideMenuBar: true,
    show: false,
    webPreferences: { preload: path.join(__dirname, "preload.js"), contextIsolation: true, sandbox: true },
  });
  win.once("ready-to-show", () => {
    win.maximize();
    win.show();
  });
  // the till stays on the site; anything else (WhatsApp, help links) opens in the normal browser
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (!url.startsWith(ORIGIN)) shell.openExternal(url);
    return { action: "deny" };
  });
  win.webContents.on("will-navigate", (e, url) => {
    if (!url.startsWith(ORIGIN) && !url.startsWith("file:")) {
      e.preventDefault();
      shell.openExternal(url);
    }
  });
  win.loadURL(APP_URL + "/satis");

  Menu.setApplicationMenu(Menu.buildFromTemplate([
    {
      label: "Kassa",
      submenu: [
        { label: "Yenilə", accelerator: "F5", click: () => win.webContents.reload() },
        { label: "Tam ekran", accelerator: "F11", click: () => win.setFullScreen(!win.isFullScreen()) },
        {
          label: "Windows açılanda kassanı aç",
          type: "checkbox",
          checked: app.getLoginItemSettings().openAtLogin,
          click: (item) => app.setLoginItemSettings({ openAtLogin: item.checked }),
        },
        { type: "separator" },
        { label: "Proqram haqqında", click: () => dialog.showMessageBox(win, { title: "HesabPro Kassa", message: "HesabPro Kassa " + app.getVersion(), detail: APP_URL }) },
        { role: "quit", label: "Çıxış" },
      ],
    },
  ]));
}

// ---------- start ----------

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on("second-instance", () => {
    if (win) {
      if (win.isMinimized()) win.restore();
      win.focus();
    }
  });

  ipcMain.on("app-version", (e) => { e.returnValue = app.getVersion(); });
  ipcMain.handle("print-html", (_e, html) => printHtml(String(html)));
  ipcMain.handle("get-printers", async () => (await win.webContents.getPrintersAsync()).map((p) => ({ name: p.name, isDefault: !!p.isDefault })));
  ipcMain.handle("get-printer", () => settings().printer || null);
  ipcMain.handle("set-printer", (_e, name) => saveSettings({ printer: name || null }));
  ipcMain.handle("queue-sale", (_e, sale) => {
    writeJson(QUEUE_FILE, [...queue(), { id: crypto.randomUUID(), savedAt: new Date().toISOString(), ...sale }]);
    notifyPending();
  });
  ipcMain.handle("pending", () => ({
    count: pendingCount(),
    failed: queue().filter((q) => q.error).map((q) => ({ total: q.total, error: q.error, savedAt: q.savedAt })),
  }));
  ipcMain.handle("sync-now", () => sync());
  ipcMain.handle("update-status", () => updateStatus);
  ipcMain.handle("get-auto-start", () => app.getLoginItemSettings().openAtLogin);
  ipcMain.handle("set-auto-start", (_e, on) => { app.setLoginItemSettings({ openAtLogin: !!on }); return !!on; });
  ipcMain.handle("open-drawer", () => openDrawer());
  ipcMain.handle("get-drawer-auto", () => !!settings().drawerAuto);
  ipcMain.handle("set-drawer-auto", (_e, on) => { saveSettings({ drawerAuto: !!on }); return !!on; });
  ipcMain.handle("open-customer-display", () => openCustomerDisplay());
  ipcMain.handle("get-label-printer", () => settings().labelPrinter || null);
  ipcMain.handle("set-label-printer", (_e, name) => saveSettings({ labelPrinter: name || null }));
  ipcMain.handle("print-labels", (_e, job) => {
    const w = Math.round(Number(job?.widthMm) * 1000), h = Math.round(Number(job?.heightMm) * 1000);
    if (!(w > 0 && h > 0)) return false;
    return printHtml(String(job.html), { printer: settings().labelPrinter || "", pageSize: { width: w, height: h } });
  });
  ipcMain.handle("install-update", () => {
    if (updateStatus.state === "ready" && updater) {
      // closes the app, installs the new version quietly and opens it again
      setImmediate(() => updater.quitAndInstall(true, true));
      return true;
    }
    return false;
  });

  app.whenReady().then(() => {
    protocol.handle("https", siteHandler);
    createWindow();
    // first start: the till opens with Windows (can be switched off on the till screen)
    if (!settings().autoStartSet) {
      try { app.setLoginItemSettings({ openAtLogin: true }); } catch { /* ignore */ }
      saveSettings({ autoStartSet: true });
    }
    setInterval(sync, 30_000);
    setTimeout(backupQueue, 60_000);
    setInterval(backupQueue, 60 * 60 * 1000);
    setTimeout(sync, 5_000);
    // the Microsoft Store version is updated by the Store itself
    if (!process.windowsStore) startUpdater();
  });
  app.on("window-all-closed", () => app.quit());
}
