package io.github.superthom196.matv.ma

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
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
    val showPlaylists: Boolean = false,
    val showRadio: Boolean = false,
)

class Prefs(private val context: Context) {
    private object K {
        val defaultTab = stringPreferencesKey("default_tab")
        val showPlaylists = booleanPreferencesKey("show_playlists")
        val showRadio = booleanPreferencesKey("show_radio")
        val baseUrl = stringPreferencesKey("base_url")
        val token = stringPreferencesKey("token")
        val serverId = stringPreferencesKey("server_id")
        val serverName = stringPreferencesKey("server_name")
        val username = stringPreferencesKey("username")
        val playerId = stringPreferencesKey("player_id")
        // Hi-res verdicts, cached so the album scan runs once rather than on every launch.
        val hiResAlbums = stringSetPreferencesKey("hires_albums")
        val hiResChecked = stringSetPreferencesKey("hires_checked")
    }

    suspend fun hiResAlbums(): Set<String> = context.dataStore.data.first()[K.hiResAlbums].orEmpty()

    suspend fun hiResChecked(): Set<String> = context.dataStore.data.first()[K.hiResChecked].orEmpty()

    suspend fun saveHiRes(hiRes: Set<String>, checked: Set<String>) {
        context.dataStore.edit { it[K.hiResAlbums] = hiRes; it[K.hiResChecked] = checked }
    }

    val config: Flow<SavedConfig> = context.dataStore.data.map { p ->
        SavedConfig(p[K.baseUrl], p[K.token], p[K.serverId], p[K.serverName], p[K.username], p[K.playerId])
    }

    suspend fun current(): SavedConfig = config.first()

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(p[K.defaultTab] ?: "artists", p[K.showPlaylists] ?: false, p[K.showRadio] ?: false)
    }

    suspend fun saveSettings(s: AppSettings) {
        context.dataStore.edit { it[K.defaultTab] = s.defaultTab; it[K.showPlaylists] = s.showPlaylists; it[K.showRadio] = s.showRadio }
    }

    suspend fun saveServer(baseUrl: String, token: String, serverId: String, serverName: String?, username: String) {
        context.dataStore.edit {
            it[K.baseUrl] = baseUrl; it[K.token] = token; it[K.serverId] = serverId
            if (serverName != null) it[K.serverName] = serverName else it.remove(K.serverName)
            it[K.username] = username
        }
    }

    suspend fun saveToken(token: String) { context.dataStore.edit { it[K.token] = token } }

    suspend fun savePlayer(playerId: String) { context.dataStore.edit { it[K.playerId] = playerId } }

    suspend fun clearServer() {
        context.dataStore.edit { it.remove(K.baseUrl); it.remove(K.token); it.remove(K.serverId); it.remove(K.serverName); it.remove(K.playerId) }
    }
}
