"use client";
import { useEffect, useMemo, useState } from "react";
import { EscudoMark } from "@/brand/EscudoMark";
import { buildPresenterState, clock, EXPECTED_APPROVALS, phoneScreen, selectRun } from "@/lib/timeline";
import { usePresenterFeed } from "./usePresenterFeed";
import { useCloudAwake, type Awake } from "./useCloudAwake";
import { PhoneMirror } from "./PhoneMirror";
import { Timeline } from "./Timeline";
import styles from "./presenter.module.css";

const STAGE_W = 1920;
const STAGE_H = 1080;

/** Scales the fixed 1920×1080 stage to any projector without reflowing the layout. */
function useStageScale(): number {
  const [scale, setScale] = useState(1);
  useEffect(() => {
    const fit = () => setScale(Math.min(window.innerWidth / STAGE_W, window.innerHeight / STAGE_H));
    fit();
    window.addEventListener("resize", fit);
    return () => window.removeEventListener("resize", fit);
  }, []);
  return scale;
}

function useNow(active: boolean): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!active) return;
    const t = setInterval(() => setNow(Date.now()), 250);
    return () => clearInterval(t);
  }, [active]);
  return now;
}

export function PresenterView({ demo, run }: { demo: boolean; run?: string }) {
  const feed = usePresenterFeed(demo);
  const awake = useCloudAwake(demo);
  const followed = useMemo(() => selectRun(feed.events, run), [feed.events, run]);
  const state = useMemo(() => buildPresenterState(followed.events), [followed.events]);
  const phone = useMemo(() => {
    const claim = feed.claims.find((c) => c.claim_id === state.claimId) ?? null;
    return phoneScreen(claim, feed.approvals.filter((a) => a.claim_id === state.claimId));
  }, [feed.claims, feed.approvals, state.claimId]);
  const running = Boolean(state.startedAt && !state.endedAt);
  const now = useNow(running);
  const scale = useStageScale();

  const elapsed = state.startedAt ? clock(state.startedAt, state.endedAt ?? new Date(now).toISOString()) : "00:00";
  const approvals = Math.min(state.approvalsGranted, EXPECTED_APPROVALS);

  return (
    <div className={styles.viewport}>
      <main
        className={styles.stage}
        style={{ transform: `translate(-50%, -50%) scale(${scale})` }}
        aria-label={demo ? "Vista del presentador: repetición grabada" : "Vista del presentador: reclamo en vivo"}
      >
        <header className={styles.header}>
          <div className={styles.brand}>
            <EscudoMark size={64} />
            <span>Escudo</span>
          </div>
          <div className={styles.title}>
            <h1>{demo ? "Reclamo grabado" : "Reclamo en vivo"}</h1>
            {state.claimId && <p className={styles.claimId}>{state.claimId}</p>}
            {demo && <span className={styles.replay}>REPETICIÓN GRABADA</span>}
          </div>
          <div className={styles.meters} aria-live="polite">
            <div className={styles.approvals}>
              <span className={styles.meterLabel}>Aprobaciones</span>
              <span className={styles.meterValue}>
                {approvals} de {EXPECTED_APPROVALS}
              </span>
            </div>
            <div className={styles.clockBox}>
              <time className={`${styles.clock} ${state.endedAt ? styles.clockDone : ""}`} aria-label={`Tiempo transcurrido ${elapsed}`}>
                {elapsed}
              </time>
              {state.endedAt && <span className={styles.doneChip}>Reclamo firmado</span>}
            </div>
          </div>
        </header>

        <div className={styles.body}>
          <section className={styles.timelineCol} aria-label="Pasos del escenario">
            {feed.connection === "connecting" ? (
              <Connecting />
            ) : state.rows.length === 0 && !state.pause ? (
              awake === "ready" ? <Empty /> : <Waking awake={awake} />
            ) : (
              <Timeline rows={state.rows} pause={state.pause} done={state.endedAt ? { elapsed, approvals } : null} />
            )}
            {feed.connection === "reconnecting" && (
              <p className={styles.offline} role="status">
                Sin conexión con Escudo. Reintentando…
              </p>
            )}
          </section>
          <PhoneMirror screen={phone} replay={demo} />
        </div>
      </main>
    </div>
  );
}

function Connecting() {
  return (
    <div className={styles.placeholder} role="status">
      <p className={styles.placeholderTitle}>Conectando con Escudo…</p>
      <div className={styles.skeletonRows} aria-hidden>
        {[70, 55, 62].map((w) => (
          <div key={w} className="skeleton" style={{ width: `${w}%`, height: 40 }} />
        ))}
      </div>
    </div>
  );
}

function Waking({ awake }: { awake: Awake }) {
  return (
    <div className={styles.placeholder} role="status">
      <p className={styles.placeholderTitle}>Preparando Escudo…</p>
      <p className={styles.placeholderText}>
        {awake === "retrying"
          ? "Escudo todavía no responde. Sigo intentando cada pocos segundos."
          : "Despertando los servicios en la nube. Tarda unos 20 segundos la primera vez."}
      </p>
    </div>
  );
}

function Empty() {
  return (
    <div className={styles.placeholder}>
      <p className={styles.placeholderTitle}>Listo para empezar</p>
      <p className={styles.placeholderText}>
        Cada paso aparecerá aquí en cuanto un agente use Escudo: qué agente actúa, por qué superficie y qué logró.
      </p>
    </div>
  );
}
