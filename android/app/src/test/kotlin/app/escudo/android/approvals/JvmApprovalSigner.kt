package app.escudo.android.approvals

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/** Test double for the Keystore key: same algorithm (EC P-256, SHA256withECDSA), no user auth. */
class JvmApprovalSigner : ApprovalSigner {
  private var keyPair: KeyPair = newPair()

  override fun publicKeyBase64(): String = ApprovalMessage.base64(keyPair.public.encoded)

  override fun newSignature(): Signature = Signature.getInstance(ApprovalMessage.ALGORITHM).apply { initSign(keyPair.private) }

  override fun reset() {
    keyPair = newPair()
  }

  private fun newPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
}
