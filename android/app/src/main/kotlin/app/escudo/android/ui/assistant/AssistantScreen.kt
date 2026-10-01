package app.escudo.android.ui.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.escudo.android.R
import app.escudo.android.assistant.AssistantEvent
import app.escudo.android.assistant.AssistantSession
import app.escudo.android.assistant.AssistantSessionFactory
import app.escudo.android.assistant.FileSource
import app.escudo.android.assistant.MicrophoneSource
import app.escudo.android.assistant.VoiceRequest
import app.escudo.android.assistant.VoiceSource
import app.escudo.android.assistant.SpeakerPlayer
import app.escudo.android.ui.components.LoadingView
import app.escudo.android.ui.components.MessageView
import app.escudo.android.ui.components.PauseBand
import app.escudo.android.ui.components.PrimaryButton
import app.escudo.android.ui.components.QuietButton
import app.escudo.android.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A conversation line. [role] null = an activity note ("Pidiendo la grúa"). */
data class ChatLine(val role: AssistantEvent.Role?, val text: String, val partial: Boolean = false, val tool: String? = null)

data class AssistantUiState(
  val connection: Connection = Connection.CONNECTING,
  val talking: Boolean = false,
  /** Microphone level 0..1 while talking, drives the ring around the button. */
  val micLevel: Float = 0f,
  val lines: List<ChatLine> = emptyList(),
  val pendingApprovalId: String? = null,
  /** Server-provided (Spanish) error, if any. */
  val errorText: String? = null,
  @StringRes val errorRes: Int? = null,
) {
  enum class Connection { CONNECTING, CONNECTED, DISCONNECTED }
}

/** Streaming transcripts: deltas extend the open line; `final` replaces it (tools.md → Voice WebSocket). */
fun List<ChatLine>.withTranscript(t: AssistantEvent.Transcript): List<ChatLine> {
  val last = lastOrNull()
  val open = last != null && last.role == t.role && last.partial
  return when {
    !t.final && open -> dropLast(1) + last!!.copy(text = last.text + t.text)
    !t.final -> this + ChatLine(t.role, t.text, partial = true)
    open -> dropLast(1) + ChatLine(t.role, t.text)
    else -> this + ChatLine(t.role, t.text)
  }
}

class AssistantViewModel(private val factory: AssistantSessionFactory) : ViewModel() {
  private val _state = MutableStateFlow(AssistantUiState())
  val state: StateFlow<AssistantUiState> = _state

  private var session: AssistantSession? = null
  private var sessionEvents: Job? = null
  private val mic = MicrophoneSource(viewModelScope)
  private var activeSource: VoiceSource? = null
  private var speaker: SpeakerPlayer? = null

  /** A recorded voice waiting for the session to be ready (debug rehearsals and tests). */
  private var pendingVoice: VoiceRequest? = null

  // Audio is written on its own thread so a slow speaker never stalls the socket.
  private val playback = Channel<ByteArray>(Channel.UNLIMITED)

  init {
    viewModelScope.launch(Dispatchers.IO) {
      for (chunk in playback) speaker?.write(chunk)
    }
    connect()
  }

  fun connect() {
    sessionEvents?.cancel()
    session?.close()
    _state.update { it.copy(connection = AssistantUiState.Connection.CONNECTING, errorText = null, errorRes = null) }
    val s = factory.create().also { session = it }
    sessionEvents = viewModelScope.launch { s.events.collect(::onEvent) }
    s.connect()
  }

  /** Push-to-talk with the microphone (production). */
  fun startTalking() = talk(mic)

  /**
   * Makes the assistant "hear" a recorded file through the same pipeline as the microphone.
   * Waits for the session if it is still connecting.
   */
  fun playVoice(request: VoiceRequest) {
    when (_state.value.connection) {
      AssistantUiState.Connection.CONNECTED -> talk(FileSource(viewModelScope, request.label, request.open))
      AssistantUiState.Connection.CONNECTING -> pendingVoice = request
      AssistantUiState.Connection.DISCONNECTED -> { pendingVoice = request; connect() }
    }
  }

  @SuppressLint("MissingPermission") // the screen checks RECORD_AUDIO before using the microphone
  private fun talk(source: VoiceSource) {
    val s = session ?: return
    if (_state.value.connection != AssistantUiState.Connection.CONNECTED || activeSource != null) return
    speaker?.interrupt() // barge-in: the customer talks over the assistant
    activeSource = source
    s.startTalking()
    _state.update { it.copy(talking = true) }
    source.start(
      onLevel = { level -> _state.update { it.copy(micLevel = level) } },
      onChunk = s::sendAudio,
      onEnd = ::stopTalking, // a file ends by itself; the microphone ends when the button is released
    )
  }

  fun stopTalking() {
    val source = activeSource ?: return
    activeSource = null
    source.stop()
    session?.stopTalking()
    _state.update { it.copy(talking = false, micLevel = 0f) }
  }

  fun sendText(text: String) {
    val message = text.trim()
    if (message.isEmpty() || _state.value.connection != AssistantUiState.Connection.CONNECTED) return
    speaker?.interrupt()
    // Typed text has no audio transcription, so the phone shows it itself.
    _state.update { it.copy(lines = it.lines + ChatLine(AssistantEvent.Role.USER, message)) }
    session?.sendText(message)
  }

  fun approvalSeen() = _state.update { it.copy(pendingApprovalId = null) }

  private fun onEvent(event: AssistantEvent) {
    when (event) {
      AssistantEvent.Connected -> {
        if (speaker == null) speaker = SpeakerPlayer()
        _state.update { it.copy(connection = AssistantUiState.Connection.CONNECTED, errorText = null, errorRes = null) }
        pendingVoice?.let { pendingVoice = null; playVoice(it) }
      }
      is AssistantEvent.Disconnected -> {
        activeSource?.stop()
        activeSource = null
        _state.update { it.copy(connection = AssistantUiState.Connection.DISCONNECTED, talking = false) }
      }
      is AssistantEvent.Audio -> playback.trySend(event.pcm24k)
      is AssistantEvent.Transcript -> _state.update {
        // Some servers echo typed text as a user transcript; it is already on screen.
        val echo = event.role == AssistantEvent.Role.USER && event.final && it.lines.lastOrNull { l -> l.role == AssistantEvent.Role.USER }?.text == event.text
        if (echo) it else it.copy(lines = it.lines.withTranscript(event))
      }
      is AssistantEvent.ToolActivity -> _state.update { it.copy(lines = it.lines + ChatLine(null, event.summary ?: "", tool = event.tool)) }
      is AssistantEvent.ApprovalRequested -> _state.update { it.copy(pendingApprovalId = event.approvalId) }
      AssistantEvent.Interrupted -> speaker?.interrupt()
      AssistantEvent.TurnComplete -> _state.update { s -> s.copy(lines = s.lines.map { if (it.partial) it.copy(partial = false) else it }) }
      is AssistantEvent.Error -> _state.update {
        it.copy(
          errorText = event.message,
          errorRes = when {
            event.message != null -> null
            event.sessionProblem -> R.string.error_session
            else -> R.string.error_network
          },
        )
      }
    }
  }

  override fun onCleared() {
    activeSource?.stop()
    session?.close()
    playback.close()
    speaker?.release()
  }
}

/**
 * @param pendingApprovals server-side count of pending approvals: the pause band shows only while
 * something is actually waiting, even if it was approved from the notification.
 */
@Composable
fun AssistantScreen(
  factory: AssistantSessionFactory,
  pendingApprovals: Int,
  onOpenApprovals: (String?) -> Unit,
  voiceRequest: VoiceRequest? = null,
  onVoiceConsumed: () -> Unit = {},
) {
  val vm = viewModel { AssistantViewModel(factory) }
  val state by vm.state.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
  val hasMic = { ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }
  val listState = rememberLazyListState()

  LaunchedEffect(Unit) { if (!hasMic()) micPermission.launch(Manifest.permission.RECORD_AUDIO) }
  LaunchedEffect(pendingApprovals) { if (pendingApprovals == 0) vm.approvalSeen() }
  // Debug builds: a recorded voice (intent extra or hidden picker) goes through the same pipeline.
  LaunchedEffect(voiceRequest) {
    voiceRequest?.let { vm.playVoice(it); onVoiceConsumed() }
  }
  LaunchedEffect(state.lines.size, state.lines.lastOrNull()?.text) {
    if (state.lines.isNotEmpty()) listState.animateScrollToItem(state.lines.lastIndex)
  }
  DisposableEffect(Unit) { onDispose { vm.stopTalking() } }

  val errorMessage = state.errorText ?: state.errorRes?.let { stringResource(it) }
  var typing by rememberSaveable { mutableStateOf(false) }
  var draft by rememberSaveable { mutableStateOf("") }

  // imePadding: the typed-message field stays above the keyboard.
  Column(Modifier.fillMaxSize().imePadding()) {
    Box(Modifier.weight(1f).fillMaxWidth()) {
      when {
        state.connection == AssistantUiState.Connection.DISCONNECTED && state.lines.isEmpty() -> MessageView(
          icon = R.drawable.ic_cloud_off,
          title = stringResource(R.string.assistant_unavailable_title),
          body = errorMessage ?: stringResource(R.string.assistant_unavailable_body),
          iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
          action = { PrimaryButton(stringResource(R.string.assistant_reconnect), onClick = vm::connect) },
        )
        state.lines.isEmpty() && state.connection == AssistantUiState.Connection.CONNECTING ->
          LoadingView(stringResource(R.string.assistant_connecting))
        state.lines.isEmpty() -> MessageView(
          icon = R.drawable.ic_tab_assistant,
          title = stringResource(R.string.assistant_empty_title),
          body = stringResource(R.string.assistant_empty_body),
        )
        else -> LazyColumn(
          Modifier.fillMaxSize(),
          state = listState,
          contentPadding = PaddingValues(horizontal = Spacing.m, vertical = Spacing.s),
          verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
          itemsIndexed(state.lines) { _, line -> ChatBubble(line) }
        }
      }
    }

    // Lost the session mid-conversation: keep what was said on screen, offer to reconnect.
    if (state.connection == AssistantUiState.Connection.DISCONNECTED && state.lines.isNotEmpty()) {
      Column(
        Modifier.fillMaxWidth().padding(Spacing.m),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
      ) {
        Text(
          errorMessage ?: stringResource(R.string.assistant_unavailable_body),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
        )
        PrimaryButton(stringResource(R.string.assistant_reconnect), onClick = vm::connect, modifier = Modifier.fillMaxWidth())
      }
    }
    if (state.connection != AssistantUiState.Connection.DISCONNECTED) {
      Column(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.m).padding(bottom = Spacing.m),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
      ) {
        if (pendingApprovals > 0) {
          PauseBand(
            text = stringResource(R.string.assistant_approval_needed),
            action = { QuietButton(stringResource(R.string.pause_review), onClick = { onOpenApprovals(state.pendingApprovalId) }) },
          )
        }
        errorMessage?.let {
          Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
        val connected = state.connection == AssistantUiState.Connection.CONNECTED
        if (typing) {
          MessageField(
            value = draft,
            onValueChange = { draft = it },
            enabled = connected,
            onSend = { vm.sendText(draft); draft = "" },
            onClose = { typing = false },
          )
        } else {
          Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            PushToTalkButton(
              enabled = connected,
              talking = state.talking,
              level = state.micLevel,
              onPress = { if (hasMic()) vm.startTalking() else micPermission.launch(Manifest.permission.RECORD_AUDIO) },
              onRelease = vm::stopTalking,
            )
            IconButton(
              onClick = { typing = true },
              enabled = connected,
              modifier = Modifier.align(Alignment.CenterStart).padding(start = Spacing.l),
            ) {
              Icon(painterResource(R.drawable.ic_keyboard), contentDescription = stringResource(R.string.assistant_type), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
          }
        }
        if (!typing) Text(
          stringResource(
            when {
              !connected -> R.string.assistant_connecting
              state.talking -> R.string.assistant_listening
              else -> R.string.assistant_hint
            },
          ),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun MessageField(value: String, onValueChange: (String) -> Unit, enabled: Boolean, onSend: () -> Unit, onClose: () -> Unit) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
    IconButton(onClick = onClose) {
      Icon(painterResource(R.drawable.ic_mic), contentDescription = stringResource(R.string.assistant_back_to_voice), tint = MaterialTheme.colorScheme.primary)
    }
    OutlinedTextField(
      value = value,
      onValueChange = onValueChange,
      placeholder = { Text(stringResource(R.string.assistant_type_placeholder)) },
      enabled = enabled,
      singleLine = true,
      shape = MaterialTheme.shapes.extraLarge,
      keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
      keyboardActions = KeyboardActions(onSend = { if (value.isNotBlank()) onSend() }),
      modifier = Modifier.weight(1f),
    )
    FilledIconButton(onClick = onSend, enabled = enabled && value.isNotBlank(), modifier = Modifier.size(52.dp)) {
      Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.assistant_send))
    }
  }
}

@Composable
private fun PushToTalkButton(enabled: Boolean, talking: Boolean, level: Float, onPress: () -> Unit, onRelease: () -> Unit) {
  val colors = MaterialTheme.colorScheme
  val label = stringResource(R.string.assistant_talk)
  // The ring follows the voice: the customer sees the phone is hearing them.
  val ring by animateFloatAsState(if (talking) 1.08f + (level * 4f).coerceAtMost(0.42f) else 1f, tween(90), label = "ring")
  Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
    if (talking) {
      Box(Modifier.size(88.dp).scale(ring).background(colors.primary.copy(alpha = 0.22f), CircleShape))
    }
    Box(
      Modifier
        .size(88.dp)
        .background(if (enabled) colors.primary else colors.surfaceContainerHighest, CircleShape)
        .semantics { role = Role.Button; contentDescription = label }
        .pointerInput(enabled) {
          if (!enabled) return@pointerInput
          awaitEachGesture {
            awaitFirstDown()
            onPress()
            waitForUpOrCancellation()
            onRelease()
          }
        },
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        painterResource(R.drawable.ic_mic),
        contentDescription = null,
        tint = if (enabled) colors.onPrimary else colors.onSurfaceVariant,
        modifier = Modifier.size(36.dp),
      )
    }
  }
}

@Composable
private fun ChatBubble(line: ChatLine) {
  val colors = MaterialTheme.colorScheme
  when (line.role) {
    null -> Row(
      Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
      horizontalArrangement = Arrangement.Center,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(Modifier.size(6.dp).background(colors.primary, CircleShape))
      Text(
        line.tool?.let { toolActivity(it) } ?: line.text,
        style = MaterialTheme.typography.labelMedium,
        color = colors.onSurfaceVariant,
        modifier = Modifier.padding(start = Spacing.s),
      )
    }
    else -> {
      val user = line.role == AssistantEvent.Role.USER
      Box(Modifier.fillMaxWidth(), contentAlignment = if (user) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
          color = if (user) colors.primary else colors.surface,
          contentColor = if (user) colors.onPrimary else colors.onSurface,
          shape = RoundedCornerShape(20.dp, 20.dp, if (user) 4.dp else 20.dp, if (user) 20.dp else 4.dp),
          border = if (user) null else androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
          modifier = Modifier.widthIn(max = 300.dp).alpha(if (line.partial) 0.8f else 1f),
        ) {
          Text(line.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.sm))
        }
      }
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
