package com.github.qinsijie.hqpedlna

import android.content.Context
import android.util.Log
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

object NetworkUtils {
    private const val TAG = "NetworkUtils"

    /**
     * Resolves the local IPv4 address that routes towards the target IP.
     * Uses UDP socket connect (kernel routing lookup) which does not send any packets over wire.
     * Falls back to active WiFi (wlan) address, and then to other physical non-VPN interfaces.
     */
    fun getLocalIpAddress(context: Context, targetIp: String? = null, targetPort: Int = 80): String {
        // 1. If target IP is provided and valid, try UDP kernel routing probe
        if (!targetIp.isNullOrBlank() && !targetIp.startsWith("127.")) {
            val routedIp = getIpViaRoutingProbe(targetIp, targetPort)
            if (!routedIp.isNullOrBlank()) {
                Log.i(TAG, "Resolved local IP via kernel routing probe to $targetIp: $routedIp")
                return routedIp
            }
        }

        // 2. Try WiFi network interface directly (wlan0, etc.)
        val wifiIp = getWifiIpAddress()
        if (!wifiIp.isNullOrBlank()) {
            Log.i(TAG, "Resolved local IP via wlan interface: $wifiIp")
            return wifiIp
        }

        // 3. Iterate physical non-VPN interfaces
        val physicalIp = getPhysicalInterfaceIp()
        if (!physicalIp.isNullOrBlank()) {
            Log.i(TAG, "Resolved local IP via physical interface: $physicalIp")
            return physicalIp
        }

        return "127.0.0.1"
    }

    private fun getIpViaRoutingProbe(targetIp: String, targetPort: Int): String? {
        return try {
            DatagramSocket().use { socket ->
                val port = if (targetPort in 1..65535) targetPort else 80
                socket.connect(InetAddress.getByName(targetIp), port)
                val localAddr = socket.localAddress
                if (localAddr is Inet4Address && !localAddr.isLoopbackAddress && !localAddr.isAnyLocalAddress) {
                    val host = localAddr.hostAddress
                    if (!host.isNullOrBlank() && !host.startsWith("127.")) {
                        val intf = NetworkInterface.getByInetAddress(localAddr)
                        val name = intf?.name?.lowercase() ?: ""
                        if (!isIgnoredInterface(name)) {
                            return host
                        } else {
                            Log.w(TAG, "Routing probe returned VPN interface ($name): $host, bypassing")
                        }
                    }
                }
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Routing probe failed for $targetIp: ${e.message}")
            null
        }
    }

    private fun getWifiIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val name = intf.name.lowercase()
                if (name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("eth")) {
                    for (addr in intf.inetAddresses) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            val host = addr.hostAddress
                            if (!host.isNullOrBlank() && !host.startsWith("127.")) {
                                return host
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking wlan interface: ${e.message}")
        }
        return null
    }

    private fun getPhysicalInterfaceIp(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val name = intf.name.lowercase()
                if (isIgnoredInterface(name)) continue

                for (addr in intf.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress
                        if (!host.isNullOrBlank() && !host.startsWith("127.")) {
                            return host
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error enumerating physical interfaces: ${e.message}")
        }
        return null
    }

    fun isIgnoredInterface(name: String): Boolean {
        val lower = name.lowercase()
        return lower.startsWith("tun") ||
                lower.startsWith("tap") ||
                lower.startsWith("ppp") ||
                lower.startsWith("p2p") ||
                lower.startsWith("dummy") ||
                lower.startsWith("rmnet") ||
                lower.startsWith("ccmni") ||
                lower.startsWith("vbox") ||
                lower.startsWith("virbr") ||
                lower.startsWith("docker") ||
                lower.startsWith("wg") ||
                lower.contains("vpn")
    }
}
