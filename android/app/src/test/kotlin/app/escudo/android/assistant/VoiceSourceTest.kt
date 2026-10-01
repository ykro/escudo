package app.escudo.android.assistant

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSourceTest {
  private fun wav(pcm: ByteArray, rate: Int = 16_000, channels: Int = 1, bits: Int = 16, extraChunk: Boolean = false): ByteArray {
    val out = ByteArrayOutputStream()
    fun le32(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
    fun le16(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
    out.write("RIFF".toByteArray()); le32(0); out.write("WAVE".toByteArray())
    out.write("fmt ".toByteArray()); le32(16); le16(1); le16(channels); le32(rate); le32(rate * channels * bits / 8); le16(channels * bits / 8); le16(bits)
    if (extraChunk) { out.write("LIST".toByteArray()); le32(3); out.write(byteArrayOf(1, 2, 3, 0)) } // odd size + pad byte
    out.write("data".toByteArray()); le32(pcm.size); out.write(pcm)
    return out.toByteArray()
  }

  @Test
  fun `reader skips extra chunks and returns the PCM data`() {
    val pcm = ByteArray(100) { it.toByte() }
    val data = WavPcm.openData(ByteArrayInputStream(wav(pcm, extraChunk = true)))
    assertEquals(pcm.toList(), data.readBytes().toList())
  }

  @Test
  fun `reader rejects anything that is not the Live input format`() {
    assertThrows(WavPcm.UnsupportedWav::class.java) { WavPcm.openData(ByteArrayInputStream(wav(ByteArray(4), rate = 44_100))) }
    assertThrows(WavPcm.UnsupportedWav::class.java) { WavPcm.openData(ByteArrayInputStream(wav(ByteArray(4), channels = 2))) }
    assertThrows(WavPcm.UnsupportedWav::class.java) { WavPcm.openData(ByteArrayInputStream("not a wav".toByteArray())) }
  }

  @Test
  fun `committed fixtures are PCM16 mono 16 kHz speech`() {
    val dir = File("../testdata/voice")
    val fixtures = dir.listFiles { f -> f.extension == "wav" }.orEmpty()
    assertTrue("fixtures missing in ${dir.absolutePath}", fixtures.size >= 3)
    fixtures.forEach { file ->
      val pcm = WavPcm.openData(file.inputStream()).readBytes()
      assertTrue("${file.name} too short", pcm.size > 16_000 * 2) // > 1 s
      assertTrue("${file.name} is silent", rms(pcm) > 0.02f)
    }
  }

  @Test
  fun `file source streams every byte in 40 ms chunks, then ends by itself`() = runBlocking {
    val pcm = ByteArray(VoiceSource.CHUNK_BYTES * 3 + 100) { (it % 7).toByte() }
    val chunks = mutableListOf<ByteArray>()
    val ended = CompletableDeferred<Unit>()
    val source = FileSource(CoroutineScope(Dispatchers.IO), "test", { ByteArrayInputStream(wav(pcm)) }, realTime = false)

    source.start(onLevel = {}, onChunk = { chunks += it }, onEnd = { ended.complete(Unit) })
    withTimeout(5_000) { ended.await() }

    assertEquals(listOf(1280, 1280, 1280, 100), chunks.map { it.size })
    assertEquals(pcm.toList(), chunks.flatMap { it.toList() })
  }
}
