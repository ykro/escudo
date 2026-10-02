package app.escudo.android.assistant

import android.util.Log
import app.escudo.android.data.auth.AuthTokenProvider
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * One conversation with Asistente Escudo: an ADK agent in the backend that acts only through
 * Escudo's MCP tools. The phone is a thin client: it sends what the customer types and renders
 * what comes back.
 */
interface ChatSession {
  val events: Flow<ChatEvent>

  /** [sessionId] from a previous `ready` continues that conversation (transcript lives server-side). */
  fun connect(sessionId: String?)
  fun send(text: String)
  fun close()
}

fun interface ChatSessionFactory {
  fun create(): ChatSession
}

/** [ChatSession] over `wss://…/assistant/chat` (contracts/tools.md). */
class LiveChatSession(
  private val url: String,
  private val tokens: AuthTokenProvider,
  private val client: OkHttpClient = OkHttpClient.Builder()
    .readTimeout(0, TimeUnit.MILLISECONDS)
    .pingInterval(20, TimeUnit.SECONDS)
    .build(),
) : ChatSession {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val _events = MutableSharedFlow<ChatEvent>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
  override val events: Flow<ChatEvent> = _events

  @Volatile private var socket: WebSocket? = null

  override fun connect(sessionId: String?) {
    scope.launch {
      val token = runCatching { tokens.idToken() }.getOrElse {
        _events.emit(ChatEvent.Error("unauthenticated", null))
        _events.emit(ChatEvent.Disconnected("no token"))
        return@launch
      }
      // Auth only in the header: tokens never go in the URL (they end up in logs).
      val request = Request.Builder().url(url).header("Authorization", "Bearer $token").apply {
        if (!sessionId.isNullOrBlank()) header(ChatProtocol.SESSION_HEADER, sessionId)
      }.build()
      socket = client.newWebSocket(request, Listener())
    }
  }

  override fun send(text: String) {
    socket?.send(ChatProtocol.text(text))
  }

  override fun close() {
    socket?.send(ChatProtocol.END)
    socket?.close(1000, "bye")
    socket = null
    scope.cancel()
  }

  private inner class Listener : WebSocketListener() {
    override fun onMessage(webSocket: WebSocket, text: String) {
      ChatProtocol.parse(text)?.let { _events.tryEmit(it) }
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
      Log.i(TAG, "Server closing: $code $reason")
      webSocket.close(code, null)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
      _events.tryEmit(ChatEvent.Disconnected(reason.ifBlank { null }))
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
      Log.w(TAG, "Socket failure (HTTP ${response?.code})", t)
      _events.tryEmit(ChatEvent.Error(null, null))
      _events.tryEmit(ChatEvent.Disconnected(t.message))
    }
  }

  private companion object {
    const val TAG = "LiveChatSession"
  }
}
