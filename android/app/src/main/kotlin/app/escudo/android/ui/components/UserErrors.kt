package app.escudo.android.ui.components

import android.util.Log
import androidx.annotation.StringRes
import app.escudo.android.R
import app.escudo.android.data.api.ApiException
import java.io.IOException

/**
 * What the customer reads when something fails. Technical detail goes to logcat, never to the
 * screen: on stage a stack-trace fragment reads as "the demo broke".
 */
@StringRes
fun Throwable.userMessage(): Int {
  Log.w("Escudo", "Operation failed", this)
  return when (this) {
    is ApiException -> when (code) {
      401, 403 -> R.string.error_session
      404 -> R.string.error_not_found
      in 500..599 -> R.string.error_server
      else -> R.string.error_generic
    }
    is IOException -> R.string.error_network
    // Firebase types are matched by name to keep this file free of Firebase imports.
    else -> firebaseMessage() ?: fallback()
  }
}

@StringRes
private fun Throwable.firebaseMessage(): Int? = when {
  javaClass.name.contains("FirebaseNetwork") -> R.string.error_network
  javaClass.name.contains("FirebaseAuth") || javaClass.name.contains("FirebaseTooManyRequests") -> R.string.error_session
  else -> null
}

@StringRes
private fun Throwable.fallback(): Int {
  return when (this) {
    is IllegalStateException -> if (message.orEmpty().contains("customer", ignoreCase = true) ||
      message.orEmpty().contains("sign", ignoreCase = true)
    ) {
      R.string.error_session
    } else {
      R.string.error_generic
    }
    else -> R.string.error_generic
  }
}
