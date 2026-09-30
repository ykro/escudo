package app.escudo.android.data.api

import app.escudo.android.data.model.Approval
import app.escudo.android.data.model.Claim
import app.escudo.android.data.model.ClaimStatus
import app.escudo.android.data.model.CoverageCheck
import app.escudo.android.data.model.DamageAssessment
import app.escudo.android.data.model.DamagedPart
import app.escudo.android.data.model.EscudoEvent
import app.escudo.android.data.model.Holder
import app.escudo.android.data.model.OpenClaimRequest
import app.escudo.android.data.model.PhotoUpload
import app.escudo.android.data.model.Policy
import app.escudo.android.data.model.Vehicle
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory [EscudoApi] with fictional seed data, for development and offline rehearsals
 * (the "fake" flavor) and for unit tests. It follows the same rules as the real server,
 * including "approvals are single-use and only for pending requests".
 */
class FakeEscudoApi(
  private val latencyMs: Long = 300,
  private val clock: Clock = Clock.systemUTC(),
) : EscudoApi {

  private val mutex = Mutex()
  private val claims = linkedMapOf<String, Claim>()
  private val approvals = linkedMapOf<String, Approval>()
  private var nextId = 1

  val events = mutableListOf<EscudoEvent>()
  val deviceTokens = mutableListOf<String>()

  val policy = Policy(
    policyId = "pol_demo_001",
    holder = Holder(name = "Ana Lucía Morales", customerId = "cus_demo_001"),
    vehicle = Vehicle(make = "Toyota", model = "Corolla", year = 2022, plate = "P-512HGK"),
    coverages = listOf("own_damage", "third_party", "towing", "rental_car"),
    deductibleUsd = 500.0,
    rentalDays = 7,
    status = "active",
  )

  override suspend fun getPolicy(): Policy = simulate { policy }

  override suspend fun checkCoverage(incidentType: String): CoverageCheck = simulate {
    val covered = incidentType.lowercase() in COVERED_INCIDENTS
    CoverageCheck(
      covered = covered,
      notes = if (covered) "Cubierto por daños propios; deducible de 500 USD." else "No incluido en la póliza.",
    )
  }

  override suspend fun openClaim(request: OpenClaimRequest): Claim = simulate {
    val id = "clm_${nextId++}"
    val now = Instant.now(clock).toString()
    Claim(
      claimId = id,
      status = ClaimStatus.OPEN,
      createdAt = now,
      description = request.description,
      location = request.location,
      occurredAt = request.occurredAt ?: now,
    ).also { claims[id] = it }
  }

  override suspend fun attachClaimPhotos(claimId: String, photos: List<PhotoUpload>): DamageAssessment = simulate {
    val claim = claims[claimId] ?: throw ApiException(404, "Unknown claim $claimId")
    val assessment = DamageAssessment(
      parts = listOf(DamagedPart("Parachoques trasero", "Abolladura"), DamagedPart("Luz trasera izquierda", "Rota")),
      severity = "minor",
      costRangeUsd = listOf(650.0, 900.0),
      drivable = true,
    )
    claims[claimId] = claim.copy(
      status = ClaimStatus.ASSESSED,
      photos = claim.photos + photos.map { "fake://photos/${it.fileName}" },
      assessment = assessment,
    )
    // In the fake, the "agent" immediately asks to accept the deductible so the approval flow
    // can be rehearsed without a backend.
    val approvalId = "apr_${nextId++}"
    approvals[approvalId] = Approval(
      approvalId = approvalId,
      tool = "accept_deductible",
      reason = "Aceptar el deducible requiere tu aprobación.",
      summary = "Pagar el deducible de 500 USD para el reclamo $claimId.",
      amountUsd = policy.deductibleUsd,
      status = "pending",
      createdAt = Instant.now(clock).toString(),
    )
    assessment
  }

  override suspend fun getLatestClaim(): Claim? = simulate { claims.values.lastOrNull() }

  override suspend fun getClaim(claimId: String): Claim = simulate {
    claims[claimId] ?: throw ApiException(404, "Unknown claim $claimId")
  }

  override suspend fun listPendingApprovals(): List<Approval> = simulate {
    approvals.values.filter { it.status == "pending" }
  }

  override suspend fun approve(approvalId: String, deviceId: String) = simulate {
    val approval = approvals[approvalId] ?: throw ApiException(404, "Unknown approval")
    if (approval.status != "pending") throw ApiException(409, "Approval is ${approval.status}")
    approvals[approvalId] = approval.copy(status = "approved")
    if (approval.tool == "accept_deductible") {
      claims.entries.lastOrNull()?.let { (id, claim) ->
        claims[id] = claim.copy(status = ClaimStatus.DEDUCTIBLE_ACCEPTED, deductibleAccepted = true)
      }
    }
  }

  override suspend fun reject(approvalId: String) = simulate {
    val approval = approvals[approvalId] ?: throw ApiException(404, "Unknown approval")
    approvals[approvalId] = approval.copy(status = "rejected")
  }

  override suspend fun registerDevice(fcmToken: String) = simulate { deviceTokens += fcmToken; Unit }

  override suspend fun postEvent(event: EscudoEvent) = mutex.withLock { events += event; Unit }

  /** Test/rehearsal hook: add a pending approval as if the agent had requested one. */
  suspend fun seedApproval(approval: Approval) = mutex.withLock { approvals[approval.approvalId] = approval }

  private suspend fun <T> simulate(block: () -> T): T {
    if (latencyMs > 0) delay(latencyMs)
    return mutex.withLock { block() }
  }

  private companion object {
    val COVERED_INCIDENTS = setOf("collision", "choque", "minor_collision", "own_damage", "towing", "rental_car", "third_party")
  }
}
