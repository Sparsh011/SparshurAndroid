package com.sparsh.sparshur.lab.paired

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/** Software Ed25519 seed: encrypted at rest, usable in app memory. Never sent to Mac. */
class PairingStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "paired-identity-v2.bin"))
    private val preferences = context.getSharedPreferences("paired_mac_v2", Context.MODE_PRIVATE)
    private val store get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val alias = "bridge.paired.identity.wrap.v2"
    private val domain = "bridge-v2/android-identity-at-rest".toByteArray()

    fun identity(): PairedProtocol.Identity = synchronized(lock) {
        val exists = file.baseFile.exists()
        var key = store.getKey(alias, null) as? SecretKey
        if (exists) {
            check(key != null) { "The pairing identity's Keystore key is missing. Do not silently replace it." }
            val bytes = file.readFully()
            check(bytes.size in 29..2048)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            cipher.updateAAD(domain)
            val decoded = cipher.doFinal(bytes.copyOfRange(12, bytes.size))
            try {
                val body = JSONObject(String(decoded, Charsets.UTF_8))
                return@synchronized PairedProtocol.Identity(
                    PairedProtocol.uuid(body.getString("sender_id")),
                    PairedProtocol.bytes(body.getString("seed"), 32..32),
                    PairedProtocol.bytes(body.getString("public_key"), 32..32)
                )
            } finally {
                decoded.fill(0)
            }
        }
        check(key == null && !preferences.contains("peer")) {
            "Incomplete local identity. Clear lab app data to start over."
        }
        key = KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true).build()
            )
            generateKey()
        }
        val identity = PairedProtocol.newIdentity()
        val plaintext = JSONObject().put("sender_id", identity.senderId).put("seed", PairedProtocol.b64(identity.seed))
            .put("public_key", PairedProtocol.b64(identity.publicKey)).toString().toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(domain)
        val encrypted = cipher.iv + cipher.doFinal(plaintext)
        plaintext.fill(0)
        val output = file.startWrite()
        try {
            output.write(encrypted)
            file.finishWrite(output)
        } catch (e: Exception) {
            file.failWrite(output)
            throw e
        }
        identity
    }
    fun peer(): PairedProtocol.Peer? = synchronized(lock) {
        preferences.getString("peer", null)?.let { PairedProtocol.peer(JSONObject(it)) }
    }
    fun savePeer(peer: PairedProtocol.Peer) = synchronized(lock) {
        val old = peer()
        // An exact pairing retry must not reset the epoch's send counter.
        val counter = if (old?.pairingId == peer.pairingId) preferences.getLong("counter", 0) else 0L
        check(preferences.edit().putString("peer", peer.json().toString()).putLong("counter", counter).commit()) {
            "Could not save the paired Mac."
        }
    }
    fun reserve(peer: PairedProtocol.Peer): Long = synchronized(lock) {
        check(peer()?.pairingId == peer.pairingId) { "Pairing changed. Encrypt again." }
        val counter = preferences.getLong("counter", 0) + 1
        check(counter in 1..1000000)
        check(preferences.edit().putLong("counter", counter).commit()) {
            "Counter could not be saved; nothing encrypted."
        }
        counter
    }
    companion object {
        private val lock = Any()
    }
}
