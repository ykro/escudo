package app.escudo.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import app.escudo.android.R
import app.escudo.android.ui.theme.Spacing

/** Loading / error / ready, the three states every screen designs for. */
sealed interface LoadState<out T> {
  data object Loading : LoadState<Nothing>
  data class Error(val message: String) : LoadState<Nothing>
  data class Ready<T>(val data: T) : LoadState<T>
}

@Composable
fun <T> LoadStateContent(
  state: LoadState<T>,
  onRetry: () -> Unit,
  modifier: Modifier = Modifier,
  content: @Composable (T) -> Unit,
) {
  when (state) {
    LoadState.Loading -> LoadingView(modifier)
    is LoadState.Error -> ErrorView(state.message, onRetry, modifier)
    is LoadState.Ready -> content(state.data)
  }
}

@Composable
fun LoadingView(modifier: Modifier = Modifier) {
  Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    CircularProgressIndicator()
  }
}

@Composable
fun ErrorView(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
  MessageView(
    title = stringResource(R.string.state_error_title),
    body = message,
    modifier = modifier,
    action = { Button(onClick = onRetry) { Text(stringResource(R.string.state_retry)) } },
  )
}

@Composable
fun MessageView(
  title: String,
  body: String,
  modifier: Modifier = Modifier,
  action: (@Composable () -> Unit)? = null,
) {
  Column(
    modifier.fillMaxSize().padding(Spacing.xl),
    verticalArrangement = Arrangement.spacedBy(Spacing.m, Alignment.CenterVertically),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
    Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    action?.invoke()
  }
}

/** Short, user-safe description of a failure. */
fun Throwable.userMessage(): String = message?.take(160) ?: javaClass.simpleName
