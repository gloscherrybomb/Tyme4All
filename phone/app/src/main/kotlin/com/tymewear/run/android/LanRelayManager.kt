package com.tymewear.run.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.tymewear.run.domain.Constants
import com.tymewear.run.domain.relay.RelayServer
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.net.Inet4Address
import timber.log.Timber

/**
 * Runs the token-protected relay on the phone's Wi-Fi IPv4 address while the LAN overlay
 * setting is on. Binds the concrete address, never 0.0.0.0, so it is never reachable over
 * mobile data. Rebinds when the address changes and stops when Wi-Fi is lost.
 */
class LanRelayManager(
    context: Context,
    private val makeServer: (host: String, token: String) -> RelayServer,
) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var server: RelayServer? = null
    private var boundHost: String? = null
    private var token: String? = null

    @Synchronized
    fun apply(enabled: Boolean, newToken: String?) {
        if (!enabled || newToken == null) { stop(); return }
        if (newToken != token) { stop(); token = newToken }
        if (callback == null) register()
    }

    @Synchronized
    fun stop() {
        callback?.let { try { cm.unregisterNetworkCallback(it) } catch (e: Exception) { Timber.w(e, "unregister failed") } }
        callback = null
        stopServer()
    }

    private fun register() {
        val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) { rebind(lp) }
            override fun onLost(network: Network) { synchronized(this@LanRelayManager) { stopServer() } }
        }
        cm.registerNetworkCallback(req, cb)
        callback = cb
    }

    @Synchronized
    private fun rebind(lp: LinkProperties) {
        val t = token ?: return
        val ip = lp.linkAddresses.map { it.address }.filterIsInstance<Inet4Address>().firstOrNull()?.hostAddress
        if (ip == null) { stopServer(); return }
        if (ip == boundHost && server != null) return
        stopServer()
        try {
            server = makeServer(ip, t).also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
            boundHost = ip
            Graph.lanOverlayUrl.value = "http://$ip:${Constants.RELAY_PORT}/overlay?token=$t"
            Timber.i("LAN relay bound on $ip")
        } catch (e: IOException) {
            Timber.e(e, "LAN relay failed to bind on $ip")
            server = null; boundHost = null
            Graph.lanOverlayUrl.value = null
        }
    }

    private fun stopServer() {
        server?.stop(); server = null; boundHost = null
        Graph.lanOverlayUrl.value = null
    }
}
