import test from "node:test";
import assert from "node:assert/strict";
import {
  consultarPoliza,
  agendarTaller,
  createApprovalReceipt,
  aprobarReembolso,
  resetUsedReceipts
} from "./server.mjs";

test("1. Nivel 1 (consultarPoliza) devuelve los datos de la póliza de inmediato sin pedir confirmación", () => {
  const res = consultarPoliza();
  assert.equal(res.ok, true);
  assert.equal(res.data.claimId, "ESC-2027-042");
});

test("2. Nivel 2 (agendarTaller) solicita confirmación cuando userConfirmed es false y aplica el cambio cuando es true", () => {
  const pause = agendarTaller({ workshopId: "w-pradera", userConfirmed: false });
  assert.equal(pause.ok, false);
  assert.equal(pause.requiresConfirmation, true);

  const confirmed = agendarTaller({ workshopId: "w-pradera", userConfirmed: true });
  assert.equal(confirmed.ok, true);
  assert.match(confirmed.data.assignedWorkshop, /Pradera/);
});

test("3. Error guiado: si el taller no existe, indica al asistente que llame a consultar_poliza", () => {
  const err = agendarTaller({ workshopId: "taller-inexistente", userConfirmed: false });
  assert.equal(err.ok, false);
  assert.equal(err.suggestedNextTool, "consultar_poliza");
});

test("4. Nivel 3 (aprobarReembolso) bloquea pagos sin recibo o con monto alterado, y aprueba el monto exacto una sola vez", () => {
  resetUsedReceipts();
  const noReceipt = aprobarReembolso({ claimId: "ESC-2027-042", amountGtq: 3400, approvalReceipt: null });
  assert.equal(noReceipt.ok, false);
  assert.equal(noReceipt.errorCode, "FALTA_AUTORIZACION");

  const receipt = createApprovalReceipt({ claimId: "ESC-2027-042", amountGtq: 3400 });
  const tampered = aprobarReembolso({ claimId: "ESC-2027-042", amountGtq: 15000, approvalReceipt: receipt });
  assert.equal(tampered.ok, false);
  assert.equal(tampered.errorCode, "MONTO_ALTERADO");

  const valid = aprobarReembolso({ claimId: "ESC-2027-042", amountGtq: 3400, approvalReceipt: receipt });
  assert.equal(valid.ok, true);
  assert.equal(valid.data.disbursedGtq, 3400);

  const replay = aprobarReembolso({ claimId: "ESC-2027-042", amountGtq: 3400, approvalReceipt: receipt });
  assert.equal(replay.ok, false);
  assert.equal(replay.errorCode, "RECIBO_YA_UTILIZADO");
});
