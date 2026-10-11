import test from "node:test";
import assert from "node:assert/strict";
import {
  MCP_TOOLS,
  A2A_AGENT_CARD,
  computeArgsHash,
  signSettlementChallenge,
  verifySettlementGate,
  executeMcpTool,
} from "./server.mjs";

const sampleSettlementArgs = {
  claimId: "CLM-2026-089",
  amountGtq: 3400,
  recipient: "Taller Multimarcas Zona 10 (Cuenta GT82-ESCUDO-99)",
};

test("1. Catálogo MCP clasifica correctamente los 3 niveles de autonomía (Verde / Ámbar / Rojo) y Descriptor A2A 1.0", () => {
  const greenTools = MCP_TOOLS.filter(t => t.autonomyTier === "GREEN");
  const amberTools = MCP_TOOLS.filter(t => t.autonomyTier === "AMBER");
  const redTools = MCP_TOOLS.filter(t => t.autonomyTier === "RED");

  assert.equal(greenTools.length, 2);
  assert.equal(amberTools.length, 2);
  assert.equal(redTools.length, 1);
  assert.equal(greenTools.every(t => t.annotations.readOnlyHint === true), true);
  assert.equal(redTools[0].name, "approve_settlement");
  assert.equal(redTools[0].annotations.destructiveHint, true);
  assert.equal(A2A_AGENT_CARD.protocolVersion, "1.0");
});

test("2. Herramientas Ámbar devuelven pausa elicitation_required hasta recibir confirmación explícita del usuario", () => {
  const paused = executeMcpTool("assign_workshop", {
    claimId: "CLM-2026-089",
    workshopId: "WS-Z10-01",
    dropOffDate: "2026-10-12",
  }, { elicitationConfirmed: false });

  assert.equal(paused.httpStatus, 202);
  assert.equal(paused.status, "elicitation_required");
  assert.equal(paused.autonomyTier, "AMBER");
  assert.ok(paused.elicitationSchema.properties.confirmAction);

  const confirmed = executeMcpTool("assign_workshop", {
    claimId: "CLM-2026-089",
    workshopId: "WS-Z10-01",
    dropOffDate: "2026-10-12",
  }, { elicitationConfirmed: true });

  assert.equal(confirmed.httpStatus, 200);
  assert.equal(confirmed.status, "confirmed");
});

test("3. Ataque 1 (Prompt Injection sin firma en hardware) es bloqueado con 403 MISSING_SIGNATURE", () => {
  const verdict = verifySettlementGate({
    claimId: "CLM-2026-089",
    amountGtq: 25000,
    recipient: "Cuenta Atacante GT00-9999",
  }, null, new Set());

  assert.equal(verdict.allowed, false);
  assert.equal(verdict.httpStatus, 403);
  assert.equal(verdict.code, "MISSING_SIGNATURE");
});

test("4. Ataque 2 (Tampering de Q3,400 a Q15,000 tras firmar) es bloqueado con 403 ARGS_HASH_MISMATCH", () => {
  const signed = signSettlementChallenge(sampleSettlementArgs);
  const tamperedArgs = {
    ...sampleSettlementArgs,
    amountGtq: 15000,
  };

  const verdict = verifySettlementGate(tamperedArgs, signed.token, new Set());
  assert.equal(verdict.allowed, false);
  assert.equal(verdict.httpStatus, 403);
  assert.equal(verdict.code, "ARGS_HASH_MISMATCH");
  assert.notEqual(verdict.computedArgsHash, verdict.signedArgsHash);
});

test("5. Ataque 3 (Replay Attack reenviando el mismo JTI dos veces) aprueba el primero y bloquea el segundo con 403 JTI_ALREADY_CONSUMED", () => {
  const localJtiStore = new Set();
  const signed = signSettlementChallenge(sampleSettlementArgs);

  const firstAttempt = verifySettlementGate(sampleSettlementArgs, signed.token, localJtiStore);
  assert.equal(firstAttempt.allowed, true);
  assert.equal(firstAttempt.httpStatus, 200);
  assert.equal(firstAttempt.code, "SETTLEMENT_DISBURSED");

  const replayAttempt = verifySettlementGate(sampleSettlementArgs, signed.token, localJtiStore);
  assert.equal(replayAttempt.allowed, false);
  assert.equal(replayAttempt.httpStatus, 403);
  assert.equal(replayAttempt.code, "JTI_ALREADY_CONSUMED");
});

test("6. computeArgsHash produce un digest SHA-256 determinista independiente del orden de propiedades de entrada", () => {
  const h1 = computeArgsHash({
    claimId: "CLM-2026-089",
    amountGtq: 3400,
    recipient: "Taller Multimarcas Zona 10 (Cuenta GT82-ESCUDO-99)",
  });
  const h2 = computeArgsHash({
    recipient: "Taller Multimarcas Zona 10 (Cuenta GT82-ESCUDO-99)",
    amountGtq: 3400,
    claimId: "CLM-2026-089",
  });
  assert.equal(h1, h2);
  assert.equal(h1.length, 64);
});
