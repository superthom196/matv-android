package io.github.superthom196.matv.ui.screens

import io.github.superthom196.matv.ui.PlayOptionsMenu
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
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
import io.github.superthom196.matv.ui.Nav
import io.github.superthom196.matv.ui.PillButton
import io.github.superthom196.matv.ui.VSpace
import io.github.superthom196.matv.ui.formatTime

/**
 * Folder tree of the music collection, like Music Assistant's Browse page.
 * Folders open in place (Back goes up a level); tracks play from that point to the end of
 * the folder; albums and artists that providers surface open the usual detail screen.
 */
@Composable
fun FolderBrowser(vm: AppViewModel, ui: UiState, nav: Nav) {
    val stack by vm.folders.collectAsStateWithLifecycle()
    val busy by vm.folderBusy.collectAsStateWithLifecycle()
    var menuFor by remember { mutableStateOf<MediaItem?>(null) }
    menuFor?.let { m ->
        val tracks = stack.lastOrNull()?.items?.filter { it.mediaType == "track" } ?: emptyList()
        PlayOptionsMenu(vm, m, onDismiss = { menuFor = null }, playNow = if (m.mediaType == "track") ({ vm.playTracksFrom(tracks, m) }) else null)
    }
    LaunchedEffect(ui.connection) { vm.ensureFoldersLoaded() }
    val level = stack.lastOrNull()
    BackHandler(enabled = stack.size > 1) { vm.folderUp() }

    val firstFocus = remember { FocusRequester() }
    // Auto-focus the first row only when a folder level is first shown (open / up), never when the
    // Folders tab is merely re-entered: otherwise walking across the tab row gets hijacked by the list.
    LaunchedEffect(level?.id, level?.loading) {
        if (level != null && !level.loading && level.items.isNotEmpty() && vm.folderLevelAutoFocused != level.id) {
            vm.folderLevelAutoFocused = level.id
            runCatching { firstFocus.requestFocus() }
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Breadcrumb + actions
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val crumbs = stack.drop(1).joinToString("  ›  ") { it.name }
            Text(
                if (crumbs.isEmpty()) "Music folders" else crumbs,
                style = MaterialTheme.typography.titleLarge, color = HiFiColors.Muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            HSpace(16.dp)
            val folder = level?.folder
            if (folder != null && folder.isPlayable && folder.uri != null) {
                PillButton("Play folder", onClick = { vm.playItem(folder) }, icon = Icons.Default.PlayArrow, primary = true)
                HSpace(10.dp)
            }
            if (busy) { Text("Loading…", style = MaterialTheme.typography.labelMedium, color = HiFiColors.Muted); HSpace(12.dp) }
            PillButton("Refresh", onClick = { vm.reloadFolders() }, icon = Icons.Default.Refresh)
        }
        VSpace(8.dp)
        when {
            level == null || level.loading -> Text("Loading…", style = MaterialTheme.typography.bodyLarge, color = HiFiColors.Muted)
            level.error != null -> Text("Couldn't load: ${level.error}", style = MaterialTheme.typography.bodyLarge, color = HiFiColors.Danger)
            level.items.isEmpty() -> Text("Empty folder.", style = MaterialTheme.typography.bodyLarge, color = HiFiColors.Muted)
            else -> {
                val tracks = remember(level.id) { level.items.filter { it.mediaType == "track" } }
                val listState = rememberLazyListState()
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(top = 4.dp, bottom = 48.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxSize().focusRestorer(),
                ) {
                    itemsIndexed(level.items, key = { i, it -> "$i:${it.provider}:${it.itemId}" }) { i, item ->
                        val mod = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier
                        FolderRow(item, vm.imageUrl(item, 80), modifier = mod, onLongClick = if (item.name != ".." && item.isPlayable && item.uri != null) ({ menuFor = item }) else null) {
                            when {
                                item.isFolder && item.name == ".." -> vm.folderUp()
                                item.isFolder -> vm.openFolder(item)
                                item.mediaType == "track" -> vm.playTracksFrom(tracks, item)
                                item.mediaType in setOf("album", "artist", "playlist") -> nav.push(MainScreen.Detail(item))
                                item.isPlayable && item.uri != null -> vm.playItem(item)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderRow(item: MediaItem, imageUrl: String?, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    FocusSurface(onClick = onClick, onLongClick = onLongClick, modifier = modifier.fillMaxWidth(), container = Color.Transparent, scale = 1.01f) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            val icon: ImageVector? = when (item.mediaType) {
                "folder" -> Icons.Default.Folder
                "track" -> Icons.Default.MusicNote
                "album" -> Icons.Default.Album
                "artist" -> Icons.Default.Person
                "playlist" -> Icons.Default.QueueMusic
                else -> null
            }
            if (imageUrl != null && item.mediaType != "folder") {
                Artwork(imageUrl, Modifier.size(44.dp), corner = 6.dp)
            } else {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    Icon(icon ?: Icons.Default.MusicNote, null, tint = if (item.isFolder) HiFiColors.Accent else HiFiColors.Muted, modifier = Modifier.size(28.dp))
                }
            }
            HSpace(18.dp)
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = when (item.mediaType) {
                    "track" -> listOfNotNull(item.artistLine.takeIf { it.isNotBlank() }, item.album?.name).joinToString("  ·  ")
                    "album" -> listOfNotNull(item.artistLine.takeIf { it.isNotBlank() }, item.year?.toString()).joinToString("  ·  ")
                    "folder" -> ""
                    else -> item.mediaType.replace('_', ' ')
                }
                if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = HiFiColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            HSpace(16.dp)
            if (item.mediaType == "track") Text(formatTime(item.duration), style = MaterialTheme.typography.bodyMedium, color = HiFiColors.Muted, modifier = Modifier.width(80.dp))
            else if (item.isFolder) Text("›", style = MaterialTheme.typography.headlineSmall, color = HiFiColors.Muted)
        }
    }
}
