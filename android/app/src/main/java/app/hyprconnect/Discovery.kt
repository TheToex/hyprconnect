package app.hyprconnect

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Finds the PC again via mDNS (`_hyprconnect._tcp`) after its IP changed. */
object Discovery {
    suspend fun find(ctx: Context, fp: String, timeoutMs: Long = 5000): Pair<String, Int>? =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
                lateinit var listener: NsdManager.DiscoveryListener
                var done = false
                fun finish(r: Pair<String, Int>?) {
                    if (done) return
                    done = true
                    runCatching { nsd.stopServiceDiscovery(listener) }
                    if (cont.isActive) cont.resume(r)
                }
                listener = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(t: String) {}
                    override fun onDiscoveryStopped(t: String) {}
                    override fun onStartDiscoveryFailed(t: String, e: Int) = finish(null)
                    override fun onStopDiscoveryFailed(t: String, e: Int) {}
                    override fun onServiceLost(s: NsdServiceInfo) {}
                    override fun onServiceFound(s: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        nsd.resolveService(s, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(s: NsdServiceInfo, e: Int) {}
                            override fun onServiceResolved(r: NsdServiceInfo) {
                                val got = r.attributes["fp"]?.toString(Charsets.UTF_8)
                                @Suppress("DEPRECATION")
                                val h = r.host?.hostAddress
                                if (got != null && fp.startsWith(got) && h != null) finish(h to r.port)
                            }
                        })
                    }
                }
                nsd.discoverServices("_hyprconnect._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
                cont.invokeOnCancellation { runCatching { nsd.stopServiceDiscovery(listener) } }
            }
        }
}
