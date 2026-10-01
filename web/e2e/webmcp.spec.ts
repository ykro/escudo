import { chromium, expect, test } from "@playwright/test";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

/**
 * WebMCP in real Chrome (150+), with chrome://flags/#enable-webmcp-testing turned on through the
 * profile's Local State. Needs the deployed API (the tools call it): E2E_WEBMCP=1 npm run e2e.
 */
test.skip(!process.env.E2E_WEBMCP, "set E2E_WEBMCP=1 (uses the cloud API and a headed Chrome)");
const EMAIL = process.env.ESCUDO_CUSTOMER_EMAIL ?? "";
const PASSWORD = process.env.ESCUDO_CUSTOMER_PASSWORD ?? "";

test("the portal registers exactly four WebMCP tools and they answer", async ({ baseURL }) => {
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), "escudo-webmcp-"));
  fs.writeFileSync(
    path.join(profile, "Local State"),
    JSON.stringify({ browser: { enabled_labs_experiments: ["enable-webmcp-testing@1", "devtools-webmcp-support@1"] } }),
  );
  const context = await chromium.launchPersistentContext(profile, { channel: "chrome", headless: false });
  try {
    const page = await context.newPage();
    await page.goto(`${baseURL}/entrar`);
    // The seeded customer (make seed writes the login to escudo/.env.local).
    await page.getByLabel("Correo").fill(EMAIL);
    await page.getByLabel("Contraseña").fill(PASSWORD);
    await page.getByRole("button", { name: "Entrar", exact: true }).click();
    await expect(page.getByText("WebMCP · 4 herramientas")).toBeVisible({ timeout: 30_000 });

    const result = await page.evaluate(async () => {
      type Tool = { name: string; annotations?: { readOnlyHint?: boolean } };
      const mc = (document as unknown as { modelContext: { getTools(): Promise<Tool[]>; executeTool(t: Tool, input: string): Promise<string> } }).modelContext;
      const tools = await mc.getTools();
      const deductible = await mc.executeTool(tools.find((t) => t.name === "getDeductible")!, "{}");
      return { names: tools.map((t) => t.name).sort(), readOnly: tools.every((t) => t.annotations?.readOnlyHint), deductible: JSON.parse(deductible) };
    });
    expect(result.names).toEqual(["findRepairShops", "getClaimStatus", "getCoverage", "getDeductible"]);
    expect(result.readOnly).toBe(true);
    expect(result.deductible).toMatchObject({ deductible_usd: 500, currency: "USD" });
  } finally {
    await context.close();
    fs.rmSync(profile, { recursive: true, force: true });
  }
});
