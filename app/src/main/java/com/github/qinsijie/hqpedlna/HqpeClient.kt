package com.github.qinsijie.hqpedlna

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URL
import java.util.concurrent.TimeUnit

object HqpeClient {

    private const val TAG = "HqpeClient"
    private val XML_MEDIA_TYPE = "text/xml; charset=\"utf-8\"".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    suspend fun setUri(controlUrl: String, streamUrl: String): Result<Unit> = withContext(Dispatchers.IO) {
        val payload = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
      <InstanceID>0</InstanceID>
      <CurrentURI>$streamUrl</CurrentURI>
      <CurrentURIMetaData></CurrentURIMetaData>
    </u:SetAVTransportURI>
  </s:Body>
</s:Envelope>""".trimIndent()

        executeSoap(controlUrl, "SetAVTransportURI", payload)
    }

    suspend fun play(controlUrl: String): Result<Unit> = withContext(Dispatchers.IO) {
        val payload = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
      <InstanceID>0</InstanceID>
      <Speed>1</Speed>
    </u:Play>
  </s:Body>
</s:Envelope>""".trimIndent()

        executeSoap(controlUrl, "Play", payload)
    }

    suspend fun stop(controlUrl: String): Result<Unit> = withContext(Dispatchers.IO) {
        val payload = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:Stop xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
      <InstanceID>0</InstanceID>
    </u:Stop>
  </s:Body>
</s:Envelope>""".trimIndent()

        executeSoap(controlUrl, "Stop", payload)
    }

    private fun executeSoap(controlUrl: String, action: String, xmlPayload: String): Result<Unit> {
        return try {
            val url = URL(controlUrl)
            val hostHeader = if (url.port != -1) "${url.host}:${url.port}" else url.host
            val soapActionHeader = "\"urn:schemas-upnp-org:service:AVTransport:1#$action\""

            Log.d(TAG, "Executing SOAP $action on $controlUrl (Host: $hostHeader)")

            val request = Request.Builder()
                .url(controlUrl)
                .header("Host", hostHeader)
                .header("SOAPAction", soapActionHeader)
                .header("Content-Type", "text/xml; charset=\"utf-8\"")
                .post(xmlPayload.toRequestBody(XML_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    Log.i(TAG, "SOAP $action succeeded (HTTP ${response.code})")
                    Result.success(Unit)
                } else {
                    Log.e(TAG, "SOAP $action failed (HTTP ${response.code}): $body")
                    Result.failure(IOException("SOAP $action failed: HTTP ${response.code} $body"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "SOAP $action error: ${e.message}", e)
            Result.failure(e)
        }
    }
}
