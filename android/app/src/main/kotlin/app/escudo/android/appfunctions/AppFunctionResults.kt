package app.escudo.android.appfunctions

import androidx.appfunctions.AppFunctionSerializable

// What an agent gets back from Escudo's AppFunctions. Flat, self-describing fields: an agent
// reads these, so every field has a KDoc that ends up in the function metadata.

/** The customer's car insurance policy and, optionally, whether one incident type is covered. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class CoverageResult(
  /** Policy identifier. */
  val policyId: String,
  /** Name of the policy holder. */
  val holderName: String,
  /** Insured vehicle, e.g. "Toyota Corolla 2022". */
  val vehicle: String,
  /** License plate of the insured vehicle. */
  val plate: String,
  /** Coverage codes: own_damage, third_party, towing, rental_car, theft. */
  val coverages: List<String>,
  /** Deductible the customer pays per claim, in USD. */
  val deductibleUsd: Double,
  /** Days of replacement car included (0 if not covered). */
  val rentalDays: Int,
  /** True when the policy is active. */
  val active: Boolean,
  /** The incident type that was checked, if one was given. */
  val incidentType: String? = null,
  /** Whether that incident type is covered; null when no incident type was given. */
  val covered: Boolean? = null,
  /** Insurer notes about the coverage decision, in Spanish. */
  val notes: String? = null,
)

/** Current state of an insurance claim and each step already completed. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class ClaimStatusResult(
  /** Claim identifier. */
  val claimId: String,
  /** Status code: open, assessed, tow_requested, repair_selected, deductible_accepted or signed. */
  val status: String,
  /** Human-readable status in Spanish. */
  val statusLabel: String,
  /** What the customer reported. */
  val description: String? = null,
  /** When the claim was opened (ISO-8601). */
  val createdAt: String,
  /** Number of photos attached. */
  val photoCount: Int,
  /** Damage severity from the assessor: minor, moderate or severe. */
  val severity: String? = null,
  /** Damaged parts, e.g. "Parachoques trasero: abolladura". */
  val damagedParts: List<String> = emptyList(),
  /** Lower bound of the estimated repair cost, in USD. */
  val costMinUsd: Double? = null,
  /** Upper bound of the estimated repair cost, in USD. */
  val costMaxUsd: Double? = null,
  /** Tow provider, once a tow was requested. */
  val towProvider: String? = null,
  /** Estimated tow arrival in minutes. */
  val towEtaMinutes: Int? = null,
  /** Selected repair shop, once booked. */
  val repairShop: String? = null,
  /** Booked repair slot (ISO-8601). */
  val repairSlot: String? = null,
  /** True once the customer accepted the deductible. */
  val deductibleAccepted: Boolean = false,
  /** True once the claim was signed and submitted. */
  val signed: Boolean = false,
)

/** Result of reporting an accident: the new claim and, if photos were attached, the damage assessment. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class ReportAccidentResult(
  /** Identifier of the claim that was opened. Use it with getClaimStatus. */
  val claimId: String,
  /** Claim status after reporting. */
  val status: String,
  /** Number of photos attached and assessed. */
  val photosAttached: Int,
  /** Damage severity: minor, moderate or severe; null without photos. */
  val severity: String? = null,
  /** Damaged parts found in the photos. */
  val damagedParts: List<String> = emptyList(),
  /** Lower bound of the estimated repair cost, in USD. */
  val costMinUsd: Double? = null,
  /** Upper bound of the estimated repair cost, in USD. */
  val costMaxUsd: Double? = null,
  /** Whether the car can still be driven; null when unknown. */
  val drivable: Boolean? = null,
)
