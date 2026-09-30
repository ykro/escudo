package app.escudo.android.data

import app.escudo.android.data.api.FakeEscudoApi
import app.escudo.android.data.model.ClaimStatus
import app.escudo.android.data.model.Location
import app.escudo.android.data.model.PhotoUpload
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EscudoRepositoryTest {
  private val api = FakeEscudoApi(latencyMs = 0)
  private val repository = EscudoRepository(api)

  private fun photo(name: String) = PhotoUpload(name, byteArrayOf(1, 2, 3))

  @Test
  fun `report accident opens a claim and assesses the photos`() = runTest {
    val report = repository.reportAccident(
      description = "  Me chocaron por detrás en un semáforo  ",
      photos = listOf(photo("a.jpg"), photo("b.jpg")),
      location = Location(label = "Zona 10"),
    )

    assertEquals(ClaimStatus.ASSESSED, report.claim.status)
    assertEquals("Me chocaron por detrás en un semáforo", report.claim.description)
    assertEquals(2, report.claim.photos.size)
    assertNotNull(report.assessment)
    assertEquals(report.claim.claimId, repository.claim()?.claimId)
  }

  @Test
  fun `report accident without photos skips the assessor`() = runTest {
    val report = repository.reportAccident("Rayón en la puerta", photos = emptyList())

    assertEquals(ClaimStatus.OPEN, report.claim.status)
    assertNull(report.assessment)
    assertTrue(api.listPendingApprovals().isEmpty())
  }

  @Test(expected = IllegalArgumentException::class)
  fun `blank description is rejected before calling the API`() = runTest {
    repository.reportAccident("   ", emptyList())
  }

  @Test
  fun `claim without id returns the latest one, or null when there is none`() = runTest {
    assertNull(repository.claim())
    val first = repository.reportAccident("uno", emptyList()).claim
    val second = repository.reportAccident("dos", emptyList()).claim

    assertEquals(second.claimId, repository.claim(null)?.claimId)
    assertEquals(first.claimId, repository.claim(first.claimId)?.claimId)
  }

  @Test
  fun `pending approvals only lists requests still waiting for the customer`() = runTest {
    repository.reportAccident("Choque", listOf(photo("a.jpg")))
    val pending = repository.pendingApprovals()
    assertEquals(1, pending.size)

    repository.approve(pending.single().approvalId, deviceId = "device-1")

    assertTrue(repository.pendingApprovals().isEmpty())
  }
}
