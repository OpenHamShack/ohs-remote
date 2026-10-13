package com.openhamshack.remote.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
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
import com.openhamshack.remote.api.BoxAddress
import java.net.Inet4Address
import java.net.InetAddress
import kotlin.coroutines.resume

/** A box found on the LAN: what it published (TXT name, version) and where it answered. */
data class DiscoveredBox(val serviceName: String, val name: String, val version: String, val address: BoxAddress)

/**
 * OpenHamShack boxes on this network, by DNS-SD: the box publishes
 * `_openhamshack._tcp` through avahi, instance name its callsign (or host name),
 * TXT name/version/port, SRV port the console's.
 *
 * Only the LAN: mDNS does not cross the tailnet or a router, so typing an
 * address stays the way in from anywhere else. Older boxes get avahi from
 * their daily OS update and may not appear on the first day.
 *
 * NsdManager resolves one service at a time before Android 14 (a second
 * resolve fails with FAILURE_ALREADY_ACTIVE), so resolves are queued and
 * done one after another.
 */
class Discovery(context: Context) {
    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _boxes = MutableStateFlow<List<DiscoveredBox>>(emptyList())
    val boxes: StateFlow<List<DiscoveredBox>> = _boxes.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var listener: NsdManager.DiscoveryListener? = null
    private var resolver: Job? = null
    private val toResolve = Channel<NsdServiceInfo>(Channel.UNLIMITED)

    fun start() {
        if (listener != null || nsd == null) return
        _error.value = null
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
            val txt = si.attributes.mapValues { (_, v) -> v?.toString(Charsets.UTF_8).orEmpty() }
            val port = si.port.takeIf { it > 0 } ?: txt["port"]?.toIntOrNull() ?: BoxAddress.DEFAULT_PORT
            val host = ip.hostAddress?.substringBefore('%') ?: return null
            return DiscoveredBox(si.serviceName, txt["name"]?.ifBlank { null } ?: si.serviceName, txt["version"].orEmpty(),
                BoxAddress(host, port))
        }
    }
}
