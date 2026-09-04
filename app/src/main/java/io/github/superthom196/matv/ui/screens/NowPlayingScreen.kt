package io.github.superthom196.matv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import io.github.superthom196.matv.AppViewModel
import io.github.superthom196.matv.UiState
import io.github.superthom196.matv.ui.Artwork
import io.github.superthom196.matv.ui.HSpace
import io.github.superthom196.matv.ui.HiFiColors
import io.github.superthom196.matv.ui.MainScreen
import io.github.superthom196.matv.ui.Nav
import io.github.superthom196.matv.ui.PillButton
import io.github.superthom196.matv.ui.RoundIconButton
import io.github.superthom196.matv.ui.VSpace
import io.github.superthom196.matv.ui.formatTime

/** The screen that makes the hi-fi feel like part of the room. */
@Composable
fun NowPlayingScreen(vm: AppViewModel, ui: UiState, nav: Nav) {
    val np = ui.nowPlaying
    val player = ui.selectedPlayer
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }

    Box(Modifier.fillMaxSize().background(HiFiColors.Background)) {
        // Ambient backdrop: the artwork, huge and dimmed (blur is a no-op below API 31, so use scrims).
        if (np.imageUrl != null) {
            AsyncImage(model = np.imageUrl, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().alpha(0.22f))
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xDD0B0B0F), Color(0x660B0B0F), Color(0xEE0B0B0F)))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x000B0B0F), Color(0xCC0B0B0F)))))

        Row(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(np.imageUrl, Modifier.size(440.dp), corner = 18.dp)
            HSpace(56.dp)
            Column(Modifier.fillMaxHeight().weight(1f), verticalArrangement = Arrangement.Center) {
                // Player chip / state
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    PillButton(player?.name ?: "Choose player", onClick = { nav.push(MainScreen.Players) })
                    HSpace(14.dp)
                    Text(
                        when (np.state) { "playing" -> "Playing"; "paused" -> "Paused"; else -> "Idle" },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (np.isPlaying) HiFiColors.Good else HiFiColors.Muted,
                    )
                    HSpace(14.dp)
                    Text(
                        if (np.nextTitle != null) "Next: ${np.nextTitle}" else "",
                        style = MaterialTheme.typography.labelMedium, color = HiFiColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    HSpace(10.dp)
                    RoundIconButton(
                        if (player?.volumeMuted == true) Icons.Default.VolumeOff else Icons.Default.VolumeUp, "Mute",
                        onClick = { vm.toggleMute() }, size = 52.dp,
                    )
                    HSpace(8.dp)
                    RoundIconButton(Icons.Default.PowerSettingsNew, "Power", onClick = { vm.togglePower() }, size = 52.dp)
                }
                VSpace(16.dp)
                if (np.hasMedia) {
                    Text(np.title, style = MaterialTheme.typography.headlineLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    VSpace(6.dp)
                    Text(np.artist, style = MaterialTheme.typography.headlineSmall, color = HiFiColors.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (np.album.isNotBlank()) Text(np.album, style = MaterialTheme.typography.bodyLarge, color = HiFiColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else {
                    Text("Nothing playing", style = MaterialTheme.typography.displaySmall, color = HiFiColors.Muted)
                    Text("Pick something from the library.", style = MaterialTheme.typography.bodyLarge, color = HiFiColors.Muted)
                }
                VSpace(18.dp)
                // Progress
                val dur = np.duration ?: 0.0
                val frac = if (dur > 0) (np.elapsed / dur).coerceIn(0.0, 1.0).toFloat() else 0f
                Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0x33FFFFFF))) {
                    Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(HiFiColors.Accent))
                }
                VSpace(8.dp)
                Row(Modifier.fillMaxWidth()) {
                    Text(formatTime(np.elapsed), style = MaterialTheme.typography.bodyMedium, color = HiFiColors.Muted)
                    Box(Modifier.weight(1f))
                    Text(formatTime(np.duration), style = MaterialTheme.typography.bodyMedium, color = HiFiColors.Muted)
                }
                VSpace(18.dp)
                // Transport
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    RoundIconButton(Icons.Default.SkipPrevious, "Previous", onClick = { vm.previous() }, size = 64.dp)
                    RoundIconButton(
                        if (np.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play / pause",
                        onClick = { vm.playPause() }, size = 84.dp, primary = true, modifier = Modifier.focusRequester(playFocus),
                    )
                    RoundIconButton(Icons.Default.SkipNext, "Next", onClick = { vm.next() }, size = 64.dp)
                    RoundIconButton(Icons.Default.Stop, "Stop", onClick = { vm.stop() }, size = 60.dp)
                }
                VSpace(14.dp)
                VolumeRow(level = player?.volumeLevel ?: 0, muted = player?.volumeMuted == true, onUp = { vm.volumeUp() }, onDown = { vm.volumeDown() })
            }
        }
    }
}

/** Focusable volume bar: left / right on the D-pad nudge the hi-fi's volume. */
@Composable
private fun VolumeRow(level: Int, muted: Boolean, onUp: () -> Unit, onDown: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (ev.key) {
                    Key.DirectionRight -> { onUp(); true }
                    Key.DirectionLeft -> { onDown(); true }
                    else -> false
                }
            }
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .background(if (focused) HiFiColors.SurfaceHigh else Color.Transparent, RoundedCornerShape(50))
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (muted) Icons.Default.VolumeOff else Icons.Default.VolumeUp, null, tint = if (focused) HiFiColors.Text else HiFiColors.Muted, modifier = Modifier.size(28.dp))
        HSpace(16.dp)
        Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(Color(0x33FFFFFF))) {
            Box(Modifier.fillMaxWidth((level / 100f).coerceIn(0f, 1f)).fillMaxHeight().background(if (focused) HiFiColors.Focus else HiFiColors.Accent))
        }
        HSpace(16.dp)
        Text("$level", style = MaterialTheme.typography.titleMedium, color = if (focused) HiFiColors.Text else HiFiColors.Muted, modifier = Modifier.width(56.dp))
        if (focused) Text("◀ ▶ to adjust", style = MaterialTheme.typography.labelSmall, color = HiFiColors.Muted)
    }
}
