package com.sparsh.sparshur.lab.crypto

import java.util.UUID

/** Lab metadata has one unambiguous encoding shared with the laptop observer. */
data class LabHeader(
    val senderId: String,
    val messageId: String,
    val counter: Long,
    val version: Int = 1,
    val recipientId: String = "laptop-observer",
    val messageType: String = "text"
) {
    init {
        require(version == 1 && recipientId == "laptop-observer" && messageType == "text")
        require(UUID.fromString(senderId).toString() == senderId)
        require(UUID.fromString(messageId).toString() == messageId)
        require(counter in 1..MAX_ENCRYPTIONS)
    }

    fun associatedData(): ByteArray = "bridge-lab|$version|$senderId|$recipientId|$messageType|$counter|$messageId"
        .toByteArray(Charsets.UTF_8)

    companion object {
        const val MAX_ENCRYPTIONS = 10_000L
        const val MAX_TEXT_BYTES = 4096
    }
}

data class LabEnvelope(val header: LabHeader, val nonce: ByteArray, val ciphertext: ByteArray) {
    init {
        require(nonce.size == 12) { "Expected a 12-byte GCM nonce." }
        require(ciphertext.size in 17..(LabHeader.MAX_TEXT_BYTES + 16))
    }
}
