@file:OptIn(ExperimentalTvMaterial3Api::class)

package io.github.superthom196.matv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import io.github.superthom196.matv.LetterAnchor

private val alphabet: List<String> = (('A'..'Z').map { it.toString() }) + "#"

/**
 * Narrow A–Z column on the left edge of the Albums grid. Up/Down moves through the letters that
 * have albums (the grid scrolls to follow); Right or OK enters the grid at that letter.
 * Letters with no albums are shown dimmed and skipped by focus.
 */
@Composable
fun AlphabetRail(
    anchors: List<LetterAnchor>,
    currentLabel: String,
    onLetterFocused: (label: String) -> Unit,
    onEnterGrid: (label: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val populated = remember(anchors) { anchors.map { it.label }.toSet() }
    BoxWithConstraints(modifier.fillMaxHeight().width(36.dp)) {
        val rowHeight = (maxHeight / alphabet.size).coerceIn(12.dp, 24.dp)
        Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.Top) {
            alphabet.forEach { label ->
                val isPopulated = label in populated
                var focused by remember { mutableStateOf(false) }
                val isCurrent = label == currentLabel
                Box(
                    Modifier
                        .height(rowHeight)
                        .width(36.dp)
                        .then(
                            if (isPopulated) Modifier
                                .onFocusChanged { f -> focused = f.isFocused; if (f.isFocused) onLetterFocused(label) }
                                .onKeyEvent { ev ->
                                    if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                                    when (ev.key) {
                                        Key.DirectionRight, Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { onEnterGrid(label); true }
                                        else -> false
                                    }
                                }
                                .focusable()
                            else Modifier,
                        )
                        .background(if (focused) HiFiColors.Accent else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        fontSize = if (focused) 15.sp else 12.sp,
                        lineHeight = 14.sp,
                        fontWeight = if (focused || isCurrent) FontWeight.Bold else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        color = when {
                            focused -> HiFiColors.OnAccent
                            !isPopulated -> HiFiColors.Muted.copy(alpha = 0.3f)
                            isCurrent -> HiFiColors.Accent
                            else -> HiFiColors.Muted
                        },
                        modifier = Modifier.padding(0.dp),
                    )
                }
            }
        }
    }
}
