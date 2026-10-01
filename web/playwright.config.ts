import { defineConfig } from "@playwright/test";

// E2E against a running web (default: `npm run dev` on :3217, started for you).
// E2E_BASE_URL=https://escudo-web-… runs the same specs against Cloud Run.
const baseURL = process.env.E2E_BASE_URL ?? "http://localhost:3217";

export default defineConfig({
  testDir: "e2e",
  timeout: 90_000,
  use: { baseURL, channel: "chrome" },
  webServer: process.env.E2E_BASE_URL ? undefined : { command: "npm run dev", url: `${baseURL}/presenter?demo=1`, reuseExistingServer: true },
});
