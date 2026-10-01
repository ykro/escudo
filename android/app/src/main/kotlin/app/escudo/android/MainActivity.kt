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
import app.escudo.android.assistant.VoiceFixtures
import app.escudo.android.assistant.VoiceRequest
import app.escudo.android.ui.navigation.EscudoNavHost
import app.escudo.android.ui.theme.EscudoTheme

/**
 * Single activity. A FragmentActivity because androidx.biometric's BiometricPrompt (stable 1.1.0)
 * hosts its dialog in a fragment.
 */
class MainActivity : FragmentActivity() {
  private var deepLinkApprovalId by mutableStateOf<String?>(null)
  private var deepLinkNonce by mutableIntStateOf(0)
  private var debugVoice by mutableStateOf<VoiceRequest?>(null)

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
          debugVoice = debugVoice,
          onDebugVoiceHandled = { debugVoice = null },
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
    // Debug builds: `adb shell am start -n app.escudo.android/.MainActivity --es voice_input_file 01_choque`
    // makes the assistant hear that recording through the real pipeline (see testdata/voice).
    if (BuildConfig.DEBUG) {
      intent.getStringExtra(VoiceFixtures.EXTRA)?.let { debugVoice = VoiceFixtures.resolve(this, it) }
    }
    val data = intent.data
    val fromLink = data?.takeIf { it.scheme == "escudo" && it.host == "approvals" }
    val fromExtras = intent.getStringExtra("approval_id")
    if (fromLink == null && fromExtras == null) return
    deepLinkApprovalId = fromLink?.lastPathSegment ?: fromExtras
    deepLinkNonce++
  }

  private fun requestNotificationPermission() {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
      notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
  }
}
