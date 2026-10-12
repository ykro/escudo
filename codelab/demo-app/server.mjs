import http from "node:http";
import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PORT = Number(process.env.PORT || 4323);
const SECRET_KEY = "escudo-demo-secret-2027";

// Estado inicial del reclamo
export const CLAIM_STATE = {
  claimId: "ESC-2027-042",
  insuredName: "Lucía Morales",
  vehicle: "Toyota Corolla Cross 2025",
  coverageLimitGtq: 25000,
  deductibleGtq: 1200,
  estimateGtq: 3400,
  assignedWorkshop: "Taller Miraflores (Zona 11)",
  status: "Listo para liquidación",
  workshops: [
    { id: "w-miraflores", name: "Taller Miraflores (Zona 11)", etaDays: 3 },
    { id: "w-pradera", name: "Centro Automotriz Pradera (Zona 10)", etaDays: 4 }
  ]
};

const usedReceipts = new Set();

// Catálogo de 3 herramientas con sus 3 niveles de permiso
export const AGENT_TOOLS = [
  {
    name: "consultar_poliza",
    level: "Lectura directa",
    badgeColor: "green",
    description: "Consulta cobertura, deducible y talleres disponibles sin modificar datos."
  },
  {
    name: "agendar_taller",
    level: "Confirmación del usuario",
    badgeColor: "amber",
    description: "Prepara el cambio de taller, pero solicita confirmación en pantalla antes de guardar."
  },
  {
    name: "aprobar_reembolso",
    level: "Autorización de pago",
    badgeColor: "red",
    description: "Transfiere el reembolso únicamente si incluye un recibo de autorización válido para ese monto."
  }
];

// Nivel 1: Solo lectura
export function consultarPoliza() {
  return {
    ok: true,
    level: "Nivel 1 · Solo Lectura",
    title: "Póliza y cobertura consultadas sin fricción",
    summary: `El asistente leyó la póliza ${CLAIM_STATE.claimId} de ${CLAIM_STATE.insuredName}: cobertura hasta Q${CLAIM_STATE.coverageLimitGtq.toLocaleString()}, deducible de Q${CLAIM_STATE.deductibleGtq.toLocaleString()} y avalúo aprobado por Q${CLAIM_STATE.estimateGtq.toLocaleString()}.`,
    data: {
      claimId: CLAIM_STATE.claimId,
      vehicle: CLAIM_STATE.vehicle,
      deductibleGtq: CLAIM_STATE.deductibleGtq,
      estimateGtq: CLAIM_STATE.estimateGtq,
      assignedWorkshop: CLAIM_STATE.assignedWorkshop,
      workshops: CLAIM_STATE.workshops
    }
  };
}

// Nivel 2: Confirmación antes de modificar datos
export function agendarTaller({ workshopId, userConfirmed = false }) {
  const workshop = CLAIM_STATE.workshops.find((w) => w.id === workshopId);
  if (!workshop) {
    return {
      ok: false,
      errorCode: "TALLER_NO_ENCONTRADO",
      recoverable: true,
      suggestedNextTool: "consultar_poliza",
      title: "Taller no encontrado — Error con guía para el asistente",
      summary: `El identificador "${workshopId}" no existe. El servidor le indica al asistente que llame primero a consultar_poliza para ver los talleres válidos.`
    };
  }

  if (!userConfirmed) {
    return {
      ok: false,
      requiresConfirmation: true,
      level: "Nivel 2 · Pausa de Confirmación",
      title: "Confirmación solicitada antes de cambiar el taller",
      summary: `El asistente propuso asignar "${workshop.name}" (${workshop.etaDays} días hábiles) con un deducible de Q${CLAIM_STATE.deductibleGtq.toLocaleString()}. El servidor pausa la acción hasta que la asegurada confirme en su pantalla.`,
      pendingAction: {
        workshopId: workshop.id,
        workshopName: workshop.name,
        etaDays: workshop.etaDays,
        deductibleGtq: CLAIM_STATE.deductibleGtq
      }
    };
  }

  CLAIM_STATE.assignedWorkshop = workshop.name;
  return {
    ok: true,
    level: "Nivel 2 · Cambio Confirmado",
    title: `Taller actualizado a ${workshop.name}`,
    summary: `Después de recibir la confirmación de la asegurada, el reclamo ${CLAIM_STATE.claimId} quedó asignado a ${workshop.name}.`,
    data: { assignedWorkshop: CLAIM_STATE.assignedWorkshop }
  };
}

// Genera un recibo de autorización vinculado al reclamo y al monto exacto
export function createApprovalReceipt({ claimId, amountGtq, receiptId = crypto.randomUUID() }) {
  const payload = `${claimId}:${Number(amountGtq)}:${receiptId}`;
  const signature = crypto.createHmac("sha256", SECRET_KEY).update(payload).digest("hex").slice(0, 16);
  return {
    receiptId,
    claimId,
    authorizedAmountGtq: Number(amountGtq),
    signature
  };
}

// Nivel 3: Autorización vinculada al monto exacto para desembolsos de dinero
export function aprobarReembolso({ claimId, amountGtq, approvalReceipt }) {
  if (!approvalReceipt || !approvalReceipt.signature) {
    return {
      ok: false,
      blocked: true,
      errorCode: "FALTA_AUTORIZACION",
      level: "Nivel 3 · Bloqueo de Seguridad",
      title: "Pago bloqueado: El asistente intentó desembolsar sin autorización del usuario",
      summary: "Para transferir dinero no basta con que el asistente envíe un parámetro confirmado=true. El servidor exige un recibo de autorización emitido desde el dispositivo del asegurado."
    };
  }

  const expectedPayload = `${claimId}:${Number(amountGtq)}:${approvalReceipt.receiptId}`;
  const expectedSig = crypto.createHmac("sha256", SECRET_KEY).update(expectedPayload).digest("hex").slice(0, 16);

  if (expectedSig !== approvalReceipt.signature) {
    return {
      ok: false,
      blocked: true,
      errorCode: "MONTO_ALTERADO",
      level: "Nivel 3 · Bloqueo de Seguridad",
      title: `Pago bloqueado: El monto solicitado (Q${Number(amountGtq).toLocaleString()}) no coincide con el autorizado (Q${Number(approvalReceipt.authorizedAmountGtq).toLocaleString()})`,
      summary: "El usuario autorizó Q3,400, pero la llamada intentó cobrar un monto distinto. Como la firma del recibo incluye el monto exacto, el servidor rechaza la operación."
    };
  }

  if (usedReceipts.has(approvalReceipt.receiptId)) {
    return {
      ok: false,
      blocked: true,
      errorCode: "RECIBO_YA_UTILIZADO",
      level: "Nivel 3 · Bloqueo de Seguridad",
      title: "Pago bloqueado: Este recibo de autorización ya fue utilizado antes",
      summary: "Cada autorización sirve para una sola transferencia. Si alguien intenta reenviar el mismo recibo por segunda vez, el servidor lo rechaza."
    };
  }

  usedReceipts.add(approvalReceipt.receiptId);
  return {
    ok: true,
    level: "Nivel 3 · Reembolso Autorizado",
    title: `Reembolso de Q${Number(amountGtq).toLocaleString()} desembolsado con éxito`,
    summary: `El servidor comprobó que el recibo ${approvalReceipt.receiptId.slice(0, 8)} fue emitido para el reclamo ${claimId} por exactamente Q${Number(amountGtq).toLocaleString()} y liberó la transferencia.`,
    data: {
      claimId,
      disbursedGtq: Number(amountGtq),
      receiptId: approvalReceipt.receiptId,
      signature: approvalReceipt.signature
    }
  };
}

export function resetUsedReceipts() {
  usedReceipts.clear();
}

export function createServer() {
  return http.createServer(async (req, res) => {
    const url = new URL(req.url, `http://${req.headers.host}`);

    if (req.method === "GET" && url.pathname === "/api/state") {
      res.writeHead(200, { "Content-Type": "application/json; charset=utf-8" });
      res.end(JSON.stringify({ claim: CLAIM_STATE, tools: AGENT_TOOLS }));
      return;
    }

    if (req.method === "POST" && url.pathname === "/api/action") {
      let body = "";
      for await (const chunk of req) body += chunk;
      const parsed = JSON.parse(body || "{}");
      let result;

      if (parsed.action === "read") {
        result = consultarPoliza();
      } else if (parsed.action === "schedule_pause") {
        result = agendarTaller({ workshopId: "w-pradera", userConfirmed: false });
      } else if (parsed.action === "schedule_confirm") {
        result = agendarTaller({ workshopId: "w-pradera", userConfirmed: true });
      } else if (parsed.action === "pay_authorized") {
        const receipt = createApprovalReceipt({ claimId: CLAIM_STATE.claimId, amountGtq: 3400 });
        result = aprobarReembolso({ claimId: CLAIM_STATE.claimId, amountGtq: 3400, approvalReceipt: receipt });
      } else if (parsed.action === "pay_tampered") {
        const receipt = createApprovalReceipt({ claimId: CLAIM_STATE.claimId, amountGtq: 3400 });
        result = aprobarReembolso({ claimId: CLAIM_STATE.claimId, amountGtq: 15000, approvalReceipt: receipt });
      } else if (parsed.action === "pay_no_receipt") {
        result = aprobarReembolso({ claimId: CLAIM_STATE.claimId, amountGtq: 3400, approvalReceipt: null });
      } else {
        result = consultarPoliza();
      }

      res.writeHead(200, { "Content-Type": "application/json; charset=utf-8" });
      res.end(JSON.stringify({ result, claim: CLAIM_STATE }));
      return;
    }

    const publicDir = path.join(__dirname, "public");
    const filePath = path.join(publicDir, url.pathname === "/" ? "index.html" : url.pathname);
    if (fs.existsSync(filePath) && fs.statSync(filePath).isFile()) {
      res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
      res.end(fs.readFileSync(filePath));
      return;
    }

    res.writeHead(404, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ error: "Not found" }));
  });
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  createServer().listen(PORT, () => {
    console.log(`Escudo Seguros (Built for Agents) escuchando en http://localhost:${PORT}`);
  });
}
