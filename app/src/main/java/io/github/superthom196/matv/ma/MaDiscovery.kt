package io.github.superthom196.matv.ma

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
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

    fun discover(): Flow<DiscoveredServer> = callbackFlow {
        val seen = HashSet<String>()
        fun offer(s: DiscoveredServer) {
            if (seen.add(s.info.serverId)) trySend(s)
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
            val prefixes = localIpv4Prefixes()
            Log.i(TAG, "sweeping ${prefixes.joinToString()}")
            val gate = Semaphore(32)
            for (prefix in prefixes) {
                for (i in 1..254) {
                    launch {
                        gate.withPermit {
                            val base = "http://$prefix.$i:$MA_DEFAULT_PORT"
                            val info = withTimeoutOrNull(1500) { runCatching { client.fetchInfo(base, timeoutMs = 600) }.getOrNull() }
                            if (info != null) offer(DiscoveredServer(base, info, "scan"))
                        }
                    }
                }
            }
        }

        awaitClose {
            runCatching { nsd?.stopServiceDiscovery(listener) }
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
