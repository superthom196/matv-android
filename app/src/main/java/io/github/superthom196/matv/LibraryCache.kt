package io.github.superthom196.matv

import android.content.Context
import android.util.Log
import io.github.superthom196.matv.ma.MediaItem
import io.github.superthom196.matv.ma.MediaItemImage
import io.github.superthom196.matv.ma.maJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

private const val TAG = "LibraryCache"

/**
 * The library as it was last seen, kept on disk so the grids can draw straight away on the next
 * launch instead of waiting for the whole album list to page in from the server again. Whatever
 * is cached is shown first and the server's answer replaces it quietly when it differs.
 *
 * One JSON file per kind under `files/library/`, each stamped with the server it came from so a
 * different server never gets another server's albums. Writes go to a temp file and are renamed
 * over the old one, so a crash mid-write leaves the previous copy intact.
 */
class LibraryCache(context: Context) {
    private val dir = File(context.filesDir, "library")

    @Serializable
    private class Envelope(val serverId: String, val items: List<MediaItem>)

    @Serializable
    private class Covers(val serverId: String, val covers: Map<String, MediaItemImage?>)

    /** The cached list for [kind], or null when there is none or it belongs to another server. */
    suspend fun read(kind: String, serverId: String): List<MediaItem>? {
        val text = readFile("$kind.json") ?: return null
        val t0 = System.currentTimeMillis()
        val env = withContext(Dispatchers.Default) {
            runCatching { maJson.decodeFromString(Envelope.serializer(), text) }
                .onFailure { Log.w(TAG, "$kind cache unreadable: ${it.message}") }.getOrNull()
        } ?: return null
        if (env.serverId != serverId) return null
        Log.i(TAG, "$kind: ${env.items.size} items from cache in ${System.currentTimeMillis() - t0} ms")
        return env.items
    }

    suspend fun write(kind: String, serverId: String, items: List<MediaItem>) {
        val text = withContext(Dispatchers.Default) { maJson.encodeToString(Envelope.serializer(), Envelope(serverId, items)) }
        writeFile("$kind.json", text)
    }

    /** Artist key ("provider:item_id") -> the album cover it borrows; null means it was looked up and has none. */
    suspend fun readCovers(serverId: String): Map<String, MediaItemImage?> {
        val text = readFile("artist_covers.json") ?: return emptyMap()
        val c = withContext(Dispatchers.Default) {
            runCatching { maJson.decodeFromString(Covers.serializer(), text) }
                .onFailure { Log.w(TAG, "covers cache unreadable: ${it.message}") }.getOrNull()
        } ?: return emptyMap()
        return if (c.serverId == serverId) c.covers else emptyMap()
    }

    suspend fun writeCovers(serverId: String, covers: Map<String, MediaItemImage?>) {
        val text = withContext(Dispatchers.Default) { maJson.encodeToString(Covers.serializer(), Covers(serverId, covers)) }
        writeFile("artist_covers.json", text)
    }

    /** Forget everything: the user is changing servers. */
    suspend fun clear() {
        withContext(Dispatchers.IO) { dir.deleteRecursively() }
    }

    private suspend fun readFile(name: String): String? = withContext(Dispatchers.IO) {
        val f = File(dir, name)
        if (!f.isFile) null else runCatching { f.readText() }.onFailure { Log.w(TAG, "read $name: ${it.message}") }.getOrNull()
    }

    private suspend fun writeFile(name: String, text: String) = withContext(Dispatchers.IO) {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "$name.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(File(dir, name))) throw IllegalStateException("rename failed")
        }.onFailure { Log.w(TAG, "write $name: ${it.message}") }
        Unit
    }
}
