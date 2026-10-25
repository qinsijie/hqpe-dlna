package com.github.qinsijie.hqpedlna

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import javax.xml.parsers.DocumentBuilderFactory

data class UpnpDevice(
    val friendlyName: String,
    val ip: String,
    val port: Int,
    val location: String,
    val controlUrl: String
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
                                    val (friendlyName, controlUrl) = fetchDeviceDetails(location, ip, port)
                                    val device = UpnpDevice(friendlyName, ip, port, location, controlUrl)
                                    discoveredDevices[key] = device
                                    Log.i(TAG, "Discovered device: $friendlyName, controlUrl: $controlUrl")
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

    private fun fetchDeviceDetails(locationUrl: String, ip: String, port: Int): Pair<String, String> {
        val defaultControlUrl = "http://$ip:$port/upnp/control/rendertransport"
        var friendlyName = "UPnP Device ($ip:$port)"
        var controlUrl = defaultControlUrl

        try {
            val request = Request.Builder().url(locationUrl).build()
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val xml = response.body?.string()
                    if (!xml.isNullOrBlank()) {
                        friendlyName = parseFriendlyName(xml) ?: friendlyName
                        val parsedControl = parseAvTransportControlUrl(locationUrl, xml)
                        if (!parsedControl.isNullOrBlank()) {
                            controlUrl = parsedControl
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch device description XML from $locationUrl: ${e.message}")
        }

        return Pair(friendlyName, controlUrl)
    }

    private fun parseFriendlyName(xml: String): String? {
        val matcher = Pattern.compile("(?i)<friendlyName>([^<]+)</friendlyName>").matcher(xml)
        return if (matcher.find()) {
            matcher.group(1)?.trim()
        } else null
    }

    private fun parseAvTransportControlUrl(locationUrl: String, xml: String): String? {
        // Attempt DOM parsing first
        try {
            val factory = DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
            val serviceNodes = doc.getElementsByTagName("service")

            for (i in 0 until serviceNodes.length) {
                val serviceElem = serviceNodes.item(i) as? Element ?: continue
                val serviceType = serviceElem.getElementsByTagName("serviceType").item(0)?.textContent ?: ""
                val serviceId = serviceElem.getElementsByTagName("serviceId").item(0)?.textContent ?: ""

                if (serviceType.contains("AVTransport", ignoreCase = true) || serviceId.contains("AVTransport", ignoreCase = true)) {
                    val rawControl = serviceElem.getElementsByTagName("controlURL").item(0)?.textContent?.trim()
                    if (!rawControl.isNullOrEmpty()) {
                        val resolved = URL(URL(locationUrl), rawControl).toString()
                        Log.i(TAG, "Found AVTransport controlURL via DOM: $resolved (raw: $rawControl)")
                        return resolved
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "DOM parsing for AVTransport failed: ${e.message}, falling back to regex")
        }

        // Fallback: Regex parsing across service blocks
        try {
            val servicePattern = Pattern.compile("(?s)<service>(.*?)</service>", Pattern.CASE_INSENSITIVE)
            val matcher = servicePattern.matcher(xml)
            while (matcher.find()) {
                val serviceBlock = matcher.group(1) ?: continue
                if (serviceBlock.contains("AVTransport", ignoreCase = true)) {
                    val controlMatcher = Pattern.compile("(?i)<controlURL>\\s*([^<\\s]+)\\s*</controlURL>").matcher(serviceBlock)
                    if (controlMatcher.find()) {
                        val raw = controlMatcher.group(1)?.trim()
                        if (!raw.isNullOrEmpty()) {
                            val resolved = URL(URL(locationUrl), raw).toString()
                            Log.i(TAG, "Found AVTransport controlURL via Regex: $resolved (raw: $raw)")
                            return resolved
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Regex parsing for AVTransport failed: ${e.message}")
        }

        return null
    }
}
