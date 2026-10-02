package app.escudo.android.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatProtocolTest {
  @Test
  fun `server frames from tools md are parsed`() {
    assertEquals(ChatEvent.Ready("chat_1", mock = false), ChatProtocol.parse("""{"type":"ready","session_id":"chat_1","mock":false}"""))
    assertEquals(ChatEvent.Text("Hola", final = false), ChatProtocol.parse("""{"type":"text","text":"Hola","final":false}"""))
    assertEquals(ChatEvent.Text("Hola, Andrea", final = true), ChatProtocol.parse("""{"type":"text","text":"Hola, Andrea","final":true}"""))
    assertEquals(ChatEvent.ToolCall("list_repair_options"), ChatProtocol.parse("""{"type":"tool_call","name":"list_repair_options"}"""))
    assertEquals(ChatEvent.ToolResult("list_repair_options"), ChatProtocol.parse("""{"type":"tool_result","name":"list_repair_options"}"""))
    assertEquals(
      ChatEvent.ApprovalRequired("apr_1", "sign_claim", "Firmar y enviar", "Te compromete", "sha256:ab"),
      ChatProtocol.parse("""{"type":"approval_required","approval_id":"apr_1","tool":"sign_claim","summary":"Firmar y enviar","reason":"Te compromete","args_hash":"sha256:ab"}"""),
    )
    assertEquals(
      ChatEvent.ApprovalResolved("apr_1", "sign_claim", "approved"),
      ChatProtocol.parse("""{"type":"approval_resolved","approval_id":"apr_1","tool":"sign_claim","status":"approved"}"""),
    )
    assertEquals(ChatEvent.TurnComplete, ChatProtocol.parse("""{"type":"turn_complete"}"""))
  }

  @Test
  fun `errors carry Spanish text and session problems are recognized`() {
    val error = ChatProtocol.parse("""{"type":"error","code":"invalid_token","message":"Tu sesión expiró."}""") as ChatEvent.Error
    assertEquals("Tu sesión expiró.", error.message)
    assertTrue(error.sessionProblem)
  }

  @Test
  fun `unknown or malformed frames are ignored`() {
    assertNull(ChatProtocol.parse("""{"type":"something_new"}"""))
    assertNull(ChatProtocol.parse("not json"))
    assertNull(ChatProtocol.parse("""{"type":"approval_required"}"""))
  }

  @Test
  fun `client frames`() {
    assertEquals("""{"type":"text","text":"Sí, acepto el deducible."}""", ChatProtocol.text("Sí, acepto el deducible."))
    assertEquals("""{"type":"text","text":"dice \"hola\""}""", ChatProtocol.text("dice \"hola\""))
    assertEquals("""{"type":"end"}""", ChatProtocol.END)
  }
}
