package io.github.superthom196.matv.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.superthom196.matv.AppViewModel

private const val START_ANGLE = 135f
private const val SWEEP = 270f

/**
 * The volume readout that pops up on any change: an arc showing where in the 0-100 range the hi-fi
 * sits, with the number in the middle. Purely informational, never focusable, so it cannot swallow
 * a key press while it is on screen.
 */
@Composable
fun VolumeDial(hud: AppViewModel.VolumeHud, modifier: Modifier = Modifier) {
    val target = if (hud.muted) 0f else hud.level / 100f
    val frac by animateFloatAsState(targetValue = target, label = "volume")

    Column(
        modifier
            .background(Color(0xE6141414), RoundedCornerShape(24.dp))
            .padding(horizontal = 26.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(148.dp)) {
                val stroke = 14.dp.toPx()
                val inset = stroke / 2f
                val arc = Size(size.width - stroke, size.height - stroke)
                drawArc(
                    color = Color(0x33FFFFFF), startAngle = START_ANGLE, sweepAngle = SWEEP, useCenter = false,
                    topLeft = Offset(inset, inset), size = arc, style = Stroke(stroke, cap = StrokeCap.Round),
                )
                if (frac > 0f) {
                    drawArc(
                        color = if (hud.muted) HiFiColors.Muted else HiFiColors.Accent,
                        startAngle = START_ANGLE, sweepAngle = SWEEP * frac, useCenter = false,
                        topLeft = Offset(inset, inset), size = arc, style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (hud.muted) "—" else "${hud.level}",
                    style = MaterialTheme.typography.displaySmall,
                    color = if (hud.muted) HiFiColors.Muted else HiFiColors.Text,
                )
                Text(
                    if (hud.muted) "MUTED" else "VOLUME",
                    style = MaterialTheme.typography.labelSmall,
                    color = HiFiColors.Muted,
                )
            }
        }
    }
}
