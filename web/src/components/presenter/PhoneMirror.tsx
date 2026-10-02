"use client";
import { EscudoMark } from "@/brand/EscudoMark";
import { SEVERITY_LABEL, slotLabel, usd } from "@/lib/labels";
import type { PhoneScreen } from "@/lib/timeline";
import styles from "./presenter.module.css";

/**
 * Phone mirror: what the customer's Escudo app shows right now, rendered from the same
 * backend state the app reads (pending approval first, then the claim). No capture pipeline
 * to fail on stage; for pixel-exact mirroring place a scrcpy window over this panel (see docs).
 */
export function PhoneMirror({ screen, replay = false }: { screen: PhoneScreen; replay?: boolean }) {
  return (
    <aside className={styles.phoneCol} aria-label="Pantalla del teléfono de la clienta">
      <div className={styles.phone}>
        <div className={styles.phoneScreen}>
          <div className={styles.phoneBar}>
            <EscudoMark size={28} />
            <span>{screen.kind === "approval" ? "Aprobación" : "Escudo"}</span>
          </div>
          <Screen screen={screen} />
        </div>
      </div>
      <p className={styles.phoneCaption}>Teléfono de Andrea · {replay ? "grabado" : "en vivo"}</p>
    </aside>
  );
}

function Screen({ screen }: { screen: PhoneScreen }) {
  switch (screen.kind) {
    case "approval":
      return (
        <div className={styles.phoneBody} key="approval">
          <div className={styles.phoneApproval}>
            {screen.claimId && <p className={styles.phoneMuted}>{screen.claimId}</p>}
            <p className={styles.phoneApprovalTitle}>{screen.title}</p>
            {screen.amount != null && <p className={styles.phoneAmount}>{usd(screen.amount)}</p>}
            <p className={styles.phoneSmall}>{screen.reason}</p>
          </div>
          <span className={styles.phoneButton}>Aprobar con huella</span>
          <span className={styles.phoneLink}>Rechazar</span>
        </div>
      );
    case "signed":
      return (
        <div className={styles.phoneBody} key="signed">
          <div className={styles.phoneDone}>
            <svg viewBox="0 0 24 24" width="56" height="56" aria-hidden>
              <circle cx="12" cy="12" r="11" fill="var(--pine)" />
              <path d="M7 12.5l3.2 3.2L17 9" stroke="var(--mist)" strokeWidth="2.2" fill="none" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
            <p className={styles.phoneApprovalTitle}>Reclamo enviado</p>
            <p className={styles.phoneMuted}>{screen.claim.claim_id}</p>
          </div>
          {screen.claim.repair && (
            <div className={styles.phoneFact}>
              <span>Taller</span>
              <strong>{screen.claim.repair.shop_name}</strong>
              <span>{slotLabel(screen.claim.repair.slot)}</span>
            </div>
          )}
        </div>
      );
    case "claim": {
      const c = screen.claim;
      return (
        <div className={styles.phoneBody} key="claim">
          <p className={styles.phoneMuted}>Reclamo {c.claim_id}</p>
          <p className={styles.phoneApprovalTitle}>{STATUS_LABEL[c.status] ?? "En proceso"}</p>
          {c.assessment && (
            <div className={styles.phoneFact}>
              <span>Daño</span>
              <strong>{capitalize(SEVERITY_LABEL[c.assessment.severity])}</strong>
            </div>
          )}
          {c.tow && (
            <div className={styles.phoneFact}>
              <span>Grúa</span>
              <strong>Llega en {c.tow.eta_minutes} min</strong>
            </div>
          )}
          {c.rental && (
            <div className={styles.phoneFact}>
              <span>Auto de reemplazo</span>
              <strong>{c.rental.days} días</strong>
            </div>
          )}
        </div>
      );
    }
    default:
      return (
        <div className={`${styles.phoneBody} ${styles.phoneIdle}`} key="idle">
          <p className={styles.phoneApprovalTitle}>Hola, Andrea</p>
          <p className={styles.phoneSmall}>Tu póliza está activa. Si tienes un percance, dile a tu asistente.</p>
        </div>
      );
  }
}

// Firestore claims carry the status code only (status_label is added by the API).
const STATUS_LABEL: Record<string, string> = {
  open: "Reclamo abierto",
  assessed: "Daño evaluado",
  tow_requested: "Grúa en camino",
  repair_selected: "Taller reservado",
  deductible_accepted: "Listo para firmar",
  signed: "Reclamo enviado",
};

function capitalize(s: string): string {
  return s.charAt(0).toUpperCase() + s.slice(1);
}
