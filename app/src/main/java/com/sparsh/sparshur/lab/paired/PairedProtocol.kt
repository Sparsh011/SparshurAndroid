package com.sparsh.sparshur.lab.paired

import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.hybrid.HpkeParameters
import com.google.crypto.tink.hybrid.HpkePublicKey
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.subtle.Base64
import com.google.crypto.tink.subtle.ChaCha20Poly1305
import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.Ed25519Verify
import com.google.crypto.tink.util.Bytes
import com.sparsh.sparshur.lab.network.LocalEndpoint
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/** Byte-for-byte contract with tools/mac_endpoint/main.swift; see spec/PAIRED_V2.md. */
object PairedProtocol {
    const val SUITE = "X25519-SHA256-CHACHA20POLY1305"
    private val rng = SecureRandom()
    fun b64(bytes: ByteArray): String = Base64.encode(bytes)
    fun bytes(value: String, size: IntRange): ByteArray {
        require(value.length <= 12000) { "Encoded field is too large." }
        val decoded = Base64.decode(value)
        require(decoded.size in size && b64(decoded) == value) { "Invalid encoded field." }
        return decoded
    }
    fun fields(body: JSONObject, names: String) {
        require(body.keys().asSequence().toSet() == names.split(' ').toSet()) { "Unexpected protocol fields." }
    }
    fun number(body: JSONObject, name: String): Long {
        val value = body.get(name)
        require(value is Int || value is Long) { "Expected an integer." }
        return (value as Number).toLong()
    }
    fun uuid(value: String): String {
        require(UUID.fromString(value).toString() == value) { "Invalid device or message ID." }
        return value
    }
    private fun string(body: JSONObject, name: String): String {
        val value = body.get(name)
        require(value is String && value.length <= 12000) { "Expected a bounded text field." }
        return value
    }
    fun digest(data: String): String = b64(MessageDigest.getInstance("SHA-256").digest(data.toByteArray()))
    fun mac(secret: ByteArray, message: String): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(secret, "HmacSHA256"))
        doFinal(message.toByteArray(Charsets.UTF_8))
    }
    fun fingerprint(peer: Peer): String = MessageDigest.getInstance("SHA-256")
        .digest(peer.hpkeKey + peer.signingKey).joinToString("") { "%02x".format(it.toInt() and 255) }

    data class Identity(val senderId: String, val seed: ByteArray, val publicKey: ByteArray)
    data class Peer(
        val pairingId: String,
        val recipientId: String,
        val hpkeKey: ByteArray,
        val signingKey: ByteArray,
        val host: String,
        val port: Int
    ) {
        fun json(): JSONObject = JSONObject().put("pairing_id", pairingId).put("recipient_id", recipientId)
            .put("hpke_key", b64(hpkeKey)).put("signing_key", b64(signingKey)).put("host", host).put("port", port)
    }
    data class Bundle(val peer: Peer, val secret: ByteArray, val expires: Long) {
        fun transcript(identity: Identity): String =
            "bridge-pair-v2|${peer.pairingId}|${peer.recipientId}|${b64(peer.hpkeKey)}|" +
                "${b64(
                    peer.signingKey
                )}|${peer.host}|${peer.port}|$expires|${identity.senderId}|${b64(identity.publicKey)}"
    }
    fun peer(body: JSONObject): Peer {
        val port = number(body, "port")
        require(port in 1024..65535)
        val endpoint = LocalEndpoint(string(body, "host"), port.toInt())
        return Peer(
            uuid(string(body, "pairing_id")),
            uuid(string(body, "recipient_id")),
            bytes(string(body, "hpke_key"), 32..32),
            bytes(string(body, "signing_key"), 32..32),
            endpoint.address,
            endpoint.port
        )
    }
    fun bundle(encoded: String, now: Long = System.currentTimeMillis() / 1000): Bundle {
        require(encoded.toByteArray().size <= 2500) { "Pairing bundle is too large." }
        val body = JSONObject(encoded)
        fields(body, "bridge_pairing pairing_id recipient_id hpke_key signing_key host port expires_at secret")
        require(number(body, "bridge_pairing") == 2L) { "Unsupported pairing version." }
        val expires = number(body, "expires_at")
        require(expires > now && expires <= now + 600) {
            "Pairing expired or clocks differ. Create a new QR on the Mac."
        }
        return Bundle(peer(body), bytes(string(body, "secret"), 32..32), expires)
    }
    fun newIdentity(): Identity {
        val pair = Ed25519Sign.KeyPair.newKeyPair()
        return Identity(UUID.randomUUID().toString(), pair.privateKey, pair.publicKey)
    }
    fun pairRequest(bundle: Bundle, identity: Identity): JSONObject {
        val message = "bridge-v2/pair|" + bundle.transcript(identity)
        return JSONObject().put("pairing_id", bundle.peer.pairingId).put("sender_id", identity.senderId)
            .put("sender_key", b64(identity.publicKey)).put("proof", b64(mac(bundle.secret, message)))
            .put("signature", b64(Ed25519Sign(identity.seed).sign(message.toByteArray())))
    }
    fun confirm(bundle: Bundle, identity: Identity, response: JSONObject) {
        fields(response, "pairing_id recipient_id sender_id proof signature")
        require(
            string(response, "pairing_id") == bundle.peer.pairingId &&
                string(response, "recipient_id") == bundle.peer.recipientId &&
                string(response, "sender_id") == identity.senderId
        ) { "Pairing identity mismatch." }
        val message = "bridge-v2/paired|" + bundle.transcript(identity)
        require(MessageDigest.isEqual(bytes(string(response, "proof"), 32..32), mac(bundle.secret, message))) {
            "Pairing proof failed; no Mac keys were saved."
        }
        Ed25519Verify(
            bundle.peer.signingKey
        ).verify(bytes(string(response, "signature"), 64..64), message.toByteArray())
    }
    fun aad(body: JSONObject): String = "bridge-lab|2|${body.getString("pairing_id")}|${body.getString("sender_id")}|" +
        "${body.getString("recipient_id")}|text|${number(body, "counter")}|${body.getString("message_id")}"
    fun signedMessage(body: JSONObject): String = "bridge-v2/message|" + aad(body) +
        "|${body.getString("nonce")}|${body.getString("ciphertext")}|${body.getString("wrapped_key")}"

    fun encrypt(peer: Peer, identity: Identity, counter: Long, text: String): JSONObject {
        require(counter in 1..1000000) { "Pairing counter exhausted. Pair again to start a new epoch." }
        val plaintext = text.toByteArray(Charsets.UTF_8)
        require(plaintext.size in 1..4096) { "Use 1 to 4096 UTF-8 bytes of sample text." }
        val body = JSONObject().put("lab_version", 2).put("mode", "paired").put("suite", SUITE)
            .put("pairing_id", peer.pairingId).put("sender_id", identity.senderId)
            .put("recipient_id", peer.recipientId).put("message_id", UUID.randomUUID().toString())
            .put("message_type", "text").put("counter", counter)
        val contentKey = ByteArray(32).also(rng::nextBytes)
        try {
            // Tink's raw ChaCha output is nonce(12) || ciphertext || tag(16).
            val sealed = ChaCha20Poly1305(contentKey).encrypt(plaintext, aad(body).toByteArray())
            HybridConfig.register()
            val parameters = HpkeParameters.builder()
                .setKemId(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
                .setKdfId(HpkeParameters.KdfId.HKDF_SHA256)
                .setAeadId(HpkeParameters.AeadId.CHACHA20_POLY1305)
                .setVariant(HpkeParameters.Variant.NO_PREFIX).build()
            val publicKey = HpkePublicKey.create(parameters, Bytes.copyFrom(peer.hpkeKey), null)
            val handle = KeysetHandle.newBuilder()
                .addEntry(KeysetHandle.importKey(publicKey).withRandomId().makePrimary()).build()
            val wrapped = handle.getPrimitive(RegistryConfiguration.get(), HybridEncrypt::class.java)
                .encrypt(contentKey, ("bridge-v2/key-wrap|" + aad(body)).toByteArray())
            check(wrapped.size == 80) { "Unexpected HPKE encoding." }
            body.put("nonce", b64(sealed.copyOfRange(0, 12)))
                .put("ciphertext", b64(sealed.copyOfRange(12, sealed.size))).put("wrapped_key", b64(wrapped))
            body.put("signature", b64(Ed25519Sign(identity.seed).sign(signedMessage(body).toByteArray())))
            return body
        } finally {
            contentKey.fill(0)
        }
    }
    fun verifyReceipt(peer: Peer, message: JSONObject, receipt: JSONObject): Boolean {
        fields(receipt, "accepted duplicate message_id pairing_id counter digest signature")
        require(receipt.get("accepted") == true && receipt.get("duplicate") is Boolean)
        require(
            string(receipt, "message_id") == message.getString("message_id") &&
                string(receipt, "pairing_id") == peer.pairingId &&
                number(receipt, "counter") == number(message, "counter")
        )
        val digest = digest(signedMessage(message))
        require(string(receipt, "digest") == digest) { "Receipt does not match the sent envelope." }
        val duplicate = receipt.getBoolean("duplicate")
        val signed = "bridge-v2/receipt|${peer.pairingId}|${message.getString("message_id")}|" +
            "${number(message, "counter")}|$digest|${if (duplicate) 1 else 0}"
        Ed25519Verify(peer.signingKey).verify(bytes(string(receipt, "signature"), 64..64), signed.toByteArray())
        return duplicate
    }
}
