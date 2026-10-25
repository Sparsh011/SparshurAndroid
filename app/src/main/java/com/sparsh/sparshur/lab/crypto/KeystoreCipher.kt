package com.sparsh.sparshur.lab.crypto

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/** Local-only AES exercise. This key is never sent to the Mac. */
class KeystoreCipher(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("bridge_lab_state", Context.MODE_PRIVATE)

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun existingKey(): SecretKey = keyStore().getKey(KEY_ALIAS, null) as? SecretKey
        ?: error("The original key is missing. Old envelopes cannot be decrypted.")

    private fun getOrCreateKey(): SecretKey {
        val store = keyStore()
        if (store.containsAlias(KEY_ALIAS)) return existingKey()
        // State without its key signals loss/restore. Never silently replace that identity.
        check(!preferences.contains("sender_id") && !preferences.contains("counter")) {
            "The original Keystore key was lost. Reinstall the lab to start a new identity."
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .setUserAuthenticationRequired(false)
                    .build()
            )
            generateKey()
        }
    }

    fun describeKey(): String = synchronized(lock) {
        val key = getOrCreateKey()
        val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo
        val protection = if (Build.VERSION.SDK_INT >= 31) {
            when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> "StrongBox hardware"
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "Trusted execution hardware"
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> "Software Keystore"
                else -> "Keystore; hardware level not reported"
            }
        } else {
            @Suppress("DEPRECATION")
            if (info.isInsideSecureHardware) "Hardware-backed Keystore" else "Software Keystore"
        }
        "AES-256 · $protection · key is nonexportable"
    }

    fun encrypt(text: String): LabEnvelope = synchronized(lock) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.isNotEmpty() && bytes.size <= LabHeader.MAX_TEXT_BYTES) {
            "Use between 1 and 4096 UTF-8 bytes of sample text."
        }
        val key = getOrCreateKey()
        val counter = preferences.getLong("counter", 0L) + 1L
        check(counter in 1..LabHeader.MAX_ENCRYPTIONS) {
            "This learning key reached its 10,000-operation limit. Reinstall to start a new lab."
        }
        val sender = preferences.getString("sender_id", null) ?: UUID.randomUUID().toString()
        // Reserve durably BEFORE use. Failed attempts may leave gaps, which is intentional.
        check(preferences.edit().putString("sender_id", sender).putLong("counter", counter).commit()) {
            "Could not save the encryption counter. Nothing was encrypted."
        }
        val header = LabHeader(sender, UUID.randomUUID().toString(), counter)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // No IV parameter: AndroidKeyStore generates the random nonce.
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(header.associatedData())
        LabEnvelope(header, cipher.iv.copyOf(), cipher.doFinal(bytes))
    }

    fun decrypt(envelope: LabEnvelope): String = synchronized(lock) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, existingKey(), GCMParameterSpec(128, envelope.nonce))
        cipher.updateAAD(envelope.header.associatedData())
        String(cipher.doFinal(envelope.ciphertext), Charsets.UTF_8)
    }

    companion object {
        private const val KEY_ALIAS = "bridge.lab.aes256.v1"
        private val lock = Any()
    }
}
