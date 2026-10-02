import { expect, test } from "@playwright/test";

/**
 * The stage fallback must work with the network unreachable: every request outside this machine is
 * aborted, and the replay still renders with the self-hosted font and no external fetches.
 *   make up-offline   then   E2E_BASE_URL=http://127.0.0.1:3217 npx playwright test e2e/offline.spec.ts
 */
test.use({ viewport: { width: 1920, height: 1080 } });

test("presenter replay works with no network", async ({ page, baseURL }) => {
  const local = new URL(baseURL ?? "http://127.0.0.1:3217").host;
  const external: string[] = [];
  await page.route("**/*", (route) => {
    const host = new URL(route.request().url()).host;
    if (host === local) return route.continue();
    external.push(route.request().url());
    return route.abort("internetdisconnected");
  });
  await page.goto("/presenter?demo=1");
  await expect(page.getByText("REPETICIÓN GRABADA", { exact: true })).toBeVisible();
  await expect(page.getByText(/en vivo/i)).toHaveCount(0);
  await expect(page.getByRole("alert", { name: /esperando la aprobación/i })).toBeVisible({ timeout: 60_000 });
  expect(await page.evaluate(() => document.fonts.check('40px "__localFont"') || [...document.fonts].some((f) => f.status === "loaded"))).toBe(true);
  expect(external).toEqual([]);
});
