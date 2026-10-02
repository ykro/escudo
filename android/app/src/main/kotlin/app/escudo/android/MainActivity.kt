package app.escudo.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import app.escudo.android.ui.navigation.EscudoNavHost
import app.escudo.android.ui.theme.EscudoTheme

/**
 * Single activity. A FragmentActivity because androidx.biometric's BiometricPrompt (stable 1.1.0)
 * hosts its dialog in a fragment.
 */
class MainActivity : FragmentActivity() {
  private var deepLinkApprovalId by mutableStateOf<String?>(null)
  private var deepLinkNonce by mutableIntStateOf(0)
  private var debugChatMessage by mutableStateOf<String?>(null)
  private var debugReportDescription by mutableStateOf<String?>(null)

  private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    if (savedInstanceState == null) handleIntent(intent)
    requestNotificationPermission()
    setContent {
      EscudoTheme {
        EscudoNavHost(
          container = container,
          deepLinkApprovalId = deepLinkApprovalId,
          deepLinkNonce = deepLinkNonce,
          onDeepLinkHandled = { deepLinkApprovalId = null },
          debugChatMessage = debugChatMessage,
          onDebugChatHandled = { debugChatMessage = null },
          debugReportDescription = debugReportDescription,
          onDebugReportHandled = { debugReportDescription = null },
        )
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    handleIntent(intent)
  }

  /**
   * Two ways in from a push: our own notification (`escudo://approvals/{id}`), or the system
   * tray showing an FCM notification message while backgrounded (data keys become extras).
   */
  private fun handleIntent(intent: Intent?) {
    intent ?: return
    // Debug builds: `adb shell am start -n app.escudo.android/.MainActivity --es chat_message '…'`
    // sends that message in the assistant chat as if typed (scripts/chat_scenario.sh).
    // `--es report_description '…'` opens "Reportar accidente" with that text in "¿Qué pasó?"
    // (adb `input text` cannot type accents).
    if (BuildConfig.DEBUG) {
      intent.getStringExtra(EXTRA_CHAT_MESSAGE)?.let { debugChatMessage = it }
      intent.getStringExtra(EXTRA_REPORT_DESCRIPTION)?.let { debugReportDescription = it }
    }
    val data = intent.data
    val fromLink = data?.takeIf { it.scheme == "escudo" && it.host == "approvals" }
    val fromExtras = intent.getStringExtra("approval_id")
    if (fromLink == null && fromExtras == null) return
    deepLinkApprovalId = fromLink?.lastPathSegment ?: fromExtras
    deepLinkNonce++
  }

  companion object {
    const val EXTRA_CHAT_MESSAGE = "chat_message"
    const val EXTRA_REPORT_DESCRIPTION = "report_description"
  }

  private fun requestNotificationPermission() {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
      notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
  }
}
