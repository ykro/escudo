package app.escudo.android.assistant

import android.util.Base64
import app.escudo.android.data.api.EscudoJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
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
  data class Error(val message: String) : AssistantEvent

  enum class Role { USER, ASSISTANT }
}

/**
 * Wire protocol of `wss://…/assistant/live` (proposed in contracts/CHANGELOG.md).
 *
 * - Binary frames: raw audio. Phone → server PCM16 LE mono 16 kHz; server → phone PCM16 LE mono 24 kHz.
 * - Text frames: JSON control messages with a `type` field.
 *   Phone → server: `activity_start` / `activity_end` (push-to-talk, i.e. Live API manual activity detection).
 *   Server → phone: `transcript`, `tool_call`, `tool_result`, `approval_requested`, `interrupted`,
 *   `turn_complete`, `error`, and optionally `audio` with base64 `data` for servers that cannot send binary.
 *
 * Unknown types are ignored so the backend can add messages without breaking the phone.
 */
object AssistantProtocol {
  const val INPUT_SAMPLE_RATE = 16_000
  const val OUTPUT_SAMPLE_RATE = 24_000

  val ACTIVITY_START = """{"type":"activity_start"}"""
  val ACTIVITY_END = """{"type":"activity_end"}"""

  fun parse(text: String, base64Decoder: (String) -> ByteArray = ::decodeBase64): AssistantEvent? {
    val json = runCatching { EscudoJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
    return when (json.string("type")) {
      "transcript" -> {
        val body = json.string("text") ?: return null
        val role = if (json.string("role") == "user") AssistantEvent.Role.USER else AssistantEvent.Role.ASSISTANT
        AssistantEvent.Transcript(role, body, json["final"]?.jsonPrimitive?.booleanOrNull ?: true)
      }
      "tool_call", "tool_result" -> AssistantEvent.ToolActivity(json.string("tool") ?: "?", json.string("summary"))
      "approval_requested" -> json.string("approval_id")?.let { AssistantEvent.ApprovalRequested(it, json.string("reason")) }
      "interrupted" -> AssistantEvent.Interrupted
      "turn_complete" -> AssistantEvent.TurnComplete
      "error" -> AssistantEvent.Error(json.string("message") ?: "error")
      "audio" -> json.string("data")?.let { AssistantEvent.Audio(base64Decoder(it)) }
      else -> null
    }
  }

  private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

  private fun decodeBase64(data: String): ByteArray = Base64.decode(data, Base64.DEFAULT)
}
