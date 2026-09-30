package app.escudo.android.push

import android.util.Log
import app.escudo.android.AppContainer
import app.escudo.android.BuildConfig
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await

/** `POST /devices` with this phone's FCM token, so the backend knows where to send approval pushes. */
object DeviceRegistration {
  private const val TAG = "DeviceRegistration"

  suspend fun registerCurrentToken(container: AppContainer) {
    if (!BuildConfig.HAS_FIREBASE) {
      Log.i(TAG, "No google-services.json: skipping FCM registration")
      return
    }
    runCatching { FirebaseMessaging.getInstance().token.await() }
      .onSuccess { register(container, it) }
      .onFailure { Log.w(TAG, "Could not get FCM token", it) }
  }

  suspend fun register(container: AppContainer, token: String) {
    runCatching { container.repository.registerDevice(token) }
      .onSuccess { Log.i(TAG, "Device registered for approval pushes") }
      .onFailure { Log.w(TAG, "POST /devices failed", it) }
  }
}
