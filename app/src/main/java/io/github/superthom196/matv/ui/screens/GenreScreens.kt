package io.github.superthom196.matv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.superthom196.matv.AlbumScan
import io.github.superthom196.matv.AppViewModel
import io.github.superthom196.matv.ma.MediaItem
import io.github.superthom196.matv.ui.Artwork
import io.github.superthom196.matv.ui.FocusSurface
import io.github.superthom196.matv.ui.HiFiColors
import io.github.superthom196.matv.ui.MainScreen
import io.github.superthom196.matv.ui.Nav
import io.github.superthom196.matv.ui.VSpace

/**
 * Genres that actually have albums behind them, biggest first.
 *
 * The list is built from the tags on your own tracks, gathered by the album scan — the server keeps
 * a list of 99 genres but cannot say which of them hold anything, so its list is used only to
 * borrow artwork where a name happens to match.
 */
@Composable
fun GenreGrid(vm: AppViewModel, nav: Nav) {
    val perAlbum by vm.albumScan.genres.collectAsStateWithLifecycle()
    val albums by vm.albums.state.collectAsStateWithLifecycle()
    val scan by vm.albumScan.scan.collectAsStateWithLifecycle()
    val artwork by vm.genres.state.collectAsStateWithLifecycle()

    val counts = remember(perAlbum, albums.items) { genreCounts(perAlbum, albums.items) }
    val art = remember(artwork.items) { artwork.items.associateBy { it.name.lowercase() } }

    if (counts.isEmpty()) {
        Column(Modifier.fillMaxSize()) {
            Text(
                if (scan.running) "Reading genres off your albums…" else "No genres tagged yet.",
                style = MaterialTheme.typography.bodyLarge, color = HiFiColors.Muted,
            )
            if (scan.running) {
                VSpace(8.dp)
                Text("${scan.done} of ${scan.total}", style = MaterialTheme.typography.bodyMedium, color = HiFiColors.Muted)
            }
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(6),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 48.dp),
        modifier = Modifier.fillMaxSize().focusRestorer(),
    ) {
        items(counts, key = { it.first }) { (genre, list) ->
            GenreTile(
                genre = genre,
                count = list.size,
                imageUrl = art[genre.lowercase()]?.let { vm.imageUrl(it, 256) },
                onClick = { nav.push(MainScreen.Genre(genre)) },
            )
        }
    }
}

/** Albums in one genre. */
@Composable
fun GenreAlbumsScreen(vm: AppViewModel, nav: Nav, genre: String) {
    val perAlbum by vm.albumScan.genres.collectAsStateWithLifecycle()
    val albums by vm.albums.state.collectAsStateWithLifecycle()
    val list = remember(perAlbum, albums.items, genre) {
        genreCounts(perAlbum, albums.items).firstOrNull { it.first == genre }?.second.orEmpty()
    }
    Column(Modifier.fillMaxSize().padding(start = 28.dp, end = 28.dp, top = 20.dp)) {
        Text(genre, style = MaterialTheme.typography.headlineMedium)
        Text("${list.size} albums", style = MaterialTheme.typography.bodyMedium, color = HiFiColors.Muted)
        VSpace(10.dp)
        SectionedGrid(vm, nav, listOf("" to list), columns = 6, emptyText = "Nothing in this genre.")
    }
}

/** Genre -> its albums, biggest genre first; an album counts under every genre its tracks carry. */
private fun genreCounts(perAlbum: Map<String, List<String>>, albums: List<MediaItem>): List<Pair<String, List<MediaItem>>> {
    if (perAlbum.isEmpty() || albums.isEmpty()) return emptyList()
    val byKey = albums.associateBy { AlbumScan.key(it) }
    val out = HashMap<String, MutableList<MediaItem>>()
    perAlbum.forEach { (key, genres) ->
        val album = byKey[key] ?: return@forEach
        genres.forEach { g -> out.getOrPut(g) { mutableListOf() }.add(album) }
    }
    return out.entries.sortedWith(compareByDescending<Map.Entry<String, MutableList<MediaItem>>> { it.value.size }.thenBy { it.key })
        .map { it.key to it.value.toList() }
}

@Composable
private fun GenreTile(genre: String, count: Int, imageUrl: String?, onClick: () -> Unit) {
    FocusSurface(onClick = onClick, container = HiFiColors.Surface, scale = 1.05f) {
        Column(Modifier.padding(7.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                if (imageUrl != null) {
                    Artwork(imageUrl, Modifier.fillMaxSize(), corner = 10.dp)
                } else {
                    Text(
                        genre, style = MaterialTheme.typography.titleMedium, color = HiFiColors.Accent,
                        textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
            VSpace(6.dp)
            Text(genre, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$count albums", style = MaterialTheme.typography.bodySmall, color = HiFiColors.Muted)
        }
    }
}
