# Escudo web

Portal del cliente (póliza, coberturas, deducible, talleres, reclamo) con **WebMCP**, y la vista
**/presenter** para la charla. Next.js 16 en Cloud Run (`escudo-web`).

- Portal: https://escudo-web-611681112050.us-central1.run.app/
- Presentador: https://escudo-web-611681112050.us-central1.run.app/presenter (sin login; `?run=<id>` sigue
  una corrida —la imprimen `make scenario` y `make chat-scenario`—, sin `?run` la más reciente; `?demo=1`
  reproduce una corrida grabada, sin nube)

```bash
npm install
npm run dev          # http://localhost:3217 (usa .env.local; ver .env.example)
npm test             # unitarias (vitest)
npm run e2e          # Playwright con Chrome: presentador en modo demo
set -a; source ../.env.local; set +a
E2E_WEBMCP=1 npm run e2e   # + WebMCP real en Chrome, con la cuenta sembrada (usa el API desplegado)
```

## Herramientas WebMCP

`getCoverage`, `getDeductible`, `findRepairShops`, `getClaimStatus` (`src/lib/webmcp.ts`). Son de solo
lectura y llaman al mismo API REST que las pantallas, con `X-Escudo-Surface: webmcp`, así que cada
llamada aparece en la línea de tiempo con su insignia. Ninguna aprueba: aprobar solo existe en el teléfono.

### Verlas en el escenario (menos de un minuto)

1. Una vez antes de la charla: en Chrome 150+ abre `chrome://flags/#enable-webmcp-testing`, **Enabled**, relanza.
2. Abre el portal y entra con la cuenta de Andrea (correo y contraseña en `escudo/.env.local`, los crea
   `make seed`; la sesión queda guardada en el navegador). El pie dice
   «WebMCP · 4 herramientas disponibles…».
3. `⌥⌘I` → pestaña **Application** → **WebMCP**: aparecen las 4 herramientas con su descripción y
   esquema. Elige `getDeductible` → **Run tool** → respuesta `{"deductible_usd": 500, …}`, y la vista del
   presentador muestra el paso con la insignia **WebMCP**.

## Vista del presentador

Escenario fijo de 1920×1080 que se escala a cualquier proyector. Lee Firestore (`events`, `claims`,
`approvals`) **en el servidor** con la cuenta de servicio `escudo-web` (solo lectura) y lo envía por
Server-Sent Events (`/api/presenter/stream`): el navegador no necesita login ni acceso a la base.

- Línea de tiempo: reloj relativo, insignia de superficie (Android, Web, MCP, A2A, Chat, AppFunctions,
  WebMCP), agente y resultado. Las consultas de estado que hacen las pantallas no aparecen; las de agentes sí.
- Pausas de aprobación: banda ámbar (el único ámbar de la pantalla) con el motivo, fija hasta que
  Andrea aprueba en el teléfono.
- Encabezado: tiempo transcurrido (se detiene al firmar) y aprobaciones «n de 3».
- Una corrida a la vez: cada corrida de escenario tiene su propio cliente y `run_id`
  (`X-Escudo-Run-Id`); la vista muestra solo los eventos, el reclamo y las aprobaciones de la corrida que sigue.

### Espejo del teléfono: por qué es una tarjeta en vivo y no una captura

El panel derecho dibuja lo que muestra la app de Escudo **a partir del mismo estado del backend** que
lee la app (aprobación pendiente primero, luego el reclamo). No depende de USB, del emulador ni de un
proceso de captura que pueda caerse en el escenario, y siempre coincide con la línea de tiempo.
Si se quiere el píxel exacto, `scrcpy --serial "$(make -s android-serial)" --window-title "Escudo" --always-on-top`
(desde la raíz del repo, contra el emulador) y se coloca esa ventana sobre el panel; la vista no cambia.
