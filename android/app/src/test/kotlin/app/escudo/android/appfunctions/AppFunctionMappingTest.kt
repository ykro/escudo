package app.escudo.android.appfunctions

import app.escudo.android.data.model.AccidentReport
import app.escudo.android.data.model.Claim
import app.escudo.android.data.model.CoverageCheck
import app.escudo.android.data.model.DamageAssessment
import app.escudo.android.data.model.DamagedPart
import app.escudo.android.data.model.Holder
import app.escudo.android.data.model.Policy
import app.escudo.android.data.model.RepairBooking
import app.escudo.android.data.model.TowDispatch
import app.escudo.android.data.model.Vehicle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppFunctionMappingTest {

  // --- reportAccident input -----------------------------------------------------------------

  @Test
  fun `description is trimmed and photos default to none`() {
    val input = AppFunctionMapping.reportAccidentInput("  Choque leve  ", null, null, null, null)

    assertEquals("Choque leve", input.description)
    assertEquals(PhotoSelection.None, input.photos)
    assertNull(input.location)
    assertNull(input.occurredAt)
  }

  @Test
  fun `blank description is invalid`() {
    val error = assertThrows(InvalidAppFunctionInput::class.java) {
      AppFunctionMapping.reportAccidentInput(" ", null, null, null, null)
    }
    assertTrue(error.message!!.contains("ask the user"))
  }

  @Test
  fun `shared photo URIs win over recent photo count`() {
    val input = AppFunctionMapping.reportAccidentInput(
      "Choque", listOf("content://media/1", " ", "content://media/2"), 3, null, null,
    )
    assertEquals(PhotoSelection.Uris(listOf("content://media/1", "content://media/2")), input.photos)
  }

  @Test
  fun `recent photo count is used when no URIs are shared`() {
    val input = AppFunctionMapping.reportAccidentInput("Choque", emptyList(), 3, null, null)
    assertEquals(PhotoSelection.MostRecent(3), input.photos)
  }

  @Test
  fun `photo limits are enforced`() {
    assertThrows(InvalidAppFunctionInput::class.java) {
      AppFunctionMapping.reportAccidentInput("Choque", List(6) { "content://media/$it" }, null, null, null)
    }
    assertThrows(InvalidAppFunctionInput::class.java) {
      AppFunctionMapping.reportAccidentInput("Choque", null, 0, null, null)
    }
    assertThrows(InvalidAppFunctionInput::class.java) {
      AppFunctionMapping.reportAccidentInput("Choque", null, 6, null, null)
    }
  }

  @Test
  fun `location label becomes a location and blank is dropped`() {
    assertEquals("Zona 10", AppFunctionMapping.reportAccidentInput("x", null, null, " Zona 10 ", null).location?.label)
    assertNull(AppFunctionMapping.reportAccidentInput("x", null, null, "  ", null).location)
  }

  @Test
  fun `occurredAt accepts ISO-8601 with offset and normalizes to UTC`() {
    val input = AppFunctionMapping.reportAccidentInput("x", null, null, null, "2026-09-30T08:05:00-06:00")
    assertEquals("2026-09-30T14:05:00Z", input.occurredAt)
  }

  @Test
  fun `occurredAt that is not ISO-8601 is invalid`() {
    assertThrows(InvalidAppFunctionInput::class.java) {
      AppFunctionMapping.reportAccidentInput("x", null, null, null, "ayer a las 3")
    }
  }

  // --- getCoverage / getClaimStatus inputs --------------------------------------------------

  @Test
  fun `incident type is normalized to snake case`() {
    assertEquals("minor_collision", AppFunctionMapping.incidentType("  Minor Collision "))
    assertEquals("choque_menor", AppFunctionMapping.incidentType("choque-menor"))
    assertNull(AppFunctionMapping.incidentType("  "))
    assertNull(AppFunctionMapping.incidentType(null))
  }

  @Test
  fun `blank claim id means latest claim`() {
    assertNull(AppFunctionMapping.claimId(" "))
    assertEquals("clm_1", AppFunctionMapping.claimId(" clm_1 "))
  }

  // --- outputs ------------------------------------------------------------------------------

  private val policy = Policy(
    policyId = "pol_1",
    holder = Holder("Ana", "cus_1"),
    vehicle = Vehicle("Toyota", "Corolla", 2022, "P-512HGK"),
    coverages = listOf("own_damage", "towing"),
    deductibleUsd = 500.0,
    rentalDays = 7,
    status = "active",
  )

  @Test
  fun `coverage result flattens the policy and hides rental days when not covered`() {
    val result = AppFunctionMapping.coverage(policy, "collision", CoverageCheck(covered = true, notes = "ok"))

    assertEquals("Toyota Corolla 2022", result.vehicle)
    assertEquals(500.0, result.deductibleUsd, 0.0)
    assertEquals(0, result.rentalDays)
    assertTrue(result.active)
    assertEquals(true, result.covered)
    assertEquals("collision", result.incidentType)
  }

  @Test
  fun `coverage without incident type has no coverage decision`() {
    val result = AppFunctionMapping.coverage(policy.copy(coverages = listOf("rental_car")), null, null)
    assertNull(result.covered)
    assertEquals(7, result.rentalDays)
  }

  @Test
  fun `claim status exposes each completed step`() {
    val claim = Claim(
      claimId = "clm_1",
      status = "repair_selected",
      createdAt = "2026-09-30T14:00:00Z",
      photos = listOf("gs://a", "gs://b"),
      assessment = DamageAssessment(listOf(DamagedPart("Parachoques", "abolladura")), "minor", listOf(600.0, 900.0), true),
      tow = TowDispatch("Grúas Express", 18),
      repair = RepairBooking("shop_1", "Taller Central", "2026-10-01T09:00:00Z", 750.0),
    )

    val result = AppFunctionMapping.claimStatus(claim)

    assertEquals("Taller reservado", result.statusLabel)
    assertEquals(2, result.photoCount)
    assertEquals(listOf("Parachoques: abolladura"), result.damagedParts)
    assertEquals(600.0, result.costMinUsd!!, 0.0)
    assertEquals(900.0, result.costMaxUsd!!, 0.0)
    assertEquals(18, result.towEtaMinutes)
    assertEquals("Taller Central", result.repairShop)
    assertFalse(result.signed)
    assertFalse(result.deductibleAccepted)
  }

  @Test
  fun `report result falls back to the claim's assessment`() {
    val claim = Claim("clm_2", "assessed", "2026-09-30T14:00:00Z", assessment = DamageAssessment(severity = "moderate"))

    val result = AppFunctionMapping.reportAccident(AccidentReport(claim, assessment = null), photosAttached = 2)

    assertEquals("moderate", result.severity)
    assertEquals(2, result.photosAttached)
    assertNull(result.costMinUsd)
  }
}
