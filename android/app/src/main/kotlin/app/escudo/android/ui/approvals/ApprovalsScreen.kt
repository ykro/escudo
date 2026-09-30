package app.escudo.android.ui.approvals

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.escudo.android.AppContainer
import app.escudo.android.R
import app.escudo.android.approvals.ApprovalFlow
import app.escudo.android.approvals.ApprovalOutcome
import app.escudo.android.approvals.BiometricVerifier
import app.escudo.android.approvals.UserVerifier
import app.escudo.android.data.model.Approval
import app.escudo.android.ui.components.LoadState
import app.escudo.android.ui.components.LoadStateContent
import app.escudo.android.ui.components.MessageView
import app.escudo.android.ui.components.formatDateTime
import app.escudo.android.ui.components.formatUsd
import app.escudo.android.ui.components.toolLabel
import app.escudo.android.ui.components.userMessage
import app.escudo.android.ui.theme.EscudoPalette
import app.escudo.android.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ApprovalsViewModel(private val container: AppContainer) : ViewModel() {
  private val _state = MutableStateFlow<LoadState<List<Approval>>>(LoadState.Loading)
  val state: StateFlow<LoadState<List<Approval>>> = _state

  /** Approval id currently being decided (spinner on its card). */
  private val _busy = MutableStateFlow<String?>(null)
  val busy: StateFlow<String?> = _busy

  private val _outcomes = MutableSharedFlow<ApprovalOutcome>(extraBufferCapacity = 4)
  val outcomes: SharedFlow<ApprovalOutcome> = _outcomes

  fun refresh(silent: Boolean = false) {
    if (!silent) _state.value = LoadState.Loading
    viewModelScope.launch {
      val result = runCatching { container.repository.pendingApprovals() }
      if (silent && result.isFailure && _state.value is LoadState.Ready) return@launch
      _state.value = result.fold({ LoadState.Ready(it) }, { LoadState.Error(it.userMessage()) })
    }
  }

  // The verifier is passed per call: BiometricPrompt needs the current activity, which the
  // ViewModel must not hold on to.
  fun approve(approval: Approval, verifier: UserVerifier) = decide(approval, verifier) { it.approve(approval) }

  fun reject(approval: Approval, verifier: UserVerifier) = decide(approval, verifier) { it.reject(approval) }

  private fun decide(approval: Approval, verifier: UserVerifier, action: suspend (ApprovalFlow) -> ApprovalOutcome) {
    if (_busy.value != null) return
    _busy.value = approval.approvalId
    viewModelScope.launch {
      val flow = ApprovalFlow(container.repository, verifier, { container.deviceId }, container.events)
      _outcomes.emit(action(flow))
      _busy.value = null
      refresh(silent = true)
    }
  }
}

@Composable
fun ApprovalsScreen(container: AppContainer, highlightId: String?, snackbar: SnackbarHostState) {
  val vm = viewModel { ApprovalsViewModel(container) }
  val state by vm.state.collectAsStateWithLifecycle()
  val busy by vm.busy.collectAsStateWithLifecycle()
  val activity = LocalActivity.current as FragmentActivity
  val verifier = remember(activity) { BiometricVerifier(activity) }
  val context = LocalContext.current

  LaunchedEffect(Unit) {
    vm.refresh()
    while (true) {
      delay(3_000)
      vm.refresh(silent = true)
    }
  }
  LaunchedEffect(Unit) {
    vm.outcomes.collect { outcome ->
      val text = when (outcome) {
        ApprovalOutcome.Approved -> context.getString(R.string.approval_done)
        ApprovalOutcome.Rejected -> context.getString(R.string.approval_rejected)
        ApprovalOutcome.Cancelled -> context.getString(R.string.approval_cancelled)
        ApprovalOutcome.AlreadyResolved -> context.getString(R.string.approval_already_resolved)
        is ApprovalOutcome.Failed -> context.getString(R.string.approval_failed, outcome.message)
      }
      snackbar.showSnackbar(text)
    }
  }

  LoadStateContent(state, onRetry = { vm.refresh() }) { approvals ->
    if (approvals.isEmpty()) {
      MessageView(stringResource(R.string.approvals_empty_title), stringResource(R.string.approvals_empty_body))
    } else {
      LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.m),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
      ) {
        item {
          Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(stringResource(R.string.approvals_title), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.approvals_explainer), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
        items(approvals, key = { it.approvalId }) { approval ->
          ApprovalCard(
            approval = approval,
            highlighted = approval.approvalId == highlightId,
            busy = busy == approval.approvalId,
            onApprove = { vm.approve(approval, verifier) },
            onReject = { vm.reject(approval, verifier) },
          )
        }
      }
    }
  }
}

@Composable
private fun ApprovalCard(approval: Approval, highlighted: Boolean, busy: Boolean, onApprove: () -> Unit, onReject: () -> Unit) {
  Card(
    Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = EscudoPalette.AttentionContainer),
    border = if (highlighted) BorderStroke(2.dp, EscudoPalette.Attention) else null,
  ) {
    Column(Modifier.padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
      Text(toolLabel(approval.tool), style = MaterialTheme.typography.titleLarge)
      Text(approval.summary ?: approval.reason, style = MaterialTheme.typography.bodyLarge)
      if (approval.summary != null && approval.reason.isNotBlank()) {
        Text(approval.reason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      approval.amountUsd?.let {
        Text(stringResource(R.string.approval_amount, formatUsd(it)), style = MaterialTheme.typography.titleMedium, color = EscudoPalette.Attention)
      }
      formatDateTime(approval.createdAt)?.let {
        Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = onApprove, enabled = !busy, modifier = Modifier.weight(1f)) {
          if (busy) CircularProgressIndicator(Modifier.padding(end = Spacing.s).size(18.dp), strokeWidth = 2.dp)
          Text(stringResource(R.string.approval_approve))
        }
        OutlinedButton(onClick = onReject, enabled = !busy) { Text(stringResource(R.string.approval_reject)) }
      }
    }
  }
}
