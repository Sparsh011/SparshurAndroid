package com.sparsh.sparshur.lab.paired

import com.google.crypto.tink.subtle.ChaCha20Poly1305
import java.nio.ByteBuffer
import java.security.SecureRandom
import org.json.JSONObject

internal data class PairedFile(val name: String, val mime: String, val data: ByteArray) {
    init {
        require(name.isNotBlank() && name !in listOf(".", "..") && name.toByteArray().size <= 255)
        require(name.none { it == '/' || it == '\\' || it.isISOControl() })
        require(MIME.matches(mime))
        require(data.size <= MAX_BYTES)
    }

    fun encode(): ByteArray {
        val metadata = JSONObject().put("name", name).put("mime", mime).toString().toByteArray()
        require(metadata.size <= MAX_METADATA_BYTES)
        return ByteBuffer.allocate(4 + metadata.size + data.size).putInt(metadata.size).put(metadata).put(data).array()
    }

    companion object {
        const val MAX_BYTES = 5 * 1024 * 1024
        private const val MAX_METADATA_BYTES = 2048
        const val MAX_PAYLOAD_BYTES = 4 + MAX_METADATA_BYTES + MAX_BYTES
        const val MAX_WIRE_BYTES = 8 * 1024 * 1024
        private val MIME = Regex("[A-Za-z0-9!#$&^_.+-]{1,64}/[A-Za-z0-9!#$&^_.+-]{1,64}")
        fun safeMime(value: String?) = value?.takeIf(MIME::matches) ?: "application/octet-stream"

        fun decode(payload: ByteArray): PairedFile {
            require(payload.size in 5..MAX_PAYLOAD_BYTES)
            val length = ByteBuffer.wrap(payload).int
            require(length in 1..MAX_METADATA_BYTES && length <= payload.size - 4)
            val metadata = JSONObject(payload.decodeToString(4, 4 + length, throwOnInvalidSequence = true))
            PairedProtocol.fields(metadata, "name mime")
            return PairedFile(metadata.get("name") as String, metadata.get("mime") as String,
                payload.copyOfRange(4 + length, payload.size))
        }
    }
}

internal class FilePull : AutoCloseable {
    private val responseKey = ByteArray(32).also(SecureRandom()::nextBytes)

    fun request(peer: PairedProtocol.Peer, identity: PairedProtocol.Identity, counter: Long): JSONObject =
        PairedProtocol.encryptPayload(peer, identity, counter, "file_pull", responseKey)

    fun open(peer: PairedProtocol.Peer, request: JSONObject, reply: JSONObject): Pair<String, PairedFile>? {
        PairedProtocol.fields(reply, "file_id sealed")
        val id = reply.get("file_id") as String
        if (id.isNotEmpty()) PairedProtocol.uuid(id)
        val aad = "bridge-v2/file-response|${peer.pairingId}|${request.getString("message_id")}|$id"
        val sealed = PairedProtocol.bytes(reply.get("sealed") as String, 28..(PairedFile.MAX_PAYLOAD_BYTES + 28))
        val plaintext = ChaCha20Poly1305(responseKey).decrypt(sealed, aad.toByteArray())
        if (id.isEmpty()) {
            require(plaintext.isEmpty())
            return null
        }
        return id to PairedFile.decode(plaintext)
    }

    override fun close() { responseKey.fill(0) }
}
