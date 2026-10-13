package com.openhamshack.core.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import com.openhamshack.core.net.BoxAddress
import java.net.Inet4Address
import java.net.InetAddress
import kotlin.coroutines.resume

/**
 * A box found on the LAN: what it published and where it answered.
 *
 * Two shapes of advert, both `_openhamshack._tcp` (PLATFORM-PLAN section 2.1):
 * - a **Classic** box or a single app: TXT `kind` ("datv", "stationflow",
 *   ...; none at all is a OpenHamShack DATV box from before kinds, [LEGACY_KIND]),
 *   and [address] is the box itself;
 * - a **OpenHamShack platform** server: TXT `platform=1`, `kinds=datv,nb,...` and
 *   `path=/.well-known/openhamshack`. [address] is then the server's root and
 *   [directory] the path of the list of its apps (OpenHamShackDirectory), where
 *   each app's own base URL is found.
 */
data class DiscoveredBox(
    val serviceName: String,
    val name: String,
    val version: String,
    val kind: String,
    val address: BoxAddress,
    val platform: Boolean = false,
    val kinds: Set<String> = setOf(kind),
    /** The platform's app list, relative to [address]; null for a Classic box. */
    val directory: String? = null,
) {
    companion object {
        const val LEGACY_KIND = "datv"
    }
}

/**
 * Boxes on this network, by DNS-SD `_openhamshack._tcp`, filtered to [kinds]
 * (empty: every kind). TXT: name, version, port, kind - and scheme and path
 * for a box that is not plain `http://host:port/` (a Dock360 module is
 * `https`, under `/icom/`). Absent, they mean http and the root.
 *
 * Only the LAN: mDNS does not cross the tailnet or a router, so typing an
 * address stays the way in from anywhere else. NsdManager is the platform's,
 * so this works on a Fire TV with no Google services.
 *
 * NsdManager resolves one service at a time before Android 14 (a second
 * resolve fails with FAILURE_ALREADY_ACTIVE), so resolves are queued. A
 * multicast lock is held while browsing: some TVs (Fire OS among them)
 * filter multicast on Wi-Fi without one, and the list then stays empty.
 */
class Discovery(context: Context, private val kinds: Set<String> = emptySet()) {
    private val app = context.applicationContext
    private val nsd = app.getSystemService(NsdManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _boxes = MutableStateFlow<List<DiscoveredBox>>(emptyList())
    val boxes: StateFlow<List<DiscoveredBox>> = _boxes.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var listener: NsdManager.DiscoveryListener? = null
    private var resolver: Job? = null
    private val toResolve = Channel<NsdServiceInfo>(Channel.UNLIMITED)
    private var lock: WifiManager.MulticastLock? = null

    fun start() {
        if (listener != null || nsd == null) return
        _error.value = null
        lock = runCatching {
            app.getSystemService(WifiManager::class.java)?.createMulticastLock("openhamshack-discovery")?.apply {
                setReferenceCounted(false); acquire()
            }
        }.getOrNull()
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                scope.launch { _error.value = "Could not look for boxes on this network (error $errorCode)"; listener = null }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                toResolve.trySend(info)
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                scope.launch { _boxes.value = _boxes.value.filter { it.serviceName != info.serviceName } }
            }
        }
        listener = l
        resolver = scope.launch {
            for (info in toResolve) resolve(info)?.let { found ->
                if (kinds.isNotEmpty() && found.kinds.none { it in kinds }) return@let
                _boxes.value = (_boxes.value.filter { it.serviceName != found.serviceName } + found).sortedBy { it.name }
            }
        }
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
    }

    fun stop() {
        listener?.let { runCatching { nsd?.stopServiceDiscovery(it) } }
        listener = null
        resolver?.cancel()
        resolver = null
        runCatching { lock?.release() }
        lock = null
        _boxes.value = emptyList()
    }

    @Suppress("DEPRECATION")   // resolveService/host: the only way below API 34, still works above
    private suspend fun resolve(info: NsdServiceInfo): DiscoveredBox? = withTimeoutOrNull(8000) {
        suspendCancellableCoroutine { cont ->
            nsd!!.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }
                override fun onServiceResolved(si: NsdServiceInfo) {
                    if (cont.isActive) cont.resume(toBox(si))
                }
            })
        }
    }

    companion object {
        const val SERVICE_TYPE = "_openhamshack._tcp."

        @Suppress("DEPRECATION")
        fun toBox(si: NsdServiceInfo): DiscoveredBox? {
            val addrs: List<InetAddress> =
                if (Build.VERSION.SDK_INT >= 34) si.hostAddresses else listOfNotNull(si.host)
            // IPv4 first: a link-local IPv6 needs a scope the URL would have to carry.
            val ip = (addrs.firstOrNull { it is Inet4Address } ?: addrs.firstOrNull()) ?: return null
            val host = ip.hostAddress?.substringBefore('%') ?: return null
            val txt = si.attributes.mapValues { (_, v) -> v?.toString(Charsets.UTF_8).orEmpty() }
            return fromTxt(si.serviceName, host, si.port, txt)
        }

        /** The part of [toBox] that is not NsdManager: TXT and SRV to a box. Unit-tested. */
        fun fromTxt(serviceName: String, host: String, srvPort: Int, txt: Map<String, String>): DiscoveredBox? {
            val port = srvPort.takeIf { it > 0 } ?: txt["port"]?.toIntOrNull() ?: return null
            val rawPath = txt["path"].orEmpty().trim().let { if (it.isEmpty() || it.startsWith("/")) it else "/$it" }
            val platform = txt["platform"]?.trim() == "1"
            // On a platform advert the path is the app directory, not a base.
            val path = if (platform) "" else rawPath.trimEnd('/')
            val scheme = if (txt["scheme"]?.trim()?.lowercase() == "https") "https://" else "http://"
            val address = runCatching {
                BoxAddress.parse(scheme + (if (host.contains(':')) "[$host]" else host) + ":$port" + path)
            }.getOrNull() ?: return null
            val kinds = if (platform) {
                txt["kinds"].orEmpty().split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
            } else {
                setOf(txt["kind"]?.trim()?.lowercase()?.ifBlank { null } ?: DiscoveredBox.LEGACY_KIND)
            }
            return DiscoveredBox(
                serviceName = serviceName,
                name = txt["name"]?.ifBlank { null } ?: serviceName,
                version = txt["version"].orEmpty(),
                kind = kinds.firstOrNull() ?: DiscoveredBox.LEGACY_KIND,
                address = address,
                platform = platform,
                kinds = kinds,
                directory = if (platform) rawPath.ifEmpty { "/.well-known/openhamshack" } else null,
            )
        }
    }
}
