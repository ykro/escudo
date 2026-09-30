package app.escudo.android.data

import app.escudo.android.data.api.FakeEscudoApi
import app.escudo.android.data.events.Surface
import app.escudo.android.data.events.TimelineEvents
import app.escudo.android.data.model.ClaimStatus
import app.escudo.android.data.model.Location
import app.escudo.android.data.model.PhotoUpload
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EscudoRepositoryTest {
  private val api = FakeEscudoApi(latencyMs = 0)
  private val scope = TestScope(UnconfinedTestDispatcher())
  private val repository = EscudoRepository(api, TimelineEvents(api, scope))

  private fun photo(name: String) = PhotoUpload(name, byteArrayOf(1, 2, 3))

  @Test
  fun `report accident opens a claim and assesses the photos`() = runTest {
    val report = repository.reportAccident(
      description = "  Me chocaron por detrás en un semáforo  ",
      photos = listOf(photo("a.jpg"), photo("b.jpg")),
      location = Location(label = "Zona 10"),
      surface = Surface.ANDROID,
    )

    assertEquals(ClaimStatus.ASSESSED, report.claim.status)
    assertEquals("Me chocaron por detrás en un semáforo", report.claim.description)
    assertEquals(2, report.claim.photos.size)
    assertNotNull(report.assessment)
    assertEquals(report.claim.claimId, repository.claim()?.claimId)
  }

  @Test
  fun `report accident without photos skips the assessor`() = runTest {
    val report = repository.reportAccident("Rayón en la puerta", photos = emptyList(), surface = Surface.ANDROID)

    assertEquals(ClaimStatus.OPEN, report.claim.status)
    assertNull(report.assessment)
    assertTrue(api.listPendingApprovals().isEmpty())
  }

  @Test(expected = IllegalArgumentException::class)
  fun `blank description is rejected before calling the API`() = runTest {
    repository.reportAccident("   ", emptyList(), surface = Surface.ANDROID)
  }

  @Test
  fun `report accident writes timeline events tagged with the calling surface`() = runTest {
    repository.reportAccident("Choque leve", listOf(photo("a.jpg")), surface = Surface.APP_FUNCTIONS)

    val events = api.events
    assertEquals(listOf("open_claim", "open_claim", "attach_claim_photos", "attach_claim_photos"), events.map { it.tool })
    assertTrue(events.all { it.surface == "appfunctions" && it.actor == "gemini" })
    assertEquals(listOf("tool_call", "tool_result", "tool_call", "tool_result"), events.map { it.kind })
    // Once the claim exists, events are grouped under it for the presenter timeline.
    assertEquals(events.last().claimId, events.last().runId)
  }

  @Test
  fun `claim without id returns the latest one, or null when there is none`() = runTest {
    assertNull(repository.claim())
    val first = repository.reportAccident("uno", emptyList(), surface = Surface.ANDROID).claim
    val second = repository.reportAccident("dos", emptyList(), surface = Surface.ANDROID).claim

    assertEquals(second.claimId, repository.claim(null)?.claimId)
    assertEquals(first.claimId, repository.claim(first.claimId)?.claimId)
  }

  @Test
  fun `pending approvals only lists requests still waiting for the customer`() = runTest {
    repository.reportAccident("Choque", listOf(photo("a.jpg")), surface = Surface.ANDROID)
    val pending = repository.pendingApprovals()
    assertEquals(1, pending.size)

    repository.approve(pending.single().approvalId, deviceId = "device-1")

    assertTrue(repository.pendingApprovals().isEmpty())
  }
}
