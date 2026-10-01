package app.escudo.android.assistant

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import androidx.annotation.RequiresPermission
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Where the customer's voice comes from. The assistant pipeline (chunking, WebSocket, Live,
 * tools, playback) does not know or care: production uses the microphone; tests and rehearsals
 * on the emulator use a recorded file, so the exact same path runs without a person talking.
 */
interface VoiceSource {
  /**
   * Starts emitting PCM16 LE mono 16 kHz in 40 ms chunks.
   * [onEnd] fires when the source runs out by itself (a file); a microphone only ends on [stop].
   */
  fun start(onLevel: (Float) -> Unit, onChunk: (ByteArray) -> Unit, onEnd: () -> Unit)
  fun stop()

  companion object {
    const val CHUNK_MS = 40
    val CHUNK_BYTES = AssistantProtocol.INPUT_SAMPLE_RATE * 2 * CHUNK_MS / 1000
  }
}

/** Push-to-talk microphone (AudioRecord, voice-communication tuning, AEC + NS when available). */
class MicrophoneSource(private val scope: CoroutineScope) : VoiceSource {
  private var record: AudioRecord? = null
  private var job: Job? = null
  private val effects = mutableListOf<AudioEffect>()

  /** Peak level (0..1) of the current push-to-talk, logged on stop to diagnose silent mics. */
  private var peak = 0f

  @RequiresPermission(Manifest.permission.RECORD_AUDIO)
  override fun start(onLevel: (Float) -> Unit, onChunk: (ByteArray) -> Unit, onEnd: () -> Unit) {
    if (job != null) return
    val rate = AssistantProtocol.INPUT_SAMPLE_RATE
    val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val recorder = AudioRecord(
      MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate,
      AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
      maxOf(minBuffer, VoiceSource.CHUNK_BYTES * 4),
    )
    if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(recorder.audioSessionId)?.let { it.enabled = true; effects += it }
    if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(recorder.audioSessionId)?.let { it.enabled = true; effects += it }
    record = recorder
    peak = 0f
    recorder.startRecording()
    job = scope.launch(Dispatchers.IO) {
      val buffer = ByteArray(VoiceSource.CHUNK_BYTES)
      while (isActive) {
        val read = recorder.read(buffer, 0, buffer.size)
        if (read > 0) {
          val chunk = buffer.copyOf(read)
          val level = rms(chunk)
          if (level > peak) peak = level
          onLevel(level)
          onChunk(chunk)
        }
      }
    }
  }

  override fun stop() {
    if (job != null) Log.d(TAG, "push-to-talk peak level %.3f".format(peak))
    job?.cancel()
    job = null
    record?.run { runCatching { stop() }; release() }
    record = null
    effects.forEach { it.release() }
    effects.clear()
  }

  private companion object {
    const val TAG = "MicrophoneSource"
  }
}

/**
 * Plays a recorded WAV (PCM16 LE, 16 kHz, mono) into the pipeline **at real time**, 40 ms per
 * chunk, exactly as the microphone would deliver it. Used for emulator tests and rehearsals.
 */
class FileSource(
  private val scope: CoroutineScope,
  private val label: String,
  private val open: () -> InputStream,
  private val realTime: Boolean = true,
) : VoiceSource {
  private var job: Job? = null

  override fun start(onLevel: (Float) -> Unit, onChunk: (ByteArray) -> Unit, onEnd: () -> Unit) {
    if (job != null) return
    job = scope.launch(Dispatchers.IO) {
      var sent = 0L
      try {
        open().use { input ->
          val pcm = WavPcm.openData(input)
          val buffer = ByteArray(VoiceSource.CHUNK_BYTES)
          while (isActive) {
            val read = pcm.readChunk(buffer)
            if (read <= 0) break
            val chunk = if (read == buffer.size) buffer.copyOf() else buffer.copyOf(read)
            onLevel(rms(chunk))
            onChunk(chunk)
            sent += read
            if (realTime) delay(VoiceSource.CHUNK_MS.toLong())
          }
        }
        Log.d(TAG, "Streamed $label: $sent bytes (${sent / 32} ms)")
      } catch (e: IOException) {
        Log.w(TAG, "Could not stream $label", e)
      } finally {
        job = null
        onEnd()
      }
    }
  }

  override fun stop() {
    job?.cancel()
    job = null
  }

  private companion object {
    const val TAG = "FileSource"
  }
}

/** Minimal WAV reader: accepts only the Live input format, so a wrong fixture fails loudly. */
object WavPcm {
  class UnsupportedWav(message: String) : IOException(message)

  /** Validates the header and returns a stream positioned at the start of the PCM data. */
  fun openData(input: InputStream): DataInputStream {
    val data = DataInputStream(input.buffered())
    if (data.fourCC() != "RIFF") throw UnsupportedWav("not a RIFF file")
    data.le32()
    if (data.fourCC() != "WAVE") throw UnsupportedWav("not a WAVE file")
    var formatOk = false
    while (true) {
      val id = try {
        data.fourCC()
      } catch (e: EOFException) {
        throw UnsupportedWav("no data chunk")
      }
      val size = data.le32()
      when (id) {
        "fmt " -> {
          val format = data.le16()
          val channels = data.le16()
          val rate = data.le32()
          data.le32() // byte rate
          data.le16() // block align
          val bits = data.le16()
          data.skipFully(size - 16)
          if (format != 1 || channels != 1 || rate != AssistantProtocol.INPUT_SAMPLE_RATE || bits != 16) {
            throw UnsupportedWav("need PCM16 mono ${AssistantProtocol.INPUT_SAMPLE_RATE} Hz, got format=$format channels=$channels rate=$rate bits=$bits")
          }
          formatOk = true
        }
        "data" -> {
          if (!formatOk) throw UnsupportedWav("data before fmt")
          return data
        }
        else -> data.skipFully(size + (size and 1)) // chunks are word-aligned
      }
    }
  }

  private fun DataInputStream.fourCC(): String = String(ByteArray(4).also { readFully(it) }, Charsets.US_ASCII)
  private fun DataInputStream.le16(): Int = readUnsignedByte() or (readUnsignedByte() shl 8)
  private fun DataInputStream.le32(): Int = le16() or (le16() shl 16)
  private fun DataInputStream.skipFully(n: Int) {
    var left = n
    while (left > 0) {
      val skipped = skipBytes(left)
      if (skipped <= 0) throw EOFException()
      left -= skipped
    }
  }
}

/** Reads until [buffer] is full or the stream ends; returns bytes read, or -1 at end. */
internal fun InputStream.readChunk(buffer: ByteArray): Int {
  var total = 0
  while (total < buffer.size) {
    val n = read(buffer, total, buffer.size - total)
    if (n < 0) break
    total += n
  }
  return if (total == 0) -1 else total
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
