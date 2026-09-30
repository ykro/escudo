package app.escudo.android.assistant

import app.escudo.android.data.auth.AuthTokenProvider
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * Streams microphone audio to the backend's Escudo Assistant and receives its voice back.
 * The phone is a thin audio pipe: the agent, its tools and Gemini Live all run server-side.
 */
class LiveAssistantSession(
  private val url: String,
  private val tokens: AuthTokenProvider,
  private val client: OkHttpClient = OkHttpClient.Builder()
    .readTimeout(0, TimeUnit.MILLISECONDS)
    .pingInterval(15, TimeUnit.SECONDS)
    .build(),
) : AssistantSession {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val _events = MutableSharedFlow<AssistantEvent>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
  override val events: SharedFlow<AssistantEvent> = _events

  @Volatile private var socket: WebSocket? = null

  override fun connect() {
    scope.launch {
      val token = runCatching { tokens.idToken() }.getOrElse {
        _events.emit(AssistantEvent.Error("Sin sesión: ${it.message}"))
        return@launch
      }
      val request = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
      socket = client.newWebSocket(request, Listener())
    }
  }

  override fun startTalking() {
    socket?.send(AssistantProtocol.ACTIVITY_START)
  }

  override fun sendAudio(pcm16k: ByteArray) {
    socket?.send(pcm16k.toByteString())
  }

  override fun stopTalking() {
    socket?.send(AssistantProtocol.ACTIVITY_END)
  }

  override fun close() {
    socket?.close(1000, "bye")
    socket = null
    scope.cancel()
  }

  private inner class Listener : WebSocketListener() {
    override fun onOpen(webSocket: WebSocket, response: Response) {
      _events.tryEmit(AssistantEvent.Connected)
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
      _events.tryEmit(AssistantEvent.Audio(bytes.toByteArray()))
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
      AssistantProtocol.parse(text)?.let { _events.tryEmit(it) }
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
      _events.tryEmit(AssistantEvent.Disconnected(reason.ifBlank { null }))
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
      _events.tryEmit(AssistantEvent.Error(t.message ?: "Conexión perdida"))
      _events.tryEmit(AssistantEvent.Disconnected(t.message))
    }
  }
}
