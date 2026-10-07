// Screenshots of every web page at phone, tablet, laptop and projector sizes (headless).
//   node web/e2e/responsive.mjs          (from escudo/; against Cloud Run)
//   ESCUDO_WEB_URL=http://localhost:3217 node web/e2e/responsive.mjs
// Logs in with the seeded customer from escudo/.env.local and writes web/e2e/screenshots/responsive/.
// Also fails if any page scrolls sideways (content wider than the screen).
import { chromium } from "@playwright/test";
import fs from "node:fs";

const WEB = process.env.ESCUDO_WEB_URL ?? "https://escudo-web-611681112050.us-central1.run.app";
const OUT = "web/e2e/screenshots/responsive";
const SIZES = [
  ["telefono-375", 375, 812],
  ["telefono-414", 414, 896],
  ["tableta-768", 768, 1024],
  ["laptop-1280", 1280, 800],
  ["proyector-1920", 1920, 1080],
];
const env = Object.fromEntries(
  fs.readFileSync(".env.local", "utf8").split("\n").filter((l) => /^[A-Z_]+=/.test(l)).map((l) => [l.slice(0, l.indexOf("=")), l.slice(l.indexOf("=") + 1)]),
);

fs.mkdirSync(OUT, { recursive: true });
const browser = await chromium.launch({ channel: "chrome", headless: true });
const problems = [];
for (const [name, width, height] of SIZES) {
  const context = await browser.newContext({ viewport: { width, height }, deviceScaleFactor: width < 800 ? 2 : 1 });
  const page = await context.newPage();
  const shoot = async (label) => {
    await page.waitForTimeout(700);
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    if (overflow > 1) problems.push(`${label} @${name}: ${overflow}px de desborde horizontal`);
    await page.screenshot({ path: `${OUT}/${label}-${name}.png`, fullPage: true });
  };
  await page.goto(`${WEB}/presentador`);
  await page.getByText(/Listo para empezar|Preparando Escudo/).first().waitFor({ timeout: 90_000 });
  await shoot("presentador");
  // the recorded run fills the timeline and stops at the first approval
  await page.goto(`${WEB}/presentador?demo=1`);
  await page.getByRole("alert").first().waitFor({ timeout: 90_000 });
  await shoot("presentador-repeticion");
  await page.getByText("Reclamo firmado", { exact: true }).first().waitFor({ timeout: 90_000 });
  await shoot("presentador-firmado");
  await page.goto(`${WEB}/entrar`);
  await page.getByLabel("Correo").waitFor();
  await shoot("entrar");
  await page.getByLabel("Correo").fill(env.ESCUDO_CUSTOMER_EMAIL);
  await page.getByLabel("Contraseña").fill(env.ESCUDO_CUSTOMER_PASSWORD);
  await page.getByRole("button", { name: "Entrar", exact: true }).click();
  await page.waitForURL((u) => !u.pathname.startsWith("/entrar"), { timeout: 30_000 });
  for (const [label, path] of [["poliza", "/"], ["reclamo", "/reclamo"], ["talleres", "/talleres"]]) {
    await page.goto(`${WEB}${path}`);
    await page.waitForLoadState("networkidle");
    await shoot(label);
  }
  await context.close();
}
await browser.close();
console.log(problems.length ? problems.join("\n") : "sin desborde horizontal en ninguna página");
process.exit(problems.length ? 1 : 0);
