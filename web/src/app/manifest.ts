import type { MetadataRoute } from "next";

/** Lets Edge / Chrome install HesabPro as an app ("Bu saytı tətbiq kimi quraşdır"): it opens the till in its own window. */
export default function manifest(): MetadataRoute.Manifest {
  return {
    name: "HesabPro Kassa",
    short_name: "HesabPro",
    description: "HesabPro — satış kassası, borclar, anbar və kassa",
    id: "/satis",
    start_url: "/satis",
    scope: "/",
    display: "standalone",
    background_color: "#F5F7FB",
    theme_color: "#101C35",
    lang: "az",
    icons: [
      { src: "/icons/app-192.png", sizes: "192x192", type: "image/png" },
      { src: "/icons/app-512.png", sizes: "512x512", type: "image/png" },
      { src: "/icons/maskable-192.png", sizes: "192x192", type: "image/png", purpose: "maskable" },
      { src: "/icons/maskable-512.png", sizes: "512x512", type: "image/png", purpose: "maskable" },
    ],
    shortcuts: [
      { name: "Kassa", url: "/satis" },
      { name: "Panel", url: "/dashboard" },
      { name: "Müştəri borcları", url: "/borclar/musteriler" },
    ],
  };
}
