package app.escudo.android.push

import app.escudo.android.container
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.launch

/**
 * Receives the backend's "approval needed" pushes (step 2 of the approval protocol) and turns
 * them into a notification that opens the approvals screen.
 */
class EscudoMessagingService : FirebaseMessagingService() {

  override fun onNewToken(token: String) {
    container.appScope.launch { DeviceRegistration.register(container, token) }
  }

  override fun onMessageReceived(message: RemoteMessage) {
    val request = ApprovalPush.parse(message.data, message.notification?.title, message.notification?.body) ?: return
    ApprovalNotifications.show(this, request)
  }
}
