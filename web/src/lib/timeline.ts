/**
 * Presenter timeline: turns raw events (contracts/events.schema.json) into what the audience reads.
 * Pure functions — tested in src/lib/timeline.test.ts.
 */
import type { Approval, Claim } from "./types";
import { ACTOR_LABEL, SURFACE_LABEL } from "./labels";

export type EscudoEvent = {
  id: string;
  ts: string;
  run_id: string;
  claim_id?: string | null;
  actor: string;
  surface: string;
  kind: string;
  tool?: string | null;
  summary: string;
  autonomy?: string | null;
  approval_id?: string | null;
  reason?: string | null;
  data?: Record<string, unknown>;
};

export type RowTone = "step" | "a2a" | "approved" | "rejected" | "error";

export type Row = {
  id: string;
  clock: string;
  surface: string;
  surfaceLabel: string;
  actorLabel: string;
  text: string;
  tone: RowTone;
  automatic: boolean; // the agent did it alone (autonomy table): shown so autonomy reads on both sides
};

export type Pause = { approvalId: string; title: string; detail: string; reason: string; tool: string | null; since: string };

export type PresenterState = {
  rows: Row[];
  pause: Pause | null;
  approvalsGranted: number;
  startedAt: string | null;
  endedAt: string | null;
  claimId: string | null;
};

export const EXPECTED_APPROVALS = 3;

// When a partner agent or the assessor already told the story, the tool's own result is a repeat.
const TOLD_BY_OTHERS = new Set(["attach_claim_photos", "request_tow", "list_repair_options"]);
// An agent checking status after acting repeats the previous row.
const NOT_A_STEP = new Set(["get_claim_status"]);

/** Short titles for the three human decisions (the audience reads them in one glance). */
export const APPROVAL_TITLE: Record<string, string> = {
  accept_deductible: "Aceptar deducible",
  select_repair_shop: "Reservar taller",
  sign_claim: "Firmar y enviar el reclamo",
  request_rental_car: "Rentar auto",
};

function lowerFirst(s: string): string {
  return s.charAt(0).toLowerCase() + s.slice(1);
}

function approvalTitle(tool: string | null | undefined, summary: string): string {
  const title = (tool && APPROVAL_TITLE[tool]) ?? "Aprobar acción";
  const amount = tool === "accept_deductible" ? summary.match(/USD ([\d,.]+)/)?.[1] : undefined;
  return amount ? `${title} de USD ${amount}` : title;
}

export function clock(fromIso: string, toIso: string): string {
  const seconds = Math.max(0, Math.floor((Date.parse(toIso) - Date.parse(fromIso)) / 1000));
  const m = Math.floor(seconds / 60);
  const s = seconds % 60;
  return `${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
}

/**
 * Which run the stage follows: `?run=<id>` if given, else the run of the newest event. Each scenario
 * run has its own customer and run id (X-Escudo-Run-Id), so concurrent runs never mix on screen.
 */
export function selectRun(events: EscudoEvent[], run?: string | null): { runId: string | null; events: EscudoEvent[] } {
  // Without ?run, stick to the run of the active claim (the newest event that belongs to a claim), so an
  // aside without a claim (another customer browsing, a getCoverage) never pulls the stage away.
  const newest = [...events].sort((a, b) => b.ts.localeCompare(a.ts));
  const runId = run || newest.find((e) => e.claim_id)?.run_id || newest[0]?.run_id || null;
  return { runId, events: runId ? events.filter((e) => e.run_id === runId) : [] };
}

export function buildPresenterState(input: EscudoEvent[]): PresenterState {
  const events = [...input].sort((a, b) => a.ts.localeCompare(b.ts));
  const startedAt = events[0]?.ts ?? null;
  const decided = new Map<string, EscudoEvent>();
  const narrated = new Set<string>();
  for (const e of events) {
    if ((e.kind === "approval_granted" || e.kind === "approval_rejected") && e.approval_id) decided.set(e.approval_id, e);
    if ((e.kind === "a2a_response" || (e.kind === "message" && e.actor === "damage_assessor")) && e.tool) narrated.add(e.tool);
  }

  const rows: Row[] = [];
  let pause: Pause | null = null;
  let endedAt: string | null = null;
  let claimId: string | null = null;

  for (const e of events) {
    if (e.claim_id) claimId = e.claim_id;
    const base = {
      id: e.id,
      clock: startedAt ? clock(startedAt, e.ts) : "00:00",
      surface: e.surface,
      surfaceLabel: SURFACE_LABEL[e.surface] ?? e.surface,
      actorLabel: ACTOR_LABEL[e.actor] ?? e.actor,
      automatic: e.autonomy === "automatic",
    };
    switch (e.kind) {
      case "tool_result":
        if (e.tool && TOLD_BY_OTHERS.has(e.tool) && narrated.has(e.tool)) break;
        if (e.tool && NOT_A_STEP.has(e.tool)) break;
        if (e.tool === "sign_claim") endedAt = e.ts;
        rows.push({ ...base, text: e.summary, tone: "step" });
        break;
      case "a2a_request":
      case "a2a_response":
        rows.push({ ...base, text: e.summary, tone: "a2a" });
        break;
      case "message":
        rows.push({ ...base, text: e.summary, tone: "step" });
        break;
      case "approval_granted":
        rows.push({ ...base, text: `Aprobó con huella: ${lowerFirst(approvalTitle(e.tool, e.summary))}`, tone: "approved" });
        break;
      case "approval_rejected":
        rows.push({ ...base, text: `Rechazó: ${lowerFirst(approvalTitle(e.tool, e.summary))}`, tone: "rejected" });
        break;
      case "approval_requested":
        if (e.approval_id && !decided.has(e.approval_id)) {
          const detail = e.summary.replace(/^Esperando aprobación de(l| la) client[ea]: /, "");
          pause = {
            approvalId: e.approval_id,
            title: approvalTitle(e.tool, detail),
            detail,
            reason: e.reason ?? "",
            tool: e.tool ?? null,
            since: e.ts,
          };
        }
        break;
      case "error":
        rows.push({ ...base, text: e.summary, tone: "error" });
        break;
      // tool_call: the result row says it better; skipped on purpose.
    }
  }

  const approvalsGranted = events.filter((e) => e.kind === "approval_granted").length;
  return { rows, pause, approvalsGranted, startedAt, endedAt, claimId };
}

export type PhoneScreen =
  | { kind: "idle" }
  | { kind: "approval"; claimId: string | null; title: string; amount: number | null; reason: string }
  | { kind: "claim"; claim: Claim }
  | { kind: "signed"; claim: Claim };

/** What the customer's phone is showing right now, derived from the same data the phone reads. */
export function phoneScreen(claim: Claim | null, approvals: Approval[]): PhoneScreen {
  const pending = approvals.filter((a) => a.status === "pending").sort((a, b) => a.created_at.localeCompare(b.created_at));
  const next = pending[0];
  if (next) {
    return {
      kind: "approval",
      claimId: next.claim_id,
      title: APPROVAL_TITLE[next.tool] ?? "Aprobar acción",
      amount: next.amount_usd,
      reason: next.summary,
    };
  }
  if (!claim) return { kind: "idle" };
  return claim.status === "signed" ? { kind: "signed", claim } : { kind: "claim", claim };
}
