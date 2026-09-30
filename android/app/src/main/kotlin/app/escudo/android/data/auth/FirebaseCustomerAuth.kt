package app.escudo.android.data.auth

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/**
 * Signs in the seeded demo customer with email/password once and then keeps the session.
 *
 * Sign-in happens before the talk (first launch); Firebase persists the session, so there is
 * never a login screen on stage. Credentials come from local.properties via BuildConfig and are
 * never committed.
 */
class FirebaseCustomerAuth(
  private val auth: FirebaseAuth,
  private val email: String,
  private val password: String,
) : AuthTokenProvider {
  private val mutex = Mutex()

  val isSignedIn: Boolean get() = auth.currentUser != null

  suspend fun ensureSignedIn() = mutex.withLock {
    if (auth.currentUser != null) return@withLock
    check(email.isNotBlank() && password.isNotBlank()) {
      "Missing escudo.customerEmail / escudo.customerPassword in local.properties"
    }
    auth.signInWithEmailAndPassword(email, password).await()
  }

  override suspend fun idToken(): String {
    ensureSignedIn()
    val user = checkNotNull(auth.currentUser) { "Not signed in" }
    return checkNotNull(user.getIdToken(false).await().token) { "Empty ID token" }
  }
}
