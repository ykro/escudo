import { describe, expect, it } from "vitest";
import { buildPresenterState, clock, phoneScreen, selectRun, type EscudoEvent } from "./timeline";
import type { Approval, Claim } from "./types";

let n = 0;
const ev = (over: Partial<EscudoEvent>): EscudoEvent => ({
  id: `e${++n}`,
  ts: `2026-09-30T20:00:${String(n).padStart(2, "0")}.000Z`,
  run_id: "CLM-1",
  claim_id: "CLM-1",
  actor: "gemini",
  surface: "mcp",
  kind: "tool_result",
  tool: "open_claim",
  summary: "Reclamo CLM-1 abierto",
  ...over,
});

describe("clock", () => {
  it("formats elapsed mm:ss", () => {
    expect(clock("2026-01-01T00:00:00Z", "2026-01-01T00:01:24.900Z")).toBe("01:24");
    expect(clock("2026-01-01T00:00:10Z", "2026-01-01T00:00:00Z")).toBe("00:00");
  });
});

describe("buildPresenterState", () => {
  it("is empty without events", () => {
    const s = buildPresenterState([]);
    expect(s.rows).toEqual([]);
    expect(s.pause).toBeNull();
    expect(s.startedAt).toBeNull();
  });

  it("skips tool_call rows and labels surface and actor in Spanish", () => {
    const s = buildPresenterState([ev({ kind: "tool_call" }), ev({})]);
    expect(s.rows).toHaveLength(1);
    expect(s.rows[0]).toMatchObject({ surfaceLabel: "MCP", actorLabel: "Gemini", tone: "step" });
  });

  it("holds an approval pause until the customer decides, then counts it", () => {
    const requested = ev({ kind: "approval_requested", actor: "backend", tool: "accept_deductible", approval_id: "apr_1",
      summary: "Esperando aprobación del cliente: Aceptar pagar el deducible de USD 500", reason: "Te compromete a pagar el deducible." });
    const paused = buildPresenterState([requested]);
    expect(paused.pause).toMatchObject({ approvalId: "apr_1", title: "Aceptar deducible de 500 USD", detail: "Aceptar pagar el deducible de USD 500", reason: "Te compromete a pagar el deducible." });
    expect(paused.approvalsGranted).toBe(0);

    const granted = ev({ kind: "approval_granted", actor: "customer", surface: "android", tool: "accept_deductible", approval_id: "apr_1", summary: "Aprobado por el cliente: Aceptar pagar el deducible de USD 500" });
    const after = buildPresenterState([requested, granted]);
    expect(after.pause).toBeNull();
    expect(after.approvalsGranted).toBe(1);
    expect(after.rows.at(-1)).toMatchObject({ tone: "approved", surfaceLabel: "Android", text: "Aprobó con huella: aceptar deducible de 500 USD" });
  });

  it("lets the partner agent tell the tow story instead of repeating the tool result", () => {
    const s = buildPresenterState([
      ev({ kind: "a2a_request", actor: "escudo_agent", surface: "a2a", tool: "request_tow", summary: "Escudo pide una grúa a Grúas Aliadas" }),
      ev({ kind: "a2a_response", actor: "tow_agent", surface: "a2a", tool: "request_tow", summary: "Grúas Express Guate: llega en 14 min" }),
      ev({ tool: "request_tow", summary: "Grúa en camino" }),
    ]);
    expect(s.rows.map((r) => r.actorLabel)).toEqual(["Agente Escudo", "Agente de grúa"]);
  });

  it("stops the clock when the claim is signed", () => {
    const signed = ev({ tool: "sign_claim", summary: "Reclamo firmado" });
    const s = buildPresenterState([ev({}), signed]);
    expect(s.endedAt).toBe(signed.ts);
    expect(s.claimId).toBe("CLM-1");
  });
});

describe("phoneScreen", () => {
  const claim = { claim_id: "CLM-1", status: "assessed", description: "x", created_at: "2026-01-01" } as Claim;
  const approval: Approval = { approval_id: "apr_1", claim_id: "CLM-1", tool: "accept_deductible", reason: "r", summary: "Aceptar pagar el deducible", amount_usd: 500, status: "pending", created_at: "2026-01-01" };

  it("shows a pending approval before anything else", () => {
    expect(phoneScreen(claim, [approval])).toMatchObject({ kind: "approval", title: "Aceptar deducible", amount: 500 });
  });
  it("falls back to the claim, the signed state, or idle", () => {
    expect(phoneScreen(claim, [{ ...approval, status: "used" }]).kind).toBe("claim");
    expect(phoneScreen({ ...claim, status: "signed" }, []).kind).toBe("signed");
    expect(phoneScreen(null, []).kind).toBe("idle");
  });
});

describe("selectRun", () => {
  const a1 = ev({ run_id: "run-a", ts: "2026-10-01T10:00:00Z" });
  const b1 = ev({ run_id: "run-b", ts: "2026-10-01T10:00:05Z" });
  const a2 = ev({ run_id: "run-a", ts: "2026-10-01T10:00:09Z" });

  it("follows the given run only", () => {
    expect(selectRun([a1, b1, a2], "run-b")).toEqual({ runId: "run-b", events: [b1] });
  });
  it("defaults to the run of the newest event", () => {
    expect(selectRun([a1, b1, a2]).runId).toBe("run-a");
    expect(selectRun([a1, b1, a2]).events).toEqual([a1, a2]);
  });
  it("is empty without events", () => {
    expect(selectRun([])).toEqual({ runId: null, events: [] });
  });
});
