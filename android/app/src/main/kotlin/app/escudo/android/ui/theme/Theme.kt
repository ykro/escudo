package app.escudo.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Light theme only, by design (AGENTS.md "UI/UX bar"). No dynamic color: the brand is the same
// on every phone.
private val SerenoColors = lightColorScheme(
  primary = SerenoTokens.Pine,
  onPrimary = SerenoTokens.Mist,
  primaryContainer = SerenoTones.PineContainer,
  onPrimaryContainer = SerenoTones.OnPineContainer,
  inversePrimary = SerenoTones.PineContainer,
  secondary = SerenoTokens.Muted,
  onSecondary = SerenoTokens.Surface,
  secondaryContainer = SerenoTones.SageContainer,
  onSecondaryContainer = SerenoTokens.Ink,
  tertiary = SerenoTones.SandDeep,
  onTertiary = SerenoTokens.Surface,
  tertiaryContainer = SerenoTokens.Sand,
  onTertiaryContainer = SerenoTokens.Ink,
  error = SerenoTones.Error,
  onError = SerenoTokens.Surface,
  errorContainer = SerenoTones.ErrorContainer,
  onErrorContainer = SerenoTones.OnErrorContainer,
  background = SerenoTokens.Mist,
  onBackground = SerenoTokens.Ink,
  surface = SerenoTokens.Surface,
  onSurface = SerenoTokens.Ink,
  surfaceVariant = SerenoTones.SurfaceVariant,
  onSurfaceVariant = SerenoTokens.Muted,
  surfaceTint = SerenoTokens.Pine,
  outline = SerenoTones.Outline,
  outlineVariant = SerenoTones.OutlineVariant,
  surfaceContainerLowest = SerenoTones.ContainerLowest,
  surfaceContainerLow = SerenoTones.ContainerLow,
  surfaceContainer = SerenoTones.Container,
  surfaceContainerHigh = SerenoTones.ContainerHigh,
  surfaceContainerHighest = SerenoTones.ContainerHighest,
)

/** Roles Material 3 does not have. `pause` is the approval amber: use it for nothing else. */
@Immutable
data class EscudoExtraColors(
  val pause: Color = SerenoTokens.Amber,
  val onPauseContainer: Color = SerenoTokens.Amber,
  val pauseContainer: Color = SerenoTokens.AmberBg,
  val tonal: Color = SerenoTokens.Sand,
  val onTonal: Color = SerenoTokens.Ink,
)

val LocalEscudoColors = staticCompositionLocalOf { EscudoExtraColors() }

object EscudoTheme {
  val extra: EscudoExtraColors
    @Composable get() = LocalEscudoColors.current
}

@Composable
fun EscudoTheme(content: @Composable () -> Unit) {
  androidx.compose.runtime.CompositionLocalProvider(LocalEscudoColors provides EscudoExtraColors()) {
    MaterialTheme(
      colorScheme = SerenoColors,
      typography = EscudoTypography,
      shapes = EscudoShapes,
      content = content,
    )
  }
}
