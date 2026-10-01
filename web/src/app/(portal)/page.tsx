"use client";
import { useState } from "react";
import { api } from "@/lib/api";
import { ALL_COVERAGES, COVERAGE_LABEL, usd } from "@/lib/labels";
import type { Policy } from "@/lib/types";
import { useApi } from "@/lib/useApi";
import { ErrorBlock, LoadingBlock } from "@/components/portal/States";
import styles from "@/components/portal/portal.module.css";

const COVERAGE_NOTE: Record<string, string> = {
  own_damage: "Choques y golpes a tu auto, con deducible.",
  third_party: "Daños que causes a otras personas o autos.",
  towing: "Grúa hasta el taller, sin costo.",
  rental_car: "Un auto mientras reparan el tuyo.",
  theft: "Si te roban el auto completo.",
};

const INCIDENTS = [
  { id: "collision", label: "Un choque" },
  { id: "towing", label: "Necesito grúa" },
  { id: "rental_car", label: "Auto de reemplazo" },
  { id: "third_party", label: "Dañé otro auto" },
  { id: "theft", label: "Me robaron el auto" },
];

export default function PolicyPage() {
  const policy = useApi<Policy>("/policy");
  if (policy.status === "loading") return <LoadingBlock lines={4} label="Cargando tu póliza…" />;
  if (policy.status === "error") return <ErrorBlock error={policy.error} onRetry={policy.reload} />;
  const p = policy.data;
  const firstName = p.holder.name.split(" ")[0];

  return (
    <div className={styles.policyGrid}>
      <section aria-labelledby="poliza-titulo">
        <p className={styles.eyebrow}>Hola, {firstName}</p>
        <h1 id="poliza-titulo" className={styles.display}>
          Tu {p.vehicle.make} {p.vehicle.model} {p.vehicle.year} está protegido.
        </h1>
        <p className={styles.meta}>
          Placa {p.vehicle.plate} · Póliza {p.policy_id} · {p.status === "active" ? "Activa" : "Suspendida"}
        </p>

        <h2 className={styles.sectionTitle}>Qué cubre</h2>
        <ul className={styles.coverages}>
          {ALL_COVERAGES.map((c) => {
            const included = p.coverages.includes(c);
            return (
              <li key={c} className={included ? styles.included : styles.excluded}>
                <span className={styles.coverageIcon} aria-hidden>
                  {included ? "✓" : "–"}
                </span>
                <span>
                  <strong>{COVERAGE_LABEL[c]}</strong>
                  <span className={styles.coverageNote}>{included ? COVERAGE_NOTE[c] : "No incluido en tu plan."}</span>
                </span>
                <span className="visually-hidden">{included ? "Incluido" : "No incluido"}</span>
              </li>
            );
          })}
        </ul>
      </section>

      <aside className={styles.sideCol}>
        <div className={styles.tonal}>
          <p className={styles.tonalLabel}>Deducible</p>
          <p className={styles.tonalFigure}>{usd(p.deductible_usd)}</p>
          <p>Lo pagas solo en reclamos por daños propios. Escudo cubre el resto de la reparación.</p>
          {p.coverages.includes("rental_car") && (
            <p className={styles.tonalFoot}>Auto de reemplazo: {p.rental_days} días sin costo.</p>
          )}
        </div>
        <CoverageCheck />
      </aside>
    </div>
  );
}

function CoverageCheck() {
  const [selected, setSelected] = useState<string | null>(null);
  const [answer, setAnswer] = useState<{ covered: boolean; notes: string } | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function check(id: string) {
    setSelected(id);
    setAnswer(null);
    setError(null);
    try {
      setAnswer(await api(`/coverage/check?incident_type=${id}`));
    } catch (e) {
      setError((e as Error).message);
    }
  }

  return (
    <section className={styles.check} aria-labelledby="check-titulo">
      <h2 id="check-titulo" className={styles.sectionTitle}>
        ¿Estoy cubierto si…?
      </h2>
      <div className={styles.chips} role="group" aria-label="Tipo de incidente">
        {INCIDENTS.map((i) => (
          <button key={i.id} type="button" className={styles.chip} aria-pressed={selected === i.id} onClick={() => check(i.id)}>
            {i.label}
          </button>
        ))}
      </div>
      <div aria-live="polite" className={styles.answerSlot}>
        {selected && !answer && !error && <div className="skeleton" style={{ height: 24, width: "70%" }} />}
        {answer && (
          <p className={answer.covered ? styles.answerYes : styles.answerNo}>
            <strong>{answer.covered ? "Sí. " : "No. "}</strong>
            {answer.notes}
          </p>
        )}
        {error && <p className={styles.inlineError}>{error}</p>}
      </div>
    </section>
  );
}
