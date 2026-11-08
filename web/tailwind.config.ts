import type { Config } from "tailwindcss";

const config: Config = {
  content: ["./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        navy: { DEFAULT: "#101C35", 50: "#eef2f8", 100: "#d5deec", 600: "#1e2f52", 700: "#17253f", 800: "#101C35", 900: "#0a1224" },
        gold: { DEFAULT: "#D6AD60", 300: "#e8cf9f", 400: "#dfbd7f", 500: "#D6AD60", 600: "#b8903f" },
        canvas: "#F5F7FB"
      },
      fontFamily: {
        sans: ["var(--font-sans)", "system-ui", "Segoe UI", "Helvetica", "Arial", "sans-serif"],
        serif: ["var(--font-serif)", "Georgia", "serif"]
      },
      boxShadow: {
        card: "0 1px 2px rgba(16,28,53,0.04), 0 8px 24px -12px rgba(16,28,53,0.12)",
        glow: "0 0 0 4px rgba(214,173,96,0.15)"
      }
    }
  },
  plugins: []
};

export default config;
