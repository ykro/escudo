import { describe, expect, it, vi } from "vitest";
import { escudoTools, findModelContext, registerTools, type EscudoTool } from "./webmcp";

const POLICY = {
  policy_id: "POL-1",
  holder: { name: "Andrea Morales", customer_id: "cus_demo" },
  vehicle: { make: "Toyota", model: "Corolla", year: 2022, plate: "P-512KLM" },
  coverages: ["own_damage", "towing"],
  deductible_usd: 500,
  rental_days: 7,
  status: "active",
};

function fakeGet(routes: Record<string, unknown>) {
  return vi.fn(async (path: string) => {
    if (!(path in routes)) throw new Error(`unexpected ${path}`);
    return routes[path];
  }) as unknown as <T>(path: string) => Promise<T>;
}

describe("escudoTools", () => {
  it("exposes exactly the four contract tools, all read-only, none that approves", () => {
    const tools = escudoTools(fakeGet({}));
    expect(tools.map((t) => t.name)).toEqual(["getCoverage", "getDeductible", "findRepairShops", "getClaimStatus"]);
    expect(tools.every((t) => t.annotations.readOnlyHint)).toBe(true);
    expect(tools.some((t) => /approv/i.test(t.name))).toBe(false);
  });

  it("getCoverage lists coverages and checks an incident when asked", async () => {
    const get = fakeGet({ "/policy": POLICY, "/coverage/check?incident_type=theft": { covered: false, notes: "No" } });
    const tool = escudoTools(get).find((t) => t.name === "getCoverage")!;
    const result = (await tool.execute({ incident_type: "theft" })) as { coverages: { id: string; included: boolean }[]; check: unknown };
    expect(result.coverages.find((c) => c.id === "towing")?.included).toBe(true);
    expect(result.coverages.find((c) => c.id === "theft")?.included).toBe(false);
    expect(result.check).toEqual({ covered: false, notes: "No" });
  });

  it("getDeductible returns the amount in USD", async () => {
    const tool = escudoTools(fakeGet({ "/policy": POLICY })).find((t) => t.name === "getDeductible")!;
    expect(await tool.execute({})).toMatchObject({ deductible_usd: 500, currency: "USD", rental_days: 7 });
  });

  it("findRepairShops defaults to the latest claim", async () => {
    const get = fakeGet({ "/claims/latest": { claim_id: "CLM-9" }, "/claims/CLM-9/repair-options": [{ shop_id: "s1" }] });
    const tool = escudoTools(get).find((t) => t.name === "findRepairShops")!;
    expect(await tool.execute({})).toEqual({ claim_id: "CLM-9", shops: [{ shop_id: "s1" }] });
  });
});

describe("registration", () => {
  it("prefers document.modelContext (current spec) and falls back to navigator.modelContext", () => {
    const doc = { registerTool: vi.fn() };
    const nav = { registerTool: vi.fn() };
    expect(findModelContext({ document: { modelContext: doc }, navigator: { modelContext: nav } })).toBe(doc);
    expect(findModelContext({ document: {}, navigator: { modelContext: nav } })).toBe(nav);
    expect(findModelContext({ document: {}, navigator: {} })).toBeNull();
  });

  it("registers every tool with one AbortSignal that unregisters them all", async () => {
    const seen: { tool: EscudoTool; signal?: AbortSignal }[] = [];
    const mc = { registerTool: async (tool: EscudoTool, o?: { signal?: AbortSignal }) => void seen.push({ tool, signal: o?.signal }) };
    const controller = await registerTools(mc, escudoTools(fakeGet({})));
    expect(seen).toHaveLength(4);
    controller.abort();
    expect(seen.every((s) => s.signal?.aborted)).toBe(true);
  });
});
