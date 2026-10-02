package app.escudo.android.data.events

import android.util.Log
import app.escudo.android.data.api.EscudoApi
import app.escudo.android.data.model.EscudoEvent
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** Where on the phone a step happened (subset of the `surface` enum in events.schema.json). */
enum class Surface(val wire: String) {
  /** A human tapping through the app's own screens. */
  ANDROID("android"),

  /** An agent calling one of the app's AppFunctions. */
  APP_FUNCTIONS("appfunctions"),
}

/**
 * Appends steps to the presenter timeline via `POST /events`.
 *
 * Fire-and-forget on purpose: the timeline is for the audience, so a failed event must never
 * break the customer's flow.
 */
class TimelineEvents(
  private val api: EscudoApi,
  private val scope: CoroutineScope,
  private val clock: Clock = Clock.systemUTC(),
  private val sessionRunId: String = "android-" + UUID.randomUUID().toString().take(8),
) {
  fun post(
    surface: Surface,
    kind: String,
    summary: String,
    tool: String? = null,
    claimId: String? = null,
    autonomy: String? = null,
    approvalId: String? = null,
    reason: String? = null,
    data: JsonObject? = null,
  ) {
    val event = EscudoEvent(
      id = "evt_" + UUID.randomUUID().toString().replace("-", "").take(20),
      ts = Instant.now(clock).toString(),
      runId = claimId ?: sessionRunId,
      claimId = claimId,
      // Screens are driven by the customer; AppFunctions are driven by the system agent (Gemini on stage).
      actor = if (surface == Surface.APP_FUNCTIONS) "external_agent" else "customer",
      surface = surface.wire,
      kind = kind,
      tool = tool,
      summary = summary,
      autonomy = autonomy,
      approvalId = approvalId,
      reason = reason,
      data = data,
    )
    scope.launch {
      runCatching { api.postEvent(event) }
        .onFailure { Log.w("TimelineEvents", "Could not post event ${event.kind}: ${it.message}") }
    }
  }

  object Kind {
    /** The only kind clients may post (contracts/tools.md); capability events come from the backend. */
    const val MESSAGE = "message"
  }
}
