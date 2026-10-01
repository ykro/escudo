# Versions (checked 2026-09-30)

| Piece | Version | Notes |
| --- | --- | --- |
| Next.js (App Router, Turbopack) | 16.3.8 | `output: "standalone"` for the Cloud Run image; React 19.2.8 |
| Firebase JS SDK | 12.19.0 | Auth only (email/password: the seeded customer). The browser never reads Firestore |
| firebase-admin (server) | 14.5.0 | Presenter feed: `onSnapshot` on db `escudo`, streamed as Server-Sent Events |
| WebMCP spec | W3C WebML CG draft, **2026-09-30** | `document.modelContext.registerTool(tool, { signal })` → Promise; `ModelContextTool {name, title, description, inputSchema, execute, annotations{readOnlyHint…}}`; unregister by aborting the signal |
| WebMCP in Chrome | verified on **Chrome 154.0.8037.59** (stable) | Behind `chrome://flags/#enable-webmcp-testing` (Chrome 150+ moved the API from `navigator.modelContext` to `document.modelContext`; we fall back to the old one). `document.modelContext.getTools()` / `executeTool(tool, jsonString)` exist for testing. DevTools → Application → **WebMCP** lists the tools and can run them |
| Playwright | 1.63.0 | e2e with the installed Chrome (`channel: "chrome"`) |
| Vitest | 5.0.3 | unit tests (jsdom) |
| Font | Onest (Google Fonts via `next/font`, self-hosted at build) | tokens: `src/brand/tokens.json` (direction "A · Sereno") |
