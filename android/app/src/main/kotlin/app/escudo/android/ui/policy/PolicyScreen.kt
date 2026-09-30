package app.escudo.android.ui.policy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import app.escudo.android.data.EscudoRepository
import app.escudo.android.data.model.Policy
import app.escudo.android.ui.components.LoadState
import app.escudo.android.ui.components.LoadStateContent
import app.escudo.android.ui.components.PrimaryButton
import app.escudo.android.ui.components.SectionLabel
import app.escudo.android.ui.components.SurfaceCard
import app.escudo.android.ui.components.TonalCard
import app.escudo.android.ui.components.coverageLabel
import app.escudo.android.ui.components.formatUsd
import app.escudo.android.ui.components.toLoadState
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
    viewModelScope.launch { _state.value = runCatching { repository.policy() }.toLoadState() }
  }
}

@Composable
fun PolicyScreen(repository: EscudoRepository, onReportAccident: () -> Unit) {
  val vm = viewModel { PolicyViewModel(repository) }
  val state by vm.state.collectAsStateWithLifecycle()
  LoadStateContent(state, onRetry = vm::refresh, loadingLabel = R.string.loading_policy) { policy ->
    PolicyContent(policy, onReportAccident)
  }
}

@Composable
private fun PolicyContent(policy: Policy, onReportAccident: () -> Unit) {
  val active = policy.status == "active"
  Column(
    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.m, vertical = Spacing.s),
    verticalArrangement = Arrangement.spacedBy(Spacing.m),
  ) {
    TonalCard {
      StatusPill(stringResource(if (active) R.string.policy_active else R.string.policy_suspended), active)
      Text(policy.holder.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = Spacing.s))
      Text(
        "${policy.vehicle.make} ${policy.vehicle.model} ${policy.vehicle.year}",
        style = MaterialTheme.typography.headlineMedium,
      )
      Text(
        stringResource(R.string.policy_plate_number, policy.vehicle.plate, policy.policyId),
        style = MaterialTheme.typography.bodyMedium,
      )
    }

    SurfaceCard {
      Fact(stringResource(R.string.policy_deductible), stringResource(R.string.amount_usd, formatUsd(policy.deductibleUsd)))
      if ("rental_car" in policy.coverages) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Fact(stringResource(R.string.policy_rental_days), stringResource(R.string.policy_rental_days_value, policy.rentalDays))
      }
    }

    SectionLabel(stringResource(R.string.policy_coverages))
    SurfaceCard {
      policy.coverages.forEach { code ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
          Icon(painterResource(R.drawable.ic_check_circle), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
          Text(coverageLabel(code), style = MaterialTheme.typography.bodyLarge)
        }
      }
    }

    PrimaryButton(
      stringResource(R.string.policy_report_accident),
      onClick = onReportAccident,
      modifier = Modifier.fillMaxWidth().padding(top = Spacing.s, bottom = Spacing.m),
    )
  }
}

@Composable
private fun StatusPill(text: String, active: Boolean) {
  Surface(
    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.errorContainer,
    contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onErrorContainer,
    shape = MaterialTheme.shapes.extraLarge,
  ) {
    Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs))
  }
}

@Composable
private fun Fact(label: String, value: String) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
    Text(value, style = MaterialTheme.typography.titleLarge)
  }
}
