@file:OptIn(ExperimentalTvMaterial3Api::class)

package io.github.superthom196.matv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.superthom196.matv.LetterAnchor

private val alphabet: List<String> = (('A'..'Z').map { it.toString() }) + "#"

/**
 * Edge-anchored control for the Albums grid: Up/Down steps to the previous/next populated letter
 * without leaving the tab, Right hands focus into the grid, OK opens the full A-Z picker.
 */
@Composable
fun LetterTab(
    currentLabel: () -> String,
    onStep: (forward: Boolean) -> Unit,
    onOpenPicker: () -> Unit,
    onEnterGrid: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .fillMaxHeight()
            .width(72.dp)
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (ev.key) {
                    Key.DirectionUp -> { onStep(false); true }
                    Key.DirectionDown -> { onStep(true); true }
                    Key.DirectionRight -> { onEnterGrid(); true }
                    else -> false
                }
            }
            .onFocusChanged { focused = it.isFocused },
    ) {
        FocusSurface(
            onClick = onOpenPicker,
            modifier = Modifier.fillMaxHeight().fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            scale = 1.0f,
        ) {
            Column(Modifier.fillMaxSize().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(currentLabel(), style = MaterialTheme.typography.displaySmall, color = HiFiColors.Accent)
                VSpace(4.dp)
                Text("A–Z", style = MaterialTheme.typography.labelSmall, color = HiFiColors.Muted)
                if (focused) {
                    VSpace(10.dp)
                    Text("▲▼ letter", style = MaterialTheme.typography.labelSmall, color = HiFiColors.Muted)
                    Text("OK for A–Z", style = MaterialTheme.typography.labelSmall, color = HiFiColors.Muted)
                }
            }
        }
    }
}

/** Full A-Z picker: a 9x3 grid, empty letters stay focusable (dimmed) so D-pad focus search never hits a gap. */
@Composable
fun LetterPickerDialog(anchors: List<LetterAnchor>, currentLabel: String, onPick: (label: String) -> Unit, onDismiss: () -> Unit) {
    val populated = remember(anchors) { anchors.map { it.label }.toSet() }
    val current = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { current.requestFocus() } }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .background(HiFiColors.Surface, RoundedCornerShape(20.dp))
                .padding(28.dp),
        ) {
            Column {
                Text("Jump to", style = MaterialTheme.typography.titleMedium, color = HiFiColors.Muted)
                VSpace(16.dp)
                alphabet.chunked(9).forEach { row ->
                    Row {
                        row.forEach { label ->
                            val isCurrent = label == currentLabel
                            val isPopulated = label in populated
                            FocusSurface(
                                onClick = { if (isPopulated) onPick(label) },
                                modifier = Modifier.size(76.dp).padding(4.dp).let { if (isCurrent) it.focusRequester(current) else it },
                                shape = RoundedCornerShape(12.dp),
                                container = if (isCurrent) HiFiColors.SurfaceHigh else HiFiColors.Surface,
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        label,
                                        style = MaterialTheme.typography.titleLarge,
                                        color = if (isPopulated) HiFiColors.Text else HiFiColors.Muted.copy(alpha = 0.4f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
