package io.github.superthom196.matv.ui.screens

import io.github.superthom196.matv.ui.MenuAction
import io.github.superthom196.matv.ui.ActionMenu
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.superthom196.matv.AppViewModel
import io.github.superthom196.matv.UiState
import io.github.superthom196.matv.ma.Player
import io.github.superthom196.matv.ui.FocusSurface
import io.github.superthom196.matv.ui.HSpace
import io.github.superthom196.matv.ui.HiFiColors
import io.github.superthom196.matv.ui.PillButton
import io.github.superthom196.matv.ui.StatusDot
import io.github.superthom196.matv.ui.VSpace

/** Pick which hi-fi the TV drives. Shown first when nothing is chosen, and from the "player" chip later. */
@Composable
fun PlayersScreen(vm: AppViewModel, ui: UiState, onDone: () -> Unit) {
    val players = ui.selectablePlayers
    val first = remember { FocusRequester() }
    var menuFor by remember { mutableStateOf<Player?>(null) }
    menuFor?.let { p ->
        val selected = ui.selectedPlayer
        val synced = p.syncedTo != null || !p.groupMembers.isNullOrEmpty()
        ActionMenu(p.name, p.provider.replace('_', ' '), buildList {
            add(MenuAction("Use this player") { vm.selectPlayer(p.playerId); onDone() })
            if (selected != null && selected.playerId != p.playerId) add(MenuAction("Sync with ${selected.name}") { vm.groupPlayer(p.playerId, selected.playerId) })
            if (synced) add(MenuAction("Unsync", danger = true) { vm.ungroupPlayer(p.playerId) })
        }, onDismiss = { menuFor = null })
    }
    LaunchedEffect(players.size) { if (players.isNotEmpty()) runCatching { first.requestFocus() } }

    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Which hi-fi?", style = MaterialTheme.typography.displaySmall)
                Text("${ui.server?.name ?: "Music Assistant"}  ·  ${players.size} players", style = MaterialTheme.typography.bodyMedium, color = HiFiColors.Muted)
            }
            PillButton("Forget server", onClick = { vm.forgetServer() })
        }
        VSpace(16.dp)
        if (players.isEmpty()) {
            Text("No players yet. Check that your squeezelite endpoints are running and enabled in Music Assistant.",
                style = MaterialTheme.typography.bodyLarge, color = HiFiColors.Muted)
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 4.dp, bottom = 40.dp),
        ) {
            items(players, key = { it.playerId }) { p ->
                val focusMod = if (p == players.first()) Modifier.focusRequester(first) else Modifier
                PlayerCard(p, selected = p.playerId == ui.selectedPlayerId, modifier = focusMod.fillMaxWidth(), onLongClick = { menuFor = p }) {
                    vm.selectPlayer(p.playerId); onDone()
                }
            }
        }
    }
}

@Composable
private fun PlayerCard(p: Player, selected: Boolean, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    FocusSurface(onClick = onClick, onLongClick = onLongClick, modifier = modifier, container = if (selected) HiFiColors.SurfaceHigh else HiFiColors.Surface) {
        Column(Modifier.padding(22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(
                    when {
                        p.isPlaying -> HiFiColors.Good
                        p.powered == false -> HiFiColors.Muted
                        else -> HiFiColors.Accent
                    },
                )
                HSpace(12.dp)
                Text(p.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            VSpace(8.dp)
            val what = when {
                p.isPlaying -> "Playing: ${p.currentMedia?.title ?: ""}".trimEnd(':', ' ')
                p.playbackState == "paused" -> "Paused"
                p.powered == false -> "Off"
                else -> "Idle"
            }
            Text(what, style = MaterialTheme.typography.bodySmall, color = HiFiColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(p.provider.replace('_', ' '), p.deviceInfo?.model, p.volumeLevel?.let { "vol $it" }).joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall, color = HiFiColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            p.syncedTo?.let { VSpace(4.dp); Text("Synced to another player", style = MaterialTheme.typography.labelSmall, color = HiFiColors.Muted) }
            if (!p.groupMembers.isNullOrEmpty()) { VSpace(4.dp); Text("Group of ${p.groupMembers.size}", style = MaterialTheme.typography.labelSmall, color = HiFiColors.Muted) }
            if (selected) { VSpace(6.dp); Text("Selected", style = MaterialTheme.typography.labelSmall, color = HiFiColors.Accent) }
        }
    }
}
