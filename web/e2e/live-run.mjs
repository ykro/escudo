// Live rehearsal capture: deployed presenter open while `make chat-scenario CLOUD=1` drives the real chat flow.
//   node web/e2e/live-run.mjs   (from escudo/)
import { chromium } from "@playwright/test";
import { spawn } from "node:child_process";

const WEB = process.env.ESCUDO_WEB_URL ?? "https://escudo-web-611681112050.us-central1.run.app";
const OUT = "docs/screenshots";

const browser = await chromium.launch({ channel: "chrome" });
const page = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
await run("make", ["reset", "CLOUD=1"]);
await page.goto(`${WEB}/presentador`);
await page.getByText("Listo para empezar").waitFor({ timeout: 30_000 });
await page.screenshot({ path: `${OUT}/presenter-empty.png` });

const scenario = run("make", ["chat-scenario", "CLOUD=1"]);
const pause = page.getByRole("alert", { name: /esperando/i });
await pause.waitFor({ timeout: 150_000 });
await page.waitForTimeout(600); // let the entrance settle
await page.screenshot({ path: `${OUT}/presenter-chat-pause.png` });
await page.getByText("Reclamo firmado", { exact: true }).waitFor({ timeout: 150_000 });
await page.waitForTimeout(800);
await page.screenshot({ path: `${OUT}/presenter-chat-end.png` });
const code = await scenario;
await browser.close();
process.exit(code);

function run(cmd, args) {
  return new Promise((resolve) => {
    const child = spawn(cmd, args, { stdio: ["ignore", "pipe", "pipe"] });
    child.stdout.on("data", (d) => process.stdout.write(String(d).split("\n").filter((l) => /✓|⏸|total|reset|evaluación/.test(l)).map((l) => l + "\n").join("")));
    child.on("close", resolve);
  });
}
