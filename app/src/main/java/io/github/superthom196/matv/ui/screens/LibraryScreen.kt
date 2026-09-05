@file:OptIn(ExperimentalTvMaterial3Api::class)

package io.github.superthom196.matv.ui.screens

import io.github.superthom196.matv.ui.PlayOptionsMenu
import io.github.superthom196.matv.ma.MediaItem
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Tab
import androidx.tv.material3.TabRow
import androidx.tv.material3.Text
import io.github.superthom196.matv.AlbumIndex
import io.github.superthom196.matv.AppViewModel
import io.github.superthom196.matv.HiResIndex
import io.github.superthom196.matv.UiState
import io.github.superthom196.matv.indexForLetter
import io.github.superthom196.matv.labelAtIndex
import io.github.superthom196.matv.ui.Artwork
import io.github.superthom196.matv.ui.FocusSurface
import io.github.superthom196.matv.ui.HSpace
import io.github.superthom196.matv.ui.HiFiColors
import io.github.superthom196.matv.ui.AlphabetRail
import io.github.superthom196.matv.ui.DpadTracker
import io.github.superthom196.matv.ui.MainScreen
import io.github.superthom196.matv.ui.MediaCard
import io.github.superthom196.matv.ui.Nav
import io.github.superthom196.matv.ui.VSpace
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

private val baseTabs = listOf("artists" to "Artists", "albums" to "Albums", "folders" to "Folders", "favourites" to "Favourites")

@Composable
fun LibraryScreen(vm: AppViewModel, ui: UiState, nav: Nav) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val tabs = remember(settings) {
        baseTabs + listOfNotNull(
            if (settings.showPlaylists) "playlists" to "Playlists" else null,
            if (settings.showRadio) "radio" to "Radio" else null,
        )
    }
    var tab by rememberSaveable { mutableIntStateOf(tabs.indexOfFirst { it.first == settings.defaultTab }.coerceAtLeast(0)) }
    if (tab >= tabs.size) tab = 0
    val kind = tabs[tab].first
    // Note: the composed `page` can lag one frame behind a tab switch, so ask the store directly.
    LaunchedEffect(kind, ui.connection) {
        when (kind) {
            "albums" -> vm.albums.ensureLoaded()
            "artists" -> vm.artists.ensureLoaded()
            "playlists" -> vm.playlists.ensureLoaded()
            "radio" -> vm.radios.ensureLoaded()
            "favourites" -> vm.ensureFavouritesLoaded()
            else -> Unit // FolderBrowser loads itself via music/browse
        }
    }
    val tabFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { tabFocus.requestFocus() } }

    Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("MATV", style = MaterialTheme.typography.headlineLarge.copy(fontSize = 30.sp, fontWeight = FontWeight.Bold), color = HiFiColors.Accent)
            HSpace(12.dp)
            TabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { i, (_, label) ->
                    Tab(selected = i == tab, onFocus = { if (DpadTracker.userNavigatedRecently()) tab = i }, onClick = { tab = i },
                        modifier = if (i == tab) Modifier.focusRequester(tabFocus) else Modifier) {
                        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp))
                    }
                }
            }
            Box(Modifier.weight(1f))
            IconChip(Icons.Default.Search, "Search", onClick = { nav.push(MainScreen.Search) })
            HSpace(4.dp)
            NowPlayingChip(ui, onClick = { nav.push(MainScreen.NowPlaying) })
            HSpace(4.dp)
            IconChip(Icons.Default.Settings, "Settings", onClick = { nav.push(MainScreen.Settings) })
        }
        VSpace(10.dp)
        if (kind == "folders") {
            FolderBrowser(vm, ui, nav)
            return@Column
        }
        when (kind) {
            "albums" -> IndexedGrid(vm, nav, vm.albums, columns = 6, round = false, loadingText = "Sorting your albums by artist…")
            "playlists" -> IndexedGrid(vm, nav, vm.playlists, columns = 6, round = false, loadingText = "Loading playlists…")
            "radio" -> IndexedGrid(vm, nav, vm.radios, columns = 6, round = false, loadingText = "Loading radio stations…")
            "favourites" -> FavouritesTab(vm, nav)
            else -> IndexedGrid(vm, nav, vm.artists, columns = 7, round = true, loadingText = "Sorting your artists…")
        }
    }
}

/** Small round header button with just an icon. */
@Composable
fun IconChip(icon: androidx.compose.ui.graphics.vector.ImageVector, contentDescription: String, onClick: () -> Unit) {
    FocusSurface(onClick = onClick, shape = androidx.compose.foundation.shape.RoundedCornerShape(50)) {
        Box(Modifier.padding(10.dp)) { Icon(icon, contentDescription, tint = HiFiColors.Text, modifier = Modifier.size(22.dp)) }
    }
}

/** OK on a browsable item opens it; anything else (radio, track) plays. */
fun openOrPlay(vm: AppViewModel, nav: Nav, item: MediaItem) {
    if (item.mediaType in setOf("album", "artist", "playlist")) nav.push(MainScreen.Detail(item)) else vm.playItem(item)
}

/** A request to scroll to and focus a grid index; `token` makes repeat jumps to the same index re-fire. */
private data class JumpRequest(val index: Int, val token: Long)

/** Artists and Albums share this: the whole library loaded once, sorted, with the A-Z rail on the left. */
@Composable
private fun IndexedGrid(vm: AppViewModel, nav: Nav, index: AlbumIndex, columns: Int, round: Boolean, loadingText: String) {
    val state by index.state.collectAsStateWithLifecycle()
    val hiResAlbums by vm.hiRes.hiRes.collectAsStateWithLifecycle()

    if (!state.ready) {
        Column(Modifier.fillMaxSize()) {
            val err = state.error
            if (err != null) {
                Text(err, style = MaterialTheme.typography.bodyLarge, color = HiFiColors.Danger)
                VSpace(8.dp)
                Text("Press OK on a tab to retry.", style = MaterialTheme.typography.bodyMedium, color = HiFiColors.Muted)
            } else {
                Text(loadingText, style = MaterialTheme.typography.bodyLarge, color = HiFiColors.Muted)
                VSpace(8.dp)
                val of = state.total?.let { " of $it" } ?: ""
                Text("${state.loaded}$of", style = MaterialTheme.typography.bodyMedium, color = HiFiColors.Muted)
            }
        }
        return
    }

    val gridState = rememberLazyGridState()
    var menuFor by remember { mutableStateOf<MediaItem?>(null) }
    menuFor?.let { PlayOptionsMenu(vm, it, onDismiss = { menuFor = null }) }
    var jump by remember { mutableStateOf<JumpRequest?>(null) }
    val itemFocus = remember { FocusRequester() }
    val focusIndex = jump?.index ?: 0
    val currentLabel by remember(state.anchors) { derivedStateOf { labelAtIndex(state.anchors, gridState.firstVisibleItemIndex) } }

    LaunchedEffect(jump?.token) {
        val target = jump ?: return@LaunchedEffect
        gridState.scrollToItem(target.index)
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.any { it.index == target.index } }.first { it }
        withFrameNanos { }
        runCatching { itemFocus.requestFocus() }
    }

    // The rail must feel instant on a slow TV: moving between letters only records the target,
    // and the grid follows once the D-pad pauses, instead of composing a new page of covers per step.
    val railFocus = remember { FocusRequester() }
    var focusedIndex by remember { mutableIntStateOf(0) }
    var followLetter by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(followLetter) {
        val label = followLetter ?: return@LaunchedEffect
        delay(140)
        gridState.scrollToItem(indexForLetter(state.anchors, label))
    }

    Row(Modifier.fillMaxSize()) {
        AlphabetRail(
            anchors = state.anchors,
            currentLabel = currentLabel,
            onLetterFocused = { label -> followLetter = label },
            onEnterGrid = { label -> jump = JumpRequest(indexForLetter(state.anchors, label), (jump?.token ?: 0L) + 1) },
            currentFocus = railFocus,
        )
        HSpace(10.dp)
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(columns),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = 48.dp, top = 6.dp),
            modifier = Modifier
                .fillMaxSize()
                .focusRestorer()
                // Left from the first column hands focus to the A-Z rail (2D focus search does not find it on its own).
                .onPreviewKeyEvent { ev ->
                    if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionLeft && focusedIndex % columns == 0) {
                        runCatching { railFocus.requestFocus() }.isSuccess
                    } else false
                },
        ) {
            itemsIndexed(state.items, key = { _, it -> "${it.provider}:${it.itemId}" }) { index, item ->
                val mod = Modifier
                    .onFocusChanged { if (it.isFocused) focusedIndex = index }
                    .then(if (index == focusIndex) Modifier.focusRequester(itemFocus) else Modifier)
                val own = vm.imageUrl(item, 256)
                val url = if (own == null && round) vm.artistCover(item, 256).collectAsStateWithLifecycle().value else own
                MediaCard(item, url, onClick = { openOrPlay(vm, nav, item) }, modifier = mod, round = round, onLongClick = { menuFor = item }, hiRes = HiResIndex.marks(item, hiResAlbums))
            }
        }
    }
}

@Composable
fun NowPlayingChip(ui: UiState, onClick: () -> Unit) {
    val np = ui.nowPlaying
    FocusSurface(onClick = onClick, shape = androidx.compose.foundation.shape.RoundedCornerShape(50)) {
        Row(Modifier.padding(start = 5.dp, end = 12.dp, top = 5.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(np.imageUrl, Modifier.size(34.dp), corner = 50.dp)
            HSpace(8.dp)
            Icon(Icons.Default.PlayArrow, null, tint = if (np.isPlaying) HiFiColors.Good else HiFiColors.Muted, modifier = Modifier.size(22.dp))
            HSpace(6.dp)
            Text(
                if (np.hasMedia) np.title else "Now playing",
                style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 150.dp),
            )
        }
    }
}

@Composable
fun PlayerChip(ui: UiState, onClick: () -> Unit) {
    FocusSurface(onClick = onClick, shape = androidx.compose.foundation.shape.RoundedCornerShape(50)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Speaker, null, tint = HiFiColors.Accent, modifier = Modifier.size(24.dp))
            HSpace(10.dp)
            Text(ui.selectedPlayer?.name ?: "Choose player", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 110.dp))
        }
    }
}
