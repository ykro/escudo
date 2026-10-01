package app.escudo.android.assistant

import android.util.Base64
import app.escudo.android.data.api.EscudoJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Something the Escudo Assistant session tells the UI. */
sealed interface AssistantEvent {
  data object Connected : AssistantEvent
  data class Disconnected(val reason: String?) : AssistantEvent
  class Audio(val pcm24k: ByteArray) : AssistantEvent
  data class Transcript(val role: Role, val text: String, val final: Boolean) : AssistantEvent
  data class ToolActivity(val tool: String, val summary: String?) : AssistantEvent
  data class ApprovalRequested(val approvalId: String, val reason: String?) : AssistantEvent
  data object Interrupted : AssistantEvent
  data object TurnComplete : AssistantEvent
  /** [message] comes from the server and is already Spanish; null means a transport failure. */
  data class Error(val message: String?, val sessionProblem: Boolean = false) : AssistantEvent

  enum class Role { USER, ASSISTANT }
}

/**
 * Wire protocol of `wss://…/assistant/live`, as implemented by backend/escudo/adapters/assistant_live.py
 * (documented in contracts/CHANGELOG.md).
 *
 * - Auth: `Authorization: Bearer <Firebase ID token>`.
 * - Binary frames: raw audio. Phone → server PCM16 LE mono 16 kHz; server → phone PCM16 LE mono 24 kHz.
 * - Text frames, phone → server: `{"type":"text","text":…}`, `{"type":"end"}`; `activity_start` /
 *   `activity_end` mark push-to-talk and are ignored by servers using automatic voice activity detection.
 * - Text frames, server → phone: `ready`, `transcript` {role, text, final}, `tool_call` / `tool_result`
 *   {name}, `approval_required` {approval_id, tool, summary, reason}, `interrupted`, `turn_complete`,
 *   `error` {code, message}; optionally `audio` with base64 `data`.
 *
 * Unknown types are ignored so the backend can add messages without breaking the phone.
 */
object AssistantProtocol {
  const val INPUT_SAMPLE_RATE = 16_000
  const val OUTPUT_SAMPLE_RATE = 24_000

  val ACTIVITY_START = """{"type":"activity_start"}"""
  val ACTIVITY_END = """{"type":"activity_end"}"""
  val END = """{"type":"end"}"""

  fun text(message: String): String = buildJsonObject {
    put("type", "text")
    put("text", message)
  }.toString()

  /**
   * Silence sent when the button is released. With automatic activity detection the model only
   * answers after it hears the end of speech, and a stream that just stops has no end.
   * Measured against the deployed Live model: 800 ms gets no answer, 2 s always does.
   */
  const val TRAILING_SILENCE_MS = 2_000
  val TRAILING_SILENCE = ByteArray(INPUT_SAMPLE_RATE * 2 * TRAILING_SILENCE_MS / 1000)

  fun parse(text: String, base64Decoder: (String) -> ByteArray = ::decodeBase64): AssistantEvent? {
    val json = runCatching { EscudoJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
    return when (json.string("type")) {
      "ready" -> AssistantEvent.Connected
      "transcript" -> {
        val body = json.string("text") ?: return null
        val role = if (json.string("role") == "user") AssistantEvent.Role.USER else AssistantEvent.Role.ASSISTANT
        AssistantEvent.Transcript(role, body, json["final"]?.jsonPrimitive?.booleanOrNull ?: true)
      }
      "tool_call" -> AssistantEvent.ToolActivity(json.string("name") ?: json.string("tool") ?: "?", json.string("summary"))
      "approval_required", "approval_requested" -> json.string("approval_id")?.let {
        AssistantEvent.ApprovalRequested(it, json.string("summary") ?: json.string("reason"))
      }
      "interrupted" -> AssistantEvent.Interrupted
      "turn_complete" -> AssistantEvent.TurnComplete
      "error" -> AssistantEvent.Error(
        message = json.string("message"),
        sessionProblem = json.string("code") in setOf("unauthenticated", "invalid_token"),
      )
      "audio" -> json.string("data")?.let { AssistantEvent.Audio(base64Decoder(it)) }
      else -> null // tool_result and future types: nothing to show
    }
  }

  private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

  private fun decodeBase64(data: String): ByteArray = Base64.decode(data, Base64.DEFAULT)
}
