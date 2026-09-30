package app.escudo.android.ui.approvals

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
import app.escudo.android.ui.components.PauseBand
import app.escudo.android.ui.components.PrimaryButton
import app.escudo.android.ui.components.QuietButton
import app.escudo.android.ui.components.formatDateTime
import app.escudo.android.ui.components.formatUsd
import app.escudo.android.ui.components.toLoadState
import app.escudo.android.ui.components.toolLabel
import app.escudo.android.ui.theme.EscudoTheme
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

  /** Approval id currently being decided (progress on its button). */
  private val _busy = MutableStateFlow<String?>(null)
  val busy: StateFlow<String?> = _busy

  private val _outcomes = MutableSharedFlow<ApprovalOutcome>(extraBufferCapacity = 4)
  val outcomes: SharedFlow<ApprovalOutcome> = _outcomes

  fun refresh(silent: Boolean = false) {
    if (!silent) _state.value = LoadState.Loading
    viewModelScope.launch {
      val result = runCatching { container.repository.pendingApprovals() }
      if (silent && result.isFailure && _state.value is LoadState.Ready) return@launch
      _state.value = result.toLoadState()
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
        is ApprovalOutcome.Failed -> outcome.message ?: context.getString(R.string.approval_failed)
      }
      snackbar.showSnackbar(text)
    }
  }

  LoadStateContent(state, onRetry = { vm.refresh() }, loadingLabel = R.string.loading_approvals) { approvals ->
    if (approvals.isEmpty()) {
      MessageView(
        icon = R.drawable.ic_check_circle,
        title = stringResource(R.string.approvals_empty_title),
        body = stringResource(R.string.approvals_empty_body),
      )
    } else {
      val listState = rememberLazyListState()
      LaunchedEffect(highlightId, approvals.size) {
        val index = approvals.indexOfFirst { it.approvalId == highlightId }
        if (index >= 0) listState.animateScrollToItem(index + 1)
      }
      LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(horizontal = Spacing.m, vertical = Spacing.s),
        verticalArrangement = Arrangement.spacedBy(Spacing.l),
      ) {
        item { PauseBand(text = stringResource(R.string.approvals_explainer)) }
        items(approvals, key = { it.approvalId }) { approval ->
          ApprovalItem(
            approval = approval,
            highlighted = approval.approvalId == highlightId,
            busy = busy == approval.approvalId,
            enabled = busy == null,
            onApprove = { vm.approve(approval, verifier) },
            onReject = { vm.reject(approval, verifier) },
          )
        }
      }
    }
  }
}

@Composable
private fun ApprovalItem(
  approval: Approval,
  highlighted: Boolean,
  busy: Boolean,
  enabled: Boolean,
  onApprove: () -> Unit,
  onReject: () -> Unit,
) {
  val extra = EscudoTheme.extra
  Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
    Card(
      Modifier.fillMaxWidth(),
      shape = MaterialTheme.shapes.large,
      colors = CardDefaults.cardColors(containerColor = extra.tonal, contentColor = extra.onTonal),
      border = if (highlighted) BorderStroke(2.dp, extra.pause) else null,
    ) {
      Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
          Icon(painterResource(R.drawable.ic_escudo_mark), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
          Text(
            approval.claimId?.let { stringResource(R.string.claim_number, it) } ?: stringResource(R.string.app_name),
            style = MaterialTheme.typography.bodyLarge,
          )
        }
        Text(toolLabel(approval.tool), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = Spacing.s))
        approval.amountUsd?.let {
          Text(stringResource(R.string.amount_usd, formatUsd(it)), style = MaterialTheme.typography.displayMedium)
        }
        Text(approval.summary ?: approval.reason, style = MaterialTheme.typography.bodyLarge)
        if (approval.summary != null && approval.reason.isNotBlank()) {
          Text(approval.reason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        formatDateTime(approval.createdAt)?.let {
          Text(stringResource(R.string.approval_requested_at, it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
    }
    PrimaryButton(
      stringResource(R.string.approval_approve),
      onClick = onApprove,
      icon = R.drawable.ic_fingerprint,
      loading = busy,
      enabled = enabled,
      modifier = Modifier.fillMaxWidth(),
    )
    QuietButton(stringResource(R.string.approval_reject), onClick = onReject, enabled = enabled, modifier = Modifier.align(Alignment.CenterHorizontally))
  }
}
