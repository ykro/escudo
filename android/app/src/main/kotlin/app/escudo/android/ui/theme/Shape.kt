package app.escudo.android.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val EscudoShapes = Shapes(
  small = RoundedCornerShape(8.dp),
  medium = RoundedCornerShape(12.dp),
  large = RoundedCornerShape(20.dp),
)

/** Spacing scale used by every screen. */
object Spacing {
  val xs = 4.dp
  val s = 8.dp
  val m = 16.dp
  val l = 24.dp
  val xl = 32.dp
}
