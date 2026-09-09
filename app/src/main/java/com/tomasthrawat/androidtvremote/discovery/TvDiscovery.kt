package com.tomasthrawat.androidtvremote.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

data class DiscoveredTv(val name: String, val host: String, val port: Int)

private const val SERVICE_TYPE = "_androidtvremote2._tcp."

/**
 * Wraps Android's built-in NsdManager (mDNS) to find Android TV / Google TV
 * devices advertising the Android TV Remote v2 service on the local network.
 */
class TvDiscovery(context: Context, private val onFound: (DiscoveredTv) -> Unit) {

    private val nsdManager = context.applicationContext
        .getSystemService(Context.NSD_SERVICE) as NsdManager
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    fun start() {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val host = serviceInfo.host?.hostAddress ?: return
                        onFound(DiscoveredTv(serviceInfo.serviceName, host, serviceInfo.port))
                    }
                })
            }
        }
        discoveryListener = listener
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun stop() {
        discoveryListener?.let { runCatching { nsdManager.stopDiscovery(it) } }
        discoveryListener = null
    }
}
