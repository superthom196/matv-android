package io.github.superthom196.matv.ui.screens

import io.github.superthom196.matv.ui.StatusDot
import io.github.superthom196.matv.ui.MenuAction
import io.github.superthom196.matv.ui.ActionMenu
import io.github.superthom196.matv.ma.Player
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.superthom196.matv.AppViewModel
import io.github.superthom196.matv.HiResIndex
import io.github.superthom196.matv.BuildConfig
import io.github.superthom196.matv.UiState
import io.github.superthom196.matv.ma.MIN_SCHEMA_VERSION
import io.github.superthom196.matv.ma.MediaItem
import io.github.superthom196.matv.ui.FocusSurface
import io.github.superthom196.matv.ui.HSpace
import io.github.superthom196.matv.ui.HiFiColors
import io.github.superthom196.matv.ui.MainScreen
import io.github.superthom196.matv.ui.MediaCard
import io.github.superthom196.matv.ui.Nav
import io.github.superthom196.matv.ui.PillButton
import io.github.superthom196.matv.ui.PlayOptionsMenu
import io.github.superthom196.matv.ui.SectionLabel
import io.github.superthom196.matv.ui.TvTextField
import io.github.superthom196.matv.ui.VSpace

/** Sections of cards (artists round) in one scrolling grid; used by Favourites and Search. */
@Composable
fun SectionedGrid(vm: AppViewModel, nav: Nav, sections: List<Pair<String, List<MediaItem>>>, columns: Int = 6, emptyText: String) {
    var menuFor by remember { mutableStateOf<MediaItem?>(null) }
    menuFor?.let { PlayOptionsMenu(vm, it, onDismiss = { menuFor = null }) }
    val hiResAlbums by vm.hiRes.hiRes.collectAsStateWithLifecycle()
    val nonEmpty = sections.filter { it.second.isNotEmpty() }
    if (nonEmpty.isEmpty()) { Text(emptyText, style = MaterialTheme.typography.bodyLarge, color = HiFiColors.Muted); return }
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(bottom = 48.dp, top = 4.dp), modifier = Modifier.fillMaxSize().focusRestorer(),
    ) {
        nonEmpty.forEach { (title, list) ->
            item(span = { GridItemSpan(maxLineSpan) }, key = "h:$title") { SectionLabel(title, Modifier.padding(start = 8.dp, top = 10.dp, bottom = 2.dp)) }
            items(list, key = { "$title:${it.provider}:${it.itemId}" }) { item ->
                val round = item.mediaType == "artist"
                val own = vm.imageUrl(item, 256)
                val url = if (own == null && round) vm.artistCover(item, 256).collectAsStateWithLifecycle().value else own
                MediaCard(item, url, onClick = { openOrPlay(vm, nav, item) }, round = round, onLongClick = { menuFor = item }, hiRes = HiResIndex.marks(item, hiResAlbums))
            }
        }
    }
}

@Composable
fun FavouritesTab(vm: AppViewModel, nav: Nav) {
    val fav by vm.favourites.collectAsStateWithLifecycle()
    LaunchedEffect(fav.loaded) { if (!fav.loaded) vm.ensureFavouritesLoaded() }
    when {
        fav.error != null -> Text(fav.error!!, style = MaterialTheme.typography.bodyLarge, color = HiFiColors.Danger)
        fav.loading && !fav.loaded -> Text("Loading favourites…", style = MaterialTheme.typography.bodyLarge, color = HiFiColors.Muted)
        else -> SectionedGrid(vm, nav, listOf("Artists" to fav.artists, "Albums" to fav.albums, "Tracks" to fav.tracks, "Playlists" to fav.playlists),
            emptyText = "No favourites yet. Long-press anything and choose \"Add to favourites\".")
    }
}

@Composable
fun SearchScreen(vm: AppViewModel, ui: UiState, nav: Nav) {
    val st by vm.search.collectAsStateWithLifecycle()
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (st.query.isBlank()) runCatching { field.requestFocus() } }
    Column(Modifier.fillMaxSize().padding(start = 28.dp, end = 28.dp, top = 20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Search", style = MaterialTheme.typography.headlineMedium)
            HSpace(24.dp)
            TvTextField(st.query, { vm.setSearchQuery(it) }, placeholder = "artist, album or track", imeAction = ImeAction.Search,
                modifier = Modifier.width(560.dp).focusRequester(field))
            HSpace(16.dp)
            if (st.searching) Text("Searching…", style = MaterialTheme.typography.labelMedium, color = HiFiColors.Muted)
        }
        VSpace(12.dp)
        val err = st.error
        when {
            err != null -> Text(err, style = MaterialTheme.typography.bodyLarge, color = HiFiColors.Danger)
            st.query.isBlank() -> Text("Type to search your library. OK on the box opens the keyboard.", style = MaterialTheme.typography.bodyMedium, color = HiFiColors.Muted)
            else -> SectionedGrid(vm, nav, listOf(
                "Artists" to (st.results["artists"] ?: emptyList()), "Albums" to (st.results["albums"] ?: emptyList()),
                "Tracks" to (st.results["tracks"] ?: emptyList()), "Playlists" to (st.results["playlists"] ?: emptyList()),
                "Radio" to (st.results["radio"] ?: emptyList()),
            ), emptyText = if (st.searching) "" else "Nothing found for \"${st.query}\".")
        }
    }
}

@Composable
private fun SettingRow(label: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusSurface(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.bodyMedium, color = HiFiColors.Accent)
        }
    }
}

@Composable
fun SettingsScreen(vm: AppViewModel, ui: UiState, nav: Nav) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    var menuFor by remember { mutableStateOf<Player?>(null) }
    menuFor?.let { p ->
        val selected = ui.selectedPlayer
        val synced = p.syncedTo != null || !p.groupMembers.isNullOrEmpty()
        ActionMenu(p.name, p.provider.replace('_', ' '), buildList {
            add(MenuAction("Use this player") { vm.selectPlayer(p.playerId) })
            if (selected != null && selected.playerId != p.playerId) add(MenuAction("Sync with ${selected.name}") { vm.groupPlayer(p.playerId, selected.playerId) })
            if (synced) add(MenuAction("Unsync", danger = true) { vm.ungroupPlayer(p.playerId) })
        }, onDismiss = { menuFor = null })
    }
    val tabNames = listOf("artists" to "Artists", "albums" to "Albums", "folders" to "Folders", "favourites" to "Favourites")
    val players = ui.selectablePlayers
    Column(Modifier.fillMaxSize().padding(start = 28.dp, end = 28.dp, top = 20.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        VSpace(10.dp)
        Column(Modifier.width(760.dp).fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionLabel("Play on  ·  ${players.size} players found")
            if (players.isEmpty()) Text("No players yet. Check your endpoints are running and enabled in Music Assistant.", style = MaterialTheme.typography.bodyMedium, color = HiFiColors.Muted)
            players.forEachIndexed { i, p ->
                val isSel = p.playerId == ui.selectedPlayerId
                FocusSurface(
                    onClick = { vm.selectPlayer(p.playerId) }, onLongClick = { menuFor = p },
                    modifier = (if (i == 0) Modifier.focusRequester(first) else Modifier).fillMaxWidth(),
                    container = if (isSel) HiFiColors.SurfaceHigh else HiFiColors.Surface,
                ) {
                    Row(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(when { p.isPlaying -> HiFiColors.Good; p.powered == false -> HiFiColors.Muted; else -> HiFiColors.Accent })
                        HSpace(12.dp)
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.titleMedium)
                            val detail = listOfNotNull(
                                when { p.isPlaying -> "Playing: ${p.currentMedia?.title ?: ""}".trimEnd(':', ' '); p.playbackState == "paused" -> "Paused"; p.powered == false -> "Off"; else -> "Idle" },
                                p.provider.replace('_', ' '), p.volumeLevel?.let { "vol $it" },
                                if (p.syncedTo != null) "synced" else null, p.groupMembers?.takeIf { it.isNotEmpty() }?.let { "group of ${it.size}" },
                            ).joinToString("  ·  ")
                            Text(detail, style = MaterialTheme.typography.bodySmall, color = HiFiColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (isSel) Text("Selected", style = MaterialTheme.typography.labelMedium, color = HiFiColors.Accent)
                    }
                }
            }
            Text("OK selects a player. Long-press to sync it with the selected one.", style = MaterialTheme.typography.bodySmall, color = HiFiColors.Muted)
            VSpace(10.dp)
            SectionLabel("Library")
            SettingRow("Open on", tabNames.firstOrNull { it.first == s.defaultTab }?.second ?: "Artists") {
                val i = tabNames.indexOfFirst { it.first == s.defaultTab }; vm.updateSettings { it.copy(defaultTab = tabNames[(i + 1) % tabNames.size].first) }
            }
            SettingRow("Playlists tab", if (s.showPlaylists) "On" else "Off") { vm.updateSettings { it.copy(showPlaylists = !it.showPlaylists) } }
            SettingRow("Radio tab", if (s.showRadio) "On" else "Off") { vm.updateSettings { it.copy(showRadio = !it.showRadio) } }
            VSpace(10.dp)
            SectionLabel("Server")
            SettingRow("Server", "${ui.server?.name ?: ""}  ·  ${ui.baseUrl ?: ""}") { }
            SettingRow("Signed in as", ui.username ?: "") { }
            VSpace(10.dp)
            SectionLabel("About")
            SettingRow("MATV", "v${BuildConfig.VERSION_NAME}") { }
            SettingRow("Music Assistant", "v${ui.server?.serverVersion ?: "?"}  ·  schema ${ui.server?.schemaVersion ?: "?"} (needs ≥ $MIN_SCHEMA_VERSION)") { }
            VSpace(16.dp)
            Row { PillButton("Forget server and sign out", onClick = { vm.forgetServer() }) }
            VSpace(40.dp)
        }
    }
}
