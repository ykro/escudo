package app.escudo.android.assistant

import android.content.Context
import java.io.File
import java.io.InputStream

/** A recorded voice to play into the assistant instead of the microphone. */
class VoiceRequest(val label: String, val open: () -> InputStream)

/**
 * Voice fixtures for debug builds: spoken Spanish WAVs from `testdata/voice/` (bundled as debug
 * assets) or any PCM16 16 kHz mono WAV the app can read. Lets every voice feature be tested on
 * the emulator, where nobody is talking into a microphone.
 */
object VoiceFixtures {
  const val EXTRA = "voice_input_file"
  private const val DIR = "voice"

  /** Bundled fixture names, e.g. "01_choque". Empty in release builds. */
  fun bundled(context: Context): List<String> =
    context.assets.list(DIR).orEmpty().filter { it.endsWith(".wav") }.map { it.removeSuffix(".wav") }.sorted()

  /**
   * "01_choque" or "asset:voice/01_choque.wav" → bundled fixture; "/sdcard/Download/x.wav" → file
   * (needs READ_MEDIA_AUDIO, declared in debug builds only).
   */
  fun resolve(context: Context, spec: String): VoiceRequest {
    val value = spec.trim()
    return when {
      value.startsWith("/") -> VoiceRequest(File(value).name) { File(value).inputStream() }
      else -> {
        val path = value.removePrefix("asset:").let { if (it.contains('/')) it else "$DIR/$it" }
          .let { if (it.endsWith(".wav")) it else "$it.wav" }
        val assets = context.applicationContext.assets
        VoiceRequest(path.substringAfterLast('/')) { assets.open(path) }
      }
    }
  }
}
