package io.github.superthom196.matv

import android.util.Log
import io.github.superthom196.matv.ma.MediaItem
import io.github.superthom196.matv.ma.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock

private const val TAG = "HiResIndex"

/** Albums checked so far, and how many are left to check. */
data class HiResScan(val done: Int = 0, val total: Int = 0, val running: Boolean = false)

/**
 * Which albums are hi-res.
 *
 * The server cannot answer this directly: a library album's own `audio_format` is an unfilled
 * placeholder that reports 16/44.1 for everything, and only its tracks carry the real numbers. So
 * this walks the album list once, asks each album for its tracks, and remembers the verdict. The
 * answers are cached on disk and every album is asked about exactly once, so the cost is paid on
 * the first run and new albums trickle in after that.
 */
class HiResIndex(
    private val scope: CoroutineScope,
    private val prefs: Prefs,
    private val tracksOf: suspend (MediaItem) -> List<MediaItem>,
) {
    private val _hiRes = MutableStateFlow<Set<String>>(emptySet())
    val hiRes: StateFlow<Set<String>> = _hiRes.asStateFlow()

    private val _scan = MutableStateFlow(HiResScan())
    val scan: StateFlow<HiResScan> = _scan.asStateFlow()

    /** Every album already asked about, hi-res or not, so a second pass costs nothing. */
    private var checked: Set<String> = emptySet()
    private val lock = Mutex()
    private var job: Job? = null

    suspend fun restore() {
        checked = prefs.hiResChecked()
        _hiRes.value = prefs.hiResAlbums()
        Log.d(TAG, "restored ${_hiRes.value.size} hi-res of ${checked.size} checked")
    }

    /** Ask about anything in [albums] not asked about before. Safe to call repeatedly. */
    fun ensureScanned(albums: List<MediaItem>) {
        if (job?.isActive == true) return
        val pending = albums.filter { key(it) !in checked }
        if (pending.isEmpty()) return
        job = scope.launch {
            val gate = Semaphore(3)
            var done = 0
            _scan.value = HiResScan(0, pending.size, running = true)
            val foundHiRes = mutableSetOf<String>()
            val foundChecked = mutableSetOf<String>()
            pending.chunked(60).forEach { chunk ->
                chunk.map { album ->
                    launch {
                        gate.withPermit {
                            val k = key(album)
                            val hi = runCatching { tracksOf(album).any { it.isHiResTrack } }
                                .onFailure { Log.w(TAG, "tracks for ${album.name} failed: ${it.message}") }
                                .getOrNull()
                            lock.withLock {
                                // A failed lookup is left unchecked so a later pass retries it.
                                if (hi != null) {
                                    foundChecked += k
                                    if (hi) foundHiRes += k
                                }
                                done++
                                _scan.value = HiResScan(done, pending.size, running = true)
                            }
                        }
                    }
                }.forEach { it.join() }
                // Persist as we go: a scan interrupted by a restart keeps what it learned.
                lock.withLock { commit(foundHiRes, foundChecked) }
            }
            _scan.value = HiResScan(done, pending.size, running = false)
            Log.d(TAG, "scan finished: ${_hiRes.value.size} hi-res of ${checked.size} checked")
        }
    }

    private suspend fun commit(newHiRes: MutableSet<String>, newChecked: MutableSet<String>) {
        if (newChecked.isEmpty()) return
        checked = checked + newChecked
        _hiRes.value = _hiRes.value + newHiRes
        prefs.saveHiRes(_hiRes.value, checked)
        newHiRes.clear()
        newChecked.clear()
    }

    companion object {
        fun key(item: MediaItem): String = "${item.provider}:${item.itemId}"

        /**
         * Whether to mark this tile. The media type has to be checked: artists and albums are both
         * numbered from the `library` provider, so artist 311 and album 311 share a key and an
         * artist would otherwise wear an album's badge.
         */
        fun marks(item: MediaItem, hiRes: Set<String>): Boolean =
            item.mediaType == "album" && key(item) in hiRes
    }
}
