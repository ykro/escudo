package app.escudo.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.escudo.android.R

// Onest (SIL OFL 1.1), bundled as a variable font so the stage phone never waits for a download.
private fun onest(weight: Int) = Font(
  R.font.onest,
  weight = FontWeight(weight),
  variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Onest = FontFamily(onest(400), onest(500), onest(600), onest(700))

val EscudoTypography: Typography = Typography().run {
  copy(
    displayLarge = displayLarge.copy(fontFamily = Onest),
    displayMedium = displayMedium.copy(fontFamily = Onest, fontWeight = FontWeight.Medium, letterSpacing = (-0.5).sp),
    displaySmall = displaySmall.copy(fontFamily = Onest, fontWeight = FontWeight.Medium),
    headlineLarge = headlineLarge.copy(fontFamily = Onest, fontWeight = FontWeight.SemiBold),
    headlineMedium = headlineMedium.copy(fontFamily = Onest, fontWeight = FontWeight.SemiBold),
    headlineSmall = headlineSmall.copy(fontFamily = Onest, fontWeight = FontWeight.Medium),
    titleLarge = titleLarge.copy(fontFamily = Onest, fontWeight = FontWeight.Medium),
    titleMedium = titleMedium.copy(fontFamily = Onest, fontWeight = FontWeight.SemiBold),
    titleSmall = titleSmall.copy(fontFamily = Onest, fontWeight = FontWeight.SemiBold),
    bodyLarge = bodyLarge.copy(fontFamily = Onest),
    bodyMedium = bodyMedium.copy(fontFamily = Onest),
    bodySmall = bodySmall.copy(fontFamily = Onest),
    labelLarge = labelLarge.copy(fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    labelMedium = labelMedium.copy(fontFamily = Onest, fontWeight = FontWeight.Medium),
    labelSmall = labelSmall.copy(fontFamily = Onest, fontWeight = FontWeight.Medium),
  )
}
