package app.escudo.android.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// Radius tokens: sm 8, md 16, lg 28, pill.
val EscudoShapes = Shapes(
  extraSmall = RoundedCornerShape(8.dp),
  small = RoundedCornerShape(8.dp),
  medium = RoundedCornerShape(16.dp),
  large = RoundedCornerShape(28.dp),
  extraLarge = RoundedCornerShape(28.dp),
)

/** Spacing tokens: 4 px base; 4, 8, 12, 16, 24, 32, 48, 72. */
object Spacing {
  val xs = 4.dp
  val s = 8.dp
  val sm = 12.dp
  val m = 16.dp
  val l = 24.dp
  val xl = 32.dp
  val xxl = 48.dp
  val xxxl = 72.dp
}
