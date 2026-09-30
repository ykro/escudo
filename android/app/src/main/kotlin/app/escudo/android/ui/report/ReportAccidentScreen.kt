package app.escudo.android.ui.report

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.escudo.android.AppContainer
import app.escudo.android.R
import app.escudo.android.data.model.Location
import app.escudo.android.ui.components.userMessage
import app.escudo.android.ui.theme.Spacing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface ReportState {
  data object Editing : ReportState
  data object Sending : ReportState
  data class Done(val claimId: String) : ReportState
  data class Failed(val message: String) : ReportState
}

class ReportAccidentViewModel(private val container: AppContainer) : ViewModel() {
  private val _state = MutableStateFlow<ReportState>(ReportState.Editing)
  val state: StateFlow<ReportState> = _state

  fun submit(description: String, locationLabel: String, photos: List<Uri>) {
    if (_state.value == ReportState.Sending) return
    _state.value = ReportState.Sending
    viewModelScope.launch {
      _state.value = runCatching {
        val uploads = container.photos.load(photos)
        container.repository.reportAccident(
          description = description,
          photos = uploads,
          location = locationLabel.trim().takeIf { it.isNotEmpty() }?.let { Location(label = it) },
        )
      }.fold({ ReportState.Done(it.claim.claimId) }, { ReportState.Failed(it.userMessage()) })
    }
  }
}

@Composable
fun ReportAccidentScreen(container: AppContainer, onSeeClaim: () -> Unit) {
  val vm = viewModel { ReportAccidentViewModel(container) }
  val state by vm.state.collectAsStateWithLifecycle()
  var description by rememberSaveable { mutableStateOf("") }
  var location by rememberSaveable { mutableStateOf("") }
  var photos by rememberSaveable { mutableStateOf(listOf<Uri>()) }
  // System photo picker: no storage permission needed for what the customer picks.
  val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(maxItems = 5)) { picked ->
    if (picked.isNotEmpty()) photos = picked
  }
  val sending = state == ReportState.Sending

  Column(
    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.m),
    verticalArrangement = Arrangement.spacedBy(Spacing.m),
  ) {
    OutlinedTextField(
      value = description,
      onValueChange = { description = it },
      label = { Text(stringResource(R.string.report_description_label)) },
      minLines = 3,
      enabled = !sending,
      modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
      value = location,
      onValueChange = { location = it },
      label = { Text(stringResource(R.string.report_location_label)) },
      singleLine = true,
      enabled = !sending,
      modifier = Modifier.fillMaxWidth(),
    )
    Text(stringResource(R.string.report_photos), style = MaterialTheme.typography.titleMedium)
    OutlinedButton(
      onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
      enabled = !sending,
      modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.report_add_photos)) }
    if (photos.isNotEmpty()) {
      Text(stringResource(R.string.report_photos_count, photos.size), style = MaterialTheme.typography.bodyMedium)
    }

    Button(
      onClick = { vm.submit(description, location, photos) },
      enabled = description.isNotBlank() && !sending && state !is ReportState.Done,
      modifier = Modifier.fillMaxWidth(),
    ) {
      if (sending) {
        CircularProgressIndicator(Modifier.padding(end = Spacing.s).size(18.dp), strokeWidth = 2.dp)
        Text(stringResource(R.string.report_sending))
      } else {
        Text(stringResource(R.string.report_submit))
      }
    }

    when (val s = state) {
      is ReportState.Done -> Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Row(Modifier.padding(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
          Text(stringResource(R.string.report_done, s.claimId), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
          Button(onClick = onSeeClaim) { Text(stringResource(R.string.report_see_claim)) }
        }
      }
      is ReportState.Failed -> Text(
        stringResource(R.string.report_error, s.message),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
      )
      else -> Unit
    }
  }
}
