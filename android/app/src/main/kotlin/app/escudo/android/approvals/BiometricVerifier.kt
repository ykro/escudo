package app.escudo.android.approvals

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import app.escudo.android.R
import app.escudo.android.data.model.Approval
import java.security.Signature
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * [UserVerifier] backed by androidx.biometric's BiometricPrompt.
 *
 * Strong biometrics with the device PIN as fallback, so the demo also works on an emulator
 * (`adb -e emu finger touch 1`) and on a phone whose sensor is not enrolled.
 */
class BiometricVerifier(private val activity: FragmentActivity) : UserVerifier {

  override suspend fun verify(approval: Approval, decision: Decision, signature: Signature): VerificationResult {
    val authenticators = BIOMETRIC_STRONG or DEVICE_CREDENTIAL
    val canAuth = BiometricManager.from(activity).canAuthenticate(authenticators)
    if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
      return VerificationResult.Unavailable(activity.getString(R.string.approval_biometric_unavailable))
    }
    val info = BiometricPrompt.PromptInfo.Builder()
      .setTitle(activity.getString(if (decision == Decision.APPROVE) R.string.approval_prompt_title else R.string.reject_prompt_title))
      .setSubtitle(approval.summary ?: approval.reason)
      .setAllowedAuthenticators(authenticators)
      .setConfirmationRequired(true)
      .build()

    return suspendCancellableCoroutine { cont ->
      val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
          override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            if (!cont.isActive) return
            val unlocked = result.cryptoObject?.signature
            cont.resume(unlocked?.let { VerificationResult.Verified(it) } ?: VerificationResult.Unavailable(activity.getString(R.string.approval_failed)))
          }

          override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            if (!cont.isActive) return
            val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
              errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
              errorCode == BiometricPrompt.ERROR_CANCELED
            cont.resume(if (cancelled) VerificationResult.Cancelled else VerificationResult.Unavailable(errString.toString()))
          }
          // onAuthenticationFailed = one bad finger; the prompt stays open for another try.
        },
      )
      // The signature is the CryptoObject: only this authentication can unlock the device key.
      prompt.authenticate(info, BiometricPrompt.CryptoObject(signature))
      cont.invokeOnCancellation { prompt.cancelAuthentication() }
    }
  }
}
