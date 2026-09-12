package io.github.superthom196.matv.ma

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "MaClient"

/** Minimum Music Assistant schema this app was written against (auth became mandatory at 28). */
const val MIN_SCHEMA_VERSION = 28

sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data class Reconnecting(val attempt: Int, val reason: String) : ConnectionState()
    /** Socket open and `auth` accepted. */
    data class Connected(val server: ServerInfo) : ConnectionState()
    data class Failed(val reason: String, val fatal: Boolean = false) : ConnectionState()
}

class MaEvent(val event: String, val objectId: String?, val data: JsonElement)

class MaException(message: String, val code: String? = null) : Exception(message)
class MaAuthException(message: String) : Exception(message)

/**
 * One object owns the WebSocket. It correlates replies by `message_id`, re-emits server
 * events as a flow, and reconnects with exponential backoff, re-authenticating with the
 * stored token each time.
 */
class MaClient(private val http: OkHttpClient = defaultHttp()) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<MaEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<MaEvent> = _events.asSharedFlow()

    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonElement>>()
    private val nextId = AtomicLong(1)
    // Written on Main (a direct connect) and on IO (the supervisor), read on OkHttp's threads.
    @Volatile private var socket: WebSocket? = null
    @Volatile private var serverInfoDeferred: CompletableDeferred<ServerInfo>? = null
    private var connectJob: Job? = null
    /** Serialises every handshake, whether from [connect] or the supervisor: see [connect]. */
    private val handshake = Mutex()

    /** Credentials the reconnect loop needs: base URL + a bearer token. */
    private var baseUrl: String? = null
    private var token: String? = null
    private var expectedServerId: String? = null

    var serverInfo: ServerInfo? = null
        private set

    val isConnected: Boolean get() = _state.value is ConnectionState.Connected

    /**
     * Returns once the socket is up and authenticated. While a connect or reconnect is in flight
     * this waits for it (bounded), so a library refresh or a command issued in the first seconds
     * after launch, or during a reconnect, goes through instead of failing with "not connected".
     * Throws at once when nothing is trying to connect: a non-fatal Failed with a supervisor still
     * running behind it is a pause between attempts, not the end of the road.
     */
    suspend fun awaitConnected(timeoutMs: Long = 10_000) {
        when (val st = _state.value) {
            is ConnectionState.Connected -> return
            is ConnectionState.Connecting, is ConnectionState.Reconnecting -> Unit
            is ConnectionState.Failed -> if (st.fatal || connectJob?.isActive != true) throw MaException("not connected")
            is ConnectionState.Disconnected -> throw MaException("not connected")
        }
        withTimeoutOrNull(timeoutMs) { _state.first { it is ConnectionState.Connected } }
            ?: throw MaException("not connected")
    }

    // ---------------------------------------------------------------- HTTP helpers

    /** Unauthenticated: used for discovery and as the pre-flight before connecting. */
    suspend fun fetchInfo(base: String, timeoutMs: Long = 2500): ServerInfo {
        val client = http.newBuilder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        val req = Request.Builder().url("${base.trimEnd('/')}/info").get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw MaException("HTTP ${resp.code} from /info")
            val body = resp.body.string()
            return maJson.decodeFromString(ServerInfo.serializer(), body)
        }
    }

    /** POST /auth/login with the built-in provider. Returns the session token. */
    suspend fun loginHttp(base: String, username: String, password: String): String {
        val payload = buildJsonObject {
            put("credentials", buildJsonObject {
                put("username", JsonPrimitive(username))
                put("password", JsonPrimitive(password))
            })
        }
        val req = Request.Builder()
            .url("${base.trimEnd('/')}/auth/login")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        // The shared client has no read timeout, which is right for the socket and wrong here: a
        // login that never answers used to leave the Sign in button stuck at "Signing in…".
        val client = http.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body.string()
            val el = runCatching { maJson.parseToJsonElement(body) }.getOrNull()
            val tok = el?.findStringDeep("token")
            if (!resp.isSuccessful || tok.isNullOrBlank()) {
                val msg = el?.findStringDeep("error") ?: el?.findStringDeep("message") ?: body.take(120)
                throw MaAuthException(if (resp.code == 401 || resp.code == 403) "Wrong username or password" else "Login failed: $msg")
            }
            return tok
        }
    }

    // ---------------------------------------------------------------- Connection lifecycle

    /**
     * Connect and authenticate with a token. Suspends until the socket is open and `auth`
     * has been answered, throwing on failure so the caller can show it. Afterwards the client
     * keeps itself connected — and it does so after a transport failure too: the server being
     * down right now is exactly the case the backoff loop exists for, so the state goes to a
     * non-fatal Failed and a supervisor starts retrying. Only the server's own verdict (a bad
     * token, a different server, one too old: [MaAuthException]) ends in a fatal Failed with
     * nothing retrying, since the same token would only be rejected again.
     */
    suspend fun connect(base: String, token: String, expectedServerId: String?) {
        val b = base.trimEnd('/')
        // Whatever is retrying stops first, so a supervisor mid-handshake lets go of the lock at once.
        connectJob?.cancel()
        // One handshake at a time. Two connects in flight together (Retry pressed again while the
        // first was still waiting for the server, a login submitted twice) each opened a socket;
        // the second cancelled the first's, whose failure then failed the identity check in the
        // listener, so the first timed out and started a supervisor of its own beside the second's.
        // Those orphans lived until the app was killed, and on every later drop each one
        // reconnected and cancelled the others' healthy sockets.
        handshake.withLock {
            if (_state.value is ConnectionState.Connected && b == baseUrl && token == this.token &&
                expectedServerId == this.expectedServerId && connectJob?.isActive == true
            ) return // the connect that held the lock before us did this exact job
            connectJob?.cancel() // one that earlier connect's failure path launched while we waited
            connectJob = null
            this.baseUrl = b
            this.token = token
            this.expectedServerId = expectedServerId
            _state.value = ConnectionState.Connecting
            closed.tryReceive() // a drop of the socket this call is replacing is not news
            try {
                openAndAuth()
            } catch (e: CancellationException) {
                throw e
            } catch (e: MaAuthException) {
                val cur = _state.value
                if (!(cur is ConnectionState.Failed && cur.fatal)) _state.value = ConnectionState.Failed(e.message ?: "auth failed", fatal = true)
                throw e
            } catch (e: Exception) {
                // disconnect() ran while this handshake was in flight (the user forgot the server):
                // nothing must retry, or a supervisor would reconnect to a server that is gone.
                if (_state.value is ConnectionState.Disconnected) throw e
                val reason = e.message ?: "unreachable"
                _state.value = ConnectionState.Failed(reason, fatal = false)
                connectJob = scope.launch { supervise(reason) }
                throw e
            }
            connectJob = scope.launch { supervise() }
        }
    }

    /**
     * Start retrying in the background if nothing already is: for a caller that has decided a
     * handshake failure was the network rather than the server's verdict. No-op without a
     * saved address and token.
     */
    fun reconnectInBackground(reason: String) {
        if (connectJob?.isActive == true) return
        if (baseUrl == null || token == null) return
        connectJob = scope.launch { supervise(reason) }
    }

    fun disconnect() {
        connectJob?.cancel()
        connectJob = null
        socket?.close(1000, "bye")
        socket = null
        failAllPending("disconnected")
        _state.value = ConnectionState.Disconnected
    }

    /**
     * The socket dropping. A conflated channel, not a flow: a drop that lands before the supervisor
     * is back at [Channel.receive] (the server going away right after it answered `auth`, before
     * the launch that starts the supervisor has even been dispatched) has to still be there when
     * it looks. A flow with no subscriber threw it away, and the state then stayed Connected on a
     * dead socket: every send failed and nothing ever reconnected.
     */
    private val closed = Channel<String>(Channel.CONFLATED)

    /**
     * Waits for the socket to drop, then reconnects with backoff until it succeeds. With
     * [initialReason] it skips the wait and starts retrying at once (the connect that never
     * landed), then settles into the normal wait-for-drop loop.
     */
    private suspend fun supervise(initialReason: String? = null) {
        var pendingReason = initialReason
        while (true) {
            val reason = pendingReason ?: closed.receive()
            pendingReason = null
            var attempt = 0
            var delayMs = 1000L
            while (true) {
                attempt++
                _state.value = ConnectionState.Reconnecting(attempt, reason)
                try {
                    delay(delayMs)
                    handshake.withLock { openAndAuth() }
                    break
                } catch (e: CancellationException) {
                    // A cancelled supervisor must stop, not spin: delay() throws this on every
                    // pass once the job is cancelled, and the catch-all below used to swallow it.
                    throw e
                } catch (e: MaAuthException) {
                    val cur = _state.value
                    if (!(cur is ConnectionState.Failed && cur.fatal)) _state.value = ConnectionState.Failed(e.message ?: "auth failed", fatal = true)
                    return
                } catch (e: Exception) {
                    Log.w(TAG, "reconnect attempt $attempt failed: ${e.message}")
                    delayMs = (delayMs * 2).coerceAtMost(30_000L)
                }
            }
        }
    }

    private suspend fun openAndAuth() {
        val base = baseUrl ?: throw MaException("no server configured")
        val tok = token ?: throw MaAuthException("no token")
        // Detach the old socket before cancelling it. Its onFailure/onClosed fire on OkHttp's thread
        // and are told apart from the live socket's by `webSocket !== socket`; with the field still
        // pointing at it, a cancel here raced them into failing the deferred installed below for the
        // *new* handshake (seen on login, which connects twice in a row).
        val old = socket
        socket = null
        old?.cancel()
        failAllPending("reconnecting")
        val wsUrl = base.replaceFirst("http://", "ws://").replaceFirst("https://", "wss://") + "/ws"
        val info = CompletableDeferred<ServerInfo>()
        serverInfoDeferred = info
        val ws = http.newWebSocket(Request.Builder().url(wsUrl).build(), listener)
        socket = ws
        val server = try {
            withTimeout(8_000) { info.await() }
        } catch (e: TimeoutCancellationException) {
            ws.cancel()
            throw MaException("No Music Assistant answer at $base (timeout)")
        } catch (e: CancellationException) {
            ws.cancel()
            throw e
        } catch (e: Exception) {
            ws.cancel()
            throw MaException("No Music Assistant answer at $base (${e.message ?: "failed"})")
        }
        serverInfo = server
        if (server.schemaVersion < MIN_SCHEMA_VERSION) {
            ws.cancel()
            _state.value = ConnectionState.Failed(
                "Server ${server.serverVersion} (schema ${server.schemaVersion}) is too old; this app needs schema $MIN_SCHEMA_VERSION+",
                fatal = true,
            )
            throw MaAuthException("server too old")
        }
        expectedServerId?.let {
            if (it != server.serverId) {
                ws.cancel()
                throw MaAuthException("Different server at this address (id ${server.serverId.take(8)}…). Please log in again.")
            }
        }
        val result = try {
            sendRaw("auth", buildJsonObject { put("token", JsonPrimitive(tok)) })
        } catch (e: MaException) {
            ws.cancel()
            // Only an answer from the server counts as a rejection. A timeout, a send failure or the
            // socket dropping mid-handshake (`code` is null for all of those) is the network, not the
            // login — treating it as a bad token used to throw the user out to the Connect screen and
            // a LAN scan whenever the link hiccupped, typically right as the TV woke up.
            if (e.code != null) throw MaAuthException("Server rejected the saved login (${e.message}). Please log in again.")
            throw MaException("No auth answer from $base (${e.message})")
        }
        val ok = (result as? JsonObject)?.get("authenticated")?.jsonPrimitive?.contentOrNull == "true"
            || (result as? JsonPrimitive)?.contentOrNull == "true"
        if (!ok) {
            ws.cancel()
            throw MaAuthException("Server rejected the saved login. Please log in again.")
        }
        _state.value = ConnectionState.Connected(server)
    }

    private val listener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            // A frame this code cannot digest is dropped here; thrown out of the reader it would
            // fail the whole socket and cost a reconnect.
            runCatching { handleFrame(text) }.onFailure { Log.w(TAG, "bad frame dropped: ${it.message}") }
        }

        private fun handleFrame(text: String) {
            val el = runCatching { maJson.parseToJsonElement(text) }.getOrElse { return }
            val obj = el as? JsonObject ?: return
            when {
                obj.containsKey("message_id") -> {
                    val id = obj["message_id"]?.jsonPrimitive?.contentOrNull ?: return
                    val d = pending.remove(id) ?: return
                    val err = obj["error_code"] ?: obj["error"]
                    if (err != null && err !is JsonNull) {
                        val details = obj["details"]?.jsonPrimitive?.contentOrNull
                        d.completeExceptionally(MaException(details ?: err.toString(), err.toString()))
                    } else {
                        d.complete(obj["result"] ?: JsonNull)
                    }
                }
                obj.containsKey("event") -> {
                    val ev = obj["event"]?.jsonPrimitive?.contentOrNull ?: return
                    _events.tryEmit(MaEvent(ev, obj["object_id"]?.jsonPrimitive?.contentOrNull, obj["data"] ?: JsonNull))
                }
                obj.containsKey("server_id") -> {
                    val info = runCatching { maJson.decodeFromJsonElement(ServerInfo.serializer(), obj) }.getOrNull()
                    if (info != null) serverInfoDeferred?.complete(info)
                }
                else -> Log.d(TAG, "unhandled frame: ${text.take(200)}")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (webSocket !== socket) return
            Log.w(TAG, "socket failure: ${t.message}")
            serverInfoDeferred?.completeExceptionally(t)
            failAllPending(t.message ?: "socket failure")
            if (_state.value is ConnectionState.Connected) closed.trySend(t.message ?: "connection lost")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket !== socket) return
            failAllPending("closed")
            if (_state.value is ConnectionState.Connected) closed.trySend("closed ($code)")
        }
    }

    private fun failAllPending(reason: String) {
        val it = pending.entries.iterator()
        while (it.hasNext()) {
            it.next().value.completeExceptionally(MaException(reason)); it.remove()
        }
    }

    // ---------------------------------------------------------------- Commands

    private suspend fun sendRaw(command: String, args: JsonObject, timeoutMs: Long = 15_000): JsonElement {
        val ws = socket ?: throw MaException("not connected")
        val id = "hifitv-${nextId.getAndIncrement()}"
        val d = CompletableDeferred<JsonElement>()
        pending[id] = d
        val frame = buildJsonObject {
            put("message_id", JsonPrimitive(id))
            put("command", JsonPrimitive(command))
            put("args", args)
        }.toString()
        if (!ws.send(frame)) {
            pending.remove(id); throw MaException("send failed")
        }
        val t0 = System.currentTimeMillis()
        return try {
            val r = withTimeout(timeoutMs) { d.await() }
            Log.d(TAG, "$command -> ${describe(r)} in ${System.currentTimeMillis() - t0} ms")
            r
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            pending.remove(id)
            Log.w(TAG, "$command timed out after $timeoutMs ms")
            throw MaException("timeout waiting for $command")
        } catch (e: MaException) {
            Log.w(TAG, "$command failed: ${e.message}"); throw e
        }
    }

    private fun describe(el: JsonElement): String = when (el) {
        is kotlinx.serialization.json.JsonArray -> "array[${el.size}]"
        is JsonObject -> "object{${el.keys.take(6).joinToString()}}"
        else -> el.toString().take(60)
    }

    /** Send a command; `args` values may be String, Number, Boolean, List<String> or JsonElement. */
    suspend fun send(command: String, vararg args: Pair<String, Any?>): JsonElement {
        awaitConnected()
        val obj = buildJsonObject {
            for ((k, v) in args) {
                if (v == null) continue
                put(k, when (v) {
                    is JsonElement -> v
                    is String -> JsonPrimitive(v)
                    is Number -> JsonPrimitive(v)
                    is Boolean -> JsonPrimitive(v)
                    is List<*> -> kotlinx.serialization.json.JsonArray(v.map { JsonPrimitive(it.toString()) })
                    else -> JsonPrimitive(v.toString())
                })
            }
        }
        return sendRaw(command, obj)
    }

    // JSON → objects is done on Dispatchers.Default: this TV has a slow CPU and decoding a page of
    // albums on the main thread was enough to trigger an ANR.

    suspend fun players(): List<Player> = decodeList(send("players/all"), Player.serializer(), "player")

    suspend fun queues(): List<PlayerQueue> = decodeList(send("player_queues/all"), PlayerQueue.serializer(), "queue")

    suspend fun activeQueue(playerId: String): PlayerQueue? {
        val el = send("player_queues/get_active_queue", "player_id" to playerId)
        if (el is JsonNull) return null
        return withContext(Dispatchers.Default) { runCatching { maJson.decodeFromJsonElement(PlayerQueue.serializer(), el) }.getOrNull() }
    }

    suspend fun libraryItems(kind: String, offset: Int, limit: Int, orderBy: String = "sort_name", favoriteOnly: Boolean = false): List<MediaItem> {
        val extra: Array<Pair<String, Any?>> = if (kind == "artists") arrayOf("album_artists_only" to true) else emptyArray()
        val el = send("music/$kind/library_items", "limit" to limit, "offset" to offset, "order_by" to orderBy, "favorite" to (if (favoriteOnly) true else null), *extra)
        return decodeList(el, MediaItem.serializer(), "media item")
    }

    /** Ask the server to re-scan its providers. Returns once the sync is queued, not once it finishes. */
    suspend fun startSync() { send("music/sync") }

    /** Total album count, used only for a loading progress readout; null if the command fails or is absent. */
    /** Count for a library kind ("playlists", "radios", ...) used only for a loading readout. */
    suspend fun libraryCount(kind: String): Int? = runCatching {
        val el = send("music/$kind/count")
        (el as? JsonPrimitive)?.intOrNull ?: (el as? JsonObject)?.get("count")?.jsonPrimitive?.intOrNull
    }.getOrNull()

    /** Library-wide search; each section decoded leniently (results may be ItemMappings). */
    suspend fun search(query: String, limit: Int = 12): Map<String, List<MediaItem>> {
        val el = send("music/search", "search_query" to query, "limit" to limit, "library_only" to true,
            "media_types" to listOf("artist", "album", "track", "playlist", "radio"))
        val obj = el as? JsonObject ?: return emptyMap()
        return withContext(Dispatchers.Default) {
            listOf("artists", "albums", "tracks", "playlists", "radio").associateWith { key ->
                (obj[key] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { item ->
                    runCatching { maJson.decodeFromJsonElement(MediaItem.serializer(), item) }.getOrNull()
                } ?: emptyList()
            }
        }
    }

    suspend fun addFavorite(item: MediaItem) { send("music/favorites/add_item", "item" to (item.uri ?: return)) }
    suspend fun removeFavorite(item: MediaItem) { send("music/favorites/remove_item", "media_type" to item.mediaType, "library_item_id" to item.itemId) }

    suspend fun moveQueueItem(queueId: String, queueItemId: String, shift: Int) { send("player_queues/move_item", "queue_id" to queueId, "queue_item_id" to queueItemId, "pos_shift" to shift) }
    suspend fun deleteQueueItem(queueId: String, queueItemId: String) { send("player_queues/delete_item", "queue_id" to queueId, "item_id_or_index" to queueItemId) }
    suspend fun clearQueue(queueId: String) { send("player_queues/clear", "queue_id" to queueId) }

    suspend fun setShuffle(queueId: String, enabled: Boolean) { send("player_queues/shuffle", "queue_id" to queueId, "shuffle_enabled" to enabled) }
    suspend fun setRepeat(queueId: String, mode: String) { send("player_queues/repeat", "queue_id" to queueId, "repeat_mode" to mode) }
    suspend fun seek(queueId: String, positionSeconds: Int) { send("player_queues/seek", "queue_id" to queueId, "position" to positionSeconds) }

    suspend fun groupPlayer(playerId: String, targetPlayer: String) { send("players/cmd/group", "player_id" to playerId, "target_player" to targetPlayer) }
    suspend fun ungroupPlayer(playerId: String) { send("players/cmd/ungroup", "player_id" to playerId) }

    suspend fun artistsCount(): Int? = runCatching {
        val el = send("music/artists/count", "album_artists_only" to true)
        (el as? JsonPrimitive)?.intOrNull ?: (el as? JsonObject)?.get("count")?.jsonPrimitive?.intOrNull
    }.getOrNull()

    suspend fun albumsCount(): Int? = runCatching {
        val el = send("music/albums/count")
        (el as? JsonPrimitive)?.intOrNull ?: (el as? JsonObject)?.get("count")?.jsonPrimitive?.intOrNull
    }.getOrNull()

    suspend fun artistAlbums(item: MediaItem, inLibraryOnly: Boolean = false): List<MediaItem> =
        decodeList(send("music/artists/artist_albums", "item_id" to item.itemId, "provider_instance_id_or_domain" to item.provider, "in_library_only" to inLibraryOnly), MediaItem.serializer(), "album")

    suspend fun albumTracks(item: MediaItem): List<MediaItem> =
        decodeList(send("music/albums/album_tracks", "item_id" to item.itemId, "provider_instance_id_or_domain" to item.provider), MediaItem.serializer(), "track")

    suspend fun playlistTracks(item: MediaItem): List<MediaItem> =
        decodeList(send("music/playlists/playlist_tracks", "item_id" to item.itemId, "provider_instance_id_or_domain" to item.provider), MediaItem.serializer(), "track")

    suspend fun queueItems(queueId: String, offset: Int = 0, limit: Int = 500): List<QueueItem> =
        decodeList(send("player_queues/items", "queue_id" to queueId, "offset" to offset, "limit" to limit), QueueItem.serializer(), "queue item")

    suspend fun playIndex(queueId: String, index: Int) { send("player_queues/play_index", "queue_id" to queueId, "index" to index) }

    /** Folder-style browsing across providers, like the web UI's Browse page. `null` = root. */
    suspend fun browse(path: String?): List<MediaItem> =
        decodeList(send("music/browse", "path" to path), MediaItem.serializer(), "browse item")

    private suspend fun <T> decodeList(el: JsonElement, ser: kotlinx.serialization.KSerializer<T>, what: String): List<T> =
        withContext(Dispatchers.Default) {
            el.asItemArray().mapNotNull { item ->
                runCatching { maJson.decodeFromJsonElement(ser, item) }
                    .onFailure { Log.w(TAG, "bad $what: ${it.message}") }.getOrNull()
            }
        }

    suspend fun playMedia(queueId: String, uris: List<String>, option: String = "replace", startItem: String? = null) {
        send("player_queues/play_media", "queue_id" to queueId, "media" to uris, "option" to option, "start_item" to startItem)
    }

    suspend fun playerCmd(cmd: String, playerId: String, vararg args: Pair<String, Any?>) {
        send("players/cmd/$cmd", "player_id" to playerId, *args)
    }

    /** Creates a 10-year token so a TV that sits idle for a month does not get logged out. */
    suspend fun createLongLivedToken(name: String): String? =
        runCatching { send("auth/token/create", "name" to name).findStringDeep("token") }
            .onFailure { Log.w(TAG, "auth/token/create failed: ${it.message}") }.getOrNull()

    fun close() {
        disconnect()
        scope.cancel()
    }

    companion object {
        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // websocket: no read timeout
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }
}
