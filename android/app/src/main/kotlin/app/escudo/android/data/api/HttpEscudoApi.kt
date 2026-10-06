package app.escudo.android.data.api

import app.escudo.android.data.auth.AuthTokenProvider
import app.escudo.android.data.events.Surface
import app.escudo.android.data.model.Approval
import app.escudo.android.data.model.DecisionRequest
import app.escudo.android.data.model.Claim
import app.escudo.android.data.model.CoverageCheck
import app.escudo.android.data.model.DamageAssessment
import app.escudo.android.data.model.EscudoEvent
import app.escudo.android.data.model.OpenClaimRequest
import app.escudo.android.data.model.PhotoUpload
import app.escudo.android.data.model.Policy
import app.escudo.android.data.model.RegisterDeviceRequest
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/** [EscudoApi] over HTTPS against the `escudo-api` Cloud Run service. */
class HttpEscudoApi(
  baseUrl: String,
  private val tokens: AuthTokenProvider,
  private val client: OkHttpClient = defaultClient(),
  private val io: CoroutineDispatcher = Dispatchers.IO,
) : EscudoApi {

  private val base: HttpUrl = baseUrl.trimEnd('/').toHttpUrl()

  override suspend fun getPolicy(): Policy = get("policy")

  override suspend fun checkCoverage(incidentType: String): CoverageCheck =
    get("coverage/check") { addQueryParameter("incident_type", incidentType) }

  override suspend fun openClaim(request: OpenClaimRequest): Claim =
    post("claims", EscudoJson.encodeToString(OpenClaimRequest.serializer(), request).asJson())

  override suspend fun attachClaimPhotos(claimId: String, photos: List<PhotoUpload>): DamageAssessment {
    val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
      photos.forEach { photo ->
        addFormDataPart("photos", photo.fileName, photo.bytes.toRequestBody(photo.mimeType.toMediaType()))
      }
    }.build()
    return post("claims/$claimId/photos", body)
  }

  override suspend fun getLatestClaim(): Claim? =
    try {
      get<Claim>("claims/latest")
    } catch (e: ApiException) {
      if (e.code == 404) null else throw e
    }

  override suspend fun getClaim(claimId: String): Claim = get("claims/$claimId")

  override suspend fun listPendingApprovals(): List<Approval> = get("approvals")

  override suspend fun approve(approvalId: String, deviceId: String, signature: String) {
    send("approvals/$approvalId/approve", decision(deviceId, signature))
  }

  override suspend fun reject(approvalId: String, deviceId: String, signature: String) {
    send("approvals/$approvalId/reject", decision(deviceId, signature))
  }

  override suspend fun registerDevice(fcmToken: String, deviceId: String, publicKey: String) {
    val body = RegisterDeviceRequest(deviceId = deviceId, fcmToken = fcmToken, publicKey = publicKey)
    send("devices", EscudoJson.encodeToString(RegisterDeviceRequest.serializer(), body).asJson())
  }

  private fun decision(deviceId: String, signature: String): RequestBody =
    EscudoJson.encodeToString(DecisionRequest.serializer(), DecisionRequest(deviceId, signature)).asJson()

  override suspend fun postEvent(event: EscudoEvent) {
    send("events", EscudoJson.encodeToString(EscudoEvent.serializer(), event).asJson())
  }

  override suspend fun restartDemo() {
    send("demo/restart", "{}".asJson())
  }

  // --- plumbing -------------------------------------------------------------------------------

  private suspend inline fun <reified T> get(path: String, crossinline query: HttpUrl.Builder.() -> Unit = {}): T {
    val url = base.newBuilder().addPathSegments(path).apply(query).build()
    return EscudoJson.decodeFromString(execute(Request.Builder().url(url).get()))
  }

  private suspend inline fun <reified T> post(path: String, body: RequestBody): T =
    EscudoJson.decodeFromString(execute(Request.Builder().url(base.newBuilder().addPathSegments(path).build()).post(body)))

  private suspend fun send(path: String, body: RequestBody) {
    execute(Request.Builder().url(base.newBuilder().addPathSegments(path).build()).post(body))
  }

  private suspend fun execute(builder: Request.Builder): String {
    val token = tokens.idToken()
    val caller = currentCoroutineContext()[CallerSurface] ?: CallerSurface(Surface.ANDROID)
    val request = builder
      .header("Authorization", "Bearer $token")
      .header("X-Escudo-Surface", caller.surface.wire)
      .header("X-Escudo-Actor", caller.actor)
      .build()
    return withContext(io) { executeRetryingLookups(request) }
  }

  private suspend fun executeRetryingLookups(request: Request): String {
    var attempt = 0
    while (true) {
      try {
        return client.newCall(request).execute().use { response ->
          val text = response.body.string()
          if (!response.isSuccessful) throw ApiException(response.code, "HTTP ${response.code} ${request.url.encodedPath}: $text")
          text
        }
      } catch (e: IOException) {
        // The emulator's DNS sometimes fails the first lookup after a while idle. A failed lookup or
        // a refused connection means the request never reached the server, so retrying is safe even
        // for single-use calls (approvals).
        if (!(e is UnknownHostException || e is ConnectException) || ++attempt > 2) throw e
        delay(1000L * attempt)
      }
    }
  }

  private fun String.asJson(): RequestBody = toRequestBody(JSON)

  companion object {
    private val JSON = "application/json".toMediaType()

    fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
      .connectTimeout(15, TimeUnit.SECONDS)
      // Attaching photos runs the multimodal damage assessor server-side; usually 6–18 s, but a
      // 115 s spike was measured on 2026-10-01. Better a long wait than a false "sin conexión".
      .readTimeout(150, TimeUnit.SECONDS)
      .writeTimeout(60, TimeUnit.SECONDS)
      .build()
  }
}

/** Lenient JSON: the backend may add fields; the phone must not break on them. */
val EscudoJson = Json {
  ignoreUnknownKeys = true
  explicitNulls = false
  coerceInputValues = true
  encodeDefaults = true
}
