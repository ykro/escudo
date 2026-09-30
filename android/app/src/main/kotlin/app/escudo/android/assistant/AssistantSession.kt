package app.escudo.android.assistant

import kotlinx.coroutines.flow.Flow

/** One voice conversation with the Escudo Assistant (ADK + Gemini Live, running in the backend). */
interface AssistantSession {
  val events: Flow<AssistantEvent>
  fun connect()
  fun startTalking()
  fun sendAudio(pcm16k: ByteArray)
  fun stopTalking()

  /** Typed message, for noisy places or when the microphone is unavailable. */
  fun sendText(text: String)
  fun close()
}

fun interface AssistantSessionFactory {
  fun create(): AssistantSession
}
