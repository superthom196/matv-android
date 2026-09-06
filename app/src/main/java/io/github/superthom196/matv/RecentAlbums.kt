package io.github.superthom196.matv

import io.github.superthom196.matv.ma.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val PAGE = 250

/**
 * Albums newest-first, paged in as the row is scrolled. The server orders them —
 * `timestamp_added_desc` — because the album records carry no date for us to sort on ourselves.
 */
class RecentAlbums(
    private val scope: CoroutineScope,
    private val fetch: suspend (offset: Int, limit: Int) -> List<MediaItem>,
    private val cached: (suspend () -> List<MediaItem>?)? = null,      // page 0 as last seen, from disk
    private val persist: (suspend (List<MediaItem>) -> Unit)? = null,  // keep page 0 for next launch
) {
    private val _items = MutableStateFlow<List<MediaItem>>(emptyList())
    val items: StateFlow<List<MediaItem>> = _items.asStateFlow()

    private var job: Job? = null
    private var exhausted = false

    /** Load the first page, or the next one once the row is scrolled near its end. */
    fun ensureLoaded(upTo: Int = 0) {
        if (exhausted || job?.isActive == true) return
        if (_items.value.isEmpty()) { job = scope.launch { loadFirstPage() }; return }
        if (upTo < _items.value.size - 40) return
        job = scope.launch {
            val offset = _items.value.size
            val page = runCatching { fetch(offset, PAGE) }.getOrElse { emptyList() }
            if (page.size < PAGE) exhausted = true
            if (page.isNotEmpty()) _items.value = _items.value + page
        }
    }

    /** The cached page shows at once; the server's own page 0 replaces it quietly if it differs. */
    private suspend fun loadFirstPage() {
        val fromDisk = runCatching { cached?.invoke() }.getOrNull()
        val hadCache = !fromDisk.isNullOrEmpty()
        if (hadCache) _items.value = fromDisk!!
        val showing = _items.value
        val page = runCatching { fetch(0, PAGE) }.getOrNull() ?: return // offline: leave the cache as-is
        if (page != showing) {
            _items.value = page
            exhausted = page.size < PAGE
        }
        if (!hadCache || page != showing) runCatching { persist?.invoke(page) }
    }

    fun reload() {
        job?.cancel()
        exhausted = false
        _items.value = emptyList()
        job = scope.launch {
            val page = runCatching { fetch(0, PAGE) }.getOrElse { emptyList() }
            if (page.size < PAGE) exhausted = true
            _items.value = page
            runCatching { persist?.invoke(page) }
        }
    }
}
