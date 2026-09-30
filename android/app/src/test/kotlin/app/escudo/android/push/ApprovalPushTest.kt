package app.escudo.android.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApprovalPushTest {
  @Test
  fun `data-only approval push`() {
    val push = ApprovalPush.parse(
      mapOf("type" to "approval_requested", "approval_id" to "apr_7", "summary" to "Firmar el reclamo"),
      title = null, body = null,
    )
    assertEquals(ApprovalPush("apr_7", null, "Firmar el reclamo"), push)
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
