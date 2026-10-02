import type { Metadata } from "next";
import localFont from "next/font/local";
import "./globals.css";

// Self-hosted (variable 100-900, SIL OFL: fonts/Onest-OFL.txt): the build and the stage need no network.
const onest = localFont({ src: "./fonts/Onest-Variable.woff2", variable: "--font-onest", display: "swap", weight: "100 900" });

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
