"use client";
import { CLAIM_STEPS, SEVERITY_LABEL, slotLabel, usd } from "@/lib/labels";
import type { Claim, TimelineItem } from "@/lib/types";
import { useApi } from "@/lib/useApi";
import { EmptyBlock, ErrorBlock, LoadingBlock } from "@/components/portal/States";
import styles from "@/components/portal/portal.module.css";

const HEADLINE: Record<Claim["status"], string> = {
  open: "Recibimos tu reclamo.",
  assessed: "Ya evaluamos el daño.",
  tow_requested: "La grúa va en camino.",
  repair_selected: "Tu taller está reservado.",
  deductible_accepted: "Todo listo para firmar.",
  signed: "Tu reclamo fue enviado.",
};

// Status checks by agents repeat what the log already says.
const QUIET_TOOLS = new Set(["get_claim_status", "get_policy", "check_coverage"]);

// The customer reads her own log: no internal pauses, agent errors or discovery steps; her decisions in
// second person and short (the step that follows already says what happened); no repeated lines.
const HIDDEN_KINDS = new Set(["tool_call", "approval_requested", "error", "a2a_request"]);
const DECISION: Record<string, string> = {
  accept_deductible: "el deducible",
  select_repair_shop: "la reserva del taller",
  sign_claim: "la firma del reclamo",
  request_rental_car: "la renta del auto",
};
function forCustomer(t: TimelineItem): string {
  if (t.kind === "approval_granted") return `Aprobaste con tu huella ${DECISION[t.tool ?? ""] ?? "la acción"}.`;
  if (t.kind === "approval_rejected") return `Rechazaste ${DECISION[t.tool ?? ""] ?? "la acción"}.`;
  return t.summary;
}
function customerLog(items: TimelineItem[]): TimelineItem[] {
  const seen = new Set<string>();
  return items.filter((t) => {
    if (HIDDEN_KINDS.has(t.kind) || (t.tool && QUIET_TOOLS.has(t.tool)) || t.summary.includes("Agent Card")) return false;
    const key = forCustomer(t).toLowerCase().replace(/[^a-z0-9áéíóúñ]/g, "").slice(0, 40);
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

export default function ClaimPage() {
  const claim = useApi<Claim>("/claims/latest", 5000);
  if (claim.status === "loading") return <LoadingBlock lines={4} label="Cargando tu reclamo…" />;
  if (claim.status === "error") {
    if (claim.error.status === 404)
      return (
        <EmptyBlock title="No tienes reclamos abiertos">
          <p>Si tuviste un percance, repórtalo desde la app de Escudo o pídeselo a tu asistente: con tus fotos, él se encarga de la grúa, el taller y el auto de reemplazo.</p>
        </EmptyBlock>
      );
    return <ErrorBlock error={claim.error} onRetry={claim.reload} />;
  }
  const c = claim.data;
  const reached = CLAIM_STEPS.findIndex((s) => s.status === c.status);

  return (
    <div className={styles.claimLayout}>
      <section aria-labelledby="reclamo-titulo">
        <p className={styles.eyebrow}>Reclamo {c.claim_id}</p>
        <h1 id="reclamo-titulo" className={styles.display}>
          {HEADLINE[c.status]}
        </h1>
        <p className={styles.meta}>{c.description}</p>

        <ol className={styles.stepper} aria-label="Avance del reclamo">
          {CLAIM_STEPS.map((step, i) => (
            <li key={step.status} className={i <= reached ? styles.stepDone : styles.stepTodo} aria-current={i === reached ? "step" : undefined}>
              <span className={styles.stepDot} aria-hidden />
              {step.label}
            </li>
          ))}
        </ol>

        <dl className={styles.facts}>
          <Fact label="Daño">
            {c.assessment
              ? `${cap(SEVERITY_LABEL[c.assessment.severity])} · ${c.assessment.parts.map((p) => p.name).join(", ")} · ${usd(c.assessment.cost_range_usd[0])}–${usd(c.assessment.cost_range_usd[1])}`
              : "Pendiente de fotos"}
          </Fact>
          <Fact label="Grúa">{c.tow ? `${c.tow.provider}, llega en ${c.tow.eta_minutes} min` : "Sin solicitar"}</Fact>
          <Fact label="Taller">{c.repair ? `${c.repair.shop_name}, ${slotLabel(c.repair.slot)}` : "Sin reservar"}</Fact>
          <Fact label="Auto de reemplazo">
            {c.rental ? `${c.rental.days} días · ${c.rental.covered ? "sin costo" : usd(c.rental.cost_usd)} · ${c.rental.pickup}` : "Sin solicitar"}
          </Fact>
          <Fact label="Deducible">{c.deductible_accepted ? "Aceptado" : "Pendiente"}</Fact>
        </dl>
      </section>

      <aside className={styles.sideCol} aria-labelledby="pasos-titulo">
        <h2 id="pasos-titulo" className={styles.sectionTitle}>
          Lo que ha pasado
        </h2>
        {c.timeline && c.timeline.length > 0 ? (
          <ol className={styles.log}>
            {customerLog(c.timeline)
              .slice(-10)
              .reverse()
              .map((t) => (
                <li key={t.ts + t.summary}>
                  <time dateTime={t.ts}>{new Date(t.ts).toLocaleTimeString("es-GT", { hour: "numeric", minute: "2-digit" })}</time>
                  <span>{forCustomer(t)}</span>
                </li>
              ))}
          </ol>
        ) : (
          <p className={styles.coverageNote}>Aquí verás cada paso de tu reclamo.</p>
        )}
      </aside>
    </div>
  );
}

function Fact({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className={styles.fact}>
      <dt>{label}</dt>
      <dd>{children}</dd>
    </div>
  );
}

function cap(s: string) {
  return s.charAt(0).toUpperCase() + s.slice(1);
}
