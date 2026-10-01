package app.escudo.android.ui.report

import android.net.Uri
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.escudo.android.AppContainer
import app.escudo.android.R
import app.escudo.android.ScenarioLog
import app.escudo.android.data.model.AccidentReport
import app.escudo.android.data.model.Location
import app.escudo.android.ui.components.PrimaryButton
import app.escudo.android.ui.components.SecondaryButton
import app.escudo.android.ui.components.SectionLabel
import app.escudo.android.ui.components.TonalCard
import app.escudo.android.ui.components.formatUsd
import app.escudo.android.ui.components.severityLabel
import app.escudo.android.ui.components.userMessage
import app.escudo.android.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ReportState {
  data object Editing : ReportState
  data object Sending : ReportState
  data class Done(val report: AccidentReport) : ReportState
  data class Failed(@StringRes val message: Int) : ReportState
}

class ReportAccidentViewModel(private val container: AppContainer) : ViewModel() {
  private val _state = MutableStateFlow<ReportState>(ReportState.Editing)
  val state: StateFlow<ReportState> = _state

  fun submit(description: String, locationLabel: String, photos: List<Uri>) {
    if (_state.value == ReportState.Sending) return
    _state.value = ReportState.Sending
    viewModelScope.launch {
      _state.value = runCatching {
        container.repository.reportAccident(
          description = description,
          photos = container.photos.load(photos),
          location = locationLabel.trim().takeIf { it.isNotEmpty() }?.let { Location(label = it) },
        )
      }.fold(
        { ScenarioLog.mark("report_done", it.claim.claimId); ReportState.Done(it) },
        { ScenarioLog.mark("report_failed"); ReportState.Failed(it.userMessage()) },
      )
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
  val done = state as? ReportState.Done
  val editable = !sending && done == null
  val scroll = rememberScrollState()
  LaunchedEffect(state) { if (state !is ReportState.Editing) scroll.animateScrollTo(scroll.maxValue) }

  Column(
    Modifier.fillMaxSize().imePadding().verticalScroll(scroll).padding(horizontal = Spacing.m, vertical = Spacing.s),
    verticalArrangement = Arrangement.spacedBy(Spacing.m),
  ) {
    Text(stringResource(R.string.report_intro), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(
      value = description,
      onValueChange = { description = it },
      label = { Text(stringResource(R.string.report_description_label)) },
      minLines = 3,
      enabled = editable,
      keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
      shape = MaterialTheme.shapes.small,
      modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
      value = location,
      onValueChange = { location = it },
      label = { Text(stringResource(R.string.report_location_label)) },
      singleLine = true,
      enabled = editable,
      shape = MaterialTheme.shapes.small,
      modifier = Modifier.fillMaxWidth(),
    )

    SectionLabel(stringResource(R.string.report_photos))
    if (photos.isNotEmpty()) {
      LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        items(photos) { uri -> Thumbnail(uri) }
      }
    }
    SecondaryButton(
      stringResource(if (photos.isEmpty()) R.string.report_add_photos else R.string.report_change_photos),
      onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
      icon = R.drawable.ic_photo,
      enabled = editable,
      modifier = Modifier.fillMaxWidth(),
    )
    Text(stringResource(R.string.report_photos_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    when (val s = state) {
      is ReportState.Done -> ReportDone(s.report, onSeeClaim)
      is ReportState.Failed -> Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(Modifier.padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
          Text(stringResource(R.string.report_error_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
          Text(stringResource(s.message), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
        }
      }
      else -> Unit
    }

    if (done == null) {
      PrimaryButton(
        stringResource(
          when (state) {
            ReportState.Sending -> R.string.report_sending
            is ReportState.Failed -> R.string.state_retry
            else -> R.string.report_submit
          },
        ),
        onClick = { vm.submit(description, location, photos) },
        enabled = description.isNotBlank(),
        loading = sending,
        modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.m),
      )
      if (sending) {
        Text(
          stringResource(R.string.report_sending_detail),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.align(Alignment.CenterHorizontally),
        )
      }
    }
  }
}

@Composable
private fun ReportDone(report: AccidentReport, onSeeClaim: () -> Unit) {
  TonalCard {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
      Icon(painterResource(R.drawable.ic_check_circle), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
      Text(stringResource(R.string.report_done, report.claim.claimId), style = MaterialTheme.typography.titleMedium)
    }
    report.assessment?.let { a ->
      Text(stringResource(R.string.claim_severity, severityLabel(a.severity)), style = MaterialTheme.typography.headlineSmall)
      if (a.costRangeUsd.size == 2) {
        Text(stringResource(R.string.claim_cost_range, formatUsd(a.costRangeUsd[0]), formatUsd(a.costRangeUsd[1])), style = MaterialTheme.typography.bodyLarge)
      }
      a.summary?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
    Text(stringResource(R.string.report_done_next), style = MaterialTheme.typography.bodyMedium)
  }
  PrimaryButton(stringResource(R.string.report_see_claim), onClick = onSeeClaim, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun Thumbnail(uri: Uri) {
  val context = LocalContext.current
  val bitmap by produceState<ImageBitmap?>(null, uri) {
    value = withContext(Dispatchers.IO) {
      runCatching { context.contentResolver.loadThumbnail(uri, Size(320, 320), null).asImageBitmap() }.getOrNull()
    }
  }
  Box(
    Modifier.size(96.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHigh),
    contentAlignment = Alignment.Center,
  ) {
    bitmap?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
      ?: Icon(painterResource(R.drawable.ic_photo), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}
