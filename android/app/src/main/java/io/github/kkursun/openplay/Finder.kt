package io.github.kkursun.openplay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address

/** A service found on the network with mDNS: its name, address, port and TXT record. */
data class Found(val name: String, val host: String, val port: Int, val txt: Map<String, String>)

/** Looks for mDNS services, as pyatv's and pychromecast's scans do on the PC. */
class Finder(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)

    /** The services of a type ("_googlecast._tcp") that answer within seconds. */
    suspend fun find(type: String, seconds: Long): List<Found> {
        val seen = mutableListOf<NsdServiceInfo>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) { synchronized(seen) { seen += info } }
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) {}
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
        }
        nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
        try {
            delay(seconds * 1000)
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
        val names = synchronized(seen) { seen.distinctBy { it.serviceName } }
        return names.mapNotNull { resolve(it) }
    }

    @Suppress("DEPRECATION") // resolveService works on every version; its replacement needs Android 14
    private suspend fun resolve(info: NsdServiceInfo): Found? = resolving.withLock { // one at a time before Android 14
        val done = CompletableDeferred<NsdServiceInfo?>()
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, code: Int) { done.complete(null) }
            override fun onServiceResolved(info: NsdServiceInfo) { done.complete(info) }
        })
        val resolved = withTimeoutOrNull(5000) { done.await() } ?: return@withLock null
        val host = if (Build.VERSION.SDK_INT >= 34) {
            resolved.hostAddresses.firstOrNull { it is Inet4Address } ?: resolved.hostAddresses.firstOrNull()
        } else {
            resolved.host
        } ?: return@withLock null
        val txt = resolved.attributes.mapValues { (_, v) -> v?.toString(Charsets.UTF_8) ?: "" }
        Found(resolved.serviceName, host.hostAddress ?: return@withLock null, resolved.port, txt)
    }

    companion object {
        private val resolving = Mutex()
    }
}
