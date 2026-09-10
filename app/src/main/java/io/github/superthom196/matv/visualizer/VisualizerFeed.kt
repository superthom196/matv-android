package io.github.superthom196.matv.visualizer

import android.os.SystemClock
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Live band levels from Music Assistant's MilkDrop Visualizer plugin, following one player.
 *
 * The plugin's relay (ws://server/milkdrop_visualizer?player=id) taps the PCM the server is
 * already decoding for a player and sends 1024-sample mono windows, each stamped with the server
 * clock time at which that window finishes sounding on the player. We sync our clock to the
 * server's with its client/time exchange, keep the frames sorted, and on every render pick the
 * frame that is audible right now, FFT it and fold it into 16 bands. Beat frames (from Smart
 * Fades analysis, when the server has it) become a pulse.
 *
 * Wire: text {"type":"auth","token":..} first; then binary [22][int64 BE us][1024 x u8] waveform,
 * [17][int64 BE us][flags] beat; text stream/start|clear|end, color, server/time.
 */
class VisualizerFeed(
    private val wsUrl: String,
    private val token: String,
    private val playerId: String?,
) : BandSource {
    override val levels = FloatArray(16)
    override var beat = 0f
        private set
    override val palette = DEFAULT_PALETTE.copyOf()

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var closed = false
    @Volatile private var authed = false

    private class Frame(val serverUs: Long, val samples: ByteArray)

    private val lock = Any()
    private val frames = ArrayDeque<Frame>()          // sorted by serverUs, oldest first
    private val beats = ArrayDeque<Long>()            // server µs of beats still ahead
    // Clock: serverUs - localUs. Kept as the offset from the lowest-RTT sample of the last few.
    private val offsetSamples = ArrayDeque<Pair<Long, Long>>()   // (rtt, offset)
    @Volatile private var offsetUs = 0L
    @Volatile private var synced = false
    private val _status = MutableStateFlow("connecting")
    /** Human-readable feed state: connecting, authenticated, streaming, closed …, failed …. */
    val status: StateFlow<String> = _status
    private var state: String
        get() = _status.value
        set(v) { _status.value = v }
    @Volatile private var sampleRate = 44100f
    @Volatile private var framesSeen = 0L
    private var lastFrameUs = 0L
    private var lastTimePing = 0L
    private var lastLevelsUs = 0L

    // FFT scratch
    private val re = FloatArray(1024)
    private val im = FloatArray(1024)
    private val window = FloatArray(1024) { 0.5f - 0.5f * cos(2.0 * PI * it / 1024).toFloat() }
    private val bandAvg = FloatArray(16) { -40f }
    private val target = FloatArray(16)
    @Volatile private var consumed = 0L
    @Volatile private var clears = 0L
    @Volatile private var leadMs = 0L

    private fun nowUs() = SystemClock.elapsedRealtimeNanos() / 1000

    fun start() {
        closed = false
        connect()
    }

    fun stop() {
        closed = true
        socket?.close(1000, "bye")
        socket = null
    }

    private fun connect() {
        if (closed) return
        val url = if (playerId != null) "$wsUrl?player=$playerId" else wsUrl
        state = "connecting"
        authed = false
        socket = client.newWebSocket(Request.Builder().url(url).build(), listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(JSONObject().put("type", "auth").put("token", token).toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = JSONObject(text)
            when (msg.optString("type")) {
                "auth_ok" -> { state = "authenticated"; authed = true; sendTimePing(webSocket) }
                "server/time" -> onServerTime(msg.getJSONObject("payload"))
                "stream/start" -> { state = "streaming " + msg.optJSONObject("payload")?.optJSONObject("visualizer")?.optJSONArray("types"); }
                "stream/clear" -> synchronized(lock) {
                    clears++
                    val serverNow = nowUs() + offsetUs
                    while (frames.isNotEmpty() && frames.first().serverUs < serverNow - 500_000) frames.removeFirst()
                    beats.clear()
                }
                "stream/end" -> synchronized(lock) { frames.clear(); beats.clear(); clears++ }
                "color" -> msg.optJSONObject("payload")?.let { p ->
                    val names = listOf("background_dark", "background_light", "primary", "accent", "on_dark", "on_light")
                    var any = false
                    for ((k, name) in names.withIndex()) {
                        val rgb = p.optJSONArray(name) ?: continue
                        if (rgb.length() != 3) continue
                        any = true
                        for (i in 0 until 3) palette[k * 3 + i] = (rgb.getInt(i) / 255f).coerceIn(0f, 1f)
                    }
                    if (!any) System.arraycopy(DEFAULT_PALETTE, 0, palette, 0, palette.size)
                }
                "error" -> state = "error: " + msg.optString("message")
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (bytes.size < 9) return
            val buf = ByteBuffer.wrap(bytes.toByteArray())
            val tag = buf.get().toInt() and 0xff
            val ts = buf.getLong()
            when (tag) {
                22 -> if (buf.remaining() >= 1024) {
                    val samples = ByteArray(1024); buf.get(samples)
                    synchronized(lock) {
                        // Frame cadence gives the sample rate: consecutive windows are 1024 samples apart.
                        if (lastFrameUs != 0L) {
                            val d = ts - lastFrameUs
                            if (d in 5_000..60_000) sampleRate = sampleRate * 0.9f + (1024f * 1_000_000f / d) * 0.1f
                        }
                        lastFrameUs = ts
                        insertFrame(Frame(ts, samples))
                        framesSeen++
                        while (frames.size > 4096) frames.removeFirst()
                    }
                }
                17 -> synchronized(lock) { beats.addLast(ts); while (beats.size > 512) beats.removeFirst() }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            state = "failed: ${t.message ?: t.javaClass.simpleName}"
            Log.w(TAG, "feed failure", t)
            retry()
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // The server closed (4001 = auth rejected). Answer the close or OkHttp never finishes it.
            state = "closed $code $reason"
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            state = "closed $code $reason"
            retry()
        }
    }

    /** Keep frames sorted by timestamp; a frame within 12 ms of an existing one replaces it. */
    private fun insertFrame(f: Frame) {
        if (frames.isEmpty() || frames.last().serverUs < f.serverUs - 12_000) { frames.addLast(f); return }
        // out of order or a re-stamped duplicate: rebuild the tail (rare, small)
        val tail = ArrayList<Frame>()
        while (frames.isNotEmpty() && frames.last().serverUs > f.serverUs - 12_000) tail.add(frames.removeLast())
        frames.addLast(f)
        for (i in tail.indices.reversed()) { val o = tail[i]; if (kotlin.math.abs(o.serverUs - f.serverUs) > 12_000) frames.addLast(o) }
    }

    private fun retry() {
        if (closed) return
        synced = false
        Thread { Thread.sleep(3000); connect() }.start()
    }

    private fun sendTimePing(ws: WebSocket) {
        lastTimePing = nowUs()
        ws.send(JSONObject().put("type", "client/time").put("payload", JSONObject().put("client_transmitted", lastTimePing)).toString())
    }

    private fun onServerTime(p: JSONObject) {
        val t0 = p.getLong("client_transmitted")
        val t3 = nowUs()
        val serverTx = p.getLong("server_transmitted")
        val rtt = t3 - t0
        val offset = serverTx - (t0 + t3) / 2
        synchronized(lock) {
            offsetSamples.addLast(rtt to offset)
            while (offsetSamples.size > 8) offsetSamples.removeFirst()
            offsetUs = offsetSamples.minByOrNull { it.first }!!.second
            synced = true
        }
    }

    override fun update(t: Double) {
        val now = nowUs()
        if (authed) socket?.let { if (!synced || now - lastTimePing > 2_000_000) sendTimePing(it) }
        if (!synced) { decay(); return }
        val serverNow = now + offsetUs
        var frame: Frame? = null
        var beatNow = false
        synchronized(lock) {
            // The frame audible now is the newest one whose end time has passed; drop the rest.
            while (frames.size > 1 && frames.first().serverUs <= serverNow) {
                frame = frames.removeFirst()
                if (frames.first().serverUs > serverNow) break
            }
            if (frame == null && frames.isNotEmpty() && frames.first().serverUs <= serverNow) frame = frames.removeFirst()
            while (beats.isNotEmpty() && beats.first() <= serverNow) { beats.removeFirst(); beatNow = true }
            if (frame != null) consumed++
            leadMs = if (frames.isEmpty()) 0 else (frames.first().serverUs - serverNow) / 1000
        }
        if (beatNow) beat = 1f
        frame?.let { analyse(it.samples); lastLevelsUs = now }
        decay()
    }

    private fun decay() {
        // Levels hold for the frame period and fall away if the feed stalls.
        val stale = nowUs() - lastLevelsUs > 250_000
        for (i in 0 until 16) {
            levels[i] = if (stale) levels[i] * 0.9f else max(target[i], levels[i] * 0.8f)
        }
        beat *= 0.85f
    }

    /** Hann-windowed 1024-point FFT folded into 16 log-spaced bands, each with its own slow AGC. */
    private fun analyse(samples: ByteArray) {
        for (i in 0 until 1024) {
            re[i] = ((samples[i].toInt() and 0xff) - 128) / 128f * window[i]
            im[i] = 0f
        }
        fft(re, im)
        val sr = sampleRate
        val binHz = sr / 1024f
        val fMin = 40f
        val fMax = min(16000f, sr / 2f - binHz)
        val step = ln(fMax / fMin) / 16f
        // Band edges in bins, forced strictly increasing so the lowest bands do not all read
        // the same 43 Hz bin.
        var prevEdge = max(1, (fMin / binHz).toInt())
        for (b in 0 until 16) {
            val hi = fMin * exp(step * (b + 1))
            val i0 = prevEdge
            val i1 = max((hi / binHz).toInt(), i0 + 1).coerceAtMost(511)
            prevEdge = i1 + 1
            var sum = 0f
            for (i in i0..i1) sum += sqrt(re[i] * re[i] + im[i] * im[i])
            val mag = sum / (i1 - i0 + 1)
            // Absolute scale: a full-scale sine lands near 0 dB, so music bands sit around
            // -20..-50 dB, treble lower still; a 1.5 dB/band tilt evens the rings out.
            // Squared so only real energy stands up.
            val db = 20f * log10(mag / 256f + 1e-6f) + 1.5f * b
            val lin = ((db + 52f) / 38f).coerceIn(0f, 1f)
            // Ferrofluid wants transients: most spikes resting, hits shooting up. So mostly
            // energy above the band's own rolling average (~1 s), with a little absolute level
            // so a loud steady tone still shows.
            bandAvg[b] += (db - bandAvg[b]) * 0.03f
            val transient = ((db - bandAvg[b] + 2f) / 10f).coerceIn(0f, 1f)
            target[b] = 0.25f * lin * lin + 0.75f * transient * transient
        }
    }

    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang).toFloat(); val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f; var ci = 0f
                for (k in 0 until len / 2) {
                    val a = i + k; val b = a + len / 2
                    val tr = re[b] * cr - im[b] * ci
                    val ti = re[b] * ci + im[b] * cr
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr; cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

    fun statusLine(): String {
        val buffered = synchronized(lock) { frames.size }
        return "feed: %s  sync %s  %d buffered  lead %d ms  used %d  clears %d  %.0f Hz".format(
            state, if (synced) "ok" else "no", buffered, leadMs, consumed, clears, sampleRate)
    }

    companion object { const val TAG = "MATV/visualizer" }
}
