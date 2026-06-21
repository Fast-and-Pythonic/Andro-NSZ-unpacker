package com.androNSZ.ui.components

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * An HSV color wheel: hue around the circle, saturation along the radius, with a
 * separate brightness slider. Emits an opaque ARGB [Int] on every change.
 *
 * HSV is kept as the source of truth (not re-derived from [color] on each change)
 * so dragging brightness toward black doesn't lose the chosen hue. External color
 * changes (preset swatch, hex field) are resynced via [LaunchedEffect].
 */
@Composable
fun ColorWheelPicker(
   color: Int,
   onColorChange: (Int) -> Unit,
   modifier: Modifier = Modifier
) {
   val initial = remember { FloatArray(3).also { AndroidColor.colorToHSV(color, it) } }
   var hue by remember { mutableFloatStateOf(initial[0]) }
   var sat by remember { mutableFloatStateOf(initial[1]) }
   var value by remember { mutableFloatStateOf(initial[2]) }

   LaunchedEffect(color) {
      val current = AndroidColor.HSVToColor(floatArrayOf(hue, sat, value))
      if (current != color) {
         val out = FloatArray(3)
         AndroidColor.colorToHSV(color, out)
         hue = out[0]; sat = out[1]; value = out[2]
      }
   }

   fun emit() = onColorChange(AndroidColor.HSVToColor(floatArrayOf(hue, sat, value)))

   Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Box(
         modifier = Modifier
            .size(220.dp)
            .align(Alignment.CenterHorizontally)
            .pointerInput(Unit) {
               detectTapGestures { pos ->
                  pickFromTouch(pos, size.width, size.height) { h, s -> hue = h; sat = s; emit() }
               }
            }
            .pointerInput(Unit) {
               detectDragGestures { change, _ ->
                  pickFromTouch(change.position, size.width, size.height) { h, s -> hue = h; sat = s; emit() }
               }
            }
      ) {
         Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = min(size.width, size.height) / 2f
            val center = Offset(size.width / 2f, size.height / 2f)

            // Hue ring (sweep) + saturation (white centre fading out).
            val hueColors = (0..360 step 60).map { Color.hsv((it % 360).toFloat(), 1f, 1f) }
            drawCircle(brush = Brush.sweepGradient(hueColors, center), radius = radius, center = center)
            drawCircle(
               brush = Brush.radialGradient(
                  listOf(Color.White, Color.Transparent),
                  center = center,
                  radius = radius
               ),
               radius = radius,
               center = center
            )
            // Dim toward black to reflect the current brightness.
            drawCircle(color = Color.Black.copy(alpha = 1f - value), radius = radius, center = center)

            // Selector dot at (hue angle, saturation radius).
            val angleRad = Math.toRadians(hue.toDouble())
            val sx = center.x + (cos(angleRad) * sat * radius).toFloat()
            val sy = center.y + (sin(angleRad) * sat * radius).toFloat()
            drawCircle(color = Color.White, radius = 8.dp.toPx(), center = Offset(sx, sy))
            drawCircle(
               color = Color.Black,
               radius = 8.dp.toPx(),
               center = Offset(sx, sy),
               style = Stroke(width = 2.dp.toPx())
            )
         }
      }

      Slider(
         value = value,
         onValueChange = { value = it; emit() },
         valueRange = 0f..1f
      )
   }
}

/** Map a touch position to hue (angle, degrees) and saturation (radius, 0..1). */
private inline fun pickFromTouch(
   pos: Offset,
   width: Int,
   height: Int,
   onResult: (hue: Float, sat: Float) -> Unit
) {
   val radius = min(width, height) / 2f
   val dx = pos.x - width / 2f
   val dy = pos.y - height / 2f
   var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
   if (angle < 0f) angle += 360f
   val sat = (hypot(dx, dy) / radius).coerceIn(0f, 1f)
   onResult(angle, sat)
}
