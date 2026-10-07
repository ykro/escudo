import { config } from "@/lib/config";

export const dynamic = "force-dynamic";
export const runtime = "nodejs";

// The API scales to zero between talks; its first request after a while pays a cold start
// (~15 s, it also warms its A2A partners). The presenter calls this on load so the cloud is awake
// before the first tap on the phone: no command, no warm instance kept between talks.
// /health goes first and alone: four requests at once to a sleeping service start up to three
// instances, each paying the cold start; one awake instance then answers the rest in milliseconds.
const SURFACES = [
  "/a2a/escudo/.well-known/agent-card.json",
  "/a2a/tow/.well-known/agent-card.json",
  "/a2a/repair/.well-known/agent-card.json",
];

export async function GET() {
  const started = Date.now();
  try {
    const get = (path: string) => fetch(config.apiUrl + path, { cache: "no-store", signal: AbortSignal.timeout(90_000) });
    const health = await get("/health");
    const results = health.ok ? await Promise.all(SURFACES.map(get)) : [];
    const ok = health.ok && results.every((r) => r.ok);
    return Response.json({ ok, seconds: (Date.now() - started) / 1000 }, { status: ok ? 200 : 503 });
  } catch {
    return Response.json({ ok: false, seconds: (Date.now() - started) / 1000 }, { status: 503 });
  }
}
