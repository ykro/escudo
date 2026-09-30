package app.escudo.android.assistant

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantProtocolTest {
  private fun parse(text: String) = AssistantProtocol.parse(text) { it.encodeToByteArray() }

  @Test
  fun `transcripts carry role and finality`() {
    assertEquals(
      AssistantEvent.Transcript(AssistantEvent.Role.USER, "tuve un choque", final = false),
      parse("""{"type":"transcript","role":"user","text":"tuve un choque","final":false}"""),
    )
    assertEquals(
      AssistantEvent.Transcript(AssistantEvent.Role.ASSISTANT, "Lo siento", final = true),
      parse("""{"type":"transcript","role":"assistant","text":"Lo siento"}"""),
    )
  }

  @Test
  fun `session is connected only once the server says ready`() {
    assertEquals(
      AssistantEvent.Connected,
      parse("""{"type":"ready","mock":false,"input_audio":{"sample_rate":16000},"session_id":"voice-1"}"""),
    )
  }

  @Test
  fun `approval pauses and tool activity are recognized`() {
    assertEquals(
      AssistantEvent.ApprovalRequested("apr_1", "Firmar y enviar el reclamo"),
      parse("""{"type":"approval_required","approval_id":"apr_1","tool":"sign_claim","summary":"Firmar y enviar el reclamo","reason":"x"}"""),
    )
    assertEquals(AssistantEvent.ToolActivity("request_tow", null), parse("""{"type":"tool_call","name":"request_tow"}"""))
    assertNull(parse("""{"type":"tool_result","name":"request_tow"}"""))
    assertEquals(AssistantEvent.Interrupted, parse("""{"type":"interrupted"}"""))
    assertEquals(AssistantEvent.TurnComplete, parse("""{"type":"turn_complete"}"""))
    assertEquals(AssistantEvent.Error("Inicia sesión", sessionProblem = true), parse("""{"type":"error","code":"unauthenticated","message":"Inicia sesión"}"""))
  }

  @Test
  fun `base64 audio in a text frame is decoded`() {
    val event = parse("""{"type":"audio","data":"pcm"}""")
    assertTrue(event is AssistantEvent.Audio)
    assertArrayEquals("pcm".encodeToByteArray(), (event as AssistantEvent.Audio).pcm24k)
  }

  @Test
  fun `unknown or malformed messages are ignored`() {
    assertNull(parse("""{"type":"something_new"}"""))
    assertNull(parse("not json"))
    assertNull(parse("""{"type":"approval_required"}"""))
  }

  @Test
  fun `client control messages and trailing silence`() {
    assertEquals("""{"type":"activity_start"}""", AssistantProtocol.ACTIVITY_START)
    assertEquals("""{"type":"activity_end"}""", AssistantProtocol.ACTIVITY_END)
    assertEquals("""{"type":"end"}""", AssistantProtocol.END)
    // 800 ms of PCM16 mono at 16 kHz, all zeros.
    assertEquals(25_600, AssistantProtocol.TRAILING_SILENCE.size)
    assertTrue(AssistantProtocol.TRAILING_SILENCE.all { it == 0.toByte() })
  }
}
