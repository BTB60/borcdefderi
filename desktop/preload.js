// Bridge between the HesabPro web till and the Windows app. The page sees it as window.hesabDesktop.
const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("hesabDesktop", {
  version: ipcRenderer.sendSync("app-version"),
  printHtml: (html) => ipcRenderer.invoke("print-html", html),
  getPrinters: () => ipcRenderer.invoke("get-printers"),
  getPrinter: () => ipcRenderer.invoke("get-printer"),
  setPrinter: (name) => ipcRenderer.invoke("set-printer", name),
  queueSale: (sale) => ipcRenderer.invoke("queue-sale", sale),
  pending: () => ipcRenderer.invoke("pending"),
  syncNow: () => ipcRenderer.invoke("sync-now"),
  updateStatus: () => ipcRenderer.invoke("update-status"),
  installUpdate: () => ipcRenderer.invoke("install-update"),
  onUpdate: (cb) => {
    const listener = (_e, status) => cb(status);
    ipcRenderer.on("update-status", listener);
    return () => ipcRenderer.removeListener("update-status", listener);
  },
  onPendingChange: (cb) => {
    const listener = (_e, count) => cb(count);
    ipcRenderer.on("pending-changed", listener);
    return () => ipcRenderer.removeListener("pending-changed", listener);
  },
});
