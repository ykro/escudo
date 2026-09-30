package app.escudo.android.assistant

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/** Scripted assistant for the fake flavor: exercises the UI and audio capture without a backend. */
class FakeAssistantSession : AssistantSession {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val _events = MutableSharedFlow<AssistantEvent>(extraBufferCapacity = 64)
  override val events: SharedFlow<AssistantEvent> = _events
  private var bytesHeard = 0L

  override fun connect() {
    scope.launch { delay(200); _events.emit(AssistantEvent.Connected) }
  }

  override fun startTalking() {
    bytesHeard = 0
  }

  override fun sendAudio(pcm16k: ByteArray) {
    bytesHeard += pcm16k.size
  }

  override fun stopTalking() {
    val seconds = bytesHeard / (AssistantProtocol.INPUT_SAMPLE_RATE * 2.0)
    scope.launch {
      _events.emit(AssistantEvent.Transcript(AssistantEvent.Role.USER, "(%.1f s de audio)".format(seconds), final = true))
      answer()
    }
  }

  override fun sendText(text: String) {
    scope.launch { answer() }
  }

  private suspend fun answer() {
    delay(400)
    _events.emit(AssistantEvent.ToolActivity("get_policy", "Póliza consultada"))
    delay(400)
    _events.emit(
      AssistantEvent.Transcript(
        AssistantEvent.Role.ASSISTANT,
        "Modo de prueba: tu póliza cubre daños propios y grúa. Cuando el servidor esté conectado te responderé con voz.",
        final = true,
      ),
    )
    _events.emit(AssistantEvent.TurnComplete)
  }

  override fun close() = scope.cancel()
}
