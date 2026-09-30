package app.escudo.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

// Light theme only, by design (see AGENTS.md "UI/UX bar"). No dynamic color: the brand stays
// the same on every phone.
private val EscudoLightColors = lightColorScheme(
  primary = EscudoPalette.Primary,
  onPrimary = EscudoPalette.OnPrimary,
  primaryContainer = EscudoPalette.PrimaryContainer,
  onPrimaryContainer = EscudoPalette.OnPrimaryContainer,
  secondary = EscudoPalette.Secondary,
  onSecondary = EscudoPalette.OnSecondary,
  secondaryContainer = EscudoPalette.SecondaryContainer,
  onSecondaryContainer = EscudoPalette.OnSecondaryContainer,
  tertiary = EscudoPalette.Tertiary,
  onTertiary = EscudoPalette.OnTertiary,
  tertiaryContainer = EscudoPalette.TertiaryContainer,
  onTertiaryContainer = EscudoPalette.OnTertiaryContainer,
  error = EscudoPalette.Error,
  onError = EscudoPalette.OnError,
  errorContainer = EscudoPalette.ErrorContainer,
  onErrorContainer = EscudoPalette.OnErrorContainer,
  background = EscudoPalette.Background,
  onBackground = EscudoPalette.OnBackground,
  surface = EscudoPalette.Surface,
  onSurface = EscudoPalette.OnSurface,
  surfaceVariant = EscudoPalette.SurfaceVariant,
  onSurfaceVariant = EscudoPalette.OnSurfaceVariant,
  outline = EscudoPalette.Outline,
  outlineVariant = EscudoPalette.OutlineVariant,
  surfaceContainerLowest = EscudoPalette.SurfaceContainerLowest,
  surfaceContainerLow = EscudoPalette.SurfaceContainerLow,
  surfaceContainer = EscudoPalette.SurfaceContainer,
  surfaceContainerHigh = EscudoPalette.SurfaceContainerHigh,
  surfaceContainerHighest = EscudoPalette.SurfaceContainerHighest,
)

@Composable
fun EscudoTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = EscudoLightColors,
    typography = EscudoTypography,
    shapes = EscudoShapes,
    content = content,
  )
}
