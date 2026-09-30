package app.escudo.android

import android.app.Application
import android.util.Log
import app.escudo.android.push.ApprovalNotifications
import app.escudo.android.push.DeviceRegistration
import kotlinx.coroutines.launch

class EscudoApp : Application() {
  lateinit var container: AppContainer
    private set

  override fun onCreate() {
    super.onCreate()
    container = AppContainer(this)
    ApprovalNotifications.createChannel(this)

    // Sign in (first launch only, before the talk) and register this phone for approval pushes.
    container.appScope.launch {
      runCatching { container.customerAuth?.ensureSignedIn() }
        .onFailure { Log.e(TAG, "Customer sign-in failed", it) }
      DeviceRegistration.registerCurrentToken(container)
    }
  }

  private companion object {
    const val TAG = "EscudoApp"
  }
}

val android.content.Context.container: AppContainer
  get() = (applicationContext as EscudoApp).container
