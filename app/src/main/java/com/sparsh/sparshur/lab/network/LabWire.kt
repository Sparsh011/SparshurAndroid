package com.sparsh.sparshur.lab.network

import android.util.Base64
import com.sparsh.sparshur.lab.crypto.LabEnvelope
import com.sparsh.sparshur.lab.crypto.LabHeader
import java.util.UUID
import org.json.JSONObject

object LabWire {
    fun base64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    fun encrypted(envelope: LabEnvelope): String = with(envelope) {
        JSONObject()
            .put("lab_version", header.version)
            .put("mode", "encrypted")
            .put("algorithm", "AES-256-GCM")
            .put("message_id", header.messageId)
            .put("sender_id", header.senderId)
            .put("recipient_id", header.recipientId)
            .put("message_type", header.messageType)
            .put("counter", header.counter)
            .put("nonce", base64(nonce))
            .put("aad", base64(header.associatedData()))
            .put("ciphertext", base64(ciphertext))
            .toString(2)
    }

    fun plaintext(text: String): String {
        require(text.toByteArray(Charsets.UTF_8).size in 1..LabHeader.MAX_TEXT_BYTES)
        return JSONObject()
            .put("lab_version", 1)
            .put("mode", "plaintext")
            .put("message_id", UUID.randomUUID().toString())
            .put("text", text)
            .toString(2)
    }
}
