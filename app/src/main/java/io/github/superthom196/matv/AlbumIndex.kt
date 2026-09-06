package io.github.superthom196.matv

import android.util.Log
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
private const val TAG = "AlbumIndex"

/**
 * The whole Albums library, sorted by artist and indexed by letter.
 * Offset pagination and a client-side re-sort don't mix, so this loads everything up front
 * instead of paging like [LibraryStore] does for artists/folders.
 */
class AlbumIndex(
    private val scope: CoroutineScope,
    private val name: String,                       // for log lines, e.g. "albums"
    private val count: suspend () -> Int?,
    private val fetch: suspend (offset: Int, limit: Int) -> List<MediaItem>,
    /** Primary sort key and letter bucket: album artist for albums, the artist's own name for artists. */
    private val sortKey: (MediaItem) -> String = ::artistSortKey,
    private val cached: (suspend () -> List<MediaItem>?)? = null,   // last known copy from disk
    private val persist: (suspend (List<MediaItem>) -> Unit)? = null, // keep the fresh list for next time
) {
    private val _state = MutableStateFlow(AlbumsByArtist())
    val state: StateFlow<AlbumsByArtist> = _state.asStateFlow()
    private var job: Job? = null

    fun ensureLoaded() {
        val cur = _state.value
        if (cur.ready || cur.loading) return
        load(useCache = true)
    }

    fun reload() = load(useCache = false)

    private fun load(useCache: Boolean) {
        job?.cancel()
        val cur = _state.value
        _state.value = if (cur.ready) cur.copy(loading = true, error = null) else AlbumsByArtist(loading = true)
        job = scope.launch {
            val t0 = System.currentTimeMillis()
            var shown: List<MediaItem>? = null
            if (useCache) {
                val fromDisk = runCatching { cached?.invoke() }.getOrNull()
                if (!fromDisk.isNullOrEmpty()) {
                    val (sorted, anchors) = withContext(Dispatchers.Default) { buildAlbumIndex(fromDisk, sortKey) }
                    shown = sorted
                    _state.value = AlbumsByArtist(items = sorted, anchors = anchors, loading = true, ready = true, loaded = sorted.size, total = sorted.size)
                    Log.i(TAG, "$name: ${sorted.size} from cache, on screen after ${System.currentTimeMillis() - t0} ms")
                }
            }
            try {
                val total = count()
                if (shown == null) _state.update { it.copy(total = total) }
                val seen = HashSet<String>()
                val all = ArrayList<MediaItem>()
                var offset = 0
                while (true) {
                    val batch = fetch(offset, BATCH)
                    for (item in batch) if (seen.add("${item.provider}:${item.itemId}")) all.add(item)
                    offset += batch.size
                    if (shown == null) _state.update { it.copy(loaded = all.size) }
                    if (batch.size < BATCH || offset >= HARD_CAP) break
                }
                val tFetch = System.currentTimeMillis()
                val (sorted, anchors) = withContext(Dispatchers.Default) { buildAlbumIndex(all, sortKey) }
                val tSort = System.currentTimeMillis()
                val changed = shown == null || shown != sorted
                if (changed) {
                    _state.value = AlbumsByArtist(items = sorted, anchors = anchors, ready = true, loaded = sorted.size, total = total)
                } else {
                    _state.update { it.copy(loading = false, total = total) }
                }
                Log.i(TAG, "$name: ${all.size} from server in ${tFetch - t0} ms, sorted in ${tSort - tFetch} ms, ${if (changed) "replaced" else "unchanged"}")
                if (changed || shown == null) {
                    runCatching { persist?.invoke(all) }.onFailure { Log.w(TAG, "$name: cache write failed: ${it.message}") }
                }
            } catch (e: Exception) {
                if (shown == null) {
                    _state.update { it.copy(loading = false, error = e.message ?: "Failed to load albums") }
                } else {
                    // Server refresh failed but the cached list is already on screen: stay quiet and usable.
                    _state.update { it.copy(loading = false) }
                }
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

private fun dropThe(key: String): String {
    val prefix = "the "
    return if (key.length > prefix.length && key.startsWith(prefix)) key.substring(prefix.length) else key
}

/** Primary (first-listed) artist name, folded and with a leading "The " dropped for filing purposes. */
internal fun artistSortKey(item: MediaItem): String =
    dropThe(foldKey(item.artists?.firstOrNull { it.name.isNotBlank() }?.name ?: ""))

/** An artist's own name (sort_name when the server has one), for the Artists index. */
internal fun artistNameKey(item: MediaItem): String = dropThe(foldKey(item.sortName ?: item.name))

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
internal fun buildAlbumIndex(raw: List<MediaItem>, sortKey: (MediaItem) -> String = ::artistSortKey): Pair<List<MediaItem>, List<LetterAnchor>> {
    val keyed = raw.map { item ->
        val artistKey = sortKey(item)
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
