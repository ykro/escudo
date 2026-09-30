package app.escudo.android.approvals

import app.escudo.android.data.EscudoRepository
import app.escudo.android.data.api.FakeEscudoApi
import app.escudo.android.data.events.TimelineEvents
import app.escudo.android.data.model.Approval
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalFlowTest {
  private val api = FakeEscudoApi(latencyMs = 0)
  private val events = TimelineEvents(api, TestScope(UnconfinedTestDispatcher()))
  private val repository = EscudoRepository(api)

  private val approval = Approval(
    approvalId = "apr_sign",
    tool = "sign_claim",
    reason = "Firmar requiere tu aprobación",
    summary = "Firmar y enviar el reclamo",
    status = "pending",
  )

  private fun flow(result: VerificationResult, calls: MutableList<String> = mutableListOf()) =
    ApprovalFlow(repository, { calls += it.approvalId; result }, { "device-123" }, events)

  private suspend fun pendingIds() = repository.pendingApprovals().map { it.approvalId }

  @Test
  fun `approves only after the person passes the biometric check`() = runTest {
    api.seedApproval(approval)
    val verifierCalls = mutableListOf<String>()

    val outcome = flow(VerificationResult.Verified, verifierCalls).approve(approval)

    assertEquals(ApprovalOutcome.Approved, outcome)
    assertEquals(listOf("apr_sign"), verifierCalls)
    assertTrue(pendingIds().isEmpty())
  }

  @Test
  fun `cancelling the biometric prompt leaves the request pending`() = runTest {
    api.seedApproval(approval)

    val outcome = flow(VerificationResult.Cancelled).approve(approval)

    assertEquals(ApprovalOutcome.Cancelled, outcome)
    assertEquals(listOf("apr_sign"), pendingIds())
  }

  @Test
  fun `no biometrics or PIN on the device means no approval`() = runTest {
    api.seedApproval(approval)

    val outcome = flow(VerificationResult.Unavailable("sin huella")).approve(approval)

    assertEquals(ApprovalOutcome.Failed("sin huella"), outcome)
    assertEquals(listOf("apr_sign"), pendingIds())
  }

  @Test
  fun `an approval is single use`() = runTest {
    api.seedApproval(approval)
    val flow = flow(VerificationResult.Verified)

    assertEquals(ApprovalOutcome.Approved, flow.approve(approval))
    assertEquals(ApprovalOutcome.AlreadyResolved, flow.approve(approval))
  }

  @Test
  fun `unknown approval is reported as already resolved`() = runTest {
    assertEquals(ApprovalOutcome.AlreadyResolved, flow(VerificationResult.Verified).approve(approval))
  }

  @Test
  fun `rejecting needs no biometrics`() = runTest {
    api.seedApproval(approval)
    val verifierCalls = mutableListOf<String>()

    val outcome = flow(VerificationResult.Verified, verifierCalls).reject(approval)

    assertEquals(ApprovalOutcome.Rejected, outcome)
    assertTrue(verifierCalls.isEmpty())
    assertTrue(pendingIds().isEmpty())
  }

  @Test
  fun `a verified approval is visible on the presenter timeline`() = runTest {
    api.seedApproval(approval)

    flow(VerificationResult.Verified).approve(approval)

    val event = api.events.single()
    assertEquals("android", event.surface)
    assertEquals("customer", event.actor)
    assertEquals("apr_sign", event.approvalId)
  }
}
