@file:OptIn(ExperimentalTvMaterial3Api::class)

package io.github.superthom196.hifitv.ui.screens

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Tab
import androidx.tv.material3.TabRow
import androidx.tv.material3.Text
import io.github.superthom196.hifitv.AppViewModel
import io.github.superthom196.hifitv.UiState
import io.github.superthom196.hifitv.ui.Artwork
import io.github.superthom196.hifitv.ui.FocusSurface
import io.github.superthom196.hifitv.ui.HSpace
import io.github.superthom196.hifitv.ui.HiFiColors
import io.github.superthom196.hifitv.ui.MainScreen
import io.github.superthom196.hifitv.ui.MediaCard
import io.github.superthom196.hifitv.ui.Nav
import io.github.superthom196.hifitv.ui.VSpace

private val tabs = listOf("folders" to "Folders", "artists" to "Artists", "albums" to "Albums")

@Composable
fun LibraryScreen(vm: AppViewModel, ui: UiState, nav: Nav) {
    var tab by rememberSaveable { mutableIntStateOf(0) } // Folders first: the fastest way through a big collection
    val kind = tabs[tab].first
    val page by vm.library.page(kind).collectAsStateWithLifecycle()
    // Note: the composed `page` can lag one frame behind a tab switch, so ask the store directly.
    LaunchedEffect(kind, ui.connection) { vm.library.ensureLoaded(kind) }
    val tabFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { tabFocus.requestFocus() } }

    Column(Modifier.fillMaxSize().padding(start = 56.dp, end = 56.dp, top = 32.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("HiFi TV", style = MaterialTheme.typography.headlineMedium, color = HiFiColors.Accent)
            HSpace(40.dp)
            TabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { i, (_, label) ->
                    Tab(selected = i == tab, onFocus = { tab = i }, onClick = { tab = i },
                        modifier = if (i == tab) Modifier.focusRequester(tabFocus) else Modifier) {
                        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
                    }
                }
            }
            Box(Modifier.weight(1f))
            NowPlayingChip(ui, onClick = { nav.push(MainScreen.NowPlaying) })
            HSpace(14.dp)
            PlayerChip(ui, onClick = { nav.push(MainScreen.Players) })
        }
        VSpace(20.dp)
        if (kind == "folders") {
            FolderBrowser(vm, ui, nav)
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

@Composable
fun NowPlayingChip(ui: UiState, onClick: () -> Unit) {
    val np = ui.nowPlaying
    FocusSurface(onClick = onClick, shape = androidx.compose.foundation.shape.RoundedCornerShape(50)) {
        Row(Modifier.padding(start = 8.dp, end = 22.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(np.imageUrl, Modifier.size(44.dp), corner = 50.dp)
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
        Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Speaker, null, tint = HiFiColors.Accent, modifier = Modifier.size(24.dp))
            HSpace(10.dp)
            Text(ui.selectedPlayer?.name ?: "Choose player", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 260.dp))
        }
    }
}
