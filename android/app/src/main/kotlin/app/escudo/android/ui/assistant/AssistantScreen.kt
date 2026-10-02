package app.escudo.android.ui.assistant

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.escudo.android.R
import app.escudo.android.ScenarioLog
import app.escudo.android.assistant.ChatEvent
import app.escudo.android.assistant.ChatSession
import app.escudo.android.assistant.ChatSessionFactory
import app.escudo.android.ui.components.LoadingView
import app.escudo.android.ui.components.MessageView
import app.escudo.android.ui.components.PrimaryButton
import app.escudo.android.ui.components.QuietButton
import app.escudo.android.ui.components.toolLabel
import app.escudo.android.ui.theme.EscudoTheme
import app.escudo.android.ui.theme.Spacing
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AssistantViewModel(private val factory: ChatSessionFactory) : ViewModel() {
  private val _state = MutableStateFlow(ChatState())
  val state: StateFlow<ChatState> = _state

  private var session: ChatSession? = null
  private var sessionEvents: Job? = null
  private val keys = AtomicLong()

  /** A message typed before the session was ready (or sent by a debug intent): sent on `ready`. */
  private var pendingMessage: String? = null

  // "Done for now" marker for scripted rehearsals: turn complete and nothing new for a moment
  // (after an approval the server starts a new turn by itself).
  private var idleJob: Job? = null

  // Cloud Run ends WebSockets after its request timeout (60 min); the transcript lives
  // server-side, so a dropped chat reconnects quietly with the same session id.
  private var reconnectJob: Job? = null
  private var reconnectAttempts = 0

  init {
    connect()
  }

  fun connect() {
    reconnectJob?.cancel()
    sessionEvents?.cancel()
    session?.close()
    _state.update(ChatReducer::connecting)
    val s = factory.create().also { session = it }
    sessionEvents = viewModelScope.launch { s.events.collect(::onEvent) }
    s.connect(_state.value.sessionId) // continue the same conversation after a drop
  }

  fun send(text: String) {
    val message = text.trim()
    if (message.isEmpty()) return
    when (_state.value.connection) {
      ChatState.Connection.CONNECTED -> {
        if (_state.value.thinking) return
        _state.update { ChatReducer.userSent(it, message, nextKey()) }
        session?.send(message)
        ScenarioLog.mark("chat_sent")
      }
      ChatState.Connection.CONNECTING -> pendingMessage = message
      ChatState.Connection.DISCONNECTED -> { pendingMessage = message; connect() }
    }
  }

  private fun onEvent(event: ChatEvent) {
    _state.update { ChatReducer.reduce(it, event, nextKey()) }
    when (event) {
      is ChatEvent.Ready -> {
        reconnectAttempts = 0
        ScenarioLog.mark("connected")
        pendingMessage?.let { pendingMessage = null; send(it) }
      }
      is ChatEvent.ToolCall -> ScenarioLog.mark("tool_call", event.name)
      is ChatEvent.ApprovalRequired -> ScenarioLog.mark("approval_required", event.approvalId)
      is ChatEvent.ApprovalResolved -> ScenarioLog.mark("approval_resolved", "${event.approvalId} ${event.status}")
      ChatEvent.TurnComplete -> ScenarioLog.mark("turn_complete")
      is ChatEvent.Disconnected -> {
        ScenarioLog.mark("disconnected", event.reason.orEmpty())
        if (reconnectAttempts < MAX_RECONNECTS) {
          reconnectAttempts++
          reconnectJob?.cancel()
          reconnectJob = viewModelScope.launch { delay(RECONNECT_DELAY_MS * reconnectAttempts); connect() }
        }
      }
      else -> Unit
    }
    idleJob?.cancel()
    idleJob = viewModelScope.launch {
      delay(IDLE_QUIET_MS)
      if (!_state.value.thinking && _state.value.connection == ChatState.Connection.CONNECTED) ScenarioLog.mark("assistant_idle")
    }
  }

  private fun nextKey() = "c${keys.incrementAndGet()}"

  override fun onCleared() {
    session?.close()
  }

  private companion object {
    const val IDLE_QUIET_MS = 1_000L
    const val MAX_RECONNECTS = 3
    const val RECONNECT_DELAY_MS = 1_500L
  }
}

@Composable
fun AssistantScreen(
  factory: ChatSessionFactory,
  onOpenApprovals: (String?) -> Unit,
  debugMessage: String? = null,
  onDebugMessageConsumed: () -> Unit = {},
) {
  val vm = viewModel { AssistantViewModel(factory) }
  val state by vm.state.collectAsStateWithLifecycle()
  var draft by rememberSaveable { mutableStateOf("") }
  val listState = rememberLazyListState()

  // Debug builds: a message from the scenario script (intent extra) is sent as if typed.
  LaunchedEffect(debugMessage) { debugMessage?.let { vm.send(it); onDebugMessageConsumed() } }
  LaunchedEffect(state.items.size, (state.items.lastOrNull() as? ChatItem.Assistant)?.text?.length, state.thinking) {
    val count = state.items.size + if (state.thinking) 1 else 0
    if (count > 0) listState.animateScrollToItem(count - 1)
  }

  Column(Modifier.fillMaxSize().imePadding()) {
    Box(Modifier.weight(1f).fillMaxWidth()) {
      when {
        state.items.isEmpty() && state.connection == ChatState.Connection.CONNECTING ->
          LoadingView(stringResource(R.string.chat_connecting))
        state.items.isEmpty() && state.connection == ChatState.Connection.DISCONNECTED -> MessageView(
          icon = R.drawable.ic_cloud_off,
          title = stringResource(R.string.chat_unavailable_title),
          body = errorText(state) ?: stringResource(R.string.chat_unavailable_body),
          iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
          action = { PrimaryButton(stringResource(R.string.chat_reconnect), onClick = vm::connect) },
        )
        state.items.isEmpty() -> EmptyChat(onSuggestion = vm::send)
        else -> LazyColumn(
          Modifier.fillMaxSize(),
          state = listState,
          contentPadding = PaddingValues(horizontal = Spacing.m, vertical = Spacing.s),
          verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
          items(state.items, key = { it.key }) { item ->
            when (item) {
              is ChatItem.User -> Bubble(item.text, fromUser = true)
              is ChatItem.Assistant -> Bubble(item.text, fromUser = false, streaming = item.streaming)
              is ChatItem.Tool -> ToolChip(item)
              is ChatItem.Approval -> ApprovalCard(item, onReview = { onOpenApprovals(item.approvalId) })
            }
          }
          if (state.thinking && (state.items.lastOrNull() as? ChatItem.Assistant)?.streaming != true) {
            item(key = "typing") { TypingIndicator() }
          }
        }
      }
    }

    if (state.items.isNotEmpty() || state.connection == ChatState.Connection.CONNECTED) {
      errorText(state)?.let {
        Text(
          it,
          color = MaterialTheme.colorScheme.error,
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.padding(horizontal = Spacing.m),
        )
      }
      if (state.connection == ChatState.Connection.DISCONNECTED) {
        Row(
          Modifier.fillMaxWidth().padding(horizontal = Spacing.m, vertical = Spacing.s),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(stringResource(R.string.chat_disconnected), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
          QuietButton(stringResource(R.string.chat_reconnect), onClick = vm::connect)
        }
      }
      Composer(
        value = draft,
        onValueChange = { draft = it },
        enabled = state.connection == ChatState.Connection.CONNECTED && !state.thinking,
        onSend = { vm.send(draft); draft = "" },
      )
    }
  }
}

@Composable
private fun errorText(state: ChatState): String? = state.errorText ?: when (state.errorKind) {
  ChatState.ErrorKind.NETWORK -> stringResource(R.string.error_network)
  ChatState.ErrorKind.SESSION -> stringResource(R.string.error_session)
  null -> null
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyChat(onSuggestion: (String) -> Unit) {
  val suggestions = listOf(
    stringResource(R.string.chat_suggestion_crash),
    stringResource(R.string.chat_suggestion_coverage),
    stringResource(R.string.chat_suggestion_claim),
  )
  Box(Modifier.fillMaxSize().padding(horizontal = Spacing.xl), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
      Box(Modifier.size(72.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_escudo_mark), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp))
      }
      Text(stringResource(R.string.chat_empty_title), style = MaterialTheme.typography.titleLarge)
      Text(
        stringResource(R.string.chat_empty_body),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
      )
      FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s, Alignment.CenterHorizontally)) {
        suggestions.forEach { text ->
          SuggestionChip(onClick = { onSuggestion(text) }, label = { Text(text) }, shape = MaterialTheme.shapes.extraLarge)
        }
      }
    }
  }
}

@Composable
private fun Bubble(text: String, fromUser: Boolean, streaming: Boolean = false) {
  val colors = MaterialTheme.colorScheme
  Box(Modifier.fillMaxWidth(), contentAlignment = if (fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
    Surface(
      color = if (fromUser) colors.primary else colors.surface,
      contentColor = if (fromUser) colors.onPrimary else colors.onSurface,
      shape = RoundedCornerShape(20.dp, 20.dp, if (fromUser) 4.dp else 20.dp, if (fromUser) 20.dp else 4.dp),
      border = if (fromUser) null else BorderStroke(1.dp, colors.outlineVariant),
      modifier = Modifier.widthIn(max = 300.dp),
    ) {
      Text(
        if (streaming) "$text▍" else text,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.sm),
      )
    }
  }
}

@Composable
private fun ToolChip(item: ChatItem.Tool) {
  val colors = MaterialTheme.colorScheme
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
    Surface(color = colors.secondaryContainer, contentColor = colors.onSecondaryContainer, shape = MaterialTheme.shapes.extraLarge) {
      Row(
        Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs + 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
      ) {
        if (item.done) {
          Icon(painterResource(R.drawable.ic_check_circle), contentDescription = null, tint = colors.primary, modifier = Modifier.size(16.dp))
        } else {
          CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        }
        Text(
          toolActivity(item.tool) + if (item.done) "" else "…",
          style = MaterialTheme.typography.labelMedium,
        )
      }
    }
  }
}

@Composable
private fun ApprovalCard(item: ChatItem.Approval, onReview: () -> Unit) {
  val extra = EscudoTheme.extra
  val pending = item.status == ChatItem.Approval.Status.PENDING
  Surface(
    color = if (pending) extra.pauseContainer else MaterialTheme.colorScheme.surface,
    shape = MaterialTheme.shapes.large,
    border = if (pending) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(Modifier.padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Icon(
          painterResource(if (pending) R.drawable.ic_hourglass else R.drawable.ic_check_circle),
          contentDescription = null,
          tint = if (pending) extra.pause else MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(20.dp),
        )
        Text(
          stringResource(
            when (item.status) {
              ChatItem.Approval.Status.PENDING -> R.string.pause_waiting
              ChatItem.Approval.Status.APPROVED -> R.string.chat_approval_approved
              ChatItem.Approval.Status.REJECTED -> R.string.chat_approval_rejected
            },
          ),
          style = MaterialTheme.typography.titleSmall,
          color = if (pending) extra.pause else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Text(toolLabel(item.tool), style = MaterialTheme.typography.titleLarge)
      item.summary?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
      if (pending) {
        PrimaryButton(
          stringResource(R.string.chat_review_approval),
          onClick = onReview,
          icon = R.drawable.ic_fingerprint,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    }
  }
}

@Composable
private fun TypingIndicator() {
  val transition = rememberInfiniteTransition(label = "typing")
  Row(Modifier.fillMaxWidth()) {
    Surface(
      color = MaterialTheme.colorScheme.surface,
      shape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp),
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
      Row(Modifier.padding(horizontal = Spacing.m, vertical = Spacing.m), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(3) { i ->
          val alpha by transition.animateFloat(
            initialValue = 0.25f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(600, delayMillis = i * 150), RepeatMode.Reverse),
            label = "dot$i",
          )
          Box(Modifier.size(8.dp).alpha(alpha).background(MaterialTheme.colorScheme.primary, CircleShape))
        }
      }
    }
  }
}

@Composable
private fun Composer(value: String, onValueChange: (String) -> Unit, enabled: Boolean, onSend: () -> Unit) {
  Row(
    Modifier.fillMaxWidth().padding(horizontal = Spacing.m, vertical = Spacing.s),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
  ) {
    OutlinedTextField(
      value = value,
      onValueChange = onValueChange,
      placeholder = { Text(stringResource(R.string.chat_placeholder)) },
      maxLines = 4,
      shape = MaterialTheme.shapes.extraLarge,
      keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
      keyboardActions = KeyboardActions(onSend = { if (enabled && value.isNotBlank()) onSend() }),
      modifier = Modifier.weight(1f),
    )
    FilledIconButton(onClick = onSend, enabled = enabled && value.isNotBlank(), modifier = Modifier.size(52.dp)) {
      Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.chat_send))
    }
  }
}

/** What the assistant is doing, in the customer's words. */
@Composable
private fun toolActivity(tool: String): String = when (tool) {
  "get_policy" -> stringResource(R.string.activity_get_policy)
  "check_coverage" -> stringResource(R.string.activity_check_coverage)
  "open_claim" -> stringResource(R.string.activity_open_claim)
  "attach_claim_photos" -> stringResource(R.string.activity_attach_claim_photos)
  "request_tow" -> stringResource(R.string.activity_request_tow)
  "list_repair_options" -> stringResource(R.string.activity_list_repair_options)
  "request_rental_car" -> stringResource(R.string.activity_request_rental_car)
  "accept_deductible" -> stringResource(R.string.activity_accept_deductible)
  "select_repair_shop" -> stringResource(R.string.activity_select_repair_shop)
  "sign_claim" -> stringResource(R.string.activity_sign_claim)
  "get_claim_status" -> stringResource(R.string.activity_get_claim_status)
  else -> tool
}
