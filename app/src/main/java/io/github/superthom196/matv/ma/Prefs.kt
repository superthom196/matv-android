package io.github.superthom196.matv.ma

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "hifitv")

data class SavedConfig(
    val baseUrl: String? = null,
    val token: String? = null,
    val serverId: String? = null,
    val serverName: String? = null,
    val username: String? = null,
    val playerId: String? = null,
) {
    val hasServer: Boolean get() = !baseUrl.isNullOrBlank() && !token.isNullOrBlank()
}

/** User-facing settings (Settings screen). */
data class AppSettings(
    val defaultTab: String = "artists",
    val showArtists: Boolean = true,
    val showAlbums: Boolean = true,
    val showFolders: Boolean = true,
    val showFavourites: Boolean = true,
    val showGenres: Boolean = true,
    val showPlaylists: Boolean = false,
    val showRadio: Boolean = false,
) {
    /**
     * The tabs to show, in header order. Never empty: turning the last one off would leave nothing
     * to browse, so Artists comes back rather than stranding the user on a blank screen.
     */
    val tabs: List<Pair<String, String>>
        get() = listOfNotNull(
            if (showArtists) "artists" to "Artists" else null,
            if (showAlbums) "albums" to "Albums" else null,
            if (showFolders) "folders" to "Folders" else null,
            if (showFavourites) "favourites" to "Favourites" else null,
            if (showGenres) "genres" to "Genres" else null,
            if (showPlaylists) "playlists" to "Playlists" else null,
            if (showRadio) "radio" to "Radio" else null,
        ).ifEmpty { listOf("artists" to "Artists") }
}

class Prefs(private val context: Context) {
    private object K {
        val defaultTab = stringPreferencesKey("default_tab")
        val showArtists = booleanPreferencesKey("show_artists")
        val showAlbums = booleanPreferencesKey("show_albums")
        val showFolders = booleanPreferencesKey("show_folders")
        val showFavourites = booleanPreferencesKey("show_favourites")
        val showGenres = booleanPreferencesKey("show_genres")
        val showPlaylists = booleanPreferencesKey("show_playlists")
        val showRadio = booleanPreferencesKey("show_radio")
        val baseUrl = stringPreferencesKey("base_url")
        val token = stringPreferencesKey("token")
        val serverId = stringPreferencesKey("server_id")
        val serverName = stringPreferencesKey("server_name")
        val username = stringPreferencesKey("username")
        val playerId = stringPreferencesKey("player_id")
        // Hi-res verdicts, cached so the album scan runs once rather than on every launch. Kept per
        // server: album keys are "provider:item_id", and "library:311" is a different album on a
        // different server, so one server's verdicts must never be read back as another's.
        fun hiResAlbums(serverId: String) = stringSetPreferencesKey("hires_albums:$serverId")
        fun hiResChecked(serverId: String) = stringSetPreferencesKey("hires_checked:$serverId")
        fun albumGenres(serverId: String) = stringPreferencesKey("album_genres:$serverId")
        /** A marker for the code that wrote the scan, not data, so it stays global. */
        val scanVersion = intPreferencesKey("scan_version")
    }

    suspend fun hiResAlbums(serverId: String): Set<String> = context.dataStore.data.first()[K.hiResAlbums(serverId)].orEmpty()

    suspend fun hiResChecked(serverId: String): Set<String> = context.dataStore.data.first()[K.hiResChecked(serverId)].orEmpty()

    /** Album key -> genres, as JSON: genre names are free text, so no delimiter is safe. */
    suspend fun albumGenres(serverId: String): Map<String, List<String>> {
        val raw = context.dataStore.data.first()[K.albumGenres(serverId)] ?: return emptyMap()
        return runCatching { maJson.decodeFromString<Map<String, List<String>>>(raw) }.getOrDefault(emptyMap())
    }

    suspend fun saveAlbumGenres(serverId: String, map: Map<String, List<String>>) {
        val raw = maJson.encodeToString(map)
        context.dataStore.edit { it[K.albumGenres(serverId)] = raw }
    }

    suspend fun scanVersion(): Int = context.dataStore.data.first()[K.scanVersion] ?: 0

    suspend fun saveScanVersion(v: Int) { context.dataStore.edit { it[K.scanVersion] = v } }

    suspend fun saveHiRes(serverId: String, hiRes: Set<String>, checked: Set<String>) {
        context.dataStore.edit { it[K.hiResAlbums(serverId)] = hiRes; it[K.hiResChecked(serverId)] = checked }
    }

    val config: Flow<SavedConfig> = context.dataStore.data.map { p ->
        SavedConfig(p[K.baseUrl], p[K.token], p[K.serverId], p[K.serverName], p[K.username], p[K.playerId])
    }

    suspend fun current(): SavedConfig = config.first()

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            defaultTab = p[K.defaultTab] ?: "artists",
            showArtists = p[K.showArtists] ?: true,
            showAlbums = p[K.showAlbums] ?: true,
            showFolders = p[K.showFolders] ?: true,
            showFavourites = p[K.showFavourites] ?: true,
            showGenres = p[K.showGenres] ?: true,
            showPlaylists = p[K.showPlaylists] ?: false,
            showRadio = p[K.showRadio] ?: false,
        )
    }

    suspend fun saveSettings(s: AppSettings) {
        context.dataStore.edit {
            it[K.defaultTab] = s.defaultTab
            it[K.showArtists] = s.showArtists; it[K.showAlbums] = s.showAlbums
            it[K.showFolders] = s.showFolders; it[K.showFavourites] = s.showFavourites
            it[K.showGenres] = s.showGenres
            it[K.showPlaylists] = s.showPlaylists; it[K.showRadio] = s.showRadio
        }
    }

    suspend fun saveServer(baseUrl: String, token: String, serverId: String, serverName: String?, username: String) {
        context.dataStore.edit {
            it[K.baseUrl] = baseUrl; it[K.token] = token; it[K.serverId] = serverId
            if (serverName != null) it[K.serverName] = serverName else it.remove(K.serverName)
            it[K.username] = username
        }
    }

    suspend fun savePlayer(playerId: String) { context.dataStore.edit { it[K.playerId] = playerId } }

    suspend fun clearServer() {
        context.dataStore.edit { it.remove(K.baseUrl); it.remove(K.token); it.remove(K.serverId); it.remove(K.serverName); it.remove(K.playerId) }
    }
}
