package app.escudo.android.ui.policy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.escudo.android.R
import app.escudo.android.data.EscudoRepository
import app.escudo.android.data.model.Policy
import app.escudo.android.ui.components.LoadState
import app.escudo.android.ui.components.LoadStateContent
import app.escudo.android.ui.components.coverageLabel
import app.escudo.android.ui.components.formatUsd
import app.escudo.android.ui.components.userMessage
import app.escudo.android.ui.theme.Spacing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class PolicyViewModel(private val repository: EscudoRepository) : ViewModel() {
  private val _state = MutableStateFlow<LoadState<Policy>>(LoadState.Loading)
  val state: StateFlow<LoadState<Policy>> = _state

  init { refresh() }

  fun refresh() {
    _state.value = LoadState.Loading
    viewModelScope.launch {
      _state.value = runCatching { repository.policy() }
        .fold({ LoadState.Ready(it) }, { LoadState.Error(it.userMessage()) })
    }
  }
}

@Composable
fun PolicyScreen(repository: EscudoRepository, onReportAccident: () -> Unit) {
  val vm = viewModel { PolicyViewModel(repository) }
  val state by vm.state.collectAsStateWithLifecycle()
  LoadStateContent(state, onRetry = vm::refresh) { policy -> PolicyContent(policy, onReportAccident) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PolicyContent(policy: Policy, onReportAccident: () -> Unit) {
  Column(
    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.m),
    verticalArrangement = Arrangement.spacedBy(Spacing.m),
  ) {
    Text(stringResource(R.string.policy_title), style = MaterialTheme.typography.headlineMedium)

    Card(
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
      modifier = Modifier.fillMaxWidth(),
    ) {
      Column(Modifier.padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
          Text(policy.holder.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
          Text(
            stringResource(if (policy.status == "active") R.string.policy_active else R.string.policy_suspended),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
          )
        }
        Text(stringResource(R.string.policy_number, policy.policyId), style = MaterialTheme.typography.bodyMedium)
      }
    }

    Card(Modifier.fillMaxWidth()) {
      Column(Modifier.padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        LabeledValue(
          stringResource(R.string.policy_vehicle),
          "${policy.vehicle.make} ${policy.vehicle.model} ${policy.vehicle.year}",
          stringResource(R.string.policy_plate, policy.vehicle.plate),
        )
        HorizontalDivider()
        LabeledValue(stringResource(R.string.policy_deductible), "USD ${formatUsd(policy.deductibleUsd)}")
        if ("rental_car" in policy.coverages) {
          HorizontalDivider()
          LabeledValue(stringResource(R.string.policy_rental_days), stringResource(R.string.policy_rental_days_value, policy.rentalDays))
        }
      }
    }

    Text(stringResource(R.string.policy_coverages), style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
      policy.coverages.forEach { AssistChip(onClick = {}, label = { Text(coverageLabel(it)) }) }
    }

    Button(onClick = onReportAccident, modifier = Modifier.fillMaxWidth()) {
      Text(stringResource(R.string.policy_report_accident))
    }
  }
}

@Composable
private fun LabeledValue(label: String, value: String, detail: String? = null) {
  Column {
    Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(value, style = MaterialTheme.typography.titleMedium)
    detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
  }
}
