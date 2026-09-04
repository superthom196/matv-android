package io.github.superthom196.matv.ui.screens

import io.github.superthom196.matv.ui.MenuAction
import io.github.superthom196.matv.ui.ConfirmMenu
import io.github.superthom196.matv.ui.ActionMenu
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.superthom196.matv.AppViewModel
import io.github.superthom196.matv.UiState
import io.github.superthom196.matv.ma.ImageUrls
import io.github.superthom196.matv.ma.QueueItem
import io.github.superthom196.matv.ui.Artwork
import io.github.superthom196.matv.ui.FocusSurface
import io.github.superthom196.matv.ui.HSpace
import io.github.superthom196.matv.ui.HiFiColors
import io.github.superthom196.matv.ui.Nav
import io.github.superthom196.matv.ui.PillButton
import io.github.superthom196.matv.ui.VSpace
import io.github.superthom196.matv.ui.formatTime

/** What the selected player has queued: current track highlighted, OK on a row jumps to it. */
@Composable
fun QueueScreen(vm: AppViewModel, ui: UiState, nav: Nav) {
    val q by vm.queue.collectAsStateWithLifecycle()
    DisposableEffect(ui.activeQueueId) {
        vm.setQueueVisible(true)
        onDispose { vm.setQueueVisible(false) }
    }
    val current = q.currentIndex ?: q.items.indexOfFirst { it.queueItemId == ui.nowPlaying.queueItemId }.takeIf { it >= 0 } ?: 0
    val listState = rememberLazyListState()
    var menuFor by remember { mutableStateOf<Pair<Int, QueueItem>?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    menuFor?.let { (i, item) ->
        ActionMenu(item.mediaItem?.name ?: item.name, item.mediaItem?.artistLine, listOf(
            MenuAction("Play from here", Icons.Default.PlayArrow) { vm.playQueueIndex(i) },
            MenuAction("Move up", Icons.Default.ArrowUpward) { vm.moveQueueItem(item, -1) },
            MenuAction("Move down", Icons.Default.ArrowDownward) { vm.moveQueueItem(item, 1) },
            MenuAction("Remove from queue", Icons.Default.Delete, danger = true) { vm.removeQueueItem(item) },
        ), onDismiss = { menuFor = null })
    }
    if (confirmClear) ConfirmMenu("Clear the queue?", "Stops playback on ${ui.selectedPlayer?.name ?: "the player"}", "Clear queue", onConfirm = { vm.clearQueue() }, onDismiss = { confirmClear = false })
    val currentFocus = remember { FocusRequester() }
    LaunchedEffect(q.items.size, q.loading) {
        if (!q.loading && q.items.isNotEmpty()) {
            listState.scrollToItem((current - 1).coerceAtLeast(0))
            runCatching { currentFocus.requestFocus() }
        }
    }

    Column(Modifier.fillMaxSize().padding(start = 28.dp, end = 28.dp, top = 20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Queue", style = MaterialTheme.typography.headlineMedium)
                val name = ui.nowPlaying.queueName.ifBlank { ui.selectedPlayer?.name ?: "" }
                Text(
                    listOf(name, "${q.items.size} tracks").filter { it.isNotBlank() }.joinToString("  ·  "),
                    style = MaterialTheme.typography.bodyMedium, color = HiFiColors.Muted,
                )
            }
            if (q.items.isNotEmpty()) { PillButton("Clear", onClick = { confirmClear = true }, icon = Icons.Default.Delete); HSpace(10.dp) }
            PillButton("Refresh", onClick = { vm.refreshQueue() }, icon = Icons.Default.Refresh)
        }
        VSpace(12.dp)
        when {
            q.error != null -> Text(q.error!!, style = MaterialTheme.typography.bodyLarge, color = HiFiColors.Danger)
            q.loading && q.items.isEmpty() -> Text("Loading…", style = MaterialTheme.typography.bodyLarge, color = HiFiColors.Muted)
            q.items.isEmpty() -> Text("Nothing queued. Pick something from the library.", style = MaterialTheme.typography.bodyLarge, color = HiFiColors.Muted)
            else -> LazyColumn(
                state = listState,
                contentPadding = PaddingValues(top = 4.dp, bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxSize().focusRestorer(),
            ) {
                itemsIndexed(q.items, key = { i, it -> "$i:${it.queueItemId}" }) { i, item ->
                    val isCurrent = i == current
                    QueueRow(
                        i, item, isCurrent, ImageUrls.forQueueItem(ui.baseUrl, item, 80),
                        modifier = if (isCurrent) Modifier.focusRequester(currentFocus) else Modifier,
                        onLongClick = { menuFor = i to item },
                    ) { vm.playQueueIndex(i) }
                }
            }
        }
    }
}

@Composable
private fun QueueRow(index: Int, item: QueueItem, isCurrent: Boolean, imageUrl: String?, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    FocusSurface(onClick = onClick, onLongClick = onLongClick, modifier = modifier.fillMaxWidth(), container = if (isCurrent) HiFiColors.Surface else Color.Transparent, scale = 1.01f) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(36.dp), contentAlignment = Alignment.Center) {
                if (isCurrent) Icon(Icons.Default.PlayArrow, null, tint = HiFiColors.Accent, modifier = Modifier.size(22.dp))
                else Text((index + 1).toString(), style = MaterialTheme.typography.bodySmall, color = HiFiColors.Muted)
            }
            HSpace(8.dp)
            Artwork(imageUrl, Modifier.size(40.dp), corner = 5.dp)
            HSpace(14.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    // Queue item names are "Artist - Title"; the artist already sits on the line below.
                    item.mediaItem?.name?.takeIf { it.isNotBlank() } ?: item.name, style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    color = if (isCurrent) HiFiColors.Accent else HiFiColors.Text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val sub = listOfNotNull(item.mediaItem?.artistLine?.takeIf { it.isNotBlank() }, item.mediaItem?.album?.name).joinToString("  ·  ")
                if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = HiFiColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            HSpace(12.dp)
            Text(formatTime(item.duration), style = MaterialTheme.typography.bodySmall, color = HiFiColors.Muted, modifier = Modifier.width(64.dp))
        }
    }
}
