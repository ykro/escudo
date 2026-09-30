package app.escudo.android.appfunctions

import app.escudo.android.data.model.AccidentReport
import app.escudo.android.data.model.Claim
import app.escudo.android.data.model.ClaimStatus
import app.escudo.android.data.model.CoverageCheck
import app.escudo.android.data.model.DamageAssessment
import app.escudo.android.data.model.Location
import app.escudo.android.data.model.Policy
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** Agent input that cannot be used. Translated to AppFunctionInvalidArgumentException by the service. */
class InvalidAppFunctionInput(message: String) : IllegalArgumentException(message)

/** Where the photos for reportAccident come from. */
sealed interface PhotoSelection {
  data object None : PhotoSelection
  data class Uris(val uris: List<String>) : PhotoSelection
  data class MostRecent(val count: Int) : PhotoSelection
}

data class ReportAccidentInput(
  val description: String,
  val location: Location?,
  val occurredAt: String?,
  val photos: PhotoSelection,
)

/**
 * Pure translation between what an agent sends/receives and the repository's domain types.
 * Kept free of Android and AppFunctions classes so it is unit-tested on the JVM.
 */
object AppFunctionMapping {
  const val MAX_PHOTOS = 5
  const val MAX_DESCRIPTION = 2000

  fun reportAccidentInput(
    description: String,
    photoUris: List<String>?,
    recentPhotoCount: Int?,
    locationLabel: String?,
    occurredAt: String?,
  ): ReportAccidentInput {
    val text = description.trim()
    if (text.isEmpty()) throw InvalidAppFunctionInput("description is empty: ask the user what happened")
    if (text.length > MAX_DESCRIPTION) throw InvalidAppFunctionInput("description is longer than $MAX_DESCRIPTION characters")

    val uris = photoUris.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
    if (uris.size > MAX_PHOTOS) throw InvalidAppFunctionInput("at most $MAX_PHOTOS photos per report")
    if (recentPhotoCount != null && recentPhotoCount !in 1..MAX_PHOTOS) {
      throw InvalidAppFunctionInput("recentPhotoCount must be between 1 and $MAX_PHOTOS")
    }
    val photos = when {
      uris.isNotEmpty() -> PhotoSelection.Uris(uris)
      recentPhotoCount != null -> PhotoSelection.MostRecent(recentPhotoCount)
      else -> PhotoSelection.None
    }

    return ReportAccidentInput(
      description = text,
      location = locationLabel?.trim()?.takeIf { it.isNotEmpty() }?.let { Location(label = it) },
      occurredAt = occurredAt?.trim()?.takeIf { it.isNotEmpty() }?.let(::normalizeTimestamp),
      photos = photos,
    )
  }

  /** "Choque menor" → "choque_menor": the API takes snake_case incident types. */
  fun incidentType(raw: String?): String? =
    raw?.trim()?.lowercase()?.replace(Regex("[\\s-]+"), "_")?.takeIf { it.isNotEmpty() }

  fun claimId(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }

  fun coverage(policy: Policy, incidentType: String?, check: CoverageCheck?): CoverageResult = CoverageResult(
    policyId = policy.policyId,
    holderName = policy.holder.name,
    vehicle = listOf(policy.vehicle.make, policy.vehicle.model, policy.vehicle.year.takeIf { it > 0 }?.toString())
      .filterNot { it.isNullOrBlank() }.joinToString(" "),
    plate = policy.vehicle.plate,
    coverages = policy.coverages,
    deductibleUsd = policy.deductibleUsd,
    rentalDays = if ("rental_car" in policy.coverages) policy.rentalDays else 0,
    active = policy.status == "active",
    incidentType = incidentType,
    covered = check?.covered,
    notes = check?.notes,
  )

  fun claimStatus(claim: Claim): ClaimStatusResult {
    val assessment = claim.assessment
    return ClaimStatusResult(
      claimId = claim.claimId,
      status = claim.status,
      statusLabel = statusLabel(claim.status),
      description = claim.description,
      createdAt = claim.createdAt,
      photoCount = claim.photos.size,
      severity = assessment?.severity,
      damagedParts = assessment.partLabels(),
      costMinUsd = assessment?.costRangeUsd?.getOrNull(0),
      costMaxUsd = assessment?.costRangeUsd?.getOrNull(1),
      towProvider = claim.tow?.provider,
      towEtaMinutes = claim.tow?.etaMinutes,
      repairShop = claim.repair?.shopName ?: claim.repair?.shopId,
      repairSlot = claim.repair?.slot,
      deductibleAccepted = claim.deductibleAccepted == true,
      signed = claim.signedAt != null || claim.status == ClaimStatus.SIGNED,
    )
  }

  fun reportAccident(report: AccidentReport, photosAttached: Int): ReportAccidentResult {
    val assessment = report.assessment ?: report.claim.assessment
    return ReportAccidentResult(
      claimId = report.claim.claimId,
      status = report.claim.status,
      photosAttached = photosAttached,
      severity = assessment?.severity,
      damagedParts = assessment.partLabels(),
      costMinUsd = assessment?.costRangeUsd?.getOrNull(0),
      costMaxUsd = assessment?.costRangeUsd?.getOrNull(1),
      drivable = assessment?.drivable,
    )
  }

  fun statusLabel(status: String): String = when (status) {
    ClaimStatus.OPEN -> "Reclamo abierto"
    ClaimStatus.ASSESSED -> "Daños evaluados"
    ClaimStatus.TOW_REQUESTED -> "Grúa en camino"
    ClaimStatus.REPAIR_SELECTED -> "Taller reservado"
    ClaimStatus.DEDUCTIBLE_ACCEPTED -> "Deducible aceptado"
    ClaimStatus.SIGNED -> "Reclamo firmado y enviado"
    else -> status
  }

  private fun DamageAssessment?.partLabels(): List<String> =
    this?.parts.orEmpty().map { if (it.damage.isBlank()) it.name else "${it.name}: ${it.damage}" }

  private fun normalizeTimestamp(raw: String): String =
    try {
      Instant.parse(raw).toString()
    } catch (_: DateTimeParseException) {
      try {
        OffsetDateTime.parse(raw).toInstant().toString()
      } catch (_: DateTimeParseException) {
        throw InvalidAppFunctionInput("occurredAt must be ISO-8601, e.g. 2026-09-30T14:05:00Z")
      }
    }
}
