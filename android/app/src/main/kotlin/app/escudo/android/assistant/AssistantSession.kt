package app.escudo.android.assistant

import kotlinx.coroutines.flow.Flow

/** One voice conversation with the Escudo Assistant (ADK + Gemini Live, running in the backend). */
interface AssistantSession {
  val events: Flow<AssistantEvent>
  fun connect()
  fun startTalking()
  fun sendAudio(pcm16k: ByteArray)
  fun stopTalking()
  fun close()
}

fun interface AssistantSessionFactory {
  fun create(): AssistantSession
}
