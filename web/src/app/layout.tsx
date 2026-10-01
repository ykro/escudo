import type { Metadata } from "next";
import { Onest } from "next/font/google";
import "./globals.css";

const onest = Onest({ subsets: ["latin"], variable: "--font-onest", display: "swap" });

export const metadata: Metadata = {
  title: { default: "Escudo", template: "%s · Escudo" },
  description: "Tu seguro de auto, listo para ti y para tus agentes.",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="es-GT" className={onest.variable}>
      <body>{children}</body>
    </html>
  );
}
