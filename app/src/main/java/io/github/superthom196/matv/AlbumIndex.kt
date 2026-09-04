package io.github.superthom196.matv

import io.github.superthom196.matv.ma.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Normalizer

/** Where the currently visible album falls in the alphabet, and how many albums share that letter. */
data class LetterAnchor(val label: String, val index: Int, val count: Int)

data class AlbumsByArtist(
    val items: List<MediaItem> = emptyList(),
    val anchors: List<LetterAnchor> = emptyList(),
    val loading: Boolean = false,
    val ready: Boolean = false,
    val loaded: Int = 0,
    val total: Int? = null,
    val error: String? = null,
)

private const val BATCH = 200
private const val HARD_CAP = 20_000

/**
 * The whole Albums library, sorted by artist and indexed by letter.
 * Offset pagination and a client-side re-sort don't mix, so this loads everything up front
 * instead of paging like [LibraryStore] does for artists/folders.
 */
class AlbumIndex(
    private val scope: CoroutineScope,
    private val count: suspend () -> Int?,
    private val fetch: suspend (offset: Int, limit: Int) -> List<MediaItem>,
) {
    private val _state = MutableStateFlow(AlbumsByArtist())
    val state: StateFlow<AlbumsByArtist> = _state.asStateFlow()
    private var job: Job? = null

    fun ensureLoaded() {
        val cur = _state.value
        if (cur.ready || cur.loading) return
        load()
    }

    fun reload() = load()

    private fun load() {
        job?.cancel()
        _state.value = AlbumsByArtist(loading = true)
        job = scope.launch {
            try {
                val total = count()
                _state.update { it.copy(total = total) }
                val seen = HashSet<String>()
                val all = ArrayList<MediaItem>()
                var offset = 0
                while (true) {
                    val batch = fetch(offset, BATCH)
                    for (item in batch) if (seen.add("${item.provider}:${item.itemId}")) all.add(item)
                    offset += batch.size
                    _state.update { it.copy(loaded = all.size) }
                    if (batch.size < BATCH || offset >= HARD_CAP) break
                }
                val (sorted, anchors) = withContext(Dispatchers.Default) { buildAlbumIndex(all) }
                _state.value = AlbumsByArtist(items = sorted, anchors = anchors, ready = true, loaded = sorted.size, total = total)
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Failed to load albums") }
            }
        }
    }

    fun reset() {
        job?.cancel()
        _state.value = AlbumsByArtist()
    }
}

private val whitespaceRegex = Regex("\\s+")
private val nonWordRegex = Regex("[^a-z0-9 ]")

/** Lowercase, strip accents/punctuation, collapse whitespace: "Björk" and "Ólafur Arnalds" fold predictably. */
internal fun foldKey(raw: String): String {
    val normalized = Normalizer.normalize(raw.trim().lowercase(), Normalizer.Form.NFD)
        .filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
    return nonWordRegex.replace(normalized, "").let { whitespaceRegex.replace(it, " ") }.trim()
}

/** Primary (first-listed) artist name, folded and with a leading "The " dropped for filing purposes. */
internal fun artistSortKey(item: MediaItem): String {
    val name = item.artists?.firstOrNull { it.name.isNotBlank() }?.name ?: ""
    val key = foldKey(name)
    val prefix = "the "
    return if (key.length > prefix.length && key.startsWith(prefix)) key.substring(prefix.length) else key
}

/** The letter this sort key files under: 'A'..'Z', or '#' for anything else (blank, digits, symbols). */
internal fun bucketOf(key: String): Char {
    val c = key.firstOrNull() ?: return '#'
    return if (c in 'a'..'z') c.uppercaseChar() else '#'
}

/** '#' sorts after Z, so real artists start at the top of the grid instead of behind metadata gaps. */
internal fun rankOf(label: String): Int = if (label == "#") 27 else (label[0] - 'A' + 1)

private data class Keyed(val item: MediaItem, val artistKey: String, val titleKey: String, val bucket: Char)

private val albumComparator = compareBy<Keyed>(
    { rankOf(it.bucket.toString()) },
    { it.artistKey },
    { it.titleKey },
    { it.item.year ?: Int.MAX_VALUE },
    { it.item.provider },
    { it.item.itemId },
)

/** Sorts by artist (then album title, then year) and records where each letter's run begins. */
internal fun buildAlbumIndex(raw: List<MediaItem>): Pair<List<MediaItem>, List<LetterAnchor>> {
    val keyed = raw.map { item ->
        val artistKey = artistSortKey(item)
        Keyed(item, artistKey, foldKey(item.sortName ?: item.name), bucketOf(artistKey))
    }.sortedWith(albumComparator)

    val anchors = ArrayList<LetterAnchor>()
    var i = 0
    while (i < keyed.size) {
        val label = keyed[i].bucket.toString()
        var j = i
        while (j < keyed.size && keyed[j].bucket.toString() == label) j++
        anchors.add(LetterAnchor(label, i, j - i))
        i = j
    }
    return keyed.map { it.item } to anchors
}

/** First index at/after a letter — landing past an empty letter is what makes every OK press useful. */
internal fun indexForLetter(anchors: List<LetterAnchor>, label: String): Int {
    val target = rankOf(label)
    return anchors.firstOrNull { rankOf(it.label) >= target }?.index ?: (anchors.lastOrNull()?.let { it.index + it.count } ?: 0)
}

/** The letter the grid is currently showing, given the topmost visible item's index. */
internal fun labelAtIndex(anchors: List<LetterAnchor>, index: Int): String =
    anchors.lastOrNull { it.index <= index }?.label ?: anchors.firstOrNull()?.label ?: "A"

/** Previous/next populated letter from wherever the grid is now (snaps to the start of the current run first). */
internal fun stepAnchor(anchors: List<LetterAnchor>, currentIndex: Int, forward: Boolean): Int? {
    if (anchors.isEmpty()) return null
    val currentPos = anchors.indexOfLast { it.index <= currentIndex }.coerceAtLeast(0)
    return if (forward) {
        anchors.getOrNull(currentPos + 1)?.index
    } else {
        if (currentIndex > anchors[currentPos].index) anchors[currentPos].index else anchors.getOrNull(currentPos - 1)?.index
    }
}
