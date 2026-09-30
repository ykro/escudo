package app.escudo.android.data

import app.escudo.android.data.api.ApiException
import app.escudo.android.data.api.HttpEscudoApi
import app.escudo.android.data.api.asCaller
import app.escudo.android.data.events.Surface
import app.escudo.android.data.auth.StaticTokenProvider
import app.escudo.android.data.model.PhotoUpload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class HttpEscudoApiTest {
  private val server = MockWebServer()
  private lateinit var api: HttpEscudoApi

  @Before
  fun setUp() {
    server.start()
    api = HttpEscudoApi(server.url("/").toString(), StaticTokenProvider("id-token-1"), io = Dispatchers.Unconfined)
  }

  @After
  fun tearDown() = server.close()

  private fun json(body: String, code: Int = 200) =
    MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

  @Test
  fun `sends the Firebase ID token and tolerates unknown fields`() = runTest {
    server.enqueue(
      json(
        """{"policy_id":"pol_1","holder":{"name":"Ana","customer_id":"c"},"vehicle":{"make":"Toyota","model":"Corolla","year":2022,"plate":"P-1"},
           "coverages":["own_damage"],"deductible_usd":500,"rental_days":7,"status":"active","new_field":{"x":1}}""",
      ),
    )

    val policy = api.getPolicy()

    val request = server.takeRequest()
    assertEquals("Bearer id-token-1", request.headers["Authorization"])
    assertEquals("/policy", request.url.encodedPath)
    assertEquals(500.0, policy.deductibleUsd, 0.0)
  }

  @Test
  fun `calls are labeled with the caller surface for the presenter timeline`() = runTest {
    server.enqueue(json("""{"covered":true}"""))
    server.enqueue(json("""{"covered":true}"""))

    api.checkCoverage("collision")
    asCaller(Surface.APP_FUNCTIONS) { api.checkCoverage("collision") }

    val fromScreen = server.takeRequest()
    assertEquals("android", fromScreen.headers["X-Escudo-Surface"])
    assertEquals("customer", fromScreen.headers["X-Escudo-Actor"])
    val fromAgent = server.takeRequest()
    assertEquals("appfunctions", fromAgent.headers["X-Escudo-Surface"])
    assertEquals("gemini", fromAgent.headers["X-Escudo-Actor"])
  }

  @Test
  fun `latest claim 404 means no claims`() = runTest {
    server.enqueue(MockResponse.Builder().code(404).build())
    assertNull(api.getLatestClaim())
  }

  @Test
  fun `photos are uploaded as multipart field photos`() = runTest {
    server.enqueue(json("""{"parts":[],"severity":"minor","cost_range_usd":[100,200],"drivable":true}"""))

    val assessment = api.attachClaimPhotos("clm_1", listOf(PhotoUpload("a.jpg", byteArrayOf(1)), PhotoUpload("b.jpg", byteArrayOf(2))))

    val request = server.takeRequest()
    val body = request.body!!.utf8()
    assertEquals("/claims/clm_1/photos", request.url.encodedPath)
    assertTrue(request.headers["Content-Type"]!!.startsWith("multipart/form-data"))
    assertEquals(2, Regex("name=\"photos\"").findAll(body).count())
    assertEquals("minor", assessment.severity)
  }

  @Test
  fun `approve sends the device id and surfaces 409 as ApiException`() = runTest {
    server.enqueue(MockResponse.Builder().code(409).body("not pending").build())

    try {
      api.approve("apr_1", "device-9")
      fail("expected ApiException")
    } catch (e: ApiException) {
      assertEquals(409, e.code)
    }
    val request = server.takeRequest()
    assertEquals("/approvals/apr_1/approve", request.url.encodedPath)
    assertEquals("""{"device_id":"device-9"}""", request.body!!.utf8())
  }

  @Test
  fun `coverage check passes incident type as query`() = runTest {
    server.enqueue(json("""{"covered":true,"notes":"ok"}"""))

    val check = api.checkCoverage("collision")

    assertEquals("collision", server.takeRequest().url.queryParameter("incident_type"))
    assertTrue(check.covered)
  }
}
