package com.github.qinsijie.hqpedlna

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class UpnpDevice(
    val friendlyName: String,
    val ip: String,
    val port: Int,
    val location: String
) {
    override fun toString(): String = "$friendlyName ($ip:$port)"
}

object SsdpScanner {
    private const val TAG = "SsdpScanner"
    private const val MULTICAST_ADDRESS = "239.255.255.250"
    private const val MULTICAST_PORT = 1900

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    suspend fun scan(context: Context, timeoutMs: Int = 3000): List<UpnpDevice> = withContext(Dispatchers.IO) {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val multicastLock = wifiManager?.createMulticastLock("ssdp_multicast_lock")?.apply {
            setReferenceCounted(true)
            acquire()
        }

        val discoveredDevices = mutableMapOf<String, UpnpDevice>()

        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 800
                socket.broadcast = true

                val group = InetAddress.getByName(MULTICAST_ADDRESS)

                // SSDP M-SEARCH broadcast with mandatory quotes around "ssdp:discover"
                val searchTargets = listOf(
                    "urn:schemas-upnp-org:device:MediaRenderer:1",
                    "ssdp:all"
                )

                for (st in searchTargets) {
                    val mSearch = "M-SEARCH * HTTP/1.1\r\n" +
                            "HOST: $MULTICAST_ADDRESS:$MULTICAST_PORT\r\n" +
                            "MAN: \"ssdp:discover\"\r\n" +
                            "MX: 2\r\n" +
                            "ST: $st\r\n" +
                            "\r\n"
                    val sendData = mSearch.toByteArray(Charsets.UTF_8)
                    val sendPacket = DatagramPacket(sendData, sendData.size, group, MULTICAST_PORT)
                    socket.send(sendPacket)
                }

                val startTime = System.currentTimeMillis()
                val receiveBuffer = ByteArray(4096)

                while (System.currentTimeMillis() - startTime < timeoutMs) {
                    val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
                    try {
                        socket.receive(receivePacket)
                        val response = String(receivePacket.data, 0, receivePacket.length, Charsets.UTF_8)
                        val location = extractHeader(response, "LOCATION")
                        if (!location.isNullOrBlank()) {
                            try {
                                val url = URL(location)
                                val ip = url.host
                                val port = if (url.port != -1) url.port else 80
                                val key = "$ip:$port"
                                if (!discoveredDevices.containsKey(key)) {
                                    val friendlyName = fetchFriendlyName(location) ?: "UPnP Renderer ($ip:$port)"
                                    discoveredDevices[key] = UpnpDevice(friendlyName, ip, port, location)
                                    Log.i(TAG, "Discovered UPnP device: $friendlyName at $key")
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed parsing URL or fetching description: $location", e)
                            }
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                        // socket timeout per read, keep looping until timeoutMs expires
                    } catch (e: Exception) {
                        Log.e(TAG, "Error receiving SSDP response", e)
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "SSDP scan error", e)
        } finally {
            try {
                if (multicastLock?.isHeld == true) {
                    multicastLock.release()
                }
            } catch (_: Exception) {}
        }

        discoveredDevices.values.toList()
    }

    private fun extractHeader(response: String, headerName: String): String? {
        val pattern = Pattern.compile("(?i)^$headerName:\\s*(.*)$", Pattern.MULTILINE)
        val matcher = pattern.matcher(response)
        return if (matcher.find()) {
            matcher.group(1)?.trim()
        } else {
            null
        }
    }

    private fun fetchFriendlyName(locationUrl: String): String? {
        return try {
            val request = Request.Builder().url(locationUrl).build()
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val xml = response.body?.string() ?: return null
                    val matcher = Pattern.compile("(?i)<friendlyName>([^<]+)</friendlyName>").matcher(xml)
                    if (matcher.find()) {
                        matcher.group(1)?.trim()
                    } else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }
}
