package app.escudo.android.ui.assistant

import app.escudo.android.assistant.ChatEvent

/** One row of the conversation. */
sealed interface ChatItem {
  val key: String

  data class User(override val key: String, val text: String) : ChatItem

  /** [streaming] while deltas arrive; the final frame replaces the text. */
  data class Assistant(override val key: String, val text: String, val streaming: Boolean) : ChatItem

  /** Inline chip: what the assistant is doing ("Buscando talleres…"); [done] once the tool answered. */
  data class Tool(override val key: String, val tool: String, val done: Boolean) : ChatItem

  /** Inline approval pause; [status] pending → approved/rejected when the customer decides. */
  data class Approval(
    override val key: String,
    val approvalId: String,
    val tool: String,
    val summary: String?,
    val status: Status,
  ) : ChatItem {
    enum class Status { PENDING, APPROVED, REJECTED }
  }
}

data class ChatState(
  val connection: Connection = Connection.CONNECTING,
  val items: List<ChatItem> = emptyList(),
  /** The assistant is working on a turn (typing indicator, send disabled). */
  val thinking: Boolean = false,
  val sessionId: String? = null,
  /** Spanish error from the server, or null. */
  val errorText: String? = null,
  /** Transport or session problem without server text: the UI picks its own message. */
  val errorKind: ErrorKind? = null,
) {
  enum class Connection { CONNECTING, CONNECTED, DISCONNECTED }
  enum class ErrorKind { NETWORK, SESSION }

  val pendingApprovalId: String?
    get() = items.lastOrNull { it is ChatItem.Approval && it.status == ChatItem.Approval.Status.PENDING }
      ?.let { (it as ChatItem.Approval).approvalId }
}

/**
 * Pure state machine for the chat: `state + event → state`. Kept free of Android so the
 * protocol rules (deltas vs final, tool chips, approval cards) are unit-tested on the JVM.
 */
object ChatReducer {

  fun userSent(state: ChatState, text: String, key: String): ChatState =
    state.copy(items = state.items + ChatItem.User(key, text), thinking = true, errorText = null, errorKind = null)

  fun connecting(state: ChatState): ChatState =
    state.copy(connection = ChatState.Connection.CONNECTING, errorText = null, errorKind = null)

  fun reduce(state: ChatState, event: ChatEvent, key: String): ChatState = when (event) {
    is ChatEvent.Ready -> state.copy(
      connection = ChatState.Connection.CONNECTED,
      sessionId = event.sessionId ?: state.sessionId,
      errorText = null,
      errorKind = null,
    )
    is ChatEvent.Text -> state.copy(items = state.items.withAssistantText(event, key), thinking = !event.final || state.thinking)
    // The server may repeat a tool_call for the same running tool (seen on the deployed backend):
    // one chip per running tool, not per frame.
    is ChatEvent.ToolCall ->
      if (state.items.any { it is ChatItem.Tool && it.tool == event.name && !it.done }) {
        state.copy(thinking = true)
      } else {
        state.copy(items = state.items + ChatItem.Tool(key, event.name, done = false), thinking = true)
      }
    is ChatEvent.ToolResult -> state.copy(
      items = state.items.map { if (it is ChatItem.Tool && it.tool == event.name && !it.done) it.copy(done = true) else it },
    )
    is ChatEvent.ApprovalRequired ->
      if (state.items.any { it is ChatItem.Approval && it.approvalId == event.approvalId }) {
        state
      } else {
        state.copy(items = state.items + ChatItem.Approval(key, event.approvalId, event.tool, event.summary ?: event.reason, ChatItem.Approval.Status.PENDING))
      }
    is ChatEvent.ApprovalResolved -> state.copy(
      items = state.items.map {
        if (it is ChatItem.Approval && it.approvalId == event.approvalId) {
          it.copy(status = if (event.status == "rejected") ChatItem.Approval.Status.REJECTED else ChatItem.Approval.Status.APPROVED)
        } else {
          it
        }
      },
      // The server runs the next assistant turn by itself.
      thinking = true,
    )
    // A finished turn has no running tools or streaming text left.
    ChatEvent.TurnComplete -> state.copy(
      thinking = false,
      items = state.items.map {
        when {
          it is ChatItem.Assistant && it.streaming -> it.copy(streaming = false)
          it is ChatItem.Tool && !it.done -> it.copy(done = true)
          else -> it
        }
      },
    )
    is ChatEvent.Error -> state.copy(
      thinking = false,
      errorText = event.message,
      errorKind = when {
        event.message != null -> null
        event.sessionProblem -> ChatState.ErrorKind.SESSION
        else -> ChatState.ErrorKind.NETWORK
      },
    )
    is ChatEvent.Disconnected -> state.copy(connection = ChatState.Connection.DISCONNECTED, thinking = false)
  }

  /** Deltas extend the streaming reply; the final frame replaces it (or adds a new reply). */
  private fun List<ChatItem>.withAssistantText(event: ChatEvent.Text, key: String): List<ChatItem> {
    val index = indexOfLast { it is ChatItem.Assistant && it.streaming }
    // A streaming reply is only extended while it is still the latest assistant output.
    val open = index >= 0 && subList(index + 1, size).none { it is ChatItem.User }
    return when {
      open && !event.final -> toMutableList().also { val a = it[index] as ChatItem.Assistant; it[index] = a.copy(text = a.text + event.text) }
      open -> toMutableList().also { it[index] = ChatItem.Assistant((it[index] as ChatItem.Assistant).key, event.text, streaming = false) }
      else -> this + ChatItem.Assistant(key, event.text, streaming = !event.final)
    }
  }
}
