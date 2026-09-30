package app.escudo.android.ui.claim

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.escudo.android.R
import app.escudo.android.appfunctions.AppFunctionMapping
import app.escudo.android.data.EscudoRepository
import app.escudo.android.data.model.Claim
import app.escudo.android.ui.components.LoadState
import app.escudo.android.ui.components.LoadStateContent
import app.escudo.android.ui.components.MessageView
import app.escudo.android.ui.components.PauseBand
import app.escudo.android.ui.components.PrimaryButton
import app.escudo.android.ui.components.QuietButton
import app.escudo.android.ui.components.SectionLabel
import app.escudo.android.ui.components.SurfaceCard
import app.escudo.android.ui.components.TonalCard
import app.escudo.android.ui.components.formatDateTime
import app.escudo.android.ui.components.formatUsd
import app.escudo.android.ui.components.severityLabel
import app.escudo.android.ui.components.toLoadState
import app.escudo.android.ui.theme.EscudoTheme
import app.escudo.android.ui.theme.Spacing
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** The latest claim (null = none yet) and the approval-gated tools the agent is waiting on. */
data class ClaimScreenData(val claim: Claim?, val pendingTools: Set<String>)

class ClaimViewModel(private val repository: EscudoRepository) : ViewModel() {
  private val _state = MutableStateFlow<LoadState<ClaimScreenData>>(LoadState.Loading)
  val state: StateFlow<LoadState<ClaimScreenData>> = _state

  fun refresh(silent: Boolean = false) {
    if (!silent) _state.value = LoadState.Loading
    viewModelScope.launch {
      val result = runCatching {
        coroutineScope {
          val claim = async { repository.claim() }
          val approvals = async { runCatching { repository.pendingApprovals() }.getOrDefault(emptyList()) }
          val c = claim.await()
          ClaimScreenData(c, approvals.await().filter { it.claimId == null || it.claimId == c?.claimId }.map { it.tool }.toSet())
        }
      }
      // A background poll that fails keeps showing the last good timeline.
      if (silent && result.isFailure && _state.value is LoadState.Ready) return@launch
      _state.value = result.toLoadState()
    }
  }
}

@Composable
fun ClaimStatusScreen(repository: EscudoRepository, onReportAccident: () -> Unit, onOpenApprovals: () -> Unit) {
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

  LoadStateContent(state, onRetry = { vm.refresh() }, loadingLabel = R.string.loading_claim) { data ->
    val claim = data.claim
    if (claim == null) {
      MessageView(
        icon = R.drawable.ic_tab_claim,
        title = stringResource(R.string.claim_empty_title),
        body = stringResource(R.string.claim_empty_body),
        action = { PrimaryButton(stringResource(R.string.policy_report_accident), onClick = onReportAccident) },
      )
    } else {
      ClaimContent(claim, data.pendingTools, onOpenApprovals)
    }
  }
}

@Composable
private fun ClaimContent(claim: Claim, pendingTools: Set<String>, onOpenApprovals: () -> Unit) {
  Column(
    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.m, vertical = Spacing.s),
    verticalArrangement = Arrangement.spacedBy(Spacing.m),
  ) {
    if (pendingTools.isNotEmpty()) {
      PauseBand(action = { QuietButton(stringResource(R.string.pause_review), onClick = onOpenApprovals) })
    }

    TonalCard {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Icon(painterResource(R.drawable.ic_escudo_mark), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Text(stringResource(R.string.claim_number, claim.claimId), style = MaterialTheme.typography.titleSmall)
      }
      Text(
        displayStatus(claim),
        style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier.padding(top = Spacing.xs),
      )
      claim.description?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
      formatDateTime(claim.occurredAt ?: claim.createdAt)?.let {
        Text(
          listOfNotNull(it, claim.location?.label).joinToString(" · "),
          style = MaterialTheme.typography.bodyMedium,
        )
      }
    }

    SectionLabel(stringResource(R.string.claim_progress))
    SurfaceCard {
      val steps = claim.timeline(pendingTools)
      Column {
        steps.forEachIndexed { index, step ->
          TimelineRow(step, detail = step.detail(claim), isLast = index == steps.lastIndex)
        }
      }
    }
  }
}

/** App labels for known statuses (consistent casing and tone); the server's label otherwise. */
private fun displayStatus(claim: Claim): String {
  val local = AppFunctionMapping.statusLabel(claim.status)
  return if (local != claim.status) local else (claim.statusLabel ?: claim.status).replaceFirstChar { it.uppercase() }
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
  if (waitingForApproval) return stringResource(R.string.pause_waiting)
  if (!done) return stringResource(R.string.claim_step_pending)
  return when (kind) {
    TimelineStep.Kind.OPEN -> formatDateTime(claim.createdAt)
    TimelineStep.Kind.ASSESSED -> claim.assessment?.let { a ->
      val cost = if (a.costRangeUsd.size == 2) {
        stringResource(R.string.claim_cost_range, formatUsd(a.costRangeUsd[0]), formatUsd(a.costRangeUsd[1]))
      } else {
        null
      }
      val headline = listOfNotNull(stringResource(R.string.claim_severity, severityLabel(a.severity)), cost).joinToString(" · ")
      listOfNotNull(headline, a.summary ?: a.parts.joinToString { it.name }.ifBlank { null }).joinToString("\n")
    }
    TimelineStep.Kind.TOW -> claim.tow?.let { stringResource(R.string.claim_tow_detail, it.provider ?: "", it.etaMinutes ?: 0) }
    TimelineStep.Kind.REPAIR -> claim.repair?.let {
      listOfNotNull(it.shopName ?: it.shopId, formatDateTime(it.slot), it.quoteUsd?.let { q -> stringResource(R.string.amount_usd, formatUsd(q)) })
        .joinToString(" · ")
    }
    TimelineStep.Kind.RENTAL -> claim.rental?.days?.let { stringResource(R.string.claim_rental_detail, it) }
    TimelineStep.Kind.DEDUCTIBLE -> null
    TimelineStep.Kind.SIGNED -> formatDateTime(claim.signedAt)
  }
}

@Composable
private fun TimelineRow(step: TimelineStep, detail: String?, isLast: Boolean) {
  val colors = MaterialTheme.colorScheme
  val extra = EscudoTheme.extra
  val nodeColor by animateColorAsState(
    when {
      step.done -> colors.primary
      step.waitingForApproval -> extra.pauseContainer
      else -> colors.surfaceContainerHighest
    },
    label = "node",
  )
  Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxHeight()) {
      Box(Modifier.size(28.dp).background(nodeColor, CircleShape), contentAlignment = Alignment.Center) {
        when {
          step.done -> Icon(Icons.Filled.Check, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(18.dp))
          step.waitingForApproval -> Icon(painterResource(R.drawable.ic_hourglass), contentDescription = null, tint = extra.pause, modifier = Modifier.size(16.dp))
        }
      }
      if (!isLast) Box(Modifier.width(2.dp).weight(1f).background(if (step.done) colors.primary else colors.outlineVariant))
    }
    Column(Modifier.padding(bottom = if (isLast) 0.dp else Spacing.l).weight(1f)) {
      Text(
        step.title(),
        style = MaterialTheme.typography.titleMedium,
        color = if (step.done || step.waitingForApproval) colors.onSurface else colors.onSurfaceVariant,
      )
      detail?.let {
        Text(
          it,
          style = MaterialTheme.typography.bodyMedium,
          color = if (step.waitingForApproval) extra.pause else colors.onSurfaceVariant,
        )
      }
    }
  }
}
