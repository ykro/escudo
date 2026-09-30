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
  fun `approval requests and tool activity are recognized`() {
    assertEquals(
      AssistantEvent.ApprovalRequested("apr_1", "Firmar requiere aprobación"),
      parse("""{"type":"approval_requested","approval_id":"apr_1","reason":"Firmar requiere aprobación"}"""),
    )
    assertEquals(AssistantEvent.ToolActivity("request_tow", "Grúa en camino"), parse("""{"type":"tool_result","tool":"request_tow","summary":"Grúa en camino"}"""))
    assertEquals(AssistantEvent.Interrupted, parse("""{"type":"interrupted"}"""))
    assertEquals(AssistantEvent.TurnComplete, parse("""{"type":"turn_complete"}"""))
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
    assertNull(parse("""{"type":"approval_requested"}"""))
  }

  @Test
  fun `push-to-talk control messages`() {
    assertEquals("""{"type":"activity_start"}""", AssistantProtocol.ACTIVITY_START)
    assertEquals("""{"type":"activity_end"}""", AssistantProtocol.ACTIVITY_END)
  }
}
