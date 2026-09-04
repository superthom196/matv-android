package io.github.superthom196.matv

import io.github.superthom196.matv.ma.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryPage(
    val items: List<MediaItem> = emptyList(),
    val loading: Boolean = false,
    val end: Boolean = false,
    val error: String? = null,
)

/** Offset-paged cache of the library lists (artists / albums / playlists), kept across navigation. */
class LibraryStore(
    private val scope: CoroutineScope,
    private val fetch: suspend (kind: String, offset: Int, limit: Int) -> List<MediaItem>,
) {
    private val pages = HashMap<String, MutableStateFlow<LibraryPage>>()
    private val jobs = HashMap<String, Job>()
    val pageSize = 60

    fun page(kind: String): StateFlow<LibraryPage> = pages.getOrPut(kind) { MutableStateFlow(LibraryPage()) }

    /** Load the first page if this kind has nothing yet. Reads the store's own state, never a composed snapshot. */
    fun ensureLoaded(kind: String) {
        val cur = page(kind).value
        if (cur.items.isEmpty() && !cur.loading && !cur.end) loadMore(kind)
    }

    fun loadMore(kind: String, force: Boolean = false) {
        val flow = pages.getOrPut(kind) { MutableStateFlow(LibraryPage()) }
        val cur = flow.value
        if (!force && (cur.loading || cur.end)) { android.util.Log.d("LibraryStore", "$kind: skip (loading=${cur.loading} end=${cur.end})"); return }
        if (jobs[kind]?.isActive == true) { android.util.Log.d("LibraryStore", "$kind: skip (job active)"); return }
        android.util.Log.d("LibraryStore", "$kind: load offset=${cur.items.size}")
        flow.update { it.copy(loading = true, error = null) }
        jobs[kind] = scope.launch {
            try {
                val got = fetch(kind, cur.items.size, pageSize)
                android.util.Log.d("LibraryStore", "$kind: got ${got.size}")
                flow.update { it.copy(items = it.items + got, loading = false, end = got.size < pageSize) }
            } catch (e: Exception) {
                android.util.Log.w("LibraryStore", "$kind: failed: $e")
                flow.update { it.copy(loading = false, error = e.message ?: "Failed to load") }
            }
        }
    }

    fun reset() { pages.values.forEach { it.value = LibraryPage() }; jobs.values.forEach { it.cancel() } }
}
