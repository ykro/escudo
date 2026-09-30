package app.escudo.android.data

import app.escudo.android.data.api.EscudoApi
import app.escudo.android.data.events.Surface
import app.escudo.android.data.events.TimelineEvents
import app.escudo.android.data.model.AccidentReport
import app.escudo.android.data.model.Approval
import app.escudo.android.data.model.Claim
import app.escudo.android.data.model.CoverageCheck
import app.escudo.android.data.model.Location
import app.escudo.android.data.model.OpenClaimRequest
import app.escudo.android.data.model.PhotoUpload
import app.escudo.android.data.model.Policy
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The phone's single domain layer. Screens and AppFunctions call the same methods, so a
 * capability behaves the same whether a person taps it or an agent invokes it.
 */
class EscudoRepository(
  private val api: EscudoApi,
  private val events: TimelineEvents,
) {
  suspend fun policy(): Policy = api.getPolicy()

  suspend fun checkCoverage(incidentType: String): CoverageCheck = api.checkCoverage(incidentType)

  /** Latest claim when [claimId] is null; null when the customer has no claims. */
  suspend fun claim(claimId: String? = null): Claim? =
    if (claimId.isNullOrBlank()) api.getLatestClaim() else api.getClaim(claimId)

  /**
   * open_claim + attach_claim_photos: both automatic in the autonomy table, so an agent may do
   * this end to end without asking.
   */
  suspend fun reportAccident(
    description: String,
    photos: List<PhotoUpload>,
    location: Location? = null,
    occurredAt: String? = null,
    surface: Surface,
  ): AccidentReport {
    require(description.isNotBlank()) { "description must not be blank" }

    events.post(surface, TimelineEvents.Kind.TOOL_CALL, "Reportar accidente: abrir reclamo", tool = "open_claim", autonomy = "automatic")
    val claim = api.openClaim(OpenClaimRequest(description.trim(), location, occurredAt))
    events.post(surface, TimelineEvents.Kind.TOOL_RESULT, "Reclamo ${claim.claimId} abierto", tool = "open_claim", claimId = claim.claimId)

    if (photos.isEmpty()) return AccidentReport(claim, assessment = null)

    events.post(
      surface, TimelineEvents.Kind.TOOL_CALL, "Adjuntar ${photos.size} foto(s) para evaluar daños",
      tool = "attach_claim_photos", claimId = claim.claimId, autonomy = "automatic",
    )
    val assessment = api.attachClaimPhotos(claim.claimId, photos)
    events.post(
      surface, TimelineEvents.Kind.TOOL_RESULT,
      "Daños evaluados: ${assessment.severity ?: "sin severidad"}",
      tool = "attach_claim_photos", claimId = claim.claimId,
      data = buildJsonObject {
        put("parts", assessment.parts.size)
        assessment.severity?.let { put("severity", it) }
      },
    )
    val updated = runCatching { api.getClaim(claim.claimId) }.getOrDefault(claim.copy(assessment = assessment))
    return AccidentReport(updated, assessment)
  }

  suspend fun pendingApprovals(): List<Approval> = api.listPendingApprovals().filter { it.status == "pending" }

  suspend fun approve(approvalId: String, deviceId: String) = api.approve(approvalId, deviceId)

  suspend fun reject(approvalId: String) = api.reject(approvalId)

  suspend fun registerDevice(fcmToken: String) = api.registerDevice(fcmToken)
}
