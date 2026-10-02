package app.escudo.android.push

import app.escudo.android.ScenarioLog
import app.escudo.android.container
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.launch

/**
 * Receives the backend's "approval needed" pushes (step 2 of the approval protocol) and turns
 * them into a notification that opens the approvals screen.
 */
class EscudoMessagingService : FirebaseMessagingService() {

  // FCM 25 delivers the registration token here after register() (onNewToken is deprecated).
  override fun onRegistered(token: String) {
    container.appScope.launch { DeviceRegistration.register(container, token) }
  }

  override fun onMessageReceived(message: RemoteMessage) {
    val request = ApprovalPush.parse(message.data, message.notification?.title, message.notification?.body) ?: return
    ScenarioLog.mark("approval_push", "${request.approvalId} ${message.data["tool"].orEmpty()}")
    ApprovalNotifications.show(this, request)
  }
}
