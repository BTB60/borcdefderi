// HesabPro Kassa — Windows app around the HesabPro web till (/satis).
// Adds what a browser can't: silent receipt printing, selling without internet (the site is kept on disk and
// sales are queued and sent later) and a full-screen till that opens with Windows.
const { app, BrowserWindow, ipcMain, protocol, net, shell, Menu, dialog } = require("electron");
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

async function printHtml(html) {
  const printer = settings().printer || "";
  const pw = new BrowserWindow({ show: false, webPreferences: { sandbox: true } });
  try {
    await pw.loadURL("data:text/html;charset=utf-8," + encodeURIComponent(html));
    return await new Promise((resolve) => {
      pw.webContents.print(
        { silent: true, deviceName: printer, printBackground: true, margins: { marginType: "none" } },
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

  app.whenReady().then(() => {
    protocol.handle("https", siteHandler);
    createWindow();
    setInterval(sync, 30_000);
    setTimeout(sync, 5_000);
    try {
      const { autoUpdater } = require("electron-updater");
      autoUpdater.checkForUpdatesAndNotify().catch(() => undefined);
    } catch { /* not packaged */ }
  });
  app.on("window-all-closed", () => app.quit());
}
