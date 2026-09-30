package app.escudo.android.data.auth

/** Supplies the customer's Firebase ID token for `Authorization: Bearer …`. */
fun interface AuthTokenProvider {
  suspend fun idToken(): String
}

/** Used by the fake flavor and tests: no Firebase, a constant token. */
class StaticTokenProvider(private val token: String = "fake-token") : AuthTokenProvider {
  override suspend fun idToken(): String = token
}
