package app.escudo.android.assistant

import android.Manifest
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Push-to-talk microphone: PCM16 mono 16 kHz in ~40 ms chunks, the Live API input format. */
class MicrophoneStreamer(private val scope: CoroutineScope) {
  private var record: AudioRecord? = null
  private var job: Job? = null
  private val effects = mutableListOf<android.media.audiofx.AudioEffect>()

  @RequiresPermission(Manifest.permission.RECORD_AUDIO)
  fun start(onChunk: (ByteArray) -> Unit) {
    if (job != null) return
    val rate = AssistantProtocol.INPUT_SAMPLE_RATE
    val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val chunkBytes = rate * 2 / 25 // 40 ms
    val recorder = AudioRecord(
      MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate,
      AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
      maxOf(minBuffer, chunkBytes * 4),
    )
    if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(recorder.audioSessionId)?.let { it.enabled = true; effects += it }
    if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(recorder.audioSessionId)?.let { it.enabled = true; effects += it }
    record = recorder
    recorder.startRecording()
    job = scope.launch(Dispatchers.IO) {
      val buffer = ByteArray(chunkBytes)
      while (isActive) {
        val read = recorder.read(buffer, 0, buffer.size)
        if (read > 0) onChunk(buffer.copyOf(read))
      }
    }
  }

  fun stop() {
    job?.cancel()
    job = null
    record?.run { runCatching { stop() }; release() }
    record = null
    effects.forEach { it.release() }
    effects.clear()
  }
}

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
