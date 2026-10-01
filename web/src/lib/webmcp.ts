/**
 * WebMCP: the portal's capabilities as tools for the browser's agent.
 *
 * Current spec (W3C WebML CG draft, 2026-09-30): `document.modelContext.registerTool(tool, { signal })`.
 * Chrome 150+ implements it behind chrome://flags/#enable-webmcp-testing (verified on Chrome 154);
 * earlier builds exposed `navigator.modelContext`, so we fall back to it.
 * The tools are thin adapters over the same REST API the screens use — and none of them approves.
 */
import type { Claim, Policy, RepairOption } from "./types";
import { COVERAGE_LABEL, ALL_COVERAGES } from "./labels";

export type ToolInput = Record<string, unknown>;
export type EscudoTool = {
  name: string;
  title: string;
  description: string;
  inputSchema: object;
  annotations: { readOnlyHint: boolean };
  execute: (input: ToolInput) => Promise<unknown>;
};

/** Minimal shape of the browser's ModelContext we rely on. */
export type ModelContextLike = {
  registerTool: (tool: EscudoTool, options?: { signal?: AbortSignal }) => unknown;
};

export type Fetcher = <T>(path: string) => Promise<T>;

export function escudoTools(get: Fetcher): EscudoTool[] {
  const latestClaimId = async (input: ToolInput): Promise<string> =>
    typeof input.claim_id === "string" && input.claim_id ? input.claim_id : (await get<Claim>("/claims/latest")).claim_id;

  return [
    {
      name: "getCoverage",
      title: "Coberturas de la póliza",
      description:
        "Get the signed-in customer's car insurance coverages (what is and is not covered). Optionally pass incident_type (collision, theft, towing, rental_car, third_party) to check one incident.",
      inputSchema: {
        type: "object",
        properties: {
          incident_type: { type: "string", description: "Optional incident to check, e.g. collision, theft, towing, rental_car, third_party" },
        },
      },
      annotations: { readOnlyHint: true },
      execute: async (input) => {
        const policy = await get<Policy>("/policy");
        const result: Record<string, unknown> = {
          policy_id: policy.policy_id,
          vehicle: `${policy.vehicle.make} ${policy.vehicle.model} ${policy.vehicle.year}`,
          coverages: ALL_COVERAGES.map((c) => ({ id: c, label: COVERAGE_LABEL[c], included: policy.coverages.includes(c) })),
        };
        if (typeof input.incident_type === "string" && input.incident_type) {
          result.check = await get(`/coverage/check?incident_type=${encodeURIComponent(input.incident_type)}`);
        }
        return result;
      },
    },
    {
      name: "getDeductible",
      title: "Deducible",
      description: "Get the deductible (USD) the customer pays per own-damage claim, and the replacement-car days included.",
      inputSchema: { type: "object", properties: {} },
      annotations: { readOnlyHint: true },
      execute: async () => {
        const policy = await get<Policy>("/policy");
        return {
          deductible_usd: policy.deductible_usd,
          currency: "USD",
          rental_days: policy.rental_days,
          notes: `Pagas ${policy.deductible_usd} USD por reclamo de daños propios; Escudo cubre el resto.`,
        };
      },
    },
    {
      name: "findRepairShops",
      title: "Talleres con cupo",
      description:
        "Find partner repair shops for a claim (the latest one if claim_id is omitted): address, rating, open slots and a quote for the assessed damage. Nothing is booked; booking requires the customer's approval in the Escudo app.",
      inputSchema: { type: "object", properties: { claim_id: { type: "string", description: "Claim id; latest claim when omitted" } } },
      annotations: { readOnlyHint: true },
      execute: async (input) => {
        const claimId = await latestClaimId(input);
        const shops = await get<RepairOption[]>(`/claims/${encodeURIComponent(claimId)}/repair-options`);
        return { claim_id: claimId, shops };
      },
    },
    {
      name: "getClaimStatus",
      title: "Estado del reclamo",
      description: "Get the status of a claim (the latest one if claim_id is omitted): damage assessment, tow, repair shop, rental car and recent steps.",
      inputSchema: { type: "object", properties: { claim_id: { type: "string", description: "Claim id; latest claim when omitted" } } },
      annotations: { readOnlyHint: true },
      execute: async (input) => {
        const claimId = typeof input.claim_id === "string" && input.claim_id ? input.claim_id : null;
        const claim = await get<Claim>(claimId ? `/claims/${encodeURIComponent(claimId)}` : "/claims/latest");
        const { timeline, repair_options, photos, ...rest } = claim;
        return { ...rest, photos: photos?.length ?? 0, recent_steps: (timeline ?? []).slice(-8).map((t) => t.summary), offered_shops: repair_options?.length ?? 0 };
      },
    },
  ];
}

export function findModelContext(win: unknown = globalThis): ModelContextLike | null {
  const w = win as { document?: { modelContext?: ModelContextLike }; navigator?: { modelContext?: ModelContextLike } };
  return w.document?.modelContext ?? w.navigator?.modelContext ?? null;
}

/** Registers every tool; aborting the returned controller unregisters them all. */
export async function registerTools(mc: ModelContextLike, tools: EscudoTool[]): Promise<AbortController> {
  const controller = new AbortController();
  for (const tool of tools) {
    await mc.registerTool(tool, { signal: controller.signal });
  }
  return controller;
}
