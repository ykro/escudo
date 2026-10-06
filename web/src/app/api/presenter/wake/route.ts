import { config } from "@/lib/config";

export const dynamic = "force-dynamic";
export const runtime = "nodejs";

// The API scales to zero between talks; its first request after a while pays a cold start
// (~20 s, it also warms its A2A partners). The presenter calls this on load so the cloud is awake
// before the first tap on the phone: no command, no warm instance kept between talks.
const SURFACES = [
  "/health",
  "/a2a/escudo/.well-known/agent-card.json",
  "/a2a/tow/.well-known/agent-card.json",
  "/a2a/repair/.well-known/agent-card.json",
];

export async function GET() {
  const started = Date.now();
  try {
    const results = await Promise.all(
      SURFACES.map((path) => fetch(config.apiUrl + path, { cache: "no-store", signal: AbortSignal.timeout(90_000) })),
    );
    const ok = results.every((r) => r.ok);
    return Response.json({ ok, seconds: (Date.now() - started) / 1000 }, { status: ok ? 200 : 503 });
  } catch {
    return Response.json({ ok: false, seconds: (Date.now() - started) / 1000 }, { status: 503 });
  }
}
