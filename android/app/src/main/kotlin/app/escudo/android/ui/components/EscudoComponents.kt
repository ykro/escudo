package app.escudo.android.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.escudo.android.R
import app.escudo.android.ui.theme.EscudoTheme
import app.escudo.android.ui.theme.Spacing

/** Full-width, 56 dp primary action (mockup: "Aprobar con huella"). */
@Composable
fun PrimaryButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  @DrawableRes icon: Int? = null,
  enabled: Boolean = true,
  loading: Boolean = false,
) {
  Button(
    onClick = onClick,
    enabled = enabled && !loading,
    shape = MaterialTheme.shapes.small,
    // While loading the button stays pine: it is busy, not unavailable.
    colors = if (loading) {
      ButtonDefaults.buttonColors(
        disabledContainerColor = MaterialTheme.colorScheme.primary,
        disabledContentColor = MaterialTheme.colorScheme.onPrimary,
      )
    } else {
      ButtonDefaults.buttonColors()
    },
    modifier = modifier.heightIn(min = 56.dp),
  ) {
    when {
      loading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
      icon != null -> Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp))
    }
    if (loading || icon != null) Spacer(Modifier.width(Spacing.sm))
    Text(text, style = MaterialTheme.typography.labelLarge)
  }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null, enabled: Boolean = true) {
  OutlinedButton(onClick = onClick, enabled = enabled, shape = MaterialTheme.shapes.small, modifier = modifier.heightIn(min = 56.dp)) {
    if (icon != null) {
      Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp))
      Spacer(Modifier.width(Spacing.s))
    }
    Text(text, style = MaterialTheme.typography.labelLarge)
  }
}

@Composable
fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
  TextButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp)) {
    Text(text, style = MaterialTheme.typography.labelLarge)
  }
}

/** Sand tonal card: the one place per screen that holds the main fact. */
@Composable
fun TonalCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
  Card(
    modifier = modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.large,
    colors = CardDefaults.cardColors(containerColor = EscudoTheme.extra.tonal, contentColor = EscudoTheme.extra.onTonal),
  ) {
    Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s), content = content)
  }
}

/** Plain surface card for secondary information. */
@Composable
fun SurfaceCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
  Card(
    modifier = modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.medium,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    border = CardDefaults.outlinedCardBorder(),
  ) {
    Column(Modifier.padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.sm), content = content)
  }
}

/**
 * The amber "Esperando tu aprobación" band. Amber means one thing in Escudo: the agent stopped
 * and a person has to decide.
 */
@Composable
fun PauseBand(modifier: Modifier = Modifier, text: String = stringResource(R.string.pause_waiting), action: (@Composable () -> Unit)? = null) {
  val extra = EscudoTheme.extra
  Surface(color = extra.pauseContainer, contentColor = extra.onPauseContainer, shape = MaterialTheme.shapes.medium, modifier = modifier.fillMaxWidth()) {
    Row(
      Modifier.padding(horizontal = Spacing.m, vertical = Spacing.sm),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
      Icon(painterResource(R.drawable.ic_hourglass), contentDescription = null, modifier = Modifier.size(20.dp))
      Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
      action?.invoke()
    }
  }
}

/** Screen section label. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
  Text(
    text,
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = modifier.padding(top = Spacing.s),
  )
}
