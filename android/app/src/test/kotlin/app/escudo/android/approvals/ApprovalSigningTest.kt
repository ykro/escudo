package app.escudo.android.approvals

import app.escudo.android.data.api.EscudoJson
import app.escudo.android.data.model.DecisionRequest
import app.escudo.android.data.model.RegisterDeviceRequest
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalSigningTest {
  @Test
  fun `signed message follows escudo-approval v1`() {
    assertEquals(
      "escudo-approval:v1:apr_123:sha256:abc:approve",
      ApprovalMessage.text("apr_123", "sha256:abc", Decision.APPROVE),
    )
    assertEquals(
      "escudo-approval:v1:apr_123:sha256:abc:reject",
      ApprovalMessage.text("apr_123", "sha256:abc", Decision.REJECT),
    )
  }

  @Test
  fun `message refuses ambiguous or missing parts`() {
    assertThrows(IllegalArgumentException::class.java) { ApprovalMessage.text("apr:1", "h", Decision.APPROVE) }
    assertThrows(IllegalArgumentException::class.java) { ApprovalMessage.text("apr_1", "", Decision.APPROVE) }
  }

  @Test
  fun `signature is base64 DER ECDSA that the public key verifies`() {
    val signer = JvmApprovalSigner()
    val message = ApprovalMessage.bytes("apr_9", "sha256:ff", Decision.APPROVE)
    val signatureB64 = signer.newSignature().run { update(message); ApprovalMessage.base64(sign()) }

    val der = Base64.getDecoder().decode(signatureB64)
    assertEquals(0x30, der[0].toInt() and 0xFF) // DER SEQUENCE, not raw r||s
    val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(signer.publicKeyBase64())))
    assertTrue(Signature.getInstance("SHA256withECDSA").run { initVerify(publicKey); update(message); verify(der) })
  }

  @Test
  fun `request bodies match the contract`() {
    assertEquals(
      """{"device_id":"dev-1","signature":"MEUCIQ=="}""",
      EscudoJson.encodeToString(DecisionRequest.serializer(), DecisionRequest("dev-1", "MEUCIQ==")),
    )
    assertEquals(
      """{"device_id":"dev-1","fcm_token":"tok","platform":"android","public_key":"MFkw"}""",
      EscudoJson.encodeToString(RegisterDeviceRequest.serializer(), RegisterDeviceRequest(deviceId = "dev-1", fcmToken = "tok", publicKey = "MFkw")),
    )
  }

  @Test
  fun `approval_forbidden details are recognized`() {
    assertEquals(Refusal.BAD_SIGNATURE, Refusal.fromErrorBody("""HTTP 403 /approvals/a/approve: {"error":"approval_forbidden","detail":"bad_signature","message":"x"}"""))
    assertEquals(Refusal.UNKNOWN_DEVICE, Refusal.fromErrorBody("""{"error": "approval_forbidden", "detail": "unknown_device"}"""))
    assertEquals(null, Refusal.fromErrorBody("""{"error":"approval_invalid","detail":"already_used"}"""))
    assertTrue(Refusal.NO_DEVICE.fixedByRegistering)
    assertTrue(!Refusal.NOT_YOUR_APPROVAL.fixedByRegistering)
  }
}
