package app.escudo.android.push

import android.util.Log
import app.escudo.android.AppContainer
import app.escudo.android.BuildConfig
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await

/**
 * `POST /devices` with this phone's id, FCM token and approval public key: the backend sends
 * approval pushes to the token and accepts only decisions signed by this key.
 */
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
    runCatching {
      val publicKey = container.approvalSigner.publicKeyBase64() // creates the key on first run
      container.repository.registerDevice(token, container.deviceId, publicKey)
    }
      .onSuccess { Log.i(TAG, "Device registered for approval pushes") }
      .onFailure { Log.w(TAG, "POST /devices failed", it) }
  }
}
