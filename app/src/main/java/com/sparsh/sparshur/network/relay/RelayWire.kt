package com.sparsh.sparshur.network.relay

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import okio.ByteString
import okio.ByteString.Companion.toByteString

internal const val MAX_RELAY_FRAME = 65536
private const val PACKET_PROTOCOL = "bridge-relay-e2ee-v1"

internal data class RelayChallenge(
    val relayId: String,
    val sessionId: String,
    val nonce: ByteString,
    val challenge: ByteString,
    val expiresAt: ULong
) {
    init {
        require(RelayAuth.validRelayId(relayId))
        RelayAuth.uuid(sessionId)
        require(nonce.size == 32 && challenge.size == 32 && expiresAt != 0uL)
    }

    fun validate(configuration: RelayConfiguration, expectedNonce: ByteString, now: Long) {
        val seconds = now.coerceAtLeast(0).toULong()
        require(relayId == configuration.relayId && nonce == expectedNonce &&
            expiresAt > seconds && expiresAt - seconds <= 30uL)
    }
}

internal data class RelayPacket(
    val senderId: String,
    val recipientId: String,
    val pairingId: String,
    val messageId: String,
    val protectedBody: ByteString,
    val kind: Kind
) {
    enum class Kind(val wire: ULong) { ENVELOPE(1uL), RECEIPT(2uL) }

    init {
        listOf(senderId, recipientId, pairingId, messageId).forEach(RelayAuth::uuid)
        require(senderId != recipientId && protectedBody.size in 1..32768)
    }

    fun encode(): ByteString = RelayProto.Writer().apply {
        text(1, senderId)
        text(2, recipientId)
        text(3, pairingId)
        text(4, messageId)
        bytes(5, protectedBody)
        text(6, PACKET_PROTOCOL)
        integer(7, kind.wire)
    }.encoded()

    fun digest(): ByteString = MessageDigest.getInstance("SHA-256").digest(encode().toByteArray()).toByteString()

    companion object {
        fun decode(data: ByteString): RelayPacket {
            val fields = RelayProto.Fields(data, 7)
            fields.exact(1, 2, 3, 4, 5, 6, 7)
            require(fields.text(6) == PACKET_PROTOCOL)
            return RelayPacket(
                fields.text(1), fields.text(2), fields.text(3), fields.text(4), fields.bytes(5),
                Kind.entries.single { it.wire == fields.integer(7) }
            )
        }
    }
}

internal sealed class RelayFrame(private val kind: ULong) {
    data class Hello(val deviceId: String, val nonce: ByteString) : RelayFrame(1uL) {
        init { RelayAuth.uuid(deviceId); require(nonce.size == 32) }
    }
    data class Challenge(val value: RelayChallenge) : RelayFrame(2uL)
    data class Authenticate(val deviceId: String, val value: RelayChallenge, val signature: ByteString) : RelayFrame(3uL) {
        init { RelayAuth.uuid(deviceId); require(signature.size == 64) }
    }
    data class Ready(val deviceId: String, val sessionId: String) : RelayFrame(4uL) {
        init { RelayAuth.uuid(deviceId); RelayAuth.uuid(sessionId) }
    }
    data class Submit(val packet: RelayPacket) : RelayFrame(5uL)
    sealed class Acknowledgement(kind: ULong, val messageId: String, val digest: ByteString) : RelayFrame(kind) {
        init { RelayAuth.uuid(messageId); require(digest.size == 32) }
    }
    class Forwarded(messageId: String, digest: ByteString) : Acknowledgement(6uL, messageId, digest)
    data class Deliver(val packet: RelayPacket) : RelayFrame(7uL)
    class Unavailable(messageId: String, digest: ByteString) : Acknowledgement(8uL, messageId, digest)
    data object AuthRejected : RelayFrame(9uL)

    fun encode(): ByteString = RelayProto.Writer().apply {
        integer(1, 1uL)
        integer(2, kind)
        when (val frame = this@RelayFrame) {
            is Hello -> { text(3, frame.deviceId); bytes(4, frame.nonce) }
            is Challenge -> challenge(frame.value)
            is Authenticate -> {
                text(3, frame.deviceId)
                challenge(frame.value)
                bytes(9, frame.signature)
            }
            is Ready -> { text(3, frame.deviceId); text(6, frame.sessionId) }
            is Submit -> bytes(10, frame.packet.encode())
            is Deliver -> bytes(10, frame.packet.encode())
            is Acknowledgement -> { text(11, frame.messageId); bytes(12, frame.digest) }
            AuthRejected -> Unit
        }
    }.encoded()

    private fun RelayProto.Writer.challenge(value: RelayChallenge) {
        bytes(4, value.nonce)
        text(5, value.relayId)
        text(6, value.sessionId)
        bytes(7, value.challenge)
        integer(8, value.expiresAt)
    }

    companion object {
        fun decode(data: ByteString): RelayFrame {
            val fields = RelayProto.Fields(data, 12)
            require(fields.integer(1) == 1uL)
            fun exact(vararg ids: Int) = fields.exact(1, 2, *ids)
            fun challenge() = RelayChallenge(fields.text(5), fields.text(6), fields.bytes(4), fields.bytes(7), fields.integer(8))
            return when (fields.integer(2)) {
                1uL -> { exact(3, 4); Hello(fields.text(3), fields.bytes(4)) }
                2uL -> { exact(4, 5, 6, 7, 8); Challenge(challenge()) }
                3uL -> { exact(3, 4, 5, 6, 7, 8, 9); Authenticate(fields.text(3), challenge(), fields.bytes(9)) }
                4uL -> { exact(3, 6); Ready(fields.text(3), fields.text(6)) }
                5uL -> { exact(10); Submit(RelayPacket.decode(fields.bytes(10))) }
                6uL -> { exact(11, 12); Forwarded(fields.text(11), fields.bytes(12)) }
                7uL -> { exact(10); Deliver(RelayPacket.decode(fields.bytes(10))) }
                8uL -> { exact(11, 12); Unavailable(fields.text(11), fields.bytes(12)) }
                9uL -> { exact(); AuthRejected }
                else -> throw IllegalArgumentException("Unsupported relay frame kind")
            }
        }
    }
}

private object RelayProto {
    class Writer {
        private val output = ByteArrayOutputStream()

        private fun varint(value: ULong) {
            var rest = value
            while (rest >= 128uL) {
                output.write((rest and 127uL).toInt() or 128)
                rest = rest shr 7
            }
            output.write(rest.toInt())
        }

        fun integer(field: Int, value: ULong) {
            varint((field shl 3).toULong())
            varint(value)
        }

        fun bytes(field: Int, value: ByteString) {
            varint(((field shl 3) or 2).toULong())
            varint(value.size.toULong())
            output.write(value.toByteArray())
        }

        fun text(field: Int, value: String) = bytes(field, value.toByteArray(Charsets.UTF_8).toByteString())

        fun encoded(): ByteString = output.toByteArray().toByteString()
    }

    class Fields(data: ByteString, maxField: Int) {
        private val values = arrayOfNulls<Any>(maxField + 1)
        private var mask = 0

        init {
            require(data.size in 1..MAX_RELAY_FRAME)
            var offset = 0

            fun varint(): ULong {
                var decoded = 0uL
                for (index in 0..9) {
                    require(offset < data.size)
                    val byte = data[offset++].toInt() and 255
                    require(index != 9 || byte <= 1)
                    decoded = decoded or ((byte and 127).toULong() shl (index * 7))
                    if (byte < 128) {
                        require(byte != 0)
                        return decoded
                    }
                }
                throw IllegalArgumentException("Overflowing relay varint")
            }

            while (offset < data.size) {
                val tag = varint()
                val id = tag shr 3
                require(id >= 1uL && id <= maxField.toULong())
                val field = id.toInt()
                require(values[field] == null)
                mask = mask or (1 shl field)
                val value = varint()
                values[field] = when (tag and 7uL) {
                    0uL -> value
                    2uL -> {
                        require(value <= (data.size - offset).toULong())
                        val end = offset + value.toInt()
                        data.substring(offset, end).also { offset = end }
                    }
                    else -> throw IllegalArgumentException("Invalid relay wire type")
                }
            }
        }

        fun exact(vararg fields: Int) {
            require(mask == fields.fold(0) { all, field -> all or (1 shl field) })
        }

        fun integer(field: Int): ULong = requireNotNull(values[field] as? ULong)
        fun bytes(field: Int): ByteString = requireNotNull(values[field] as? ByteString)
        fun text(field: Int): String {
            val bytes = bytes(field)
            val text = bytes.utf8()
            require(text.toByteArray(Charsets.UTF_8).toByteString() == bytes)
            return text
        }
    }
}
