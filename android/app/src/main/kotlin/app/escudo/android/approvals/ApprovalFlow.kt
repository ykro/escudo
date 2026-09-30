package app.escudo.android.approvals

import android.util.Log
import app.escudo.android.data.EscudoRepository
import app.escudo.android.data.api.ApiException
import app.escudo.android.data.events.Surface
import app.escudo.android.data.events.TimelineEvents
import app.escudo.android.data.model.Approval

/** Proof that a person is holding the phone. Implemented with BiometricPrompt in the app. */
fun interface UserVerifier {
  suspend fun verify(approval: Approval): VerificationResult
}

sealed interface VerificationResult {
  data object Verified : VerificationResult
  data object Cancelled : VerificationResult
  data class Unavailable(val message: String) : VerificationResult
}

sealed interface ApprovalOutcome {
  data object Approved : ApprovalOutcome
  data object Rejected : ApprovalOutcome
  data object Cancelled : ApprovalOutcome
  data object AlreadyResolved : ApprovalOutcome
  /** [message] is already user-facing (e.g. "set up a fingerprint"); null means a generic failure. */
  data class Failed(val message: String?) : ApprovalOutcome
}

/**
 * The human half of the approval protocol (contracts/tools.md):
 * biometric check on this device first, then `POST /approvals/{id}/approve`.
 *
 * This is the only place in the app that can approve, and it is reachable only from the
 * approvals screen. It is never an AppFunction: an agent can ask, but only a person can say yes.
 */
class ApprovalFlow(
  private val repository: EscudoRepository,
  private val verifier: UserVerifier,
  private val deviceId: () -> String,
  private val events: TimelineEvents,
) {
  suspend fun approve(approval: Approval): ApprovalOutcome {
    when (val verification = verifier.verify(approval)) {
      VerificationResult.Cancelled -> return ApprovalOutcome.Cancelled
      is VerificationResult.Unavailable -> return ApprovalOutcome.Failed(verification.message)
      VerificationResult.Verified -> Unit
    }
    // The backend records approval_granted; this adds the phone-only fact that a person proved presence.
    events.post(
      Surface.ANDROID, TimelineEvents.Kind.MESSAGE, "Identidad verificada con biometría en el teléfono",
      tool = approval.tool, approvalId = approval.approvalId,
    )
    return try {
      repository.approve(approval.approvalId, deviceId())
      ApprovalOutcome.Approved
    } catch (e: ApiException) {
      when (e.code) {
        404, 409 -> ApprovalOutcome.AlreadyResolved
        else -> ApprovalOutcome.Failed(null).also { Log.w(TAG, "approve failed", e) }
      }
    } catch (e: Exception) {
      Log.w(TAG, "approve failed", e)
      ApprovalOutcome.Failed(null)
    }
  }

  /** Saying "no" needs no biometrics: it can only make the agent do less. */
  suspend fun reject(approval: Approval): ApprovalOutcome = try {
    repository.reject(approval.approvalId)
    ApprovalOutcome.Rejected
  } catch (e: ApiException) {
    if (e.code == 404 || e.code == 409) ApprovalOutcome.AlreadyResolved else ApprovalOutcome.Failed(null)
  } catch (e: Exception) {
    Log.w(TAG, "reject failed", e)
    ApprovalOutcome.Failed(null)
  }

  private companion object {
    const val TAG = "ApprovalFlow"
  }
}
