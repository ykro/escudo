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
    assertEquals(ChatItem.Tool((s.items.last() as ChatItem.Tool).key, "list_repair_options", ChatItem.Tool.Status.RUNNING), s.items.last())
    assertTrue(s.thinking)
    val done = s.on(ChatEvent.ToolResult("list_repair_options"))
    assertEquals(ChatItem.Tool.Status.DONE, (done.items.last() as ChatItem.Tool).status)
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
    assertEquals(listOf(ChatItem.Tool.Status.DONE, ChatItem.Tool.Status.RUNNING), s.items.map { (it as ChatItem.Tool).status })
    assertTrue(s.on(ChatEvent.TurnComplete).items.all { (it as ChatItem.Tool).status == ChatItem.Tool.Status.DONE })
  }

  private fun ChatState.tool(name: String) = items.filterIsInstance<ChatItem.Tool>().single { it.tool == name }.status

  @Test
  fun `a paused tool waits for the human and only completes after the approved retry`() {
    val required = ChatEvent.ApprovalRequired("apr_1", "select_repair_shop", "Reservar", null, "sha256:a")
    val paused = ChatReducer.userSent(ready, "agenda el taller", "u1").on(
      ChatEvent.ToolCall("select_repair_shop"), ChatEvent.ToolCall("select_repair_shop"),
      ChatEvent.ToolResult("select_repair_shop"), required,
    )
    assertEquals(ChatItem.Tool.Status.WAITING, paused.tool("select_repair_shop"))
    // The agent is stopped by design: no typing dots, even while its explanation streams.
    assertFalse(paused.showTyping)
    val explained = paused.on(ChatEvent.Text("Te envié una solicitud", final = true), ChatEvent.TurnComplete)
    assertEquals(ChatItem.Tool.Status.WAITING, explained.tool("select_repair_shop"))

    val approved = explained.on(ChatEvent.ApprovalResolved("apr_1", "select_repair_shop", "approved"))
    assertEquals(ChatItem.Tool.Status.WAITING, approved.tool("select_repair_shop"))
    assertTrue(approved.showTyping) // now the agent really works again
    val retrying = approved.on(ChatEvent.ToolCall("select_repair_shop"))
    assertEquals(ChatItem.Tool.Status.RUNNING, retrying.tool("select_repair_shop"))
    assertEquals(ChatItem.Tool.Status.DONE, retrying.on(ChatEvent.ToolResult("select_repair_shop")).tool("select_repair_shop"))
  }

  @Test
  fun `a rejected pause marks the action as not done`() {
    val s = ready.on(
      ChatEvent.ToolCall("sign_claim"), ChatEvent.ToolResult("sign_claim"),
      ChatEvent.ApprovalRequired("apr_2", "sign_claim", "Firmar", null, "h"),
      ChatEvent.ApprovalResolved("apr_2", "sign_claim", "rejected"),
    )
    assertEquals(ChatItem.Tool.Status.NOT_DONE, s.tool("sign_claim"))
  }

  @Test
  fun `the retry may start before approval_resolved arrives`() {
    val s = ready.on(
      ChatEvent.ToolCall("accept_deductible"), ChatEvent.ToolResult("accept_deductible"),
      ChatEvent.ApprovalRequired("apr_3", "accept_deductible", "Pagar USD 500", null, "h"), ChatEvent.TurnComplete,
      ChatEvent.ToolCall("accept_deductible"), ChatEvent.ToolResult("accept_deductible"),
      ChatEvent.ApprovalResolved("apr_3", "accept_deductible", "approved"),
    )
    assertEquals(ChatItem.Tool.Status.DONE, s.tool("accept_deductible"))
    assertEquals(ChatItem.Approval.Status.APPROVED, s.items.filterIsInstance<ChatItem.Approval>().single().status)
  }
}
