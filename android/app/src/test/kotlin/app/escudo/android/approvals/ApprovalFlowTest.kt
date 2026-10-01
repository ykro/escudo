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
    argsHash = "sha256:abc",
  )
  private val signer = JvmApprovalSigner()

  /** Registers this test device's key with the fake server, which then verifies every decision. */
  private suspend fun registerDevice(deviceId: String = "device-123") = api.registerDevice("fcm", deviceId, signer.publicKeyBase64())

  private enum class Mode { VERIFIED, CANCELLED, UNAVAILABLE }

  private fun flow(mode: Mode, calls: MutableList<String> = mutableListOf(), deviceId: String = "device-123") =
    ApprovalFlow(
      repository,
      { a, _, signature ->
        calls += a.approvalId
        when (mode) {
          Mode.VERIFIED -> VerificationResult.Verified(signature)
          Mode.CANCELLED -> VerificationResult.Cancelled
          Mode.UNAVAILABLE -> VerificationResult.Unavailable("sin huella")
        }
      },
      signer, { deviceId }, events,
    )

  private suspend fun pendingIds() = repository.pendingApprovals().map { it.approvalId }

  @Test
  fun `approves only after the person passes the biometric check`() = runTest {
    api.seedApproval(approval)
    val verifierCalls = mutableListOf<String>()

    val outcome = flow(Mode.VERIFIED, verifierCalls).approve(approval)

    assertEquals(ApprovalOutcome.Approved, outcome)
    assertEquals(listOf("apr_sign"), verifierCalls)
    assertTrue(pendingIds().isEmpty())
  }

  @Test
  fun `cancelling the biometric prompt leaves the request pending`() = runTest {
    api.seedApproval(approval)

    val outcome = flow(Mode.CANCELLED).approve(approval)

    assertEquals(ApprovalOutcome.Cancelled, outcome)
    assertEquals(listOf("apr_sign"), pendingIds())
  }

  @Test
  fun `no biometrics or PIN on the device means no approval`() = runTest {
    api.seedApproval(approval)

    val outcome = flow(Mode.UNAVAILABLE).approve(approval)

    assertEquals(ApprovalOutcome.Failed("sin huella"), outcome)
    assertEquals(listOf("apr_sign"), pendingIds())
  }

  @Test
  fun `an approval is single use`() = runTest {
    api.seedApproval(approval)
    val flow = flow(Mode.VERIFIED)

    assertEquals(ApprovalOutcome.Approved, flow.approve(approval))
    assertEquals(ApprovalOutcome.AlreadyResolved, flow.approve(approval))
  }

  @Test
  fun `unknown approval is reported as already resolved`() = runTest {
    assertEquals(ApprovalOutcome.AlreadyResolved, flow(Mode.VERIFIED).approve(approval))
  }

  @Test
  fun `rejecting is signed after the person authenticates too`() = runTest {
    api.seedApproval(approval)
    registerDevice()
    val verifierCalls = mutableListOf<String>()

    val outcome = flow(Mode.VERIFIED, verifierCalls).reject(approval)

    assertEquals(ApprovalOutcome.Rejected, outcome)
    assertEquals(listOf("apr_sign"), verifierCalls)
    assertTrue(pendingIds().isEmpty())
  }

  @Test
  fun `server accepts the signature of the registered device key`() = runTest {
    api.seedApproval(approval)
    registerDevice()

    assertEquals(ApprovalOutcome.Approved, flow(Mode.VERIFIED).approve(approval))
  }

  @Test
  fun `a different device or key cannot approve`() = runTest {
    api.seedApproval(approval)
    registerDevice("device-123")

    // Same token, other device id: rejected by the server.
    assertEquals(ApprovalOutcome.Refused(Refusal.UNKNOWN_DEVICE), flow(Mode.VERIFIED, deviceId = "other-device").approve(approval))
    // Same device id, but the key changed without re-registering: bad signature.
    signer.reset()
    assertEquals(ApprovalOutcome.Refused(Refusal.BAD_SIGNATURE), flow(Mode.VERIFIED).approve(approval))
    assertEquals(listOf("apr_sign"), pendingIds())
  }

  @Test
  fun `approval without args_hash cannot be signed`() = runTest {
    val unsigned = approval.copy(argsHash = null)
    api.seedApproval(unsigned)

    assertEquals(ApprovalOutcome.Failed(null), flow(Mode.VERIFIED).approve(unsigned))
  }

  @Test
  fun `a verified approval is visible on the presenter timeline`() = runTest {
    api.seedApproval(approval)

    flow(Mode.VERIFIED).approve(approval)

    val event = api.events.single()
    assertEquals("android", event.surface)
    assertEquals("customer", event.actor)
    assertEquals("apr_sign", event.approvalId)
  }
}
