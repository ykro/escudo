package app.escudo.android.assistant

import app.escudo.android.data.api.EscudoJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** What the Escudo Assistant chat tells the app (contracts/tools.md → Chat WebSocket). */
sealed interface ChatEvent {
  data class Ready(val sessionId: String?, val mock: Boolean) : ChatEvent

  /** Assistant text: `final = false` is a delta to append; `final = true` is the whole message. */
  data class Text(val text: String, val final: Boolean) : ChatEvent
  data class ToolCall(val name: String) : ChatEvent
  data class ToolResult(val name: String) : ChatEvent
  data class ApprovalRequired(
    val approvalId: String,
    val tool: String,
    val summary: String?,
    val reason: String?,
    val argsHash: String?,
  ) : ChatEvent

  /** The customer decided on her phone; the assistant continues on its own. */
  data class ApprovalResolved(val approvalId: String, val tool: String?, val status: String) : ChatEvent
  data object TurnComplete : ChatEvent

  /** [message] is Spanish from the server; null = transport failure (the UI uses its own text). */
  data class Error(val code: String?, val message: String?) : ChatEvent {
    val sessionProblem: Boolean get() = code == "unauthenticated" || code == "invalid_token"
  }

  data class Disconnected(val reason: String?) : ChatEvent
}

/**
 * Wire format of `wss://…/assistant/chat`: every frame is JSON text. Auth is the
 * `Authorization: Bearer` header on the upgrade request only; `X-Escudo-Chat-Session` continues a
 * conversation. Unknown frame types are ignored so the backend can add messages.
 */
object ChatProtocol {
  const val SESSION_HEADER = "X-Escudo-Chat-Session"
  val END = """{"type":"end"}"""

  fun text(message: String): String = buildJsonObject {
    put("type", "text")
    put("text", message)
  }.toString()

  fun parse(frame: String): ChatEvent? {
    val json = runCatching { EscudoJson.parseToJsonElement(frame).jsonObject }.getOrNull() ?: return null
    return when (json.string("type")) {
      "ready" -> ChatEvent.Ready(json.string("session_id"), json["mock"]?.jsonPrimitive?.booleanOrNull ?: false)
      "text" -> json.string("text")?.let { ChatEvent.Text(it, json["final"]?.jsonPrimitive?.booleanOrNull ?: true) }
      "tool_call" -> json.string("name")?.let { ChatEvent.ToolCall(it) }
      "tool_result" -> json.string("name")?.let { ChatEvent.ToolResult(it) }
      "approval_required" -> json.string("approval_id")?.let {
        ChatEvent.ApprovalRequired(it, json.string("tool").orEmpty(), json.string("summary"), json.string("reason"), json.string("args_hash"))
      }
      "approval_resolved" -> json.string("approval_id")?.let {
        ChatEvent.ApprovalResolved(it, json.string("tool"), json.string("status") ?: "approved")
      }
      "turn_complete" -> ChatEvent.TurnComplete
      "error" -> ChatEvent.Error(json.string("code"), json.string("message"))
      else -> null
    }
  }

  private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
}
