package app.escudo.android.ui

import app.escudo.android.assistant.ChatEvent
import app.escudo.android.ui.assistant.ChatItem
import app.escudo.android.ui.assistant.ChatReducer
import app.escudo.android.ui.assistant.ChatState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatStateTest {
  private var n = 0
  private fun ChatState.on(vararg events: ChatEvent): ChatState = events.fold(this) { s, e -> ChatReducer.reduce(s, e, "k${n++}") }
  private val ready = ChatState().on(ChatEvent.Ready("chat_1", mock = false))

  @Test
  fun `ready connects and keeps the session id for reconnects`() {
    assertEquals(ChatState.Connection.CONNECTED, ready.connection)
    assertEquals("chat_1", ready.sessionId)
    val dropped = ready.on(ChatEvent.Disconnected("abort"))
    assertEquals(ChatState.Connection.DISCONNECTED, dropped.connection)
    assertEquals("chat_1", ChatReducer.connecting(dropped).sessionId)
  }

  @Test
  fun `deltas stream into one reply and the final frame replaces them`() {
    val s = ChatReducer.userSent(ready, "Hola", "u1").on(
      ChatEvent.Text("Ho", final = false),
      ChatEvent.Text("la", final = false),
    )
    assertEquals(ChatItem.Assistant((s.items.last() as ChatItem.Assistant).key, "Hola", streaming = true), s.items.last())
    val done = s.on(ChatEvent.Text("¡Hola, Andrea!", final = true), ChatEvent.TurnComplete)
    assertEquals(2, done.items.size)
    assertEquals("¡Hola, Andrea!", (done.items.last() as ChatItem.Assistant).text)
    assertFalse((done.items.last() as ChatItem.Assistant).streaming)
    assertFalse(done.thinking)
  }

  @Test
  fun `a new user message starts a new assistant reply`() {
    val first = ChatReducer.userSent(ready, "a", "u1").on(ChatEvent.Text("uno", final = true), ChatEvent.TurnComplete)
    val second = ChatReducer.userSent(first, "b", "u2").on(ChatEvent.Text("dos", final = false))
    assertEquals(listOf("a", "uno", "b", "dos"), second.items.map { (it as? ChatItem.User)?.text ?: (it as ChatItem.Assistant).text })
  }

  @Test
  fun `tool calls become chips that complete with their result`() {
    val s = ChatReducer.userSent(ready, "taller", "u1").on(ChatEvent.ToolCall("list_repair_options"))
    assertEquals(ChatItem.Tool((s.items.last() as ChatItem.Tool).key, "list_repair_options", done = false), s.items.last())
    assertTrue(s.thinking)
    val done = s.on(ChatEvent.ToolResult("list_repair_options"))
    assertTrue((done.items.last() as ChatItem.Tool).done)
  }

  @Test
  fun `approval pauses appear once and resolve in place`() {
    val required = ChatEvent.ApprovalRequired("apr_1", "select_repair_shop", "Reservar Taller Diez", "x", "sha256:a")
    val s = ready.on(required, required)
    assertEquals(1, s.items.count { it is ChatItem.Approval })
    assertEquals("apr_1", s.pendingApprovalId)

    val resolved = s.on(ChatEvent.TurnComplete, ChatEvent.ApprovalResolved("apr_1", "select_repair_shop", "approved"))
    assertEquals(ChatItem.Approval.Status.APPROVED, (resolved.items.single() as ChatItem.Approval).status)
    assertNull(resolved.pendingApprovalId)
    // The server resumes the conversation by itself after an approval.
    assertTrue(resolved.thinking)
  }

  @Test
  fun `errors stop the typing indicator and keep server text`() {
    val s = ChatReducer.userSent(ready, "hola", "u1").on(ChatEvent.Error("model_error", "El asistente tuvo un problema."))
    assertFalse(s.thinking)
    assertEquals("El asistente tuvo un problema.", s.errorText)
    val transport = ready.on(ChatEvent.Error(null, null))
    assertEquals(ChatState.ErrorKind.NETWORK, transport.errorKind)
  }

  @Test
  fun `repeated tool_call frames make one chip, and a finished turn stops every spinner`() {
    val s = ready.on(
      ChatEvent.ToolCall("request_tow"), ChatEvent.ToolCall("list_repair_options"),
      ChatEvent.ToolCall("request_tow"), ChatEvent.ToolCall("list_repair_options"),
      ChatEvent.ToolResult("request_tow"),
    )
    assertEquals(listOf("request_tow", "list_repair_options"), s.items.map { (it as ChatItem.Tool).tool })
    assertEquals(listOf(true, false), s.items.map { (it as ChatItem.Tool).done })
    assertTrue(s.on(ChatEvent.TurnComplete).items.all { (it as ChatItem.Tool).done })
  }
}
