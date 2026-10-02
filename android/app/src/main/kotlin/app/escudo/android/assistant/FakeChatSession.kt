package app.escudo.android.assistant

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * Scripted assistant for the fake flavor, speaking the real protocol: tool calls, streamed
 * deltas, a final message, and an approval pause when asked to book a shop.
 */
class FakeChatSession : ChatSession {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val _events = MutableSharedFlow<ChatEvent>(extraBufferCapacity = 64)
  override val events: SharedFlow<ChatEvent> = _events

  override fun connect(sessionId: String?) {
    scope.launch { delay(200); _events.emit(ChatEvent.Ready(sessionId ?: "fake-session", mock = true)) }
  }

  override fun send(text: String) {
    scope.launch {
      val wantsShop = text.contains("taller", ignoreCase = true)
      tool(if (wantsShop) "list_repair_options" else "get_policy")
      if (wantsShop) {
        tool("select_repair_shop")
        _events.emit(
          ChatEvent.ApprovalRequired("apr_fake", "select_repair_shop", "Reservar Taller Central mañana a las 8:00.", "Reserva un taller a tu nombre.", "sha256:fake"),
        )
      }
      val reply = if (wantsShop) {
        "Te envié una solicitud para reservar Taller Central. Apruébala con tu huella y sigo yo."
      } else {
        "Modo de prueba: tu póliza cubre daños propios, grúa y auto de reemplazo por 7 días."
      }
      reply.split(" ").forEach { word -> _events.emit(ChatEvent.Text("$word ", final = false)); delay(30) }
      _events.emit(ChatEvent.Text(reply, final = true))
      _events.emit(ChatEvent.TurnComplete)
    }
  }

  private suspend fun tool(name: String) {
    _events.emit(ChatEvent.ToolCall(name))
    delay(400)
    _events.emit(ChatEvent.ToolResult(name))
  }

  override fun close() = scope.cancel()
}
