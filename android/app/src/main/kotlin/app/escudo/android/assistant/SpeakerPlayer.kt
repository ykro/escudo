package app.escudo.android.assistant

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/** Streams the assistant's PCM16 mono 24 kHz voice to the speaker as it arrives. */
class SpeakerPlayer {
  private val track: AudioTrack = run {
    val rate = AssistantProtocol.OUTPUT_SAMPLE_RATE
    val minBuffer = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
    AudioTrack.Builder()
      .setAudioAttributes(
        AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_MEDIA) // loud through the stage speakers, not the earpiece
          .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
          .build(),
      )
      .setAudioFormat(
        AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
          .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
      )
      .setBufferSizeInBytes(maxOf(minBuffer, rate * 2 / 2)) // ~500 ms of headroom
      .setTransferMode(AudioTrack.MODE_STREAM)
      .build()
      .also { it.play() }
  }

  /** Blocking write; call from a background thread. */
  fun write(pcm: ByteArray) {
    track.write(pcm, 0, pcm.size)
  }

  /** Barge-in: drop whatever the assistant was still saying. */
  fun interrupt() {
    track.pause()
    track.flush()
    track.play()
  }

  fun release() {
    runCatching { track.stop() }
    track.release()
  }
}
