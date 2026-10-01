"use client";
import { useEffect, useState } from "react";
import type { Approval, Claim } from "@/lib/types";
import type { EscudoEvent } from "@/lib/timeline";
import demoEvents from "@/lib/fixtures/scenario-events.json";

export type Feed = {
  connection: "connecting" | "live" | "reconnecting";
  events: EscudoEvent[];
  claim: Claim | null;
  approvals: Approval[];
};

/** Live snapshot over SSE (/api/presenter/stream); EventSource reconnects on its own. */
export function usePresenterFeed(demo: boolean): Feed {
  const [feed, setFeed] = useState<Feed>({ connection: "connecting", events: [], claim: null, approvals: [] });

  useEffect(() => {
    if (demo) return replayDemo(setFeed);
    const source = new EventSource("/api/presenter/stream");
    source.onmessage = (message) => {
      const snapshot = JSON.parse(message.data);
      setFeed({ connection: "live", events: snapshot.events, claim: snapshot.claim, approvals: snapshot.approvals });
    };
    source.onerror = () => setFeed((f) => ({ ...f, connection: "reconnecting" }));
    return () => source.close();
  }, [demo]);

  return feed;
}

/**
 * `?demo=1`: replays a recorded run (src/lib/fixtures/scenario-events.json) in real time,
 * for rehearsing the view without the cloud. Approvals wait 4 s before the "customer" approves.
 */
function replayDemo(setFeed: (f: Feed) => void): () => void {
  const recorded = (demoEvents as EscudoEvent[]).slice().sort((a, b) => a.ts.localeCompare(b.ts));
  const t0 = Date.parse(recorded[0].ts);
  let extra = 0;
  const schedule = recorded.map((e) => {
    const at = Date.parse(e.ts) - t0 + extra;
    if (e.kind === "approval_requested") extra += 4000; // give the "customer" time to reach for the phone
    return { e, at };
  });
  const start = Date.now();
  const timer = setInterval(() => {
    const elapsed = Date.now() - start;
    const shown = schedule
      .filter(({ at }) => at <= elapsed)
      .map(({ e, at }) => ({ ...e, ts: new Date(start + at).toISOString() }));
    const granted = new Set(shown.filter((e) => e.kind === "approval_granted").map((e) => e.approval_id));
    const pending = shown.filter((e) => e.kind === "approval_requested" && !granted.has(e.approval_id));
    const approvals: Approval[] = pending.map((e) => ({
      approval_id: e.approval_id ?? "",
      claim_id: e.claim_id ?? null,
      tool: e.tool ?? "",
      reason: e.reason ?? "",
      summary: e.summary.replace(/^Esperando aprobación del cliente: /, ""),
      amount_usd: e.tool === "accept_deductible" ? 500 : null,
      status: "pending",
      created_at: e.ts,
    }));
    const claimId = shown.find((e) => e.claim_id)?.claim_id ?? null;
    const done = (tool: string, kind = "tool_result") => shown.some((e) => e.kind === kind && e.tool === tool);
    const status = done("sign_claim") ? "signed" : done("select_repair_shop") ? "repair_selected"
      : done("request_tow", "a2a_response") ? "tow_requested" : done("attach_claim_photos", "message") ? "assessed" : "open";
    const claim = claimId
      ? ({
          claim_id: claimId,
          status,
          status_label: { open: "abierto", assessed: "evaluado", tow_requested: "grúa en camino", repair_selected: "taller reservado", signed: "firmado y enviado" }[status],
          description: "Choque menor",
          created_at: shown[0].ts,
          tow: done("request_tow", "a2a_response") ? { provider: "Grúas Express Guate", eta_minutes: 14 } : null,
          rental: done("request_rental_car") ? { days: 7, pickup: "Arrendadora Aurora", covered: true, cost_usd: 0 } : null,
          repair: done("select_repair_shop") ? { shop_id: "shop_zona10", shop_name: "Taller Carrocería Diez", slot: "2026-10-01T08:00:00-06:00", quote_usd: 910 } : null,
        } as Claim)
      : null;
    setFeed({ connection: "live", events: shown, claim, approvals });
    if (shown.length === recorded.length) clearInterval(timer);
  }, 250);
  return () => clearInterval(timer);
}
