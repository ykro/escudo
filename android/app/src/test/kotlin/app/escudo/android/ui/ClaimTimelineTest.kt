package app.escudo.android.ui

import app.escudo.android.data.model.Claim
import app.escudo.android.data.model.DamageAssessment
import app.escudo.android.data.model.RentalReservation
import app.escudo.android.data.model.TowDispatch
import app.escudo.android.ui.claim.TimelineStep.Kind
import app.escudo.android.ui.claim.timeline
import org.junit.Assert.assertEquals
import org.junit.Test

class ClaimTimelineTest {
  @Test
  fun `steps are derived from claim fields, not only from status`() {
    // Tow requested before the assessment finished: both must be reflected.
    val claim = Claim("clm_1", "tow_requested", "2026-09-30T14:00:00Z", tow = TowDispatch("Grúas GT", 15))

    val done = claim.timeline().filter { it.done }.map { it.kind }

    assertEquals(listOf(Kind.OPEN, Kind.TOW), done)
  }

  @Test
  fun `rental step appears only when a rental exists`() {
    val base = Claim("clm_1", "assessed", "2026-09-30T14:00:00Z", assessment = DamageAssessment())
    assertEquals(false, base.timeline().any { it.kind == Kind.RENTAL })
    assertEquals(true, base.copy(rental = RentalReservation(7, "Avis")).timeline().any { it.kind == Kind.RENTAL })
  }

  @Test
  fun `a pending approval marks its step as waiting, not done`() {
    val claim = Claim("clm_1", "assessed", "2026-09-30T14:00:00Z", assessment = DamageAssessment())

    val steps = claim.timeline(pendingTools = setOf("accept_deductible", "get_policy"))

    assertEquals(listOf(Kind.DEDUCTIBLE), steps.filter { it.waitingForApproval }.map { it.kind })
    assertEquals(false, steps.single { it.kind == Kind.DEDUCTIBLE }.done)
  }

  @Test
  fun `signed claim completes the last step`() {
    val claim = Claim("clm_1", "signed", "2026-09-30T14:00:00Z", signedAt = "2026-09-30T14:03:00Z", deductibleAccepted = true)
    val steps = claim.timeline()
    assertEquals(true, steps.last().done)
    assertEquals(Kind.SIGNED, steps.last().kind)
  }
}
