package io.github.superthom196.matv

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.superthom196.matv.ma.ConnectionState
import io.github.superthom196.matv.ma.DiscoveredServer
import io.github.superthom196.matv.ma.ImageUrls
import io.github.superthom196.matv.ma.MaAuthException
import io.github.superthom196.matv.ma.MaClient
import io.github.superthom196.matv.ma.MaDiscovery
import io.github.superthom196.matv.ma.MediaItem
import io.github.superthom196.matv.ma.Player
import io.github.superthom196.matv.ma.PlayerQueue
import io.github.superthom196.matv.ma.QueueItem
import io.github.superthom196.matv.ma.Prefs
import io.github.superthom196.matv.ma.SavedConfig
import io.github.superthom196.matv.ma.ServerInfo
import io.github.superthom196.matv.ma.maJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

private const val TAG = "AppViewModel"

/** Where the app is in its setup flow. Screens are driven from this. */
enum class Phase { Loading, Connect, Login, Main }

/** What the Now Playing screen shows for the selected player, merged from queue + player. */
data class NowPlaying(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val imageUrl: String? = null,
    val duration: Double? = null,
    val elapsed: Double = 0.0,
    val state: String = "idle",
    val nextTitle: String? = null,
    val queueName: String = "",
    val shuffle: Boolean = false,
    val repeat: String = "off",
    val queueItemId: String? = null,
) {
    val hasMedia: Boolean get() = title.isNotBlank()
    val isPlaying: Boolean get() = state == "playing"
}

data class UiState(
    val phase: Phase = Phase.Loading,
    val connection: ConnectionState = ConnectionState.Disconnected,
    val server: ServerInfo? = null,
    val baseUrl: String? = null,
    val username: String? = null,
    val players: List<Player> = emptyList(),
    val selectedPlayerId: String? = null,
    val activeQueueId: String? = null,
    val nowPlaying: NowPlaying = NowPlaying(),
    val message: String? = null,
    /** Server picked on the Connect screen, awaiting login. */
    val pendingServer: DiscoveredServer? = null,
    val loginBusy: Boolean = false,
    val loginError: String? = null,
    val discovered: List<DiscoveredServer> = emptyList(),
    val discovering: Boolean = false,
    val connectError: String? = null,
) {
    val selectedPlayer: Player? get() = players.firstOrNull { it.playerId == selectedPlayerId }
    val selectablePlayers: List<Player> get() = players.filter { it.isSelectable }.sortedBy { it.name.lowercase() }
}

/** One level of the folder browser. */
data class FolderLevel(
    val id: Long,
    val folder: MediaItem?,
    val items: List<MediaItem> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
) {
    val name: String get() = folder?.name ?: "Folders"
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    val client = MaClient()
    private val prefs = Prefs(app)
    private val discovery = MaDiscovery(app, client)
    val albums = AlbumIndex(viewModelScope, count = { client.albumsCount() }, fetch = { off, lim -> client.libraryItems("albums", off, lim) })
    val artists = AlbumIndex(viewModelScope, count = { client.artistsCount() }, fetch = { off, lim -> client.libraryItems("artists", off, lim) }, sortKey = ::artistNameKey)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /** Items of the selected player's queue; only kept fresh while the Queue screen is showing. */
    data class QueueView(val items: List<QueueItem> = emptyList(), val currentIndex: Int? = null, val loading: Boolean = false, val error: String? = null)
    private val _queue = MutableStateFlow(QueueView())
    val queue: StateFlow<QueueView> = _queue.asStateFlow()
    @Volatile private var queueVisible = false

    private val _folders = MutableStateFlow<List<FolderLevel>>(emptyList())
    /** Folder browser stack; empty until the Folders tab is first opened. */
    val folders: StateFlow<List<FolderLevel>> = _folders.asStateFlow()
    private var nextFolderId = 1L
    /** Last folder level whose first row was auto-focused (UI bookkeeping, survives tab switches). */
    var folderLevelAutoFocused: Long = -1L

    private val queues = java.util.concurrent.ConcurrentHashMap<String, PlayerQueue>()
    private var discoveryJob: Job? = null
    private var tickerJob: Job? = null

    init {
        viewModelScope.launch { client.state.collect { st -> onConnectionState(st) } }
        viewModelScope.launch(Dispatchers.Default) { client.events.collect { ev -> onEvent(ev.event, ev.objectId, ev.data) } }
        viewModelScope.launch { bootstrap() }
        viewModelScope.launch {
            var last = ""
            _ui.collect {
                val line = "ui: phase=${it.phase} conn=${it.connection::class.simpleName} discovering=${it.discovering} found=${it.discovered.size} err=${it.connectError} player=${it.selectedPlayerId}"
                if (line != last) { last = line; Log.i(TAG, line) }
            }
        }
    }

    // ------------------------------------------------------------------ startup / connect

    private suspend fun bootstrap() {
        val cfg = prefs.current()
        if (!cfg.hasServer) { _ui.update { it.copy(phase = Phase.Connect) }; startDiscovery(); return }
        _ui.update { it.copy(phase = Phase.Loading, baseUrl = cfg.baseUrl, username = cfg.username, selectedPlayerId = cfg.playerId) }
        try {
            client.connect(cfg.baseUrl!!, cfg.token!!, cfg.serverId)
            afterConnected(cfg)
        } catch (e: MaAuthException) {
            // Token rejected or a different server answered: go back through login, keep the address.
            _ui.update { it.copy(phase = Phase.Connect, connectError = e.message) }
            startDiscovery()
        } catch (e: Exception) {
            // Server unreachable right now: keep trying quietly, show the Connect screen with a hint.
            _ui.update { it.copy(phase = Phase.Connect, connectError = "Cannot reach ${cfg.serverName ?: cfg.baseUrl}: ${e.message}") }
            startDiscovery()
            retrySavedUntilUp(cfg)
        }
    }

    private fun retrySavedUntilUp(cfg: SavedConfig) {
        viewModelScope.launch {
            var d = 3000L
            while (isActive && _ui.value.phase == Phase.Connect && _ui.value.pendingServer == null) {
                delay(d); d = (d * 2).coerceAtMost(20_000)
                try {
                    client.connect(cfg.baseUrl!!, cfg.token!!, cfg.serverId)
                    afterConnected(cfg)
                    return@launch
                } catch (_: Exception) { }
            }
        }
    }

    fun startDiscovery() {
        if (discoveryJob?.isActive == true) return
        _ui.update { it.copy(discovering = true, discovered = emptyList()) }
        discoveryJob = viewModelScope.launch {
            try {
                discovery.discover().collect { s ->
                    _ui.update { st -> if (st.discovered.any { it.info.serverId == s.info.serverId }) st else st.copy(discovered = st.discovered + s) }
                }
            } finally {
                _ui.update { it.copy(discovering = false) }
            }
        }
        // The sweep finishes in a few seconds; stop after a bounded window.
        viewModelScope.launch { delay(12_000); discoveryJob?.cancel() }
    }

    fun stopDiscovery() { discoveryJob?.cancel() }

    /** Manual host entry: "192.168.1.50", "my-server", "my-server:8095" or a full http URL. */
    fun connectManual(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val base = when {
            t.startsWith("http://") || t.startsWith("https://") -> t.trimEnd('/')
            t.contains(':') -> "http://$t"
            else -> "http://$t:8095"
        }
        _ui.update { it.copy(connectError = null) }
        viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.IO) { client.fetchInfo(base) }
                chooseServer(DiscoveredServer(base, info, "manual"))
            } catch (e: Exception) {
                _ui.update { it.copy(connectError = "No Music Assistant at $base (${e.message})") }
            }
        }
    }

    fun chooseServer(s: DiscoveredServer) {
        stopDiscovery()
        _ui.update { it.copy(pendingServer = s, phase = Phase.Login, loginError = null) }
    }

    fun backToConnect() {
        _ui.update { it.copy(pendingServer = null, phase = Phase.Connect, loginError = null) }
        startDiscovery()
    }

    // ------------------------------------------------------------------ login

    fun login(username: String, password: String) {
        val s = _ui.value.pendingServer ?: return
        if (username.isBlank() || password.isBlank()) { _ui.update { it.copy(loginError = "Enter your Music Assistant username and password") }; return }
        _ui.update { it.copy(loginBusy = true, loginError = null) }
        viewModelScope.launch {
            try {
                val sessionToken = withContext(Dispatchers.IO) { client.loginHttp(s.baseUrl, username, password) }
                client.connect(s.baseUrl, sessionToken, s.info.serverId)
                // Swap the 30-day session token for a 10-year one so the TV never silently logs out.
                val longLived = client.createLongLivedToken("MATV (${android.os.Build.MODEL})")
                val token = longLived ?: sessionToken
                prefs.saveServer(s.baseUrl, token, s.info.serverId, s.info.name, username)
                if (longLived != null) {
                    // Re-open with the long-lived token so the running session already uses it.
                    client.connect(s.baseUrl, token, s.info.serverId)
                }
                afterConnected(prefs.current())
            } catch (e: Exception) {
                Log.w(TAG, "login failed", e)
                _ui.update { it.copy(loginBusy = false, loginError = e.message ?: "Login failed") }
            }
        }
    }

    fun forgetServer() {
        viewModelScope.launch {
            client.disconnect()
            prefs.clearServer()
            queues.clear()
            albums.reset()
            artists.reset()
            _folders.value = emptyList()
            AuthHolder.token = null
            _ui.value = UiState(phase = Phase.Connect)
            startDiscovery()
        }
    }

    // ------------------------------------------------------------------ connected state

    private suspend fun afterConnected(cfg: SavedConfig) {
        AuthHolder.token = cfg.token
        albums.reset()
        artists.reset()
        _folders.value = emptyList()
        _ui.update {
            it.copy(
                phase = Phase.Main, loginBusy = false, loginError = null, pendingServer = null,
                baseUrl = cfg.baseUrl, username = cfg.username, server = client.serverInfo,
                selectedPlayerId = it.selectedPlayerId ?: cfg.playerId, connectError = null,
            )
        }
        refreshAll()
    }

    private suspend fun refreshAll() {
        try {
            val players = client.players()
            val qs = client.queues()
            queues.clear(); qs.forEach { queues[it.queueId] = it }
            _ui.update { st ->
                val sel = st.selectedPlayerId?.takeIf { id -> players.any { it.playerId == id } }
                st.copy(players = players, selectedPlayerId = sel)
            }
            _ui.value.selectedPlayerId?.let { resolveActiveQueue(it) }
            recomputeNowPlaying()
        } catch (e: Exception) {
            Log.w(TAG, "refresh failed: ${e.message}")
        }
    }

    private fun onConnectionState(st: ConnectionState) {
        _ui.update { it.copy(connection = st, server = client.serverInfo ?: it.server) }
        if (st is ConnectionState.Connected && _ui.value.phase == Phase.Main) {
            viewModelScope.launch { refreshAll() }
        }
        if (st is ConnectionState.Failed && st.fatal && _ui.value.phase == Phase.Main) {
            _ui.update { it.copy(phase = Phase.Connect, connectError = st.reason) }
            startDiscovery()
        }
    }

    private fun onEvent(event: String, objectId: String?, data: kotlinx.serialization.json.JsonElement) {
        when (event) {
            "player_updated", "player_added" -> {
                val p = runCatching { maJson.decodeFromJsonElement(Player.serializer(), data) }.getOrNull() ?: return
                _ui.update { st ->
                    val list = if (st.players.any { it.playerId == p.playerId }) st.players.map { if (it.playerId == p.playerId) p else it } else st.players + p
                    st.copy(players = list)
                }
                if (p.playerId == _ui.value.selectedPlayerId) recomputeNowPlaying()
            }
            "player_removed" -> {
                val id = objectId ?: return
                _ui.update { st -> st.copy(players = st.players.filterNot { it.playerId == id }) }
            }
            "queue_updated", "queue_items_updated", "queue_added" -> {
                val q = runCatching { maJson.decodeFromJsonElement(PlayerQueue.serializer(), data) }.getOrNull() ?: return
                queues[q.queueId] = q
                if (q.queueId == _ui.value.activeQueueId) {
                    recomputeNowPlaying()
                    if (queueVisible && (event == "queue_items_updated" || q.currentIndex != _queue.value.currentIndex)) refreshQueue()
                }
            }
            "queue_time_updated" -> {
                val id = objectId ?: return
                val secs = (data as? JsonPrimitive)?.doubleOrNull ?: return
                val q = queues[id] ?: return
                queues[id] = q.copy(elapsedTime = secs, elapsedTimeLastUpdated = System.currentTimeMillis() / 1000.0)
                if (id == _ui.value.activeQueueId) recomputeNowPlaying()
            }
            else -> Log.v(TAG, "event $event ignored")
        }
    }

    private suspend fun resolveActiveQueue(playerId: String) {
        val q = runCatching { client.activeQueue(playerId) }.getOrNull()
        if (q != null) queues[q.queueId] = q
        _ui.update { it.copy(activeQueueId = q?.queueId ?: playerId) }
    }

    private fun recomputeNowPlaying() {
        val st = _ui.value
        val player = st.selectedPlayer
        val q = st.activeQueueId?.let { queues[it] }
        val base = st.baseUrl
        val cur = q?.currentItem
        val mi = cur?.mediaItem
        val pm = player?.currentMedia
        val np = NowPlaying(
            title = mi?.name ?: cur?.name ?: pm?.title ?: "",
            artist = mi?.artistLine?.takeIf { it.isNotBlank() } ?: pm?.artist ?: "",
            album = mi?.album?.name ?: pm?.album ?: "",
            imageUrl = ImageUrls.forQueueItem(base, cur, 1024) ?: ImageUrls.forPlayerMedia(base, pm?.imageUrl),
            duration = cur?.duration ?: mi?.duration ?: pm?.duration,
            elapsed = q?.elapsedTime ?: player?.elapsedTime ?: 0.0,
            state = q?.state ?: player?.playbackState ?: "idle",
            nextTitle = q?.nextItem?.name,
            queueName = q?.displayName ?: player?.name ?: "",
            shuffle = q?.shuffleEnabled ?: false,
            repeat = q?.repeatMode ?: "off",
            queueItemId = cur?.queueItemId ?: pm?.queueItemId,
        )
        _ui.update { it.copy(nowPlaying = np) }
        ensureTicker(np.isPlaying)
    }

    /** Local 1 Hz clock between server time updates: no polling. */
    private fun ensureTicker(playing: Boolean) {
        if (!playing) { tickerJob?.cancel(); tickerJob = null; return }
        if (tickerJob?.isActive == true) return
        tickerJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                _ui.update { st ->
                    val np = st.nowPlaying
                    if (!np.isPlaying) st else st.copy(nowPlaying = np.copy(elapsed = np.elapsed + 1.0))
                }
            }
        }
    }

    // ------------------------------------------------------------------ player selection & transport

    fun selectPlayer(playerId: String) {
        _ui.update { it.copy(selectedPlayerId = playerId) }
        viewModelScope.launch {
            prefs.savePlayer(playerId)
            resolveActiveQueue(playerId)
            recomputeNowPlaying()
        }
    }

    private fun cmd(block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block() } catch (e: Exception) {
                Log.w(TAG, "command failed: ${e.message}")
                flash(e.message ?: "Command failed")
            }
        }
    }

    fun playPause() = withPlayer { client.playerCmd("play_pause", it) }
    fun play() = withPlayer { client.playerCmd("play", it) }
    fun pause() = withPlayer { client.playerCmd("pause", it) }
    fun stop() = withPlayer { client.playerCmd("stop", it) }
    fun next() = withPlayer { client.playerCmd("next", it) }
    fun previous() = withPlayer { client.playerCmd("previous", it) }
    fun volumeUp() = withPlayer { client.playerCmd("volume_up", it) }
    fun volumeDown() = withPlayer { client.playerCmd("volume_down", it) }
    fun setVolume(level: Int) = withPlayer { client.playerCmd("volume_set", it, "volume_level" to level.coerceIn(0, 100)) }
    fun shuffleOn() {
        val q = _ui.value.activeQueueId ?: return
        cmd { client.send("player_queues/shuffle", "queue_id" to q, "shuffle_enabled" to true) }
    }
    fun seekRelative(seconds: Int) {
        val q = _ui.value.activeQueueId ?: return
        cmd { client.send("player_queues/skip", "queue_id" to q, "seconds" to seconds) }
    }

    private fun withPlayer(block: suspend (String) -> Unit) {
        val id = _ui.value.selectedPlayerId ?: run { flash("Choose a player first"); return }
        cmd { block(id) }
    }

    /** Play a browsable item (album, playlist, artist, track) on the selected player's queue. */
    fun playItem(item: MediaItem, startFrom: MediaItem? = null, option: String = "replace") {
        val uri = item.uri ?: return
        val playerId = _ui.value.selectedPlayerId ?: run { flash("Choose a player first"); return }
        cmd {
            val queueId = _ui.value.activeQueueId ?: run { resolveActiveQueue(playerId); _ui.value.activeQueueId ?: playerId }
            client.playMedia(queueId, listOf(uri), option, startFrom?.uri)
            flash("Playing ${item.name} on ${_ui.value.selectedPlayer?.name ?: "player"}")
        }
    }

    fun flash(text: String) {
        _ui.update { it.copy(message = text) }
        viewModelScope.launch { delay(2500); _ui.update { if (it.message == text) it.copy(message = null) else it } }
    }

    // ------------------------------------------------------------------ library

    suspend fun library(kind: String, offset: Int, limit: Int): List<MediaItem> = client.libraryItems(kind, offset, limit)
    suspend fun children(item: MediaItem): List<MediaItem> = when (item.mediaType) {
        "artist" -> client.artistAlbums(item)
        "album" -> client.albumTracks(item)
        "playlist" -> client.playlistTracks(item)
        else -> emptyList()
    }

    fun imageUrl(item: MediaItem?, size: Int): String? = ImageUrls.forItem(_ui.value.baseUrl, item, size)

    // Artists without their own image (no fanart.tv / TheAudioDB match) borrow an album cover.
    private val artistCoverCache = java.util.concurrent.ConcurrentHashMap<String, MutableStateFlow<String?>>()
    private val artistCoverGate = kotlinx.coroutines.sync.Semaphore(3)

    fun artistCover(artist: MediaItem, size: Int): StateFlow<String?> {
        val key = "${artist.provider}:${artist.itemId}:$size"
        artistCoverCache[key]?.let { return it }
        val flow = MutableStateFlow<String?>(null)
        artistCoverCache[key] = flow
        viewModelScope.launch {
            artistCoverGate.withPermit {
                val albums = runCatching { client.artistAlbums(artist, inLibraryOnly = true) }.getOrDefault(emptyList())
                flow.value = albums.firstNotNullOfOrNull { ImageUrls.forImage(_ui.value.baseUrl, it.thumb, size) }
            }
        }
        return flow
    }

    // ------------------------------------------------------------------ queue

    fun setQueueVisible(visible: Boolean) {
        queueVisible = visible
        if (visible) refreshQueue()
    }

    fun refreshQueue() {
        val qid = _ui.value.activeQueueId ?: run { _queue.value = QueueView(error = "No active queue"); return }
        _queue.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val items = client.queueItems(qid)
                _queue.value = QueueView(items = items, currentIndex = queues[qid]?.currentIndex, loading = false)
            } catch (e: Exception) {
                _queue.update { it.copy(loading = false, error = e.message ?: "Failed to load queue") }
            }
        }
    }

    fun playQueueIndex(index: Int) {
        val qid = _ui.value.activeQueueId ?: return
        cmd { client.playIndex(qid, index) }
    }

    // ------------------------------------------------------------------ folder browser

    fun ensureFoldersLoaded() { if (_folders.value.isEmpty()) openFolder(null) }

    fun reloadFolders() { _folders.value = emptyList(); openFolder(null) }

    /** Push a level and load it. `null` loads the root (one entry per provider). */
    fun openFolder(folder: MediaItem?) {
        val level = FolderLevel(nextFolderId++, folder)
        // The root list never grabs focus on its own (that hijacks walking along the tab row); only
        // levels the user opens do.
        if (folder == null) folderLevelAutoFocused = level.id
        _folders.update { if (folder == null) listOf(level) else it + level }
        viewModelScope.launch {
            try {
                val items = client.browse(folder?.path)
                val sorted = items.sortedWith(compareBy({ it.name != ".." }, { !it.isFolder }, { (it.sortName ?: it.name).lowercase() }))
                _folders.update { st -> st.map { if (it.id == level.id) it.copy(items = sorted, loading = false) else it } }
                // A single provider at the root is the common case: skip straight into it.
                if (folder == null && sorted.size == 1 && sorted[0].isFolder) openFolder(sorted[0])
            } catch (e: Exception) {
                _folders.update { st -> st.map { if (it.id == level.id) it.copy(loading = false, error = e.message ?: "Failed to load") else it } }
            }
        }
    }

    /** Back inside the folder browser: pop one level. Returns false at the top. */
    fun folderUp(): Boolean {
        if (_folders.value.size <= 1) return false
        _folders.update { it.dropLast(1) }
        return true
    }

    /** Play the folder's tracks from the chosen one to the end. */
    fun playTracksFrom(tracks: List<MediaItem>, start: MediaItem) {
        val uris = tracks.dropWhile { it !== start }.mapNotNull { it.uri }.ifEmpty { listOfNotNull(start.uri) }
        if (uris.isEmpty()) return
        val playerId = _ui.value.selectedPlayerId ?: run { flash("Choose a player first"); return }
        cmd {
            val queueId = _ui.value.activeQueueId ?: run { resolveActiveQueue(playerId); _ui.value.activeQueueId ?: playerId }
            client.playMedia(queueId, uris, "replace")
            flash("Playing ${start.name} on ${_ui.value.selectedPlayer?.name ?: "player"}")
        }
    }

    override fun onCleared() {
        client.close()
    }
}
