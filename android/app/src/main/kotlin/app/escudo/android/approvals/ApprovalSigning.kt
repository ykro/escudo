package app.escudo.android.approvals

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/** What the customer decides on an approval request. */
enum class Decision(val wire: String) { APPROVE("approve"), REJECT("reject") }

/**
 * Protocol `escudo-approval:v1` (contracts/tools.md): the phone signs
 * `escudo-approval:v1:{approval_id}:{args_hash}:{approve|reject}` with a key that never leaves
 * this device and can only be used right after a biometric or PIN check. Holding the customer's
 * Firebase token is therefore not enough to approve: an agent would also need this phone and
 * this person.
 */
object ApprovalMessage {
  const val PREFIX = "escudo-approval:v1"
  const val ALGORITHM = "SHA256withECDSA"

  fun text(approvalId: String, argsHash: String, decision: Decision): String {
    require(approvalId.isNotBlank() && argsHash.isNotBlank()) { "approval_id and args_hash are required" }
    require(':' !in approvalId) { "approval_id must not contain ':'" }
    return "$PREFIX:$approvalId:$argsHash:${decision.wire}"
  }

  fun bytes(approvalId: String, argsHash: String, decision: Decision): ByteArray =
    text(approvalId, argsHash, decision).toByteArray(Charsets.UTF_8)

  /** base64 (standard, padded) of DER-encoded data: public keys (SubjectPublicKeyInfo) and signatures. */
  fun base64(der: ByteArray): String = Base64.getEncoder().encodeToString(der)
}

/** The device-bound approval key. */
interface ApprovalSigner {
  /** base64 of the public key's DER SubjectPublicKeyInfo; creates the key on first use. */
  fun publicKeyBase64(): String

  /**
   * A [Signature] initialized for signing with the private key. With the Keystore key it can
   * only `sign()` after the user authenticates through BiometricPrompt with it as CryptoObject.
   */
  fun newSignature(): Signature

  /** Forget the key (e.g. invalidated by a new biometric enrollment); the next call creates a new one. */
  fun reset()
}

/**
 * EC P-256 key in the Android Keystore, alias `escudo-approval`. Every signature needs a fresh
 * strong biometric or the device PIN (timeout 0 = per use).
 */
class KeystoreApprovalSigner : ApprovalSigner {

  private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

  override fun publicKeyBase64(): String {
    ensureKey()
    return ApprovalMessage.base64(keyStore.getCertificate(ALIAS).publicKey.encoded)
  }

  override fun newSignature(): Signature {
    ensureKey()
    val key = keyStore.getKey(ALIAS, null) as PrivateKey
    return Signature.getInstance(ApprovalMessage.ALGORITHM).apply { initSign(key) }
  }

  override fun reset() {
    if (keyStore.containsAlias(ALIAS)) keyStore.deleteEntry(ALIAS)
  }

  @Synchronized
  private fun ensureKey() {
    if (keyStore.containsAlias(ALIAS)) return
    val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
      .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
      .setDigests(KeyProperties.DIGEST_SHA256)
      .setUserAuthenticationRequired(true)
      .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
      .build()
    KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).apply { initialize(spec) }.generateKeyPair()
  }

  companion object {
    const val ALIAS = "escudo-approval"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
  }
}
