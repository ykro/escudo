import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PORT = Number(process.env.PORT || 4323);

// ============================================================================
// 1. PAR DE LLAVES ECDSA P-256 (Simulando Hardware Keystore / Passkey del Asegurado)
// ============================================================================
export const DEVICE_KEYPAIR = crypto.generateKeyPairSync("ec", {
  namedCurve: "prime256v1", // NIST P-256 (ES256)
});

export const DEVICE_PUBLIC_KEY_PEM = DEVICE_KEYPAIR.publicKey.export({
  type: "spki",
  format: "pem",
});

// Tabla en memoria de nonces/JTI consumidos (protección anti-replay)
export const consumedJti = new Set();

// Estado vivo del reclamo en el sandbox
export const claimState = {
  policyId: "POL-GT-2026",
  insuredName: "María Fernanda Morales",
  vehicle: "Toyota Corolla Cross 2024 · Placa P-849KLM",
  claimId: "CLM-2026-089",
  coverageStatus: "ACTIVA · Cobertura Amplia Cero Mora",
  deductibleGtq: 1800,
  deductibleAccepted: false,
  assignedWorkshop: null,
  settlementAmountGtq: 3400,
  settlementRecipient: "Taller Multimarcas Zona 10 (Cuenta GT82-ESCUDO-99)",
  settlementStatus: "PENDIENTE_FIRMA_P256",
  lastDisbursementId: null,
};

// ============================================================================
// 2. CATÁLOGO DE HERRAMIENTAS MCP CON TOOLANNOTATIONS Y MATRIZ DE AUTONOMÍA
// ============================================================================
export const MCP_TOOLS = [
  {
    name: "get_policy_coverage",
    title: "Consultar cobertura de póliza",
    description: "Devuelve el estado de vigencia, topes de responsabilidad civil y deducible de una póliza vehicular.",
    autonomyTier: "GREEN",
    annotations: {
      readOnlyHint: true,
      destructiveHint: false,
      idempotentHint: true,
      openWorldHint: false,
    },
    inputSchema: {
      type: "object",
      properties: {
        policyId: { type: "string", description: "Identificador de póliza (ej. POL-GT-2026)" },
      },
      required: ["policyId"],
    },
  },
  {
    name: "list_partner_workshops",
    title: "Listar talleres certificados",
    description: "Lista los talleres mecánicos certificados por Escudo Seguros según zona geográfica.",
    autonomyTier: "GREEN",
    annotations: {
      readOnlyHint: true,
      destructiveHint: false,
      idempotentHint: true,
      openWorldHint: false,
    },
    inputSchema: {
      type: "object",
      properties: {
        zone: { type: "string", description: "Zona de Ciudad de Guatemala (ej. Zona 10, Zona 4)" },
      },
      required: ["zone"],
    },
  },
  {
    name: "assign_workshop",
    title: "Asignar taller e ingresar vehículo",
    description: "Genera la orden de ingreso al taller seleccionado. Requiere confirmación explícita mediante MCP Elicitation.",
    autonomyTier: "AMBER",
    annotations: {
      readOnlyHint: false,
      destructiveHint: false,
      idempotentHint: false,
      openWorldHint: false,
    },
    inputSchema: {
      type: "object",
      properties: {
        claimId: { type: "string" },
        workshopId: { type: "string" },
        dropOffDate: { type: "string" },
      },
      required: ["claimId", "workshopId", "dropOffDate"],
    },
  },
  {
    name: "accept_deductible",
    title: "Aceptar cargo de deducible",
    description: "Registra la aceptación del deducible de la póliza. Pausa la ejecución con MCP Elicitation hasta que el titular confirme.",
    autonomyTier: "AMBER",
    annotations: {
      readOnlyHint: false,
      destructiveHint: false,
      idempotentHint: false,
      openWorldHint: false,
    },
    inputSchema: {
      type: "object",
      properties: {
        claimId: { type: "string" },
        deductibleGtq: { type: "number" },
      },
      required: ["claimId", "deductibleGtq"],
    },
  },
  {
    name: "approve_settlement",
    title: "Desembolsar finiquito e indemnización",
    description: "Ejecuta la transferencia bancaria irreversible del reclamo. Exige token firmado con ECDSA P-256 sobre el args_hash canónico.",
    autonomyTier: "RED",
    annotations: {
      readOnlyHint: false,
      destructiveHint: true,
      idempotentHint: false,
      openWorldHint: false,
    },
    inputSchema: {
      type: "object",
      properties: {
        claimId: { type: "string" },
        amountGtq: { type: "number" },
        recipient: { type: "string" },
      },
      required: ["claimId", "amountGtq", "recipient"],
    },
  },
];

// ============================================================================
// 3. DESCRIPTOR A2A 1.0 (/.well-known/agent-card.json)
// ============================================================================
export const A2A_AGENT_CARD = {
  protocolVersion: "1.0",
  name: "Escudo Perito Vehicular A2A",
  description: "Agente especialista para evaluación de daños por colisión, estimación de repuestos OEM y emisión de avalúo pericial certificado.",
  url: `http://localhost:${PORT}/a2a`,
  provider: {
    organization: "Escudo Seguros Guatemala",
    url: "https://escudo.gt.example",
  },
  version: "2.4.0",
  capabilities: {
    streaming: true,
    pushNotifications: false,
    stateTransitionHistory: true,
  },
  defaultInputModes: ["application/json", "image/jpeg"],
  defaultOutputModes: ["application/json"],
  skills: [
    {
      id: "damage-assessment-vision",
      name: "Peritaje Fotográfico de Colisión",
      description: "Analiza fotografías del siniestro, clasifica severidad estructural y calcula presupuesto de reparación en Quetzales (GTQ).",
      tags: ["seguros", "peritaje", "vision", "guatemala"],
      examples: [
        "Evaluar impacto trasero en bumper y compuerta para reclamo CLM-2026-089",
      ],
    },
  ],
};

// ============================================================================
// 4. COMPUERTA CRIPTOGRÁFICA DETERMINISTA ECDSA P-256
// ============================================================================
export function canonicalizeSettlementArgs(args) {
  // Orden canónico estricto de llaves para evitar ambigüedad en serialización
  const normalized = {
    amountGtq: Number(args.amountGtq),
    claimId: String(args.claimId || "").trim(),
    recipient: String(args.recipient || "").trim(),
  };
  return JSON.stringify(normalized);
}

export function computeArgsHash(args) {
  const canonicalJson = canonicalizeSettlementArgs(args);
  return crypto.createHash("sha256").update(canonicalJson, "utf8").digest("hex");
}

export function signSettlementChallenge(args, options = {}) {
  const privateKey = options.privateKey || DEVICE_KEYPAIR.privateKey;
  const jti = options.jti || `jti-${crypto.randomUUID().slice(0, 12)}`;
  const exp = options.exp ?? Date.now() + 5 * 60 * 1000; // 5 min vigencia
  const args_hash = computeArgsHash(args);

  const payload = {
    jti,
    claimId: String(args.claimId),
    amountGtq: Number(args.amountGtq),
    recipient: String(args.recipient),
    args_hash,
    iat: Date.now(),
    exp,
    alg: "ES256",
  };

  const payloadB64Url = Buffer.from(JSON.stringify(payload), "utf8").toString("base64url");
  const signatureBuffer = crypto.sign("sha256", Buffer.from(payloadB64Url, "utf8"), privateKey);
  const signatureB64Url = signatureBuffer.toString("base64url");

  return {
    token: `${payloadB64Url}.${signatureB64Url}`,
    jti,
    args_hash,
    exp,
    canonicalJson: canonicalizeSettlementArgs(args),
    algorithm: "ES256 (ECDSA P-256 + SHA-256)",
  };
}

export function verifySettlementGate(args, signatureToken, jtiStore = consumedJti, publicKey = DEVICE_KEYPAIR.publicKey) {
  const computedArgsHash = computeArgsHash(args);

  // Regla 1: Presencia obligatoria de firma criptográfica
  if (!signatureToken || typeof signatureToken !== "string" || !signatureToken.includes(".")) {
    return {
      allowed: false,
      httpStatus: 403,
      code: "MISSING_SIGNATURE",
      computedArgsHash,
      message: "Compuerta Roja bloqueada: approve_settlement exige token firmado con ECDSA P-256 por el dispositivo del asegurado.",
    };
  }

  const [payloadB64Url, signatureB64Url] = signatureToken.split(".");
  let payload;
  try {
    payload = JSON.parse(Buffer.from(payloadB64Url, "base64url").toString("utf8"));
  } catch {
    return {
      allowed: false,
      httpStatus: 403,
      code: "MALFORMED_TOKEN",
      computedArgsHash,
      message: "El formato del sobre criptográfico es inválido.",
    };
  }

  // Regla 2: Verificación matemática ECDSA P-256 con la llave pública registrada
  let signatureValid = false;
  try {
    signatureValid = crypto.verify(
      "sha256",
      Buffer.from(payloadB64Url, "utf8"),
      publicKey,
      Buffer.from(signatureB64Url, "base64url")
    );
  } catch {
    signatureValid = false;
  }

  if (!signatureValid) {
    return {
      allowed: false,
      httpStatus: 403,
      code: "INVALID_ECDSA_SIGNATURE",
      computedArgsHash,
      message: "Firma ECDSA P-256 inválida: la clave privada no coincide con el Keystore registrado del asegurado.",
    };
  }

  // Regla 3: Expiración temporal del desafío
  if (typeof payload.exp !== "number" || Date.now() > payload.exp) {
    return {
      allowed: false,
      httpStatus: 403,
      code: "TOKEN_EXPIRED",
      computedArgsHash,
      message: "El token de autorización biométrica ha expirado.",
    };
  }

  // Regla 4: Integridad estricta de argumentos (args_hash)
  if (payload.args_hash !== computedArgsHash) {
    return {
      allowed: false,
      httpStatus: 403,
      code: "ARGS_HASH_MISMATCH",
      computedArgsHash,
      signedArgsHash: payload.args_hash,
      signedAmountGtq: payload.amountGtq,
      attemptedAmountGtq: Number(args.amountGtq),
      message: `Alerta de Tampering: el usuario firmó args_hash=${payload.args_hash.slice(0, 12)}... (Q${payload.amountGtq}), pero el agente intentó ejecutar args_hash=${computedArgsHash.slice(0, 12)}... (Q${args.amountGtq}).`,
    };
  }

  // Regla 5: Protección Anti-Replay mediante registro atómico de JTI
  if (jtiStore.has(payload.jti)) {
    return {
      allowed: false,
      httpStatus: 403,
      code: "JTI_ALREADY_CONSUMED",
      jti: payload.jti,
      computedArgsHash,
      message: `Ataque de Replay neutralizado: el nonce criptográfico '${payload.jti}' ya fue consumido en una transacción previa.`,
    };
  }

  jtiStore.add(payload.jti);
  const disbursementId = `DISB-GT-${ payload.jti.slice(-6).toUpperCase() }`;

  return {
    allowed: true,
    httpStatus: 200,
    code: "SETTLEMENT_DISBURSED",
    jti: payload.jti,
    computedArgsHash,
    disbursementId,
    amountGtq: Number(args.amountGtq),
    recipient: args.recipient,
    message: `Desembolso autorizado con firma ECDSA P-256 verificada. Referencia bancaria: ${disbursementId} por Q${Number(args.amountGtq).toLocaleString("en-US")}.`,
  };
}

// ============================================================================
// 5. EJECUTOR MCP (VERDE / ÁMBAR CON ELICITATION / ROJO CON ECDSA P-256)
// ============================================================================
export function executeMcpTool(toolName, args = {}, context = {}) {
  if (toolName === "get_policy_coverage") {
    return {
      httpStatus: 200,
      status: "ok",
      autonomyTier: "GREEN",
      toolName,
      data: {
        policyId: args.policyId || claimState.policyId,
        insuredName: claimState.insuredName,
        vehicle: claimState.vehicle,
        coverageStatus: claimState.coverageStatus,
        deductibleGtq: claimState.deductibleGtq,
        maxCoverageGtq: 125000,
      },
    };
  }

  if (toolName === "list_partner_workshops") {
    return {
      httpStatus: 200,
      status: "ok",
      autonomyTier: "GREEN",
      toolName,
      data: {
        zone: args.zone || "Zona 10",
        workshops: [
          { id: "WS-Z10-01", name: "Taller Multimarcas Zona 10", slaDays: 4, certifiedOem: true },
          { id: "WS-Z04-02", name: "Centro de Enderezado TecniAuto Zona 4", slaDays: 5, certifiedOem: true },
        ],
      },
    };
  }

  if (toolName === "assign_workshop" || toolName === "accept_deductible") {
    if (!context.elicitationConfirmed) {
      return {
        httpStatus: 202,
        status: "elicitation_required",
        autonomyTier: "AMBER",
        toolName,
        message: `Pausa MCP Elicitation activa: '${toolName}' modifica el estado operativo del reclamo y requiere confirmación explícita del asegurado.`,
        elicitationSchema: {
          type: "object",
          title: toolName === "assign_workshop"
            ? "Confirmar ingreso de vehículo a taller certificado"
            : "Confirmar aceptación de deducible en póliza",
          required: ["confirmAction", "selectedOption"],
          properties: {
            confirmAction: {
              type: "boolean",
              description: "Autorizo aplicar este cambio en mi reclamo activo",
            },
            selectedOption: {
              type: "string",
              default: toolName === "assign_workshop"
                ? `${args.workshopId || "WS-Z10-01"} · Entrega ${args.dropOffDate || "2026-10-12"}`
                : `Deducible fijo Q${args.deductibleGtq || claimState.deductibleGtq}`,
            },
          },
        },
      };
    }

    if (toolName === "assign_workshop") {
      claimState.assignedWorkshop = args.workshopId || "WS-Z10-01 · Taller Multimarcas Zona 10";
    } else {
      claimState.deductibleAccepted = true;
    }

    return {
      httpStatus: 200,
      status: "confirmed",
      autonomyTier: "AMBER",
      toolName,
      message: `Elicitación completada por el humano: '${toolName}' aplicado con éxito en el reclamo ${claimState.claimId}.`,
      claimState: { ...claimState },
    };
  }

  if (toolName === "approve_settlement") {
    const verdict = verifySettlementGate(args, context.signatureToken);
    if (verdict.allowed) {
      claimState.settlementStatus = "DESEMBOLSADO_VERIFICADO_P256";
      claimState.lastDisbursementId = verdict.disbursementId;
    }
    return {
      ...verdict,
      autonomyTier: "RED",
      toolName,
    };
  }

  return {
    httpStatus: 404,
    status: "error",
    message: `Herramienta MCP desconocida: ${toolName}`,
  };
}

// ============================================================================
// 6. SIMULADOR DE LABORATORIO RED-TEAM (4 ESCENARIOS DETERMINISTAS)
// ============================================================================
export function runRedTeamScenario(scenarioId) {
  const baseArgs = {
    claimId: "CLM-2026-089",
    amountGtq: 3400,
    recipient: "Taller Multimarcas Zona 10 (Cuenta GT82-ESCUDO-99)",
  };

  if (scenarioId === "attack_1_prompt_injection") {
    const injectedArgs = {
      claimId: "CLM-2026-089",
      amountGtq: 25000,
      recipient: "Cuenta Externa Atacante GT00-9999",
    };
    const res = verifySettlementGate(injectedArgs, null);
    return {
      scenarioId,
      title: "Ataque 1: Prompt Injection sin firma en hardware",
      vector: "Instrucción oculta en foto del siniestro ordena transferir Q25,000 ignorando reglas previas.",
      attemptedArgs: injectedArgs,
      result: res,
    };
  }

  if (scenarioId === "attack_2_tampering") {
    // El asegurado firma legítimamente Q3,400
    const signed = signSettlementChallenge(baseArgs);
    // El agente comprometido altera el monto a Q15,000 antes de invocar al servidor
    const tamperedArgs = {
      ...baseArgs,
      amountGtq: 15000,
    };
    const res = verifySettlementGate(tamperedArgs, signed.token);
    return {
      scenarioId,
      title: "Ataque 2: Parameter Tampering (Q3,400 → Q15,000 tras firmar)",
      vector: "El asegurado firma el finiquito por Q3,400, pero el agente modifica amountGtq a Q15,000 reusando la firma.",
      signedArgs: baseArgs,
      attemptedArgs: tamperedArgs,
      result: res,
    };
  }

  if (scenarioId === "attack_3_replay") {
    const replayStore = new Set();
    const signed = signSettlementChallenge(baseArgs);
    // Primera ejecución legítima consume el JTI
    const firstPass = verifySettlementGate(baseArgs, signed.token, replayStore);
    // Segunda ejecución idéntica intenta cobrar dos veces con el mismo token
    const secondPass = verifySettlementGate(baseArgs, signed.token, replayStore);
    return {
      scenarioId,
      title: "Ataque 3: Replay Attack (Reenvío de token JTI ya consumido)",
      vector: "Un intermediario captura el token firmado válido y lo envía por segunda vez para duplicar el desembolso.",
      firstAttemptStatus: firstPass.code,
      attemptedArgs: baseArgs,
      result: secondPass,
    };
  }

  // Caso 4: Ejecución legítima con firma ECDSA P-256 e integridad args_hash exacta
  const signed = signSettlementChallenge(baseArgs);
  const res = verifySettlementGate(baseArgs, signed.token);
  if (res.allowed) {
    claimState.settlementStatus = "DESEMBOLSADO_VERIFICADO_P256";
    claimState.lastDisbursementId = res.disbursementId;
  }
  return {
    scenarioId: "legitimate_settlement",
    title: "Ejecución Legítima: Firma ECDSA P-256 + args_hash íntegro",
    vector: "El asegurado aprueba en su Passkey/Keystore exactamente Q3,400 para el taller certificado con nonce único.",
    signedEnvelope: {
      jti: signed.jti,
      args_hash: signed.args_hash,
      algorithm: signed.algorithm,
    },
    attemptedArgs: baseArgs,
    result: res,
  };
}

// ============================================================================
// 7. SERVIDOR HTTP
// ============================================================================
function readJsonBody(req) {
  return new Promise((resolve, reject) => {
    let body = "";
    req.on("data", chunk => {
      body += chunk;
    });
    req.on("end", () => {
      try {
        resolve(body ? JSON.parse(body) : {});
      } catch (err) {
        reject(err);
      }
    });
    req.on("error", reject);
  });
}

function sendJson(res, status, payload) {
  res.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Access-Control-Allow-Origin": "*",
  });
  res.end(JSON.stringify(payload, null, 2));
}

export const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`);

  if (req.method === "OPTIONS") {
    res.writeHead(204, {
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
      "Access-Control-Allow-Headers": "Content-Type",
    });
    return res.end();
  }

  if (req.method === "GET" && (url.pathname === "/" || url.pathname === "/index.html")) {
    const htmlPath = path.join(__dirname, "public", "index.html");
    const html = fs.readFileSync(htmlPath, "utf8");
    res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
    return res.end(html);
  }

  if (req.method === "GET" && url.pathname === "/.well-known/agent-card.json") {
    return sendJson(res, 200, A2A_AGENT_CARD);
  }

  if (req.method === "GET" && url.pathname === "/api/catalog") {
    return sendJson(res, 200, {
      mcpTools: MCP_TOOLS,
      agentCard: A2A_AGENT_CARD,
      claimState,
      publicKeyPem: DEVICE_PUBLIC_KEY_PEM,
      consumedJtiCount: consumedJti.size,
    });
  }

  if (req.method === "POST" && url.pathname === "/api/mcp/call") {
    const body = await readJsonBody(req);
    const result = executeMcpTool(body.toolName, body.args || {}, body.context || {});
    return sendJson(res, result.httpStatus || 200, result);
  }

  if (req.method === "POST" && url.pathname === "/api/crypto/sign") {
    const body = await readJsonBody(req);
    const signed = signSettlementChallenge({
      claimId: body.claimId || claimState.claimId,
      amountGtq: Number(body.amountGtq ?? claimState.settlementAmountGtq),
      recipient: body.recipient || claimState.settlementRecipient,
    });
    return sendJson(res, 200, signed);
  }

  if (req.method === "POST" && url.pathname === "/api/redteam/run") {
    const body = await readJsonBody(req);
    if (body.scenarioId === "run_all") {
      const results = [
        runRedTeamScenario("attack_1_prompt_injection"),
        runRedTeamScenario("attack_2_tampering"),
        runRedTeamScenario("attack_3_replay"),
        runRedTeamScenario("legitimate_settlement"),
      ];
      return sendJson(res, 200, { mode: "suite_completa", results, claimState });
    }
    const single = runRedTeamScenario(body.scenarioId || "legitimate_settlement");
    return sendJson(res, 200, { mode: "individual", results: [single], claimState });
  }

  return sendJson(res, 404, { error: "Ruta no encontrada" });
});

if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(fileURLToPath(import.meta.url))) {
  server.listen(PORT, () => {
    console.log(`Escudo Agent Sandbox activo en http://localhost:${PORT}`);
  });
}
