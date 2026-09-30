package app.escudo.android.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApprovalPushTest {
  @Test
  fun `backend approval push (notification + data)`() {
    val push = ApprovalPush.parse(
      mapOf("type" to "approval_request", "approval_id" to "apr_7", "tool" to "sign_claim", "claim_id" to "clm_1", "reason" to "Requiere aprobación"),
      title = "Escudo necesita tu aprobación", body = "Firmar y enviar el reclamo",
    )
    assertEquals(ApprovalPush("apr_7", "Escudo necesita tu aprobación", "Firmar y enviar el reclamo"), push)
  }

  @Test
  fun `data-only push falls back to summary or reason`() {
    val push = ApprovalPush.parse(mapOf("type" to "approval_request", "approval_id" to "apr_8", "reason" to "Requiere aprobación"), null, null)
    assertEquals(ApprovalPush("apr_8", null, "Requiere aprobación"), push)
  }

  @Test
  fun `notification text wins over data`() {
    val push = ApprovalPush.parse(mapOf("approval_id" to "apr_7", "summary" to "x"), title = "Escudo", body = "Aprueba")
    assertEquals(ApprovalPush("apr_7", "Escudo", "Aprueba"), push)
  }

  @Test
  fun `other pushes are ignored`() {
    assertNull(ApprovalPush.parse(mapOf("type" to "claim_updated", "approval_id" to "apr_7"), null, null))
    assertNull(ApprovalPush.parse(mapOf("type" to "approval_requested"), null, null))
  }
}
