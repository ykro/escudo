package app.escudo.android

import android.content.Context
import app.escudo.android.approvals.ApprovalSigner
import app.escudo.android.approvals.KeystoreApprovalSigner
import app.escudo.android.assistant.AssistantSessionFactory
import app.escudo.android.assistant.FakeAssistantSession
import app.escudo.android.assistant.LiveAssistantSession
import app.escudo.android.data.EscudoRepository
import app.escudo.android.data.PhotoLoader
import app.escudo.android.data.api.EscudoApi
import app.escudo.android.data.api.FakeEscudoApi
import app.escudo.android.data.api.HttpEscudoApi
import app.escudo.android.data.auth.AuthTokenProvider
import app.escudo.android.data.auth.FirebaseCustomerAuth
import app.escudo.android.data.auth.StaticTokenProvider
import app.escudo.android.data.events.TimelineEvents
import com.google.firebase.auth.FirebaseAuth
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual dependency wiring: one place that decides fake vs. live. Screens, the FCM service and
 * the AppFunctions service all get the same repository from here.
 */
class AppContainer(private val context: Context) {
  val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  val config = AppConfig.fromBuildConfig()

  /** Present only in the live flavor with google-services.json. */
  val customerAuth: FirebaseCustomerAuth? =
    if (!config.useFakeApi && BuildConfig.HAS_FIREBASE && !BuildConfig.MOCK_AUTH) {
      FirebaseCustomerAuth(FirebaseAuth.getInstance(), BuildConfig.CUSTOMER_EMAIL, BuildConfig.CUSTOMER_PASSWORD)
    } else {
      null
    }

  private val tokens: AuthTokenProvider = customerAuth ?: StaticTokenProvider()

  val api: EscudoApi =
    if (config.useFakeApi) {
      FakeEscudoApi()
    } else {
      HttpEscudoApi(config.apiUrl.ifBlank { MISSING_URL }, tokens)
    }

  val events = TimelineEvents(api, appScope)
  val repository = EscudoRepository(api)
  val photos = PhotoLoader(context)

  /** Device-bound approval key (escudo-approval:v1), EC P-256 in the Android Keystore. */
  val approvalSigner: ApprovalSigner = KeystoreApprovalSigner()

  val assistant = AssistantSessionFactory {
    if (config.useFakeApi) FakeAssistantSession() else LiveAssistantSession(config.assistantUrl, tokens)
  }

  /** Stable per-install id sent with approvals as the device-bound signal. */
  val deviceId: String by lazy {
    val prefs = context.getSharedPreferences("escudo", Context.MODE_PRIVATE)
    prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
      prefs.edit().putString(KEY_DEVICE_ID, it).apply()
    }
  }

  private companion object {
    const val KEY_DEVICE_ID = "device_id"

    // Fails every call with a clear host name instead of crashing at startup.
    const val MISSING_URL = "https://escudo-api-url-not-configured.invalid"
  }
}

data class AppConfig(val useFakeApi: Boolean, val apiUrl: String) {
  /** `wss://…/assistant/live`, derived from the REST base URL. */
  val assistantUrl: String
    get() = apiUrl.trimEnd('/').replaceFirst(Regex("^http"), "ws") + "/assistant/live"

  companion object {
    fun fromBuildConfig() = AppConfig(BuildConfig.USE_FAKE_API, BuildConfig.ESCUDO_API_URL)
  }
}
