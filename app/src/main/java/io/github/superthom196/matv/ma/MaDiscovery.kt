package io.github.superthom196.matv.ma

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address

private const val TAG = "MaDiscovery"
const val MA_DEFAULT_PORT = 8095

data class DiscoveredServer(val baseUrl: String, val info: ServerInfo, val via: String)

/**
 * Finds Music Assistant servers on the LAN two ways at once:
 *  - mDNS `_mass._tcp` (fast when it works; Android TV multicast is unreliable), and
 *  - a sweep of the device's own /24 hitting `GET /info` on :8095 (the reliable path).
 * Both feed the same flow; the UI de-duplicates by server_id.
 */
class MaDiscovery(private val context: Context, private val client: MaClient) {

    companion object {
        /** How long one scan runs before the caller stops it. */
        const val WINDOW_MS = 15_000L
    }

    /**
     * @param knownHosts base URLs worth trying before and alongside the sweep — the server we were
     *   last connected to. They are re-probed every couple of seconds for the whole window, because
     *   the usual reason for a scan on a TV is that it has just woken and the network is still coming up.
     */
    fun discover(knownHosts: List<String> = emptyList()): Flow<DiscoveredServer> = callbackFlow {
        val seen = HashSet<String>()
        fun offer(s: DiscoveredServer) {
            if (seen.add(s.info.serverId)) trySend(s)
        }

        // --- the server we already know ---
        val known = launch(Dispatchers.IO) {
            val hosts = knownHosts.map { it.trimEnd('/') }.filter { it.isNotBlank() }.distinct()
            if (hosts.isEmpty()) return@launch
            while (isActive) {
                for (base in hosts) {
                    if (seen.isNotEmpty()) return@launch
                    runCatching { client.fetchInfo(base, timeoutMs = 2500) }
                        .onSuccess { offer(DiscoveredServer(base, it, "saved")) }
                        .onFailure { Log.i(TAG, "known host $base not answering: ${it.message}") }
                }
                delay(2000)
            }
        }

        // --- mDNS ---
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { Log.w(TAG, "mdns start failed $errorCode") }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                nsd?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(si: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        val host = si.host?.hostAddress ?: return
                        val port = if (si.port > 0) si.port else MA_DEFAULT_PORT
                        val base = "http://${if (host.contains(':')) "[$host]" else host}:$port"
                        launch(Dispatchers.IO) {
                            runCatching { client.fetchInfo(base) }.onSuccess { offer(DiscoveredServer(base, it, "mDNS")) }
                        }
                    }
                })
            }
        }
        runCatching { nsd?.discoverServices("_mass._tcp.", NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { Log.w(TAG, "mdns unavailable: ${it.message}") }

        // --- subnet sweep ---
        val sweep = launch(Dispatchers.IO) {
            // No IPv4 address yet means the link is still coming up: wait for it rather than sweeping nothing.
            var prefixes = localIpv4Prefixes()
            var waited = 0L
            while (prefixes.isEmpty() && waited < WINDOW_MS - 4000 && isActive) {
                Log.i(TAG, "no local IPv4 address yet; waiting for the network")
                delay(1500); waited += 1500
                prefixes = localIpv4Prefixes()
            }
            Log.i(TAG, "sweeping ${prefixes.joinToString()}")
            val gate = Semaphore(32)
            for (prefix in prefixes) {
                for (i in 1..254) {
                    launch {
                        gate.withPermit {
                            val base = "http://$prefix.$i:$MA_DEFAULT_PORT"
                            // A busy Pi can take a while over /info; a whole pass still fits the window.
                            val info = withTimeoutOrNull(2500) { runCatching { client.fetchInfo(base, timeoutMs = 1000) }.getOrNull() }
                            if (info != null) offer(DiscoveredServer(base, info, "scan"))
                        }
                    }
                }
            }
        }

        awaitClose {
            runCatching { nsd?.stopServiceDiscovery(listener) }
            known.cancel()
            sweep.cancel()
        }
    }.flowOn(Dispatchers.IO)

    /** The /24 prefixes ("192.168.1") of every IPv4 address on the active network. */
    private fun localIpv4Prefixes(): List<String> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val props: LinkProperties? = cm.activeNetwork?.let { cm.getLinkProperties(it) }
        val fromProps = props?.linkAddresses.orEmpty().mapNotNull { la ->
            (la.address as? Inet4Address)?.hostAddress?.substringBeforeLast('.')
        }
        if (fromProps.isNotEmpty()) return fromProps.distinct()
        // Fallback: enumerate interfaces.
        return runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress!!.substringBeforeLast('.') }
                .distinct()
        }.getOrDefault(emptyList())
    }
}
