import type { Metadata, Viewport } from "next";
import { AuthProvider } from "@/lib/auth";
import "./globals.css";

export const metadata: Metadata = {
  title: {
    default: "HesabPro — Borc və mühasibat idarəetməsi",
    template: "%s · HesabPro",
  },
  description: "Mağazalar üçün borc, ödəniş və mühasibat idarəetmə platforması.",
  applicationName: "HesabPro",
  robots: { index: false, follow: false },
};

export const viewport: Viewport = {
  themeColor: "#101C35",
  width: "device-width",
  initialScale: 1,
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="az">
      <body>
        <AuthProvider>{children}</AuthProvider>
      </body>
    </html>
  );
}
