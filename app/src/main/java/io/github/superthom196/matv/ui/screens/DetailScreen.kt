package io.github.superthom196.matv.ui.screens

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Shuffle
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
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.superthom196.matv.AppViewModel
import io.github.superthom196.matv.UiState
import io.github.superthom196.matv.ma.MediaItem
import io.github.superthom196.matv.ui.Artwork
import io.github.superthom196.matv.ui.FocusSurface
import io.github.superthom196.matv.ui.HSpace
import io.github.superthom196.matv.ui.HiFiColors
import io.github.superthom196.matv.ui.MainScreen
import io.github.superthom196.matv.ui.MediaCard
import io.github.superthom196.matv.ui.Nav
import io.github.superthom196.matv.ui.PillButton
import io.github.superthom196.matv.ui.VSpace
import io.github.superthom196.matv.ui.formatTime

/** Album / playlist → tracks; artist → albums. Play the whole thing, or a track "from here". */
@Composable
fun DetailScreen(vm: AppViewModel, ui: UiState, nav: Nav, item: MediaItem) {
    var children by remember(item) { mutableStateOf<List<MediaItem>?>(null) }
    var error by remember(item) { mutableStateOf<String?>(null) }
    LaunchedEffect(item, ui.connection) {
        if (children == null) runCatching { vm.children(item) }.onSuccess { children = it }.onFailure { error = it.message }
    }
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }

    Row(Modifier.fillMaxSize().padding(start = 56.dp, end = 56.dp, top = 40.dp)) {
        // Left: artwork + actions
        Column(Modifier.width(360.dp)) {
            Artwork(vm.imageUrl(item, 512), Modifier.size(320.dp), corner = if (item.mediaType == "artist") 200.dp else 14.dp)
            VSpace(22.dp)
            Text(item.name, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val sub = when (item.mediaType) {
                "album" -> listOfNotNull(item.artistLine.takeIf { it.isNotBlank() }, item.year?.toString(), item.albumType?.takeIf { it != "unknown" }?.replace('_', ' ')).joinToString("  ·  ")
                "playlist" -> listOfNotNull("Playlist", item.owner).joinToString("  ·  ")
                "artist" -> "Artist"
                else -> ""
            }
            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodyMedium, color = HiFiColors.Muted, maxLines = 2)
            children?.let { Text("${it.size} ${if (item.mediaType == "artist") "albums" else "tracks"}", style = MaterialTheme.typography.bodySmall, color = HiFiColors.Muted) }
            VSpace(24.dp)
            PillButton("Play", onClick = { vm.playItem(item) }, icon = Icons.Default.PlayArrow, primary = true, modifier = Modifier.focusRequester(playFocus))
            VSpace(12.dp)
            Row {
                PillButton("Shuffle", onClick = { vm.playItem(item, option = "replace"); vm.shuffleOn() }, icon = Icons.Default.Shuffle)
                HSpace(10.dp)
                PillButton("Queue next", onClick = { vm.playItem(item, option = "next") }, icon = Icons.Default.PlaylistAdd)
            }
            VSpace(12.dp)
            Text("on ${ui.selectedPlayer?.name ?: "— choose a player —"}", style = MaterialTheme.typography.bodySmall, color = HiFiColors.Muted)
        }
        HSpace(40.dp)
        // Right: children
        Box(Modifier.fillMaxSize()) {
            when {
                error != null -> Text("Couldn't load: $error", style = MaterialTheme.typography.bodyLarge, color = HiFiColors.Danger)
                children == null -> Text("Loading…", style = MaterialTheme.typography.bodyLarge, color = HiFiColors.Muted)
                item.mediaType == "artist" -> LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 48.dp), modifier = Modifier.fillMaxSize().focusRestorer(),
                ) {
                    items(children!!, key = { "${it.provider}:${it.itemId}" }) { album ->
                        MediaCard(album, vm.imageUrl(album, 256), onClick = { nav.push(MainScreen.Detail(album)) })
                    }
                }
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxSize().focusRestorer()) {
                    itemsIndexed(children!!, key = { i, t -> "$i:${t.provider}:${t.itemId}" }) { i, track ->
                        TrackRow(i + 1, track, showArtist = item.mediaType == "playlist", onClick = { vm.playItem(item, startFrom = track) })
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackRow(n: Int, t: MediaItem, showArtist: Boolean, onClick: () -> Unit) {
    FocusSurface(onClick = onClick, modifier = Modifier.fillMaxWidth(), container = androidx.compose.ui.graphics.Color.Transparent, scale = 1.01f) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text((t.trackNumber ?: n).toString(), style = MaterialTheme.typography.bodyMedium, color = HiFiColors.Muted, modifier = Modifier.width(44.dp))
            Column(Modifier.weight(1f)) {
                Text(t.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (showArtist && t.artistLine.isNotBlank()) Text(t.artistLine, style = MaterialTheme.typography.bodySmall, color = HiFiColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            HSpace(16.dp)
            Text(formatTime(t.duration), style = MaterialTheme.typography.bodyMedium, color = HiFiColors.Muted)
        }
    }
}
