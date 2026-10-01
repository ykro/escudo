package app.escudo.android.approvals

import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Log
import app.escudo.android.data.EscudoRepository
import app.escudo.android.data.api.ApiException
import app.escudo.android.data.events.Surface
import app.escudo.android.data.events.TimelineEvents
import app.escudo.android.data.model.Approval
import java.security.Signature

/**
 * Proof that a person is holding this phone. Implemented with BiometricPrompt: [signature] is
 * passed as the CryptoObject and comes back unlocked in [VerificationResult.Verified].
 */
fun interface UserVerifier {
  suspend fun verify(approval: Approval, decision: Decision, signature: Signature): VerificationResult
}

sealed interface VerificationResult {
  /** The person authenticated; [signature] can now sign once. */
  class Verified(val signature: Signature) : VerificationResult
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

  /** The server refused the decision (`403 approval_forbidden`, escudo-approval:v1). */
  data class Refused(val reason: Refusal) : ApprovalOutcome
}

/** `detail` of `403 approval_forbidden` (contracts/tools.md). */
enum class Refusal(val wire: String) {
  NOT_YOUR_APPROVAL("not_your_approval"),
  NO_DEVICE("no_device"),
  UNKNOWN_DEVICE("unknown_device"),
  DEVICE_DISABLED("device_disabled"),
  BAD_SIGNATURE("bad_signature"),
  ;

  /** The server does not know this phone's current key: registering again fixes it. */
  val fixedByRegistering: Boolean get() = this == NO_DEVICE || this == UNKNOWN_DEVICE || this == BAD_SIGNATURE

  companion object {
    /** Finds the `detail` in an error body like `{"error":"approval_forbidden","detail":"bad_signature",…}`. */
    fun fromErrorBody(body: String): Refusal? {
      if ("approval_forbidden" !in body) return null
      val detail = Regex(""""detail"\s*:\s*"([a-z_]+)"""").find(body)?.groupValues?.get(1) ?: return null
      return entries.firstOrNull { it.wire == detail }
    }
  }
}

/**
 * The human half of the approval protocol `escudo-approval:v1` (contracts/tools.md):
 * biometric or PIN check on this device unlocks the device-bound key, the phone signs
 * `escudo-approval:v1:{approval_id}:{args_hash}:{approve|reject}`, then calls
 * `POST /approvals/{id}/approve|reject` with `{device_id, signature}`.
 *
 * This is the only place in the app that can approve, and it is reachable only from the
 * approvals screen. It is never an AppFunction: an agent can ask, but only a person can say yes.
 */
class ApprovalFlow(
  private val repository: EscudoRepository,
  private val verifier: UserVerifier,
  private val signer: ApprovalSigner,
  private val deviceId: () -> String,
  private val events: TimelineEvents,
  /** Called when the key had to be recreated, so the new public key gets registered (POST /devices). */
  private val onKeyReset: suspend () -> Unit = {},
) {
  suspend fun approve(approval: Approval): ApprovalOutcome = decide(approval, Decision.APPROVE)

  /** Saying "no" is signed too: the server must know the person, not just the token, refused. */
  suspend fun reject(approval: Approval): ApprovalOutcome = decide(approval, Decision.REJECT)

  private suspend fun decide(approval: Approval, decision: Decision): ApprovalOutcome {
    val argsHash = approval.argsHash
    if (argsHash.isNullOrBlank()) {
      Log.w(TAG, "approval ${approval.approvalId} has no args_hash; cannot sign")
      return ApprovalOutcome.Failed(null)
    }
    val message = ApprovalMessage.bytes(approval.approvalId, argsHash, decision)

    val signature = try {
      signer.newSignature()
    } catch (e: KeyPermanentlyInvalidatedException) {
      // A new fingerprint was enrolled: the old key is gone for good. Make a new one, register it.
      Log.w(TAG, "approval key invalidated; recreating", e)
      signer.reset()
      runCatching { signer.publicKeyBase64(); onKeyReset() }
      return ApprovalOutcome.Failed(null)
    }

    val unlocked = when (val verification = verifier.verify(approval, decision, signature)) {
      VerificationResult.Cancelled -> return ApprovalOutcome.Cancelled
      is VerificationResult.Unavailable -> return ApprovalOutcome.Failed(verification.message)
      is VerificationResult.Verified -> verification.signature
    }
    val signed = try {
      unlocked.update(message)
      ApprovalMessage.base64(unlocked.sign())
    } catch (e: Exception) {
      Log.w(TAG, "signing failed", e)
      return ApprovalOutcome.Failed(null)
    }

    if (decision == Decision.APPROVE) {
      // The backend records approval_granted; this adds the phone-only fact that a person proved presence.
      events.post(
        Surface.ANDROID, TimelineEvents.Kind.MESSAGE, "Identidad verificada con biometría en el teléfono",
        tool = approval.tool, approvalId = approval.approvalId,
      )
    }
    return try {
      when (decision) {
        Decision.APPROVE -> repository.approve(approval.approvalId, deviceId(), signed)
        Decision.REJECT -> repository.reject(approval.approvalId, deviceId(), signed)
      }
      if (decision == Decision.APPROVE) ApprovalOutcome.Approved else ApprovalOutcome.Rejected
    } catch (e: ApiException) {
      when {
        e.code == 404 || e.code == 409 -> ApprovalOutcome.AlreadyResolved
        e.code == 403 && Refusal.fromErrorBody(e.message.orEmpty()) != null -> {
          val reason = Refusal.fromErrorBody(e.message.orEmpty())!!
          Log.w(TAG, "${decision.wire} refused: ${reason.wire}", e)
          // The server does not know this phone's current key (reinstall, new key, first run
          // offline): register it again so the next attempt verifies.
          if (reason.fixedByRegistering) runCatching { onKeyReset() }
          ApprovalOutcome.Refused(reason)
        }
        else -> ApprovalOutcome.Failed(null).also { Log.w(TAG, "${decision.wire} failed", e) }
      }
    } catch (e: Exception) {
      Log.w(TAG, "${decision.wire} failed", e)
      ApprovalOutcome.Failed(null)
    }
  }

  private companion object {
    const val TAG = "ApprovalFlow"
  }
}
