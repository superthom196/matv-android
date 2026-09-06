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
import io.github.superthom196.matv.ma.AppSettings
import io.github.superthom196.matv.ma.Prefs
import io.github.superthom196.matv.ma.SavedConfig
import io.github.superthom196.matv.ma.ServerInfo
import io.github.superthom196.matv.ma.audioFormatLabel
import io.github.superthom196.matv.ma.maJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Notches the TV remote's volume rocker takes to cross the hi-fi's whole 0-100 range.
 * 25 means a full TV volume scale is 100% output, and each press moves 4 points.
 */
const val TV_VOLUME_STEPS = 25

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
    val bitDepth: Int? = null,
    val sampleRateKhz: String? = null,
    val codec: String = "",
    val fidelity: String? = null,
) {
    val hasMedia: Boolean get() = title.isNotBlank()
    val isPlaying: Boolean get() = state == "playing"

    /** Music Assistant's own verdict on the source file, not a guess from the numbers. */
    val isHiRes: Boolean get() = fidelity == "hi_res"

    /** e.g. "FLAC 24/44.1" — blank when the server has not told us what it is streaming. */
    val formatLabel: String get() = audioFormatLabel(codec, bitDepth, sampleRateKhz)
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
    val playlists = AlbumIndex(viewModelScope, count = { client.libraryCount("playlists") }, fetch = { off, lim -> client.libraryItems("playlists", off, lim) }, sortKey = ::artistNameKey)
    val radios = AlbumIndex(viewModelScope, count = { client.libraryCount("radios") }, fetch = { off, lim -> client.libraryItems("radios", off, lim) }, sortKey = ::artistNameKey)
    val genres = AlbumIndex(viewModelScope, count = { client.libraryCount("genres") }, fetch = { off, lim -> client.libraryItems("genres", off, lim) }, sortKey = ::artistNameKey)

    /**
     * Seed for the Random row, fixed for the life of the app. Reshuffling on every recomposition
     * would move albums under you as you came back from one, so the order holds until a restart.
     */
    private val shuffleSeed = System.nanoTime()

    fun shuffledAlbums(items: List<MediaItem>): List<MediaItem> = items.shuffled(kotlin.random.Random(shuffleSeed))

    /** Newest-first albums for the "Latest" row; the server does the ordering.  */
    val recentAlbums = RecentAlbums(viewModelScope) { off, lim ->
        client.libraryItems("albums", off, lim, orderBy = "timestamp_added_desc")
    }

    /** Hi-res and genre facts per album, learned in the background and cached on disk. */
    val albumScan = AlbumScan(viewModelScope, prefs, tracksOf = { client.albumTracks(it) })

    init {
        // Load the cached verdicts, then top up the scan whenever the album library finishes loading.
        viewModelScope.launch {
            albumScan.restore()
            albums.state.collect { st -> if (st.ready) albumScan.ensureScanned(st.items) }
        }
    }

    val settings: StateFlow<AppSettings> = prefs.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    fun updateSettings(transform: (AppSettings) -> AppSettings) { viewModelScope.launch { prefs.saveSettings(transform(settings.value)) } }

    /** Favourite flags changed in this session, keyed "provider:item_id"; the server's own flag is the fallback. */
    private val _favOverrides = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val favOverrides: StateFlow<Map<String, Boolean>> = _favOverrides.asStateFlow()
    fun isFavourite(item: MediaItem): Boolean = _favOverrides.value["${item.provider}:${item.itemId}"] ?: item.favorite

    data class Favourites(val artists: List<MediaItem> = emptyList(), val albums: List<MediaItem> = emptyList(), val tracks: List<MediaItem> = emptyList(), val playlists: List<MediaItem> = emptyList(), val loading: Boolean = false, val loaded: Boolean = false, val error: String? = null)
    private val _favourites = MutableStateFlow(Favourites())
    val favourites: StateFlow<Favourites> = _favourites.asStateFlow()

    data class SearchState(val query: String = "", val results: Map<String, List<MediaItem>> = emptyMap(), val searching: Boolean = false, val error: String? = null)
    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search.asStateFlow()
    private var searchJob: Job? = null

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /** Items of the selected player's queue; only kept fresh while the Queue screen is showing. */
    data class QueueView(val items: List<QueueItem> = emptyList(), val currentIndex: Int? = null, val loading: Boolean = false, val error: String? = null)
    private val _queue = MutableStateFlow(QueueView())
    val queue: StateFlow<QueueView> = _queue.asStateFlow()
    @Volatile private var queueVisible = false

    private val _folders = MutableStateFlow<List<FolderLevel>>(emptyList())
    private val _folderBusy = MutableStateFlow(false)
    /** True while a folder level is being fetched (the list keeps showing the current level meanwhile). */
    val folderBusy: StateFlow<Boolean> = _folderBusy.asStateFlow()
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
            // Token rejected (revoked on the server, expired) or a different server answered: go
            // straight to the login screen for the saved server instead of making the user rediscover it.
            val info = runCatching { withContext(Dispatchers.IO) { client.fetchInfo(cfg.baseUrl!!) } }.getOrNull()
            if (info != null) {
                _ui.update { it.copy(phase = Phase.Login, pendingServer = DiscoveredServer(cfg.baseUrl!!, info, "saved"), loginError = "Your saved login is no longer valid. Sign in again.") }
            } else {
                _ui.update { it.copy(phase = Phase.Connect, connectError = e.message) }
                startDiscovery()
            }
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
            playlists.reset()
            radios.reset()
            _favourites.value = Favourites()
            _favOverrides.value = emptyMap()
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
        playlists.reset()
        radios.reset()
        _favourites.value = Favourites()
        _favOverrides.value = emptyMap()
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
        val fmt = cur?.streamdetails?.audioFormat
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
            bitDepth = fmt?.bitDepth,
            sampleRateKhz = fmt?.sampleRateKhz,
            codec = fmt?.contentType.orEmpty(),
            fidelity = cur?.streamdetails?.fidelity,
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
    fun setVolume(level: Int) {
        flashVolumeHud(level, muted = false)
        withPlayer { client.playerCmd("volume_set", it, "volume_level" to level.coerceIn(0, 100)) }
    }

    /** Transient volume readout, shown by the dial overlay and cleared on a timer. */
    data class VolumeHud(val level: Int, val muted: Boolean, val stamp: Long = System.currentTimeMillis())

    private val _volumeHud = MutableStateFlow<VolumeHud?>(null)
    val volumeHud: StateFlow<VolumeHud?> = _volumeHud.asStateFlow()
    private var hudJob: kotlinx.coroutines.Job? = null

    private fun flashVolumeHud(level: Int, muted: Boolean) {
        _volumeHud.value = VolumeHud(level.coerceIn(0, 100), muted)
        hudJob?.cancel()
        hudJob = viewModelScope.launch { kotlinx.coroutines.delay(1800); _volumeHud.value = null }
    }

    /** Level to come back to when a software mute is lifted; null when not software-muted. */
    private var preMuteLevel: Int? = null

    /**
     * Mute / unmute the hi-fi. Players that advertise volume_mute get the real thing; the rest —
     * a Squeezelite endpoint answers "This feature is not supported" — get a software mute that
     * drops the level to zero and puts it back.
     */
    fun toggleMute() {
        val player = _ui.value.selectedPlayer ?: run { flash("Choose a player first"); return }
        if (player.supportedFeatures?.contains("volume_mute") == true) {
            val muted = player.volumeMuted == true
            flashVolumeHud(player.volumeLevel ?: 0, muted = !muted)
            withPlayer { client.playerCmd("volume_mute", it, "muted" to !muted) }
            return
        }
        val restore = preMuteLevel
        if (restore != null) {
            preMuteLevel = null
            setVolume(restore)
        } else {
            preMuteLevel = player.volumeLevel ?: 0
            setVolume(0)
            flashVolumeHud(0, muted = true)
        }
    }

    /**
     * One notch of the TV remote's volume rocker. The hi-fi's 0-100 range is split into
     * [TV_VOLUME_STEPS] notches, so a full TV scale is full output.
     */
    fun nudgeVolume(direction: Int) {
        val current = _ui.value.selectedPlayer?.volumeLevel ?: return
        val step = 100 / TV_VOLUME_STEPS
        setVolume((current + direction * step).coerceIn(0, 100))
    }
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

    fun moveQueueItem(item: QueueItem, shift: Int) { val qid = _ui.value.activeQueueId ?: return; cmd { client.moveQueueItem(qid, item.queueItemId, shift); refreshQueue() } }
    fun removeQueueItem(item: QueueItem) { val qid = _ui.value.activeQueueId ?: return; cmd { client.deleteQueueItem(qid, item.queueItemId); refreshQueue() } }
    fun clearQueue() { val qid = _ui.value.activeQueueId ?: return; cmd { client.clearQueue(qid); refreshQueue(); flash("Queue cleared") } }

    fun toggleFavourite(item: MediaItem) {
        val key = "${item.provider}:${item.itemId}"
        val now = !isFavourite(item)
        _favOverrides.update { it + (key to now) }
        cmd {
            if (now) client.addFavorite(item) else client.removeFavorite(item)
            flash(if (now) "Added ${item.name} to favourites" else "Removed ${item.name} from favourites")
            _favourites.update { it.copy(loaded = false) }
        }
    }

    fun ensureFavouritesLoaded(force: Boolean = false) {
        val cur = _favourites.value
        if (!force && (cur.loading || cur.loaded)) return
        _favourites.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val a = client.libraryItems("artists", 0, 200, favoriteOnly = true)
                val al = client.libraryItems("albums", 0, 500, favoriteOnly = true)
                val t = client.libraryItems("tracks", 0, 500, favoriteOnly = true)
                val p = runCatching { client.libraryItems("playlists", 0, 200, favoriteOnly = true) }.getOrDefault(emptyList())
                _favourites.value = Favourites(a, al, t, p, loading = false, loaded = true)
            } catch (e: Exception) {
                _favourites.update { it.copy(loading = false, error = e.message ?: "Failed to load favourites") }
            }
        }
    }

    fun setSearchQuery(q: String) {
        _search.update { it.copy(query = q) }
        searchJob?.cancel()
        if (q.isBlank()) { _search.update { it.copy(results = emptyMap(), searching = false, error = null) }; return }
        searchJob = viewModelScope.launch {
            delay(450)
            _search.update { it.copy(searching = true, error = null) }
            try {
                val res = client.search(q)
                _search.update { if (it.query == q) it.copy(results = res, searching = false) else it }
            } catch (e: Exception) {
                _search.update { it.copy(searching = false, error = e.message ?: "Search failed") }
            }
        }
    }

    fun groupPlayer(playerId: String, targetId: String) = cmd { client.groupPlayer(playerId, targetId); flash("Synced") }
    fun ungroupPlayer(playerId: String) = cmd { client.ungroupPlayer(playerId); flash("Unsynced") }

    /** User-initiated reconnect from the connection overlay. */
    fun reconnectNow() {
        viewModelScope.launch {
            val cfg = prefs.current()
            if (!cfg.hasServer) return@launch
            _ui.update { it.copy(connection = ConnectionState.Connecting) }
            try { client.connect(cfg.baseUrl!!, cfg.token!!, cfg.serverId); afterConnected(cfg) } catch (e: Exception) { flash(e.message ?: "Still unreachable") }
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
        // The root shows a loading placeholder; deeper levels are loaded first and swapped in whole,
        // so the focused row is never yanked from under the D-pad mid-load.
        if (folder == null) _folders.value = listOf(level)
        _folderBusy.value = true
        viewModelScope.launch {
            try {
                val items = client.browse(folder?.path)
                val sorted = items.sortedWith(compareBy({ it.name != ".." }, { !it.isFolder }, { (it.sortName ?: it.name).lowercase() }))
                val loaded = level.copy(items = sorted, loading = false)
                _folders.update { st -> if (folder == null) listOf(loaded) else st + loaded }
                // A single provider at the root is the common case: skip straight into it.
                if (folder == null && sorted.size == 1 && sorted[0].isFolder) openFolder(sorted[0])
            } catch (e: Exception) {
                val failed = level.copy(loading = false, error = e.message ?: "Failed to load")
                _folders.update { st -> if (folder == null) listOf(failed) else st + failed }
            } finally {
                _folderBusy.value = false
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
