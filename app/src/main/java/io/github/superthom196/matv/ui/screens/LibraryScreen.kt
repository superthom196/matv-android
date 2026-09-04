@file:OptIn(ExperimentalTvMaterial3Api::class)

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
import io.github.superthom196.matv.AppViewModel
import io.github.superthom196.matv.UiState
import io.github.superthom196.matv.indexForLetter
import io.github.superthom196.matv.labelAtIndex
import io.github.superthom196.matv.ui.Artwork
import io.github.superthom196.matv.ui.FocusSurface
import io.github.superthom196.matv.ui.HSpace
import io.github.superthom196.matv.ui.HiFiColors
import io.github.superthom196.matv.ui.AlphabetRail
import io.github.superthom196.matv.ui.MainScreen
import io.github.superthom196.matv.ui.MediaCard
import io.github.superthom196.matv.ui.Nav
import io.github.superthom196.matv.ui.VSpace
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

private val tabs = listOf("folders" to "Folders", "artists" to "Artists", "albums" to "Albums")

@Composable
fun LibraryScreen(vm: AppViewModel, ui: UiState, nav: Nav) {
    var tab by rememberSaveable { mutableIntStateOf(0) } // Folders first: the fastest way through a big collection
    val kind = tabs[tab].first
    val page by vm.library.page(kind).collectAsStateWithLifecycle()
    // Note: the composed `page` can lag one frame behind a tab switch, so ask the store directly.
    LaunchedEffect(kind, ui.connection) {
        when (kind) {
            "albums" -> vm.albums.ensureLoaded()
            "folders" -> Unit // FolderBrowser loads itself via music/browse
            else -> vm.library.ensureLoaded(kind)
        }
    }
    val tabFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { tabFocus.requestFocus() } }

    Column(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("MATV", style = MaterialTheme.typography.headlineLarge.copy(fontSize = 38.sp, fontWeight = FontWeight.Bold), color = HiFiColors.Accent)
            HSpace(28.dp)
            TabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { i, (_, label) ->
                    Tab(selected = i == tab, onFocus = { tab = i }, onClick = { tab = i },
                        modifier = if (i == tab) Modifier.focusRequester(tabFocus) else Modifier) {
                        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                    }
                }
            }
            Box(Modifier.weight(1f))
            NowPlayingChip(ui, onClick = { nav.push(MainScreen.NowPlaying) })
            HSpace(14.dp)
            PlayerChip(ui, onClick = { nav.push(MainScreen.Players) })
        }
        VSpace(10.dp)
        if (kind == "folders") {
            FolderBrowser(vm, ui, nav)
            return@Column
        }
        if (kind == "albums") {
            AlbumsGrid(vm, nav)
            return@Column
        }
        val pageError = page.error
        if (pageError != null && page.items.isEmpty()) {
            Text(pageError, style = MaterialTheme.typography.bodyLarge, color = HiFiColors.Danger)
            VSpace(8.dp)
            Text("Press OK on a tab to retry.", style = MaterialTheme.typography.bodyMedium, color = HiFiColors.Muted)
        }
        val gridState = rememberLazyGridState()
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(if (kind == "artists") 7 else 6),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = 48.dp, top = 6.dp),
            modifier = Modifier.fillMaxSize().focusRestorer(),
        ) {
            itemsIndexed(page.items, key = { _, it -> "${it.provider}:${it.itemId}" }) { index, item ->
                if (index >= page.items.size - 12) LaunchedEffect(kind, page.items.size) { vm.library.loadMore(kind) }
                val own = vm.imageUrl(item, 256)
                val url = if (own == null && kind == "artists") vm.artistCover(item, 256).collectAsStateWithLifecycle().value else own
                MediaCard(item, url, onClick = { nav.push(MainScreen.Detail(item)) }, round = kind == "artists")
            }
            if (page.loading) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Text("Loading…", style = MaterialTheme.typography.bodyMedium, color = HiFiColors.Muted, modifier = Modifier.padding(16.dp))
            }
            if (!page.loading && page.end && page.items.isEmpty() && page.error == null) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Text("Nothing in your library here yet.", style = MaterialTheme.typography.bodyLarge, color = HiFiColors.Muted, modifier = Modifier.padding(16.dp))
            }
        }
    }
}

private data class JumpRequest(val index: Int, val token: Long)

/**
 * Albums, loaded whole and sorted by artist (see AlbumIndex.kt) so an A-Z jump can be exact.
 * No infinite scroll here: everything is already in memory once `state.ready`.
 */
@Composable
private fun AlbumsGrid(vm: AppViewModel, nav: Nav) {
    val state by vm.albums.state.collectAsStateWithLifecycle()

    if (!state.ready) {
        Column(Modifier.fillMaxSize()) {
            val err = state.error
            if (err != null) {
                Text(err, style = MaterialTheme.typography.bodyLarge, color = HiFiColors.Danger)
                VSpace(8.dp)
                Text("Press OK on a tab to retry.", style = MaterialTheme.typography.bodyMedium, color = HiFiColors.Muted)
            } else {
                Text("Sorting your albums by artist…", style = MaterialTheme.typography.bodyLarge, color = HiFiColors.Muted)
                VSpace(8.dp)
                val of = state.total?.let { " of $it" } ?: ""
                Text("${state.loaded}$of", style = MaterialTheme.typography.bodyMedium, color = HiFiColors.Muted)
            }
        }
        return
    }

    val gridState = rememberLazyGridState()
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
            columns = GridCells.Fixed(6),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = 48.dp, top = 6.dp),
            modifier = Modifier
                .fillMaxSize()
                .focusRestorer()
                // Left from the first column hands focus to the A-Z rail (2D focus search does not find it on its own).
                .onPreviewKeyEvent { ev ->
                    if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionLeft && focusedIndex % 6 == 0) {
                        runCatching { railFocus.requestFocus() }.isSuccess
                    } else false
                },
        ) {
            itemsIndexed(state.items, key = { _, it -> "${it.provider}:${it.itemId}" }) { index, item ->
                val mod = Modifier
                    .onFocusChanged { if (it.isFocused) focusedIndex = index }
                    .then(if (index == focusIndex) Modifier.focusRequester(itemFocus) else Modifier)
                val url = vm.imageUrl(item, 256)
                MediaCard(item, url, onClick = { nav.push(MainScreen.Detail(item)) }, modifier = mod)
            }
        }
    }
}

@Composable
fun NowPlayingChip(ui: UiState, onClick: () -> Unit) {
    val np = ui.nowPlaying
    FocusSurface(onClick = onClick, shape = androidx.compose.foundation.shape.RoundedCornerShape(50)) {
        Row(Modifier.padding(start = 6.dp, end = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(np.imageUrl, Modifier.size(36.dp), corner = 50.dp)
            HSpace(12.dp)
            Icon(Icons.Default.PlayArrow, null, tint = if (np.isPlaying) HiFiColors.Good else HiFiColors.Muted, modifier = Modifier.size(22.dp))
            HSpace(6.dp)
            Text(
                if (np.hasMedia) np.title else "Now playing",
                style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 280.dp),
            )
        }
    }
}

@Composable
fun PlayerChip(ui: UiState, onClick: () -> Unit) {
    FocusSurface(onClick = onClick, shape = androidx.compose.foundation.shape.RoundedCornerShape(50)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Speaker, null, tint = HiFiColors.Accent, modifier = Modifier.size(24.dp))
            HSpace(10.dp)
            Text(ui.selectedPlayer?.name ?: "Choose player", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 260.dp))
        }
    }
}
