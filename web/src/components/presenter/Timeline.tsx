"use client";
import type { Pause, Row } from "@/lib/timeline";
import styles from "./presenter.module.css";

// Rendered newest-last and bottom-aligned; the column clips and fades the oldest at the top,
// like a log scrolling up. Only the last few are kept in the DOM.
const KEEP_ROWS = 10;

export function Timeline({ rows, pause, done }: { rows: Row[]; pause: Pause | null; done: { elapsed: string; approvals: number } | null }) {
  const visible = rows.slice(-KEEP_ROWS);
  return (
    <div className={styles.timeline}>
      <div className={styles.rowsClip}>
        <ol className={styles.rows} aria-live="polite">
        {visible.map((row) => (
          <li key={row.id} className={`${styles.row} ${styles[`tone_${row.tone}`]}`}>
            <span className={styles.rowClock}>{row.clock}</span>
            <span className={styles.rowDot} aria-hidden>
              {row.tone === "approved" && (
                <svg viewBox="0 0 20 20" width="20" height="20">
                  <path d="M5 10.5l3.2 3.2L15 7" stroke="currentColor" strokeWidth="2.6" fill="none" strokeLinecap="round" strokeLinejoin="round" />
                </svg>
              )}
            </span>
            <span className={`${styles.badge} ${styles[`badge_${row.surface}`] ?? ""}`}>{row.surfaceLabel}</span>
            <span className={styles.rowText}>
              <strong>{row.actorLabel}</strong>
              <span aria-hidden> — </span>
              <span className="visually-hidden">: </span>
              {row.text}
            </span>
          </li>
        ))}
        </ol>
      </div>
      {pause && (
        <div className={styles.pause} role="alert" aria-label="Pausa: esperando la aprobación de Andrea" key={pause.approvalId}>
          <span className={styles.pauseIcon} aria-hidden>
            <svg viewBox="0 0 24 24" width="36" height="36">
              <rect x="6" y="5" width="4" height="14" rx="1.5" fill="currentColor" />
              <rect x="14" y="5" width="4" height="14" rx="1.5" fill="currentColor" />
            </svg>
          </span>
          <div className={styles.pauseText}>
            <p className={styles.pauseLabel}>Esperando tu aprobación</p>
            <p className={styles.pauseSummary}>{pause.title}</p>
            {pause.reason && <p className={styles.pauseReason}>{pause.reason} Solo Andrea puede aprobarlo, desde su teléfono.</p>}
          </div>
        </div>
      )}
      {done && !pause && (
        <div className={styles.done} role="status">
          <span className={styles.doneIcon} aria-hidden>
            <svg viewBox="0 0 24 24" width="44" height="44">
              <path d="M6 12.5l4 4L18 8" stroke="currentColor" strokeWidth="2.6" fill="none" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          </span>
          <p className={styles.doneText}>
            Reclamo firmado en {done.elapsed} <span className={styles.doneMuted}>· {done.approvals} aprobaciones, todas de Andrea</span>
          </p>
        </div>
      )}
    </div>
  );
}
