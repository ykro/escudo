package app.escudo.android.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// Wire models mirror contracts/openapi.yaml one to one. The app never invents fields the
// backend does not own; anything derived for display lives in the UI layer.

@Serializable
data class Policy(
  @SerialName("policy_id") val policyId: String,
  val holder: Holder = Holder(),
  val vehicle: Vehicle = Vehicle(),
  val coverages: List<String> = emptyList(),
  @SerialName("deductible_usd") val deductibleUsd: Double = 0.0,
  @SerialName("rental_days") val rentalDays: Int = 0,
  val status: String = "active",
)

@Serializable
data class Holder(
  val name: String = "",
  @SerialName("customer_id") val customerId: String = "",
)

@Serializable
data class Vehicle(
  val make: String = "",
  val model: String = "",
  val year: Int = 0,
  val plate: String = "",
)

@Serializable
data class CoverageCheck(
  val covered: Boolean,
  val notes: String? = null,
)

@Serializable
data class Location(
  val lat: Double? = null,
  val lng: Double? = null,
  val label: String? = null,
)

@Serializable
data class OpenClaimRequest(
  val description: String,
  val location: Location? = null,
  @SerialName("occurred_at") val occurredAt: String? = null,
)

@Serializable
data class DamagedPart(
  val name: String = "",
  val damage: String = "",
)

@Serializable
data class DamageAssessment(
  val parts: List<DamagedPart> = emptyList(),
  val severity: String? = null,
  @SerialName("cost_range_usd") val costRangeUsd: List<Double> = emptyList(),
  val drivable: Boolean? = null,
  /** One-line Spanish summary from the assessor (additive field, contracts/CHANGELOG.md). */
  val summary: String? = null,
)

@Serializable
data class TowDispatch(
  val provider: String? = null,
  @SerialName("eta_minutes") val etaMinutes: Int? = null,
  val phone: String? = null,
)

@Serializable
data class RepairBooking(
  @SerialName("shop_id") val shopId: String? = null,
  @SerialName("shop_name") val shopName: String? = null,
  val slot: String? = null,
  @SerialName("quote_usd") val quoteUsd: Double? = null,
  val address: String? = null,
)

@Serializable
data class RentalReservation(
  val days: Int? = null,
  val pickup: String? = null,
)

@Serializable
data class Claim(
  @SerialName("claim_id") val claimId: String,
  val status: String,
  @SerialName("created_at") val createdAt: String,
  val description: String? = null,
  val location: Location? = null,
  @SerialName("occurred_at") val occurredAt: String? = null,
  val photos: List<String> = emptyList(),
  val assessment: DamageAssessment? = null,
  val tow: TowDispatch? = null,
  val repair: RepairBooking? = null,
  val rental: RentalReservation? = null,
  @SerialName("deductible_accepted") val deductibleAccepted: Boolean? = null,
  @SerialName("signed_at") val signedAt: String? = null,
  /** Spanish status label computed by the backend (additive field). */
  @SerialName("status_label") val statusLabel: String? = null,
)

/** Claim statuses from the contract, in scenario order. */
object ClaimStatus {
  const val OPEN = "open"
  const val ASSESSED = "assessed"
  const val TOW_REQUESTED = "tow_requested"
  const val REPAIR_SELECTED = "repair_selected"
  const val DEDUCTIBLE_ACCEPTED = "deductible_accepted"
  const val SIGNED = "signed"
}

@Serializable
data class Approval(
  @SerialName("approval_id") val approvalId: String,
  val tool: String = "",
  val reason: String = "",
  val summary: String? = null,
  @SerialName("amount_usd") val amountUsd: Double? = null,
  val status: String = "pending",
  @SerialName("created_at") val createdAt: String? = null,
  @SerialName("claim_id") val claimId: String? = null,
)

@Serializable
data class ApproveRequest(
  @SerialName("device_id") val deviceId: String,
)

@Serializable
data class RegisterDeviceRequest(
  @SerialName("fcm_token") val fcmToken: String,
  val platform: String = "android",
  /** Stable per-install id: re-registering after a token refresh replaces this phone's entry. */
  @SerialName("device_id") val deviceId: String? = null,
)

/** One step in the presenter timeline (contracts/events.schema.json). */
@Serializable
data class EscudoEvent(
  val id: String,
  val ts: String,
  @SerialName("run_id") val runId: String,
  @SerialName("claim_id") val claimId: String? = null,
  val actor: String,
  val surface: String,
  val kind: String,
  val tool: String? = null,
  val summary: String,
  val autonomy: String? = null,
  @SerialName("approval_id") val approvalId: String? = null,
  val reason: String? = null,
  val data: JsonObject? = null,
)

/** A photo ready to upload: already downscaled JPEG bytes. */
class PhotoUpload(val fileName: String, val bytes: ByteArray, val mimeType: String = "image/jpeg")

/** Result of the report-accident use case: claim opened + damage assessment. */
data class AccidentReport(val claim: Claim, val assessment: DamageAssessment?)
