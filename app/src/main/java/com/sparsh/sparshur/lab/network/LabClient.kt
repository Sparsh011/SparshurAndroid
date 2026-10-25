package com.sparsh.sparshur.lab.network

import com.sparsh.sparshur.BuildConfig
import com.sparsh.sparshur.lab.paired.PairedProtocol
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import org.json.JSONObject

class LabClient {
    fun pair(endpoint: LocalEndpoint, request: JSONObject): JSONObject =
        request(endpoint, "/pairing/complete", request.toString())

    fun paired(endpoint: LocalEndpoint, message: JSONObject, peer: PairedProtocol.Peer): Boolean =
        PairedProtocol.verifyReceipt(peer, message, request(endpoint, "/paired-message", message.toString()))
    fun check(endpoint: LocalEndpoint): String {
        val response = request(endpoint, "/health", null)
        check(
            response.getString("service") == "bridge-learning-observer" &&
                response.getInt("lab_version") == 1
        ) { "This is not the Bridge lab receiver." }
        return "Receiver reachable. HTTP lab only; the laptop's identity is not authenticated."
    }

    fun send(endpoint: LocalEndpoint, body: String): String {
        val response = request(endpoint, "/message", body)
        check(response.getBoolean("accepted")) { "The receiver did not accept this observation." }
        return if (response.getBoolean("duplicate")) {
            "Already observed: the receiver ignored this duplicate message ID."
        } else {
            "Laptop received the bytes. Receipt does not mean decryption or sender verification."
        }
    }

    private fun request(endpoint: LocalEndpoint, path: String, body: String?): JSONObject {
        check(BuildConfig.DEBUG) { "Local HTTP sending is available only in the debug learning build." }
        val connection = URL(endpoint.url + path).openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            check(status == 200 || (body != null && status == 202)) {
                if (path == "/paired-message" || path == "/pairing/complete") {
                    "Mac rejected the request (HTTP $status). Pairing, signature, tag, replay or storage check failed."
                } else {
                    "Receiver returned HTTP $status. Check the receiver and its session capacity."
                }
            }
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(512)
                while (true) {
                    val count = input.read(chunk)
                    if (count < 0) break
                    check(output.size() + count <= 4096) { "Unexpected receiver response size." }
                    output.write(chunk, 0, count)
                }
                output.toByteArray()
            }
            return JSONObject(String(bytes, Charsets.UTF_8))
        } finally {
            connection.disconnect()
        }
    }
}
