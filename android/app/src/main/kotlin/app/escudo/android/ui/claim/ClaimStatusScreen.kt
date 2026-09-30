package app.escudo.android.ui.claim

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.escudo.android.R
import app.escudo.android.data.EscudoRepository
import app.escudo.android.data.model.Claim
import app.escudo.android.ui.components.LoadState
import app.escudo.android.ui.components.LoadStateContent
import app.escudo.android.ui.components.MessageView
import app.escudo.android.ui.components.formatDateTime
import app.escudo.android.ui.components.formatUsd
import app.escudo.android.ui.components.severityLabel
import app.escudo.android.ui.components.userMessage
import app.escudo.android.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ClaimViewModel(private val repository: EscudoRepository) : ViewModel() {
  /** Ready(null) = the customer has no claims. */
  private val _state = MutableStateFlow<LoadState<Claim?>>(LoadState.Loading)
  val state: StateFlow<LoadState<Claim?>> = _state

  fun refresh(silent: Boolean = false) {
    if (!silent) _state.value = LoadState.Loading
    viewModelScope.launch {
      val result = runCatching { repository.claim() }
      // A background poll that fails keeps showing the last good timeline.
      if (silent && result.isFailure && _state.value is LoadState.Ready) return@launch
      _state.value = result.fold({ LoadState.Ready(it) }, { LoadState.Error(it.userMessage()) })
    }
  }
}

@Composable
fun ClaimStatusScreen(repository: EscudoRepository, onReportAccident: () -> Unit) {
  val vm = viewModel { ClaimViewModel(repository) }
  val state by vm.state.collectAsStateWithLifecycle()

  // The agent moves the claim forward on its own; poll while this screen is visible.
  LaunchedEffect(Unit) {
    vm.refresh()
    while (true) {
      delay(3_000)
      vm.refresh(silent = true)
    }
  }

  LoadStateContent(state, onRetry = { vm.refresh() }) { claim ->
    if (claim == null) {
      MessageView(
        title = stringResource(R.string.claim_empty_title),
        body = stringResource(R.string.claim_empty_body),
        action = { Button(onClick = onReportAccident) { Text(stringResource(R.string.policy_report_accident)) } },
      )
    } else {
      ClaimContent(claim)
    }
  }
}

@Composable
private fun ClaimContent(claim: Claim) {
  Column(
    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.m),
    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
  ) {
    Text(stringResource(R.string.claim_title), style = MaterialTheme.typography.headlineMedium)
    Text(
      stringResource(R.string.claim_number, claim.claimId) + (formatDateTime(claim.createdAt)?.let { " · $it" } ?: ""),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    claim.description?.let {
      Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = Spacing.s))
    }
    val steps = claim.timeline()
    steps.forEachIndexed { index, step ->
      TimelineRow(step, detail = step.detail(claim), isLast = index == steps.lastIndex)
    }
  }
}

@Composable
private fun TimelineStep.title(): String = stringResource(
  when (kind) {
    TimelineStep.Kind.OPEN -> R.string.claim_step_open
    TimelineStep.Kind.ASSESSED -> R.string.claim_step_assessed
    TimelineStep.Kind.TOW -> R.string.claim_step_tow
    TimelineStep.Kind.REPAIR -> R.string.claim_step_repair
    TimelineStep.Kind.RENTAL -> R.string.claim_step_rental
    TimelineStep.Kind.DEDUCTIBLE -> R.string.claim_step_deductible
    TimelineStep.Kind.SIGNED -> R.string.claim_step_signed
  },
)

@Composable
private fun TimelineStep.detail(claim: Claim): String? {
  if (!done) return stringResource(R.string.claim_step_pending)
  return when (kind) {
    TimelineStep.Kind.OPEN -> claim.location?.label
    TimelineStep.Kind.ASSESSED -> claim.assessment?.let { a ->
      val parts = a.parts.joinToString { it.name }
      val cost = if (a.costRangeUsd.size == 2) {
        stringResource(R.string.claim_cost_range, formatUsd(a.costRangeUsd[0]), formatUsd(a.costRangeUsd[1]))
      } else {
        ""
      }
      stringResource(R.string.claim_assessment_detail, severityLabel(a.severity), cost) + if (parts.isNotBlank()) "\n$parts" else ""
    }
    TimelineStep.Kind.TOW -> claim.tow?.let { stringResource(R.string.claim_tow_detail, it.provider ?: "", it.etaMinutes ?: 0) }
    TimelineStep.Kind.REPAIR -> claim.repair?.let {
      stringResource(R.string.claim_repair_detail, it.shopName ?: it.shopId ?: "", formatDateTime(it.slot) ?: "")
    }
    TimelineStep.Kind.RENTAL -> claim.rental?.days?.let { stringResource(R.string.claim_rental_detail, it) }
    TimelineStep.Kind.DEDUCTIBLE -> null
    TimelineStep.Kind.SIGNED -> formatDateTime(claim.signedAt)
  }
}

@Composable
private fun TimelineRow(step: TimelineStep, detail: String?, isLast: Boolean) {
  val colors = MaterialTheme.colorScheme
  Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxHeight()) {
      Box(
        Modifier.size(28.dp).background(if (step.done) colors.primary else colors.surfaceContainerHighest, CircleShape),
        contentAlignment = Alignment.Center,
      ) {
        if (step.done) Icon(Icons.Filled.Check, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(18.dp))
      }
      if (!isLast) {
        Box(Modifier.width(2.dp).weight(1f).background(if (step.done) colors.primary else colors.outlineVariant))
      }
    }
    Column(Modifier.padding(bottom = Spacing.l).weight(1f)) {
      Text(
        step.title(),
        style = MaterialTheme.typography.titleMedium,
        color = if (step.done) colors.onSurface else colors.onSurfaceVariant,
      )
      detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant) }
    }
  }
}
