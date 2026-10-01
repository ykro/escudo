package app.escudo.android.data.api

import app.escudo.android.data.model.Approval
import app.escudo.android.data.model.Claim
import app.escudo.android.data.model.CoverageCheck
import app.escudo.android.data.model.DamageAssessment
import app.escudo.android.data.model.EscudoEvent
import app.escudo.android.data.model.OpenClaimRequest
import app.escudo.android.data.model.PhotoUpload
import app.escudo.android.data.model.Policy

/**
 * The slice of the Escudo REST API (contracts/openapi.yaml) that the phone uses.
 *
 * Screens and AppFunctions both go through this one interface: "functions, not screens".
 * There is deliberately no method for the approval-gated capabilities (deductible, repair, sign):
 * those are the agent's job. The phone only *grants* approvals, and only a human can.
 */
interface EscudoApi {
  suspend fun getPolicy(): Policy
  suspend fun checkCoverage(incidentType: String): CoverageCheck
  suspend fun openClaim(request: OpenClaimRequest): Claim
  suspend fun attachClaimPhotos(claimId: String, photos: List<PhotoUpload>): DamageAssessment

  /** Latest claim of the customer, or null when there is none (404). */
  suspend fun getLatestClaim(): Claim?
  suspend fun getClaim(claimId: String): Claim

  /** Human-only surface: never exposed to agents. */
  suspend fun listPendingApprovals(): List<Approval>
  /** [signature]: base64 DER ECDSA over `escudo-approval:v1:{id}:{args_hash}:approve`. */
  suspend fun approve(approvalId: String, deviceId: String, signature: String)
  suspend fun reject(approvalId: String, deviceId: String, signature: String)

  suspend fun registerDevice(fcmToken: String, deviceId: String, publicKey: String)
  suspend fun postEvent(event: EscudoEvent)
}

/** Non-2xx answer from the API. [code] lets callers tell 404/409/403 apart. */
class ApiException(val code: Int, message: String) : Exception(message)
