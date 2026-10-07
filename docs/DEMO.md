# Escudo: guion de la demo

El recorrido en vivo de la charla, pantalla por pantalla: de «tuve un choque» al reclamo firmado, con
exactamente tres aprobaciones con huella. Al final vienen dos demostraciones de un minuto: la app y el
sitio como herramientas para cualquier agente (AppFunctions y WebMCP).

El día de la charla no se ejecuta ningún comando: abres una página y el emulador, y todo lo demás pasa
en la app, en el emulador y en Chrome. Las capturas son de una corrida contra la nube (el número de
reclamo cambia en cada corrida).

Duración del recorrido principal: **unos 2 minutos** de sistema más lo que hables (la evaluación de
daños tarda de 6 a 15 s y cada paso del asistente de 7 a 25 s, según lo que tarde Gemini).

[![Qué se prueba en la demo](diagrams/demo-coverage.png)](diagrams/demo-coverage.svg)

## Qué abres

| Dónde | Qué |
| --- | --- |
| Proyector, pantalla completa | Chrome: https://escudo-web-611681112050.us-central1.run.app/presentador |
| Laptop, mitad izquierda | El emulador **Pixel_9_API_36_1** (Android Studio → **Device Manager** → ▶). La app Escudo ya está instalada, con sesión, fotos del choque en la galería y una huella registrada |
| Laptop, junto al emulador | Los controles extendidos del emulador (botón **⋯** de su barra lateral) en **Fingerprint**: ahí está el botón **Touch sensor**, que es la huella |
| Laptop, mitad derecha | Chrome con el portal en https://escudo-web-611681112050.us-central1.run.app/reclamo, para WebMCP |

El presentador es solo la pantalla del proyector para esta demo en vivo. El producto, lo que cualquiera
puede probar, son la app y el portal (https://escudo-web-611681112050.us-central1.run.app/).

Abre el presentador unos minutos antes de empezar. Los servicios en la nube se apagan solos cuando nadie
los usa; al abrir la página dice **«Preparando Escudo…»** mientras los despierta (unos 20 segundos) y
después **«Listo para empezar»**.

| Mientras despierta | Listo |
| --- | --- |
| ![Preparando Escudo](demo/00-presentador-preparando.png) | ![Presentador listo](demo/01-presentador-listo.png) |

El emulador abre tal como quedó: desbloqueado, con la app en la póliza. Si pide el PIN de pantalla, es
**1234**.

### Empezar desde cero

Si el presentador muestra un reclamo de un ensayo anterior: en la app, pestaña **Póliza** → **Empezar de
nuevo** → **Empezar**. Se borran el reclamo, las aprobaciones y la conversación; la app vuelve a la
póliza y el presentador a «Listo para empezar». Tarda unos segundos.

![Empezar de nuevo](demo/02b-empezar-de-nuevo.png)

## El recorrido

### 1. La póliza (10 s)

![Póliza en la app](demo/02-app-poliza.png)

- **Haz:** muestra la pantalla **Póliza** de la app.
- **Señala:** *funciones, no pantallas*: lo que ves aquí sale de `get_policy`, la misma función que
  usan el chat, MCP, AppFunctions y WebMCP.

### 2. Reportar el accidente (30 s)

| Elegir fotos | Reporte listo |
| --- | --- |
| ![Selector de fotos](demo/03-fotos-galeria.png) | ![Formulario](demo/04-reporte.png) |

- **Haz:** toca **Reportar accidente** y escribe en «¿Qué pasó?»:

  ```text
  Choque menor: me chocaron por detrás mientras esperaba en el semáforo. Nadie salió herido.
  ```

  En «¿Dónde fue?»:

  ```text
  Calzada Roosevelt, zona 11
  ```

  Toca **Elegir fotos de la galería** y marca **las tres fotos del Corolla gris** (las tres primeras
  de la galería), luego **Done**, y después **Enviar reporte**.
- **Señala:** la app reduce las fotos antes de subirlas; nadie llena formularios de daños.

### 3. Evaluación de daños (6 a 15 s)

| App | Presentador |
| --- | --- |
| ![Evaluación en la app](demo/05-evaluacion.png) | ![Presentador con el perito](demo/06-presentador-evaluacion.png) |

- **Haz:** espera la tarjeta «Reclamo CLM-… abierto · Daño moderado».
- **Señala:** abrir el reclamo y evaluar el daño son **automáticos** (etiqueta «automático» en el
  presentador): el Perito IA (Gemini con salida estructurada) no necesita permiso para mirar fotos.

### 4. Pedirle todo al asistente (unos 10 s hasta la primera pausa)

| Chat vacío | Primera pausa |
| --- | --- |
| ![Asistente](demo/07-asistente.png) | ![Chat en pausa](demo/08-chat-pausa-taller.png) |

- **Haz:** atrás → pestaña **Asistente** y escribe:

  ```text
  Tuve un choque menor, ya subí las fotos en la app. Ayúdame con todo, por favor.
  ```

  (o toca la sugerencia «Tuve un choque, ayúdame con todo»). Si en lugar de actuar pregunta algo,
  responde `Sí, adelante con todo.`
- **Mira el proyector:**

  ![Presentador en la primera pausa](demo/09-presentador-pausa-taller.png)

- **Señala**, en este orden:
  1. *Agent Card*: justo antes de las filas A2A pasa «Agente Escudo — Descubre a Red de Talleres por su
     Agent Card». El agente no conocía al socio: lo encontró por su tarjeta en
     `/.well-known/agent-card.json`.
  2. *MCP frente a A2A*: el asistente usa herramientas por **MCP** (grúa, talleres, auto); el trabajo de
     otras empresas (Grúas Aliadas, Red de Talleres) va de agente a agente por **A2A**.
  3. *Autonomía frente a aprobación*: grúa, cotizaciones y auto de reemplazo fueron automáticos. Reservar
     el taller **no**: la herramienta devolvió `pending_approval` y no cambió nada. Se detiene en ámbar.

## Las tres aprobaciones

Cada una sigue el mismo patrón: llega un push, la tarjeta de aprobación muestra exactamente lo que se
va a hacer, apruebas con la huella y el asistente sigue solo, sin que escribas nada.

La huella: con el diálogo de huella en pantalla, pulsa **Touch sensor** en los controles extendidos del
emulador (**⋯** → **Fingerprint**). Si no responde a la primera, púlsalo otra vez: el sensor empieza a
escuchar un instante después de que aparece el diálogo.

### 5. Aprobación 1: reservar taller (unos 15 s, hasta 40 s si Gemini tarda)

| Tarjeta | Huella |
| --- | --- |
| ![Aprobación del taller](demo/10-aprobacion-taller.png) | ![Diálogo de huella](demo/11-huella.png) |

- **Haz:** en el chat toca **Revisar y aprobar** (o la notificación, o la pestaña **Aprobaciones**),
  luego **Aprobar con huella**, y pulsa **Touch sensor**.
- **Señala:** aprobar **no es una herramienta**. Solo existe `POST /approvals/{id}/approve`, firmado con
  la llave del teléfono, y la aprobación vale para *este* taller y *este* horario, una sola vez.
- **En el presentador:** aparece «Andrea — Aprobó con huella: reservar taller» con insignia Android,
  el contador pasa a **1 de 3**, el asistente reserva el taller y se detiene en el deducible:

  ![Presentador en la segunda pausa](demo/12-presentador-deducible.png)

### 6. Aprobación 2: aceptar el deducible (unos 10 s)

| Chat | Tarjeta |
| --- | --- |
| ![Chat en el deducible](demo/13-chat-deducible.png) | ![Aprobación del deducible](demo/14-aprobacion-deducible.png) |

- **Haz:** **Revisar y aprobar** → **Aprobar con huella** → **Touch sensor**.
- **Señala:** el monto (USD 500) está en la tarjeta del teléfono, no en el chat: el humano aprueba lo
  que el servidor va a ejecutar, no lo que el modelo dice.
- **En el presentador:** **2 de 3**, «Deducible de USD 500 aceptado» y la pausa de la firma:

  ![Presentador en la tercera pausa](demo/15-presentador-firma.png)

### 7. Aprobación 3: firmar y enviar (unos 10 s)

| Chat | Tarjeta |
| --- | --- |
| ![Chat en la firma](demo/16-chat-firma.png) | ![Aprobación de la firma](demo/17-aprobacion-firma.png) |

- **Haz:** **Revisar y aprobar** → **Aprobar con huella** → **Touch sensor**.
- **Señala:** tres acciones con consecuencias, tres huellas. Todo lo demás lo hizo el agente solo.

### 8. Reclamo firmado

| Chat | Reclamo |
| --- | --- |
| ![Chat final](demo/18-chat-firmado.png) | ![Reclamo firmado](demo/19-reclamo-firmado.png) |

![Presentador al final](demo/20-presentador-firmado.png)

- **Haz:** muestra la pestaña **Reclamo** («Reclamo firmado y enviado»).
- **Señala:** el reloj se detuvo; el banner verde dice «3 aprobaciones, todas de Andrea en su teléfono».

## Demostraciones adicionales (1 min cada una): la app y el sitio como herramientas para cualquier agente

Muestran las mismas capacidades fuera del chat, en Android y en la web: con AppFunctions, Android ofrece funciones de la app a
los agentes del sistema; con WebMCP, el sitio ofrece herramientas al agente del navegador.

### AppFunctions: la app como herramienta del sistema (1 min)

En el emulador ya corre el **AppFunction Testing Agent**, el agente de prueba de Google para
AppFunctions (un agente externo a Escudo, con el permiso del sistema para llamar funciones de otras
apps).

1. Ábrelo desde las apps recientes del emulador (desliza hacia arriba desde la barra inferior y
   mantén) o desde su ícono **AppFunction Testing Agent**. Toca el buscador: Escudo aparece fijada
   arriba; tócala.
2. Aparecen las tres funciones que la app ofrece al sistema: `getClaimStatus`, `reportAccident` y
   `getCoverage`.

   ![Funciones de Escudo en el agente de prueba](demo/21a-agente-funciones.png)

3. Toca **getCoverage**, escribe `collision` en `incidentType` y toca **Run**. El agente muestra la
   respuesta: póliza `POL-GT-2027-0042`, Andrea Morales, Toyota Corolla 2022, placa `P-512KLM` y las
   coberturas; más abajo, el deducible de USD 500. **Confirm** cierra el resultado.

   ![Resultado de getCoverage](demo/21b-agente-resultado.png)

4. Repite con **getClaimStatus** (sin parámetros): «Reclamo firmado y enviado».

![AppFunctions en el presentador](demo/21-presentador-appfunctions.png)

- **Señala:** el presentador dice **Agente externo** con insignia **AppFunctions**, en la misma línea de
  tiempo del reclamo. Son las mismas funciones que la pantalla Póliza. Solo hay funciones de lectura y
  de reporte: aprobar no existe como AppFunction.

### WebMCP: el sitio como herramienta del navegador (1 min)

1. En Chrome, el portal https://escudo-web-611681112050.us-central1.run.app/reclamo con la sesión de
   Andrea. El pie dice «WebMCP · 4 herramientas disponibles para el agente de tu navegador».
2. DevTools (`⌥⌘I`) → pestaña **Application** → en la barra lateral, sección *Application*,
   **WebMCP**. Abajo, en **Available Tools**, aparecen `findRepairShops`, `getClaimStatus`,
   `getCoverage` y `getDeductible`, cada una con su descripción.
3. Elige **getDeductible**; en el panel **Details** baja a **Run Tool** y pulsa **Run Tool** (sin
   entrada). Arriba, en la actividad, aparece `getDeductible · Completed`; en la pestaña **Output**:

   ```json
   {"deductible_usd":500,"currency":"USD","rental_days":7,"note":"…"}
   ```

![WebMCP en DevTools: getDeductible ejecutada](demo/22-webmcp-devtools.png)

- **Señala:** las cuatro tienen la marca `readOnly` (se ve en **Details → Flags**); ninguna aprueba. En
  el presentador aparece como **Agente externo** con insignia **WebMCP**.

Esta parte necesita, una sola vez en ese Chrome, el flag `chrome://flags/#enable-webmcp-testing` en
**Enabled** y la sesión de Andrea iniciada en el portal (ver [ONBOARDING.md, sección
9](ONBOARDING.md#9-mostrar-appfunctions-y-webmcp)). Las dos cosas quedan guardadas en el navegador.

## Si algo falla

| Síntoma | Qué hacer |
| --- | --- |
| El presentador se queda en «Preparando Escudo…» más de un minuto | Recarga la pestaña. Si sigue, revisa la conexión a internet de la laptop |
| El presentador dice «Sin conexión con Escudo. Reintentando…» | Se reconecta solo; si tarda, recarga la pestaña |
| El chat dice «Reconectando» o no responde en 30 s | Sal y vuelve a la pestaña **Asistente**: la conversación sigue (vive en la nube). Si se quedó callado tras una aprobación, escribe `sigue` |
| La huella no responde | Pulsa otra vez **Touch sensor**; si no hay diálogo, toca otra vez **Aprobar con huella** |
| No llega el push | Abre la pestaña **Aprobaciones**: la pendiente está ahí |
| Estado sucio (reclamo viejo, contador raro) | **Póliza** → **Empezar de nuevo** → **Empezar** |
| El agente de prueba de AppFunctions no aparece en recientes o no lista funciones | Salta esa demostración: el emulador arrancó desde cero y el agente necesita prepararse de nuevo (ONBOARDING, sección 9) |
| Gemini lento o caído | https://escudo-web-611681112050.us-central1.run.app/presentador?demo=1: corrida real grabada, rotulada **REPETICIÓN GRABADA**. Dilo en voz alta |
| Sin red | El video [`escudo-chat-run.mp4`](escudo-chat-run.mp4) (83 s, también rotulado como repetición) |
| El portal dice «No pudimos iniciar sesión» | Salta la demostración de WebMCP |

Después de la charla no hace falta nada: los servicios vuelven a apagarse solos cuando nadie los usa.
