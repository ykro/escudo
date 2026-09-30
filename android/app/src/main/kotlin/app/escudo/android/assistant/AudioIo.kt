package app.escudo.android.assistant

import android.Manifest
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlin.math.sqrt
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

  /** Peak level (0..1) of the current push-to-talk, logged on stop to diagnose silent mics. */
  private var peak = 0f

  @RequiresPermission(Manifest.permission.RECORD_AUDIO)
  fun start(onLevel: (Float) -> Unit = {}, onChunk: (ByteArray) -> Unit) {
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
    peak = 0f
    recorder.startRecording()
    job = scope.launch(Dispatchers.IO) {
      val buffer = ByteArray(chunkBytes)
      while (isActive) {
        val read = recorder.read(buffer, 0, buffer.size)
        if (read > 0) {
          val chunk = buffer.copyOf(read)
          val level = rms(chunk, read)
          if (level > peak) peak = level
          onLevel(level)
          onChunk(chunk)
        }
      }
    }
  }

  fun stop() {
    if (job != null) Log.d("MicrophoneStreamer", "push-to-talk peak level %.3f".format(peak))
    job?.cancel()
    job = null
    record?.run { runCatching { stop() }; release() }
    record = null
    effects.forEach { it.release() }
    effects.clear()
  }
}

/** Root-mean-square of PCM16 LE samples, scaled to 0..1 (speech is roughly 0.02–0.3). */
internal fun rms(pcm: ByteArray, length: Int = pcm.size): Float {
  var sum = 0.0
  var i = 0
  while (i + 1 < length) {
    val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toDouble()
    sum += sample * sample
    i += 2
  }
  val samples = length / 2
  return if (samples == 0) 0f else (sqrt(sum / samples) / Short.MAX_VALUE).toFloat()
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
