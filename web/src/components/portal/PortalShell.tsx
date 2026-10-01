"use client";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect } from "react";
import { EscudoLogo } from "@/brand/EscudoMark";
import { AuthProvider, useAuth } from "@/lib/auth";
import { useApi } from "@/lib/useApi";
import type { Approval } from "@/lib/types";
import { WebMcpTools } from "./WebMcpTools";
import styles from "./portal.module.css";

const NAV = [
  { href: "/", label: "Póliza" },
  { href: "/reclamo", label: "Reclamo" },
  { href: "/talleres", label: "Talleres" },
];

export function PortalShell({ children }: { children: React.ReactNode }) {
  return (
    <AuthProvider>
      <Gate>{children}</Gate>
    </AuthProvider>
  );
}

function Gate({ children }: { children: React.ReactNode }) {
  const auth = useAuth();
  const router = useRouter();
  useEffect(() => {
    if (auth.status === "signedOut") router.replace("/entrar");
  }, [auth.status, router]);

  if (auth.status !== "signedIn") {
    return (
      <div className={styles.boot} role="status" aria-live="polite">
        <EscudoLogo size={40} />
        <span className="visually-hidden">Cargando tu cuenta…</span>
      </div>
    );
  }
  return <Shell>{children}</Shell>;
}

function Shell({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const auth = useAuth();
  return (
    <div className={styles.page}>
      <a className="skip-link" href="#contenido">
        Saltar al contenido
      </a>
      <header className={styles.topbar}>
        <Link href="/" className={styles.logoLink} aria-label="Escudo, inicio">
          <EscudoLogo size={30} />
        </Link>
        <nav aria-label="Secciones" className={styles.nav}>
          {NAV.map((item) => (
            <Link key={item.href} href={item.href} className={styles.navLink} aria-current={pathname === item.href ? "page" : undefined}>
              {item.label}
            </Link>
          ))}
        </nav>
        <button type="button" className={styles.signOut} onClick={() => auth.signOut()}>
          Salir
        </button>
      </header>
      <PendingApprovalNotice />
      <main id="contenido" className={styles.main}>
        {children}
      </main>
      <footer className={styles.footer}>
        <WebMcpTools />
        <p>Escudo Seguros es una aseguradora ficticia creada para una charla.</p>
      </footer>
    </div>
  );
}

/** Approving happens only on the phone (biometrics). The web just points there. */
function PendingApprovalNotice() {
  const approvals = useApi<Approval[]>("/approvals", 5000);
  if (approvals.status !== "ready" || approvals.data.length === 0) return null;
  const next = approvals.data[0];
  return (
    <div className={styles.notice} role="status">
      <span className={styles.noticeLabel}>Esperando tu aprobación en el teléfono</span>
      <span>{next.summary}</span>
    </div>
  );
}
