package app.escudo.android.data.api

import app.escudo.android.data.events.Surface
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext

/**
 * Who is calling the API from this phone: the customer through a screen, or an agent through an
 * AppFunction. Sent as `X-Escudo-Surface` / `X-Escudo-Actor`, so the backend's own timeline events
 * for each capability say where the call came from. Same functions, different caller.
 */
class CallerSurface(val surface: Surface) : AbstractCoroutineContextElement(Key) {
  val actor: String get() = if (surface == Surface.APP_FUNCTIONS) "gemini" else "customer"

  companion object Key : CoroutineContext.Key<CallerSurface>
}

/** Runs [block] with every API call labeled as coming from [surface]. */
suspend fun <T> asCaller(surface: Surface, block: suspend () -> T): T = withContext(CallerSurface(surface)) { block() }
