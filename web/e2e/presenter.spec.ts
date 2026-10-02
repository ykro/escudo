import { expect, test } from "@playwright/test";

test.use({ viewport: { width: 1920, height: 1080 } });

test("presenter replays a run: pause in amber, 3 approvals, clock stops when signed", async ({ page }) => {
  await page.goto("/presenter?demo=1");
  await expect(page.getByRole("heading", { name: "Reclamo grabado" })).toBeVisible();
  await expect(page.getByText("REPETICIÓN GRABADA", { exact: true })).toBeVisible();

  const pause = page.getByRole("alert", { name: /esperando la aprobación/i });
  await expect(pause).toContainText("Esperando tu aprobación", { timeout: 40_000 });
  await expect(pause).toContainText("Solo Andrea puede aprobarlo");
  await expect(page.getByLabel("Pantalla del teléfono de la clienta")).toContainText("Aprobar con huella");
  await page.screenshot({ path: "e2e/screenshots/presenter-demo-pause.png" });

  await expect(page.getByText("3 de 3")).toBeVisible({ timeout: 60_000 });
  await expect(page.getByText("Reclamo firmado", { exact: true })).toBeVisible();
  await expect(page.getByRole("status").filter({ hasText: "3 aprobaciones, todas de Andrea" })).toBeVisible();
  await expect(pause).toHaveCount(0);
  await page.screenshot({ path: "e2e/screenshots/presenter-demo-end.png" });
});
