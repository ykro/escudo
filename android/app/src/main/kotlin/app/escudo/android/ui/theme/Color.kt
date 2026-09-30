package app.escudo.android.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * "A · Sereno" tokens (design/a-sereno/tokens.json). After a crash the app should lower the
 * pulse: pine, sand and air. Amber is reserved for one thing only, a human approval pause, so
 * the audience sees at a glance where the person decides.
 */
object SerenoTokens {
  val Mist = Color(0xFFF3F6F4) // background
  val Surface = Color(0xFFFCFDFC)
  val Ink = Color(0xFF14201C) // text, 15.4:1 on mist
  val Muted = Color(0xFF4E5D57) // secondary text, 6.38:1 on mist
  val Pine = Color(0xFF0F5C4D) // primary / Material 3 seed
  val Sand = Color(0xFFE9DFC8) // tonal container, ink on sand 12.65:1
  val Amber = Color(0xFF8A5200) // approval pause only, 5.72:1 on AmberBg
  val AmberBg = Color(0xFFFFF1D6)
}

/** Tonal roles derived from the pine seed for the Material 3 scheme. */
internal object SerenoTones {
  val PineContainer = Color(0xFFCCE8DE)
  val OnPineContainer = Color(0xFF00201A)
  val SageContainer = Color(0xFFDCE8E2) // navigation indicator, selected chips
  val SandDeep = Color(0xFF6B5B3A)
  val Outline = Color(0xFF6F7C76)
  val OutlineVariant = Color(0xFFC5CEC9)
  val SurfaceVariant = Color(0xFFE1E8E4)
  val ContainerLowest = Color(0xFFFFFFFF)
  val ContainerLow = Color(0xFFF7F9F8)
  val Container = Color(0xFFEEF2F0)
  val ContainerHigh = Color(0xFFE8EDEA)
  val ContainerHighest = Color(0xFFE2E8E5)
  val Error = Color(0xFFB3261E)
  val ErrorContainer = Color(0xFFF9DEDC)
  val OnErrorContainer = Color(0xFF410E0B)
}
