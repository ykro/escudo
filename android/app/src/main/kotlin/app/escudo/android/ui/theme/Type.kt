package app.escudo.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/** Swap [BrandFont] for the brand typeface later (e.g. a downloadable Google Font). */
val BrandFont: FontFamily = FontFamily.Default

val EscudoTypography: Typography = Typography().run {
  copy(
    headlineMedium = headlineMedium.copy(fontFamily = BrandFont, fontWeight = FontWeight.SemiBold),
    headlineSmall = headlineSmall.copy(fontFamily = BrandFont, fontWeight = FontWeight.SemiBold),
    titleLarge = titleLarge.copy(fontFamily = BrandFont, fontWeight = FontWeight.SemiBold),
    titleMedium = titleMedium.copy(fontFamily = BrandFont, fontWeight = FontWeight.Medium),
    bodyLarge = bodyLarge.copy(fontFamily = BrandFont),
    bodyMedium = bodyMedium.copy(fontFamily = BrandFont),
    labelLarge = labelLarge.copy(fontFamily = BrandFont, fontWeight = FontWeight.Medium),
  )
}
