package app.escudo.android.ui.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import app.escudo.android.assistant.MicrophoneStreamer
import app.escudo.android.assistant.SpeakerPlayer
import app.escudo.android.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatLine(val role: AssistantEvent.Role?, val text: String)

data class AssistantUiState(
  val connection: Connection = Connection.CONNECTING,
  val talking: Boolean = false,
  val lines: List<ChatLine> = emptyList(),
  val pendingApprovalId: String? = null,
  val error: String? = null,
) {
  enum class Connection { CONNECTING, CONNECTED, DISCONNECTED }
}

class AssistantViewModel(private val factory: AssistantSessionFactory) : ViewModel() {
  private val _state = MutableStateFlow(AssistantUiState())
  val state: StateFlow<AssistantUiState> = _state

  private var session: AssistantSession? = null
  private var sessionEvents: Job? = null
  private val mic = MicrophoneStreamer(viewModelScope)
  private var speaker: SpeakerPlayer? = null

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
    _state.update { it.copy(connection = AssistantUiState.Connection.CONNECTING, error = null) }
    val s = factory.create().also { session = it }
    sessionEvents = viewModelScope.launch { s.events.collect(::onEvent) }
    s.connect()
  }

  @SuppressLint("MissingPermission") // checked by the screen before calling
  fun startTalking() {
    val s = session ?: return
    if (_state.value.connection != AssistantUiState.Connection.CONNECTED) return
    speaker?.interrupt() // barge-in: the customer talks over the assistant
    s.startTalking()
    mic.start { s.sendAudio(it) }
    _state.update { it.copy(talking = true) }
  }

  fun stopTalking() {
    if (!_state.value.talking) return
    mic.stop()
    session?.stopTalking()
    _state.update { it.copy(talking = false) }
  }

  fun approvalSeen() = _state.update { it.copy(pendingApprovalId = null) }

  private fun onEvent(event: AssistantEvent) {
    when (event) {
      AssistantEvent.Connected -> {
        if (speaker == null) speaker = SpeakerPlayer()
        _state.update { it.copy(connection = AssistantUiState.Connection.CONNECTED, error = null) }
      }
      is AssistantEvent.Disconnected -> {
        mic.stop()
        _state.update { it.copy(connection = AssistantUiState.Connection.DISCONNECTED, talking = false) }
      }
      is AssistantEvent.Audio -> playback.trySend(event.pcm24k)
      is AssistantEvent.Transcript -> _state.update { it.copy(lines = it.lines.appendTranscript(event)) }
      is AssistantEvent.ToolActivity -> _state.update {
        it.copy(lines = it.lines + ChatLine(null, event.summary ?: event.tool))
      }
      is AssistantEvent.ApprovalRequested -> _state.update { it.copy(pendingApprovalId = event.approvalId) }
      AssistantEvent.Interrupted -> speaker?.interrupt()
      AssistantEvent.TurnComplete -> Unit
      is AssistantEvent.Error -> _state.update { it.copy(error = event.message) }
    }
  }

  /** Streaming transcripts arrive in pieces: extend the last line of the same speaker. */
  private fun List<ChatLine>.appendTranscript(t: AssistantEvent.Transcript): List<ChatLine> {
    val last = lastOrNull()
    return if (last != null && last.role == t.role && !t.final) {
      dropLast(1) + last.copy(text = last.text + t.text)
    } else if (last != null && last.role == t.role && t.final && t.text.startsWith(last.text)) {
      dropLast(1) + ChatLine(t.role, t.text)
    } else {
      this + ChatLine(t.role, t.text)
    }
  }

  override fun onCleared() {
    mic.stop()
    session?.close()
    playback.close()
    speaker?.release()
  }
}

@Composable
fun AssistantScreen(factory: AssistantSessionFactory, onOpenApprovals: (String) -> Unit) {
  val vm = viewModel { AssistantViewModel(factory) }
  val state by vm.state.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
  val hasMic = { ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }
  val listState = rememberLazyListState()

  LaunchedEffect(Unit) { if (!hasMic()) micPermission.launch(Manifest.permission.RECORD_AUDIO) }
  LaunchedEffect(state.lines.size) { if (state.lines.isNotEmpty()) listState.animateScrollToItem(state.lines.lastIndex) }
  DisposableEffect(Unit) { onDispose { vm.stopTalking() } }

  Column(Modifier.fillMaxSize().padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
    Text(stringResource(R.string.assistant_title), style = MaterialTheme.typography.headlineMedium)

    state.pendingApprovalId?.let { id ->
      Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.m)) {
          Text(stringResource(R.string.assistant_approval_needed), style = MaterialTheme.typography.titleMedium)
          TextButton(onClick = { vm.approvalSeen(); onOpenApprovals(id) }) { Text(stringResource(R.string.assistant_review)) }
        }
      }
    }

    LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
      if (state.lines.isEmpty()) {
        item {
          Text(
            stringResource(R.string.assistant_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = Spacing.l),
          )
        }
      }
      items(state.lines) { line -> ChatBubble(line) }
    }

    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
      when (state.connection) {
        AssistantUiState.Connection.DISCONNECTED -> {
          Text(stringResource(R.string.assistant_disconnected), style = MaterialTheme.typography.bodyMedium)
          Button(onClick = vm::connect) { Text(stringResource(R.string.assistant_reconnect)) }
        }
        else -> {
          val connected = state.connection == AssistantUiState.Connection.CONNECTED
          PushToTalkButton(
            enabled = connected,
            talking = state.talking,
            onPress = { if (hasMic()) vm.startTalking() else micPermission.launch(Manifest.permission.RECORD_AUDIO) },
            onRelease = vm::stopTalking,
          )
          Text(
            stringResource(
              when {
                !connected -> R.string.assistant_connecting
                state.talking -> R.string.assistant_listening
                else -> R.string.assistant_hint
              },
            ),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
          )
        }
      }
    }
  }
}

@Composable
private fun PushToTalkButton(enabled: Boolean, talking: Boolean, onPress: () -> Unit, onRelease: () -> Unit) {
  val colors = MaterialTheme.colorScheme
  val label = stringResource(R.string.assistant_talk)
  Box(
    Modifier
      .size(if (talking) 104.dp else 96.dp)
      .background(
        when {
          !enabled -> colors.surfaceContainerHighest
          talking -> colors.tertiary
          else -> colors.primary
        },
        CircleShape,
      )
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
    Icon(painterResource(R.drawable.ic_mic), contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(40.dp))
  }
}

@Composable
private fun ChatBubble(line: ChatLine) {
  val colors = MaterialTheme.colorScheme
  val (container, align) = when (line.role) {
    AssistantEvent.Role.USER -> colors.primaryContainer to Alignment.End
    AssistantEvent.Role.ASSISTANT -> colors.surfaceContainerHigh to Alignment.Start
    null -> colors.surface to Alignment.CenterHorizontally
  }
  Column(Modifier.fillMaxWidth(), horizontalAlignment = align) {
    if (line.role == null) {
      Text(line.text, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
    } else {
      Card(colors = CardDefaults.cardColors(containerColor = container)) {
        Text(line.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(Spacing.m))
      }
    }
  }
}
