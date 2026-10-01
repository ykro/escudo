package app.escudo.android.data

import android.util.Log
import app.escudo.android.data.api.EscudoApi
import app.escudo.android.data.model.AccidentReport
import app.escudo.android.data.model.Approval
import app.escudo.android.data.model.Claim
import app.escudo.android.data.model.CoverageCheck
import app.escudo.android.data.model.Location
import app.escudo.android.data.model.OpenClaimRequest
import app.escudo.android.data.model.PhotoUpload
import app.escudo.android.data.model.Policy

/**
 * The phone's single domain layer. Screens and AppFunctions call the same methods, so a
 * capability behaves the same whether a person taps it or an agent invokes it.
 */
class EscudoRepository(private val api: EscudoApi) {
  suspend fun policy(): Policy = api.getPolicy()

  suspend fun checkCoverage(incidentType: String): CoverageCheck = api.checkCoverage(incidentType)

  /** Latest claim when [claimId] is null; null when the customer has no claims. */
  suspend fun claim(claimId: String? = null): Claim? =
    if (claimId.isNullOrBlank()) api.getLatestClaim() else api.getClaim(claimId)

  /**
   * open_claim + attach_claim_photos: both automatic in the autonomy table, so an agent may do
   * this end to end without asking. The backend writes the timeline events for both capabilities,
   * labeled with the caller's surface (see [app.escudo.android.data.api.CallerSurface]).
   */
  suspend fun reportAccident(
    description: String,
    photos: List<PhotoUpload>,
    location: Location? = null,
    occurredAt: String? = null,
  ): AccidentReport {
    require(description.isNotBlank()) { "description must not be blank" }
    val claim = api.openClaim(OpenClaimRequest(description.trim(), location, occurredAt))
    if (photos.isEmpty()) return AccidentReport(claim, assessment = null)
    val started = System.nanoTime()
    val assessment = api.attachClaimPhotos(claim.claimId, photos)
    Log.i("EscudoRepository", "Uploaded ${photos.size} photo(s), ${photos.sumOf { it.bytes.size } / 1024} KB, assessed in ${(System.nanoTime() - started) / 1_000_000} ms")
    val updated = runCatching { api.getClaim(claim.claimId) }.getOrDefault(claim.copy(assessment = assessment))
    return AccidentReport(updated, assessment)
  }

  suspend fun pendingApprovals(): List<Approval> = api.listPendingApprovals().filter { it.status == "pending" }

  suspend fun approve(approvalId: String, deviceId: String, signature: String) = api.approve(approvalId, deviceId, signature)

  suspend fun reject(approvalId: String, deviceId: String, signature: String) = api.reject(approvalId, deviceId, signature)

  suspend fun registerDevice(fcmToken: String, deviceId: String, publicKey: String) =
    api.registerDevice(fcmToken, deviceId, publicKey)
}
