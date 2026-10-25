package com.sparsh.sparshur.network.relay

import android.content.Context
import com.google.crypto.tink.subtle.Ed25519Sign
import com.sparsh.sparshur.lab.paired.PairingStore

/** Holds only public enrollment metadata between operations. Never caches an unwrapped seed. */
class KeystoreRelaySigner private constructor(
    private val store: PairingStore,
    override val deviceId: String,
    val publicKeyBase64: String
) : RelaySigner {
    override fun sign(message: ByteArray): ByteArray {
        check(store.peer() != null) { "An existing paired identity is required." }
        val identity = store.identity()
        try {
            check(identity.senderId == deviceId && RelayAuth.encoded(identity.publicKey) == publicKeyBase64)
            return Ed25519Sign(identity.seed).sign(message)
        } finally {
            identity.seed.fill(0)
        }
    }

    companion object {
        /** Call off the main thread. Does not create or replace an unpaired identity. */
        fun load(context: Context): KeystoreRelaySigner {
            val store = PairingStore(context.applicationContext)
            check(store.peer() != null) { "Pair the local lab first to create your signing identity." }
            val identity = store.identity()
            try {
                RelayAuth.uuid(identity.senderId)
                return KeystoreRelaySigner(store, identity.senderId, RelayAuth.encoded(identity.publicKey))
            } finally {
                identity.seed.fill(0)
            }
        }
    }
}
