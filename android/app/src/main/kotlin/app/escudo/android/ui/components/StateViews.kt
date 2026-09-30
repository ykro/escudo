package app.escudo.android.ui.components

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.escudo.android.R
import app.escudo.android.ui.theme.Spacing

/** Loading / error / ready: the three states every screen designs for. */
sealed interface LoadState<out T> {
  data object Loading : LoadState<Nothing>
  data class Error(@StringRes val message: Int) : LoadState<Nothing>
  data class Ready<T>(val data: T) : LoadState<T>
}

fun <T> Result<T>.toLoadState(): LoadState<T> = fold({ LoadState.Ready(it) }, { LoadState.Error(it.userMessage()) })

@Composable
fun <T> LoadStateContent(
  state: LoadState<T>,
  onRetry: () -> Unit,
  modifier: Modifier = Modifier,
  @StringRes loadingLabel: Int = R.string.state_loading,
  content: @Composable (T) -> Unit,
) {
  when (state) {
    LoadState.Loading -> LoadingView(stringResource(loadingLabel), modifier)
    is LoadState.Error -> ErrorView(stringResource(state.message), onRetry, modifier)
    is LoadState.Ready -> content(state.data)
  }
}

@Composable
fun LoadingView(label: String, modifier: Modifier = Modifier) {
  Column(
    modifier.fillMaxSize().semantics { liveRegion = LiveRegionMode.Polite },
    verticalArrangement = Arrangement.spacedBy(Spacing.m, Alignment.CenterVertically),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    CircularProgressIndicator(strokeWidth = 3.dp)
    Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
fun ErrorView(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
  MessageView(
    icon = R.drawable.ic_cloud_off,
    title = stringResource(R.string.state_error_title),
    body = message,
    modifier = modifier,
    iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
    action = { PrimaryButton(stringResource(R.string.state_retry), onClick = onRetry) },
  )
}

/** Empty and error states: icon in a soft circle, a title, one sentence, at most one action. */
@Composable
fun MessageView(
  @DrawableRes icon: Int,
  title: String,
  body: String,
  modifier: Modifier = Modifier,
  iconTint: Color = MaterialTheme.colorScheme.primary,
  action: (@Composable () -> Unit)? = null,
) {
  Box(modifier.fillMaxSize().padding(horizontal = Spacing.xl), contentAlignment = Alignment.Center) {
    Column(
      Modifier.widthIn(max = 360.dp),
      verticalArrangement = Arrangement.spacedBy(Spacing.m),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Box(
        Modifier.size(72.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
      ) {
        Icon(painterResource(icon), contentDescription = null, tint = iconTint, modifier = Modifier.size(32.dp))
      }
      Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
      Text(
        body,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
      if (action != null) Box(Modifier.padding(top = Spacing.s)) { action() }
    }
  }
}
