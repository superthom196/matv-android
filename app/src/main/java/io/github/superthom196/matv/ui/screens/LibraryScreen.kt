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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import io.github.superthom196.matv.ui.SectionLabel
import androidx.compose.runtime.remember
import io.github.superthom196.matv.R
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusGroup
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
import io.github.superthom196.matv.AlbumScan
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
import io.github.superthom196.matv.ui.Wordmark
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

@Composable
fun LibraryScreen(vm: AppViewModel, ui: UiState, nav: Nav) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val tabs = remember(settings) { settings.tabs }
    var tab by rememberSaveable { mutableIntStateOf(tabs.indexOfFirst { it.first == settings.defaultTab }.coerceAtLeast(0)) }
    if (tab >= tabs.size) tab = 0
    val kind = tabs[tab].first
    // Note: the composed `page` can lag one frame behind a tab switch, so ask the store directly.
    LaunchedEffect(kind, ui.connection) {
        when (kind) {
            "albums" -> vm.albums.ensureLoaded()
            "artists" -> vm.artists.ensureLoaded()
            "genres" -> { vm.genres.ensureLoaded(); vm.albums.ensureLoaded() }
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
            Wordmark()
            HSpace(12.dp)
            TabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { i, (_, label) ->
                    Tab(selected = i == tab, onFocus = { if (DpadTracker.userNavigatedRecently()) tab = i }, onClick = { tab = i },
                        modifier = if (i == tab) Modifier.focusRequester(tabFocus) else Modifier) {
                        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp))
                    }
                }
            }
            HSpace(6.dp)
            IconChip(Icons.Default.Search, "Search", onClick = { nav.push(MainScreen.Search) })
            Box(Modifier.weight(1f))
            NowPlayingChip(vm, onClick = { nav.push(MainScreen.NowPlaying) })
            HSpace(4.dp)
            IconChip(Icons.Default.Settings, "Settings", onClick = { nav.push(MainScreen.Settings) })
        }
        VSpace(10.dp)
        if (kind == "folders") {
            FolderBrowser(vm, ui, nav)
            return@Column
        }
        when (kind) {
            "albums" -> IndexedGrid(vm, nav, vm.albums, columns = 6, round = false, loadingText = "Sorting your albums by artist…", onBackToTop = { runCatching { tabFocus.requestFocus() } }, showTopRows = true)
            "playlists" -> IndexedGrid(vm, nav, vm.playlists, columns = 6, round = false, loadingText = "Loading playlists…", onBackToTop = { runCatching { tabFocus.requestFocus() } })
            "radio" -> IndexedGrid(vm, nav, vm.radios, columns = 6, round = false, loadingText = "Loading radio stations…", onBackToTop = { runCatching { tabFocus.requestFocus() } })
            "genres" -> GenreGrid(vm, nav)
            "favourites" -> FavouritesTab(vm, nav)
            else -> IndexedGrid(vm, nav, vm.artists, columns = 7, round = true, loadingText = "Sorting your artists…", onBackToTop = { runCatching { tabFocus.requestFocus() } })
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
/** Slack around the lazy containers so a focused card's ring is not clipped as it grows. */
private val RING_ROOM = 8.dp

/** Focus is in the A-Z grid rather than in one of the shelves above it. */
private const val IN_GRID = -1

/** Height of a shelf, so an empty one still reserves its place. */
private val ROW_HEIGHT = 206.dp

/** One horizontal shelf of albums above the A-Z grid. Pages in more as it nears its end. */
@Composable
private fun AlbumRow(
    vm: AppViewModel,
    nav: Nav,
    title: String,
    items: List<MediaItem>,
    hiResAlbums: Set<String>,
    onNearEnd: (Int) -> Unit,
    onItemFocused: (index: Int) -> Unit,
    focus: FocusRequester,
) {
    val rowState = rememberLazyListState()
    val lastVisible by remember { derivedStateOf { rowState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 } }
    LaunchedEffect(lastVisible) { onNearEnd(lastVisible) }
    Column(Modifier.padding(bottom = 10.dp)) {
        SectionLabel(title, Modifier.padding(bottom = 4.dp))
        if (items.isEmpty()) {
            // Hold the space, so the row above does not jump when this one arrives.
            Box(Modifier.fillMaxWidth().height(ROW_HEIGHT), contentAlignment = Alignment.CenterStart) {
                Text("Loading…", style = MaterialTheme.typography.bodyMedium, color = HiFiColors.Muted)
            }
            return@Column
        }
        LazyRow(
            state = rowState,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            // These shelves live inside the grid, which already insets its items by RING_ROOM. Pad by
            // the same amount and shift back by it: the cards line up with the album columns below,
            // and the focused card still has room for its ring instead of being sliced off.
            contentPadding = PaddingValues(horizontal = RING_ROOM),
            // focusRestorer remembers which card was last focused here, so coming back up from
            // the grid returns to the album you left rather than to the start of the shelf.
            modifier = Modifier.fillMaxWidth().offset(x = -RING_ROOM).focusRequester(focus).focusGroup().focusRestorer(),
        ) {
            itemsIndexed(items, key = { _, it -> "$title:${it.provider}:${it.itemId}" }) { i, item ->
                MediaCard(
                    item, vm.imageUrl(item, 256),
                    onClick = { openOrPlay(vm, nav, item) },
                    modifier = Modifier.width(150.dp).onFocusChanged { if (it.isFocused) onItemFocused(i) },
                    hiRes = AlbumScan.marks(item, hiResAlbums),
                )
            }
        }
    }
}

@Composable
private fun IndexedGrid(vm: AppViewModel, nav: Nav, index: AlbumIndex, columns: Int, round: Boolean, loadingText: String, onBackToTop: () -> Unit, showTopRows: Boolean = false) {
    val state by index.state.collectAsStateWithLifecycle()
    val hiResAlbums by vm.albumScan.hiRes.collectAsStateWithLifecycle()

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

    // Latest and Random ride above the A-Z grid as full-span rows, so there is no nested scrolling
    // and the whole page scrolls as one. Both are the full library, not a capped carousel.
    val recent by vm.recentAlbums.items.collectAsStateWithLifecycle()
    LaunchedEffect(showTopRows) { if (showTopRows) vm.recentAlbums.ensureLoaded() }
    // Seeded in the view model, so the order holds while you browse in and out of albums.
    val shuffled = remember(state.items, showTopRows) { if (showTopRows) vm.shuffledAlbums(state.items) else emptyList() }
    val topRows = if (!showTopRows) emptyList() else listOf("Latest" to recent, "Random" to shuffled)
    // Latest and Random shelves, plus the "Collection" label that names the A-Z grid below them.
    val headers = if (showTopRows) topRows.size + 1 else 0

    val gridState = rememberLazyGridState()
    var menuFor by remember { mutableStateOf<MediaItem?>(null) }
    menuFor?.let { PlayOptionsMenu(vm, it, onDismiss = { menuFor = null }) }
    var jump by remember { mutableStateOf<JumpRequest?>(null) }
    val itemFocus = remember { FocusRequester() }
    val focusIndex = jump?.index ?: 0
    val currentLabel by remember(state.anchors, headers) {
        derivedStateOf { labelAtIndex(state.anchors, (gridState.firstVisibleItemIndex - headers).coerceAtLeast(0)) }
    }

    LaunchedEffect(jump?.token) {
        val target = jump ?: return@LaunchedEffect
        gridState.scrollToItem(target.index + headers)
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.any { it.index == target.index + headers } }.first { it }
        withFrameNanos { }
        runCatching { itemFocus.requestFocus() }
    }

    // The rail must feel instant on a slow TV: moving between letters only records the target,
    // and the grid follows once the D-pad pauses, instead of composing a new page of covers per step.
    val railFocus = remember { FocusRequester() }
    // Left and Up are answered from these rather than from a 2D focus search, which does not find
    // the rail at all and, leaving the top of the grid, wanders onto whichever tab sits above.
    // Every card — shelf and grid alike — reports where it is as it takes focus.
    var atRowStart by remember { mutableStateOf(false) }
    var inGridTopRow by remember { mutableStateOf(false) }
    var focusedShelf by remember { mutableIntStateOf(IN_GRID) }
    val shelfFocus = remember(topRows.size) { List(topRows.size) { FocusRequester() } }
    var followLetter by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(followLetter) {
        val label = followLetter ?: return@LaunchedEffect
        delay(140)
        gridState.scrollToItem(indexForLetter(state.anchors, label) + headers)
    }

    // Deep in a long grid the only way back to the header is holding the D-pad through every row.
    // Back jumps there instead — and only while scrolled down, so at the top it still leaves the app.
    val scope = rememberCoroutineScope()
    // Put focus into a shelf. It has to be scrolled into view first: a shelf that is off-screen is
    // not composed, and there is nothing there to take focus until it is.
    val enterShelf: suspend (Int) -> Unit = { row ->
        gridState.scrollToItem(row)
        withFrameNanos { }
        runCatching { shelfFocus[row].requestFocus() }
    }
    BackHandler(enabled = gridState.firstVisibleItemIndex > 0) {
        scope.launch { gridState.scrollToItem(0) }
        onBackToTop()
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
            // The focused card grows 5%; without room on the left its ring is clipped by the rail.
            contentPadding = PaddingValues(start = RING_ROOM, end = RING_ROOM, bottom = 48.dp, top = 6.dp),
            modifier = Modifier
                .fillMaxSize()
                .focusRestorer()
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (ev.key) {
                        // Left from the left edge hands focus to the A-Z rail (2D focus search does
                        // not find it on its own). Anywhere else Left is the shelf's or the grid's.
                        Key.DirectionLeft -> atRowStart && runCatching { railFocus.requestFocus() }.isSuccess
                        // Up moves between the shelves and the grid by hand. A 2D search cannot be
                        // trusted with it: the shelf above is often scrolled out of the grid and so
                        // not composed at all, and with nothing above to find, focus escapes to the
                        // tab bar — which switches the page. Deeper in the grid there is always a
                        // row above, so Up is left alone there.
                        Key.DirectionUp -> when {
                            focusedShelf == IN_GRID && !inGridTopRow -> false
                            else -> {
                                val from = if (focusedShelf == IN_GRID) topRows.size else focusedShelf
                                // A shelf that is still loading holds its space but has nothing
                                // focusable in it, so skip past it to the next one that has albums.
                                val above = (from - 1 downTo 0).firstOrNull { topRows[it].second.isNotEmpty() }
                                if (above == null) onBackToTop() else scope.launch { enterShelf(above) }
                                true
                            }
                        }
                        else -> false
                    }
                },
        ) {
            topRows.forEachIndexed { row, (title, items) ->
                item(span = { GridItemSpan(maxLineSpan) }, key = "row:$title") {
                    AlbumRow(vm, nav, title, items, hiResAlbums, onNearEnd = { last ->
                        if (title == "Latest") vm.recentAlbums.ensureLoaded(last)
                    }, onItemFocused = { i ->
                        focusedShelf = row
                        atRowStart = i == 0
                        inGridTopRow = false
                    }, focus = shelfFocus[row])
                }
            }
            if (showTopRows) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "row:Collection") {
                    SectionLabel("Collection", Modifier.padding(bottom = 4.dp))
                }
            }
            itemsIndexed(state.items, key = { _, it -> "${it.provider}:${it.itemId}" }) { index, item ->
                val mod = Modifier
                    .onFocusChanged {
                        if (!it.isFocused) return@onFocusChanged
                        focusedShelf = IN_GRID
                        atRowStart = index % columns == 0
                        inGridTopRow = index < columns
                    }
                    .then(if (index == focusIndex) Modifier.focusRequester(itemFocus) else Modifier)
                val own = vm.imageUrl(item, 256)
                val url = if (own == null && round) vm.artistCover(item, 256).collectAsStateWithLifecycle().value else own
                MediaCard(item, url, onClick = { openOrPlay(vm, nav, item) }, modifier = mod, round = round, onLongClick = { menuFor = item }, hiRes = AlbumScan.marks(item, hiResAlbums))
            }
        }
    }
}

@Composable
fun NowPlayingChip(vm: AppViewModel, onClick: () -> Unit) {
    // Only re-reads when the parts of NowPlaying this chip shows actually change, not once a second
    // while playing (vm.ui itself no longer ticks, but this keeps the chip decoupled either way).
    val np by remember { vm.ui.map { it.nowPlaying }.distinctUntilChanged() }.collectAsStateWithLifecycle(vm.ui.value.nowPlaying)
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
