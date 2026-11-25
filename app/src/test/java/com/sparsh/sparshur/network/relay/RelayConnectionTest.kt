package com.sparsh.sparshur.network.relay

import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.Ed25519Verify
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.TlsVersion
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Actual local TLS/HTTP upgrade/WebSocket tests; no real Mac, keys, pairing or dashboard. */
class RelayConnectionTest {
    private val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
    private val server = MockWebServer()
    private val connections = mutableListOf<RelayConnection>()
    private val statuses = LinkedBlockingQueue<RelayStatus>()
    private val received = LinkedBlockingQueue<RelayFrame>()
    private val acknowledgements = LinkedBlockingQueue<RelayFrame.Acknowledgement>()
    private val peer = "22222222-2222-4222-8222-222222222222"
    private val now = 2_000_000_000L
    private val relayId = "mini-test"
    private val signatures = AtomicInteger()
    private val testVector = JSONObject(javaClass.classLoader!!.getResourceAsStream("relay-auth.json")!!
        .bufferedReader().use { it.readText() })
    private val device = testVector.getString("device_id")
    private val session = testVector.getString("session_id")
    private fun bytes(name: String) = Base64.getDecoder().decode(testVector.getString(name))
    private fun socketChallenge(hello: RelayFrame.Hello, expiresAt: ULong = (now + 20).toULong()) =
        RelayFrame.Challenge(RelayChallenge(relayId, session, hello.nonce, bytes("challenge").toByteString(), expiresAt))
    private val signer = object : RelaySigner {
        override val deviceId = device
        override fun sign(message: ByteArray): ByteArray {
            signatures.incrementAndGet()
            return Ed25519Sign(bytes("signing_seed")).sign(message)
        }
    }

    private fun startServer() {
        val identity = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        server.useHttps(identity.sslSocketFactory())
        server.start()
    }

    private fun destination(host: String = "localhost") =
        RelayConfiguration(RelayEndpoint.parse("wss://$host:${server.port}${RelayEndpoint.PATH}"), relayId)

    private fun trustedClient(): OkHttpClient {
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        // Trust this test CA only. Hostname validation and TLS 1.3 are still enforced.
        return RelayConnection.newClient().newBuilder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
    }

    private fun connection(client: OkHttpClient = trustedClient(), timing: RelayTiming = RelayTiming(), authSigner: RelaySigner = signer): RelayConnection =
        RelayConnection(destination(), authSigner, client, Executors.newSingleThreadScheduledExecutor(), timing,
            now = { now }, onAcknowledgement = { acknowledgements.offer(it) }, onStatus = { statuses.offer(it) })
            .also(connections::add)

    private fun queue(listener: WebSocketListener, protocol: String = RelayEndpoint.SUBPROTOCOL) {
        server.enqueue(MockResponse.Builder().webSocketUpgrade(listener).addHeader("Sec-WebSocket-Protocol", protocol).build())
    }

    private fun ready(id: String = device, sid: String = session) = RelayFrame.Ready(id, sid).encode()

    private fun packet(sender: String = device, recipient: String = peer) = RelayPacket(
        sender, recipient, "55555555-5555-4555-8555-555555555555", "018bcfe5-6800-7000-8000-000000000001",
        "010203".encodeUtf8(), RelayPacket.Kind.ENVELOPE
    )

    private fun awaitPhase(phase: RelayPhase): RelayStatus {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        val seen = mutableListOf<RelayStatus>()
        while (System.nanoTime() < until) {
            val next = statuses.poll(100, TimeUnit.MILLISECONDS) ?: continue
            seen.add(next)
            if (next.phase == phase) return next
        }
        fail("Expected $phase; observed $seen")
        error("unreachable")
    }

    private fun exchanging(
        onSubmit: (WebSocket, RelayPacket) -> Unit = { _, _ -> },
        onAuth: (WebSocket) -> Unit = { it.send(ready()); Unit }
    ) = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val frame = RelayFrame.decode(bytes)
            received.offer(frame)
            when (frame) {
                is RelayFrame.Hello -> webSocket.send(socketChallenge(frame).encode())
                is RelayFrame.Authenticate -> onAuth(webSocket)
                is RelayFrame.Submit -> onSubmit(webSocket, frame.packet)
                else -> error("Unexpected client frame")
            }
        }
    }

    @After fun cleanUp() {
        connections.forEach { it.close() }
        server.close()
    }

    @Test fun authenticatesOverRealTlsAndDoesNotConfuseConnectionWithDelivery() {
        startServer()
        queue(exchanging())
        val connection = connection()
        connection.connect()
        assertEquals(RelayPhase.CONNECTED, awaitPhase(RelayPhase.CONNECTED).phase)
        val hello = received.poll(2, TimeUnit.SECONDS) as RelayFrame.Hello
        val auth = received.poll(2, TimeUnit.SECONDS) as RelayFrame.Authenticate
        assertEquals(device, auth.deviceId)
        assertEquals(socketChallenge(hello).value, auth.value)
        val transcript = RelayAuth.transcript(destination(), device, auth.value)
        Ed25519Verify(bytes("signing_public_key")).verify(auth.signature.toByteArray(), transcript)
        val upgrade = checkNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("/v1/relay", upgrade.url.encodedPath)
        assertEquals(RelayEndpoint.SUBPROTOCOL, upgrade.headers["Sec-WebSocket-Protocol"])
        assertEquals(TlsVersion.TLS_1_3, upgrade.handshake?.tlsVersion)
        assertEquals(1, signatures.get())
        connection.disconnect()
        awaitPhase(RelayPhase.STOPPED)
    }

    @Test fun untrustedCertificateFailsWithoutSigningOrRetrying() {
        startServer()
        queue(exchanging())
        connection(RelayConnection.newClient()).connect()
        assertEquals(RelayProblem.TLS, awaitPhase(RelayPhase.FAILED).problem)
        assertEquals(0, signatures.get())
        assertNull(received.poll(200, TimeUnit.MILLISECONDS))
    }

    @Test fun trustedCertificateForWrongHostnameStillFailsBeforeSigning() {
        startServer()
        queue(exchanging())
        val destination = destination("127.0.0.1")
        val connection = RelayConnection(destination, signer, trustedClient(), Executors.newSingleThreadScheduledExecutor(),
            RelayTiming(), now = { now }, onStatus = { statuses.offer(it) }).also(connections::add)
        connection.connect()
        assertEquals(RelayProblem.TLS, awaitPhase(RelayPhase.FAILED).problem)
        assertEquals(0, signatures.get())
    }

    @Test fun signingFailureIsTerminalAndDoesNotReplaceIdentity() {
        startServer()
        queue(exchanging())
        connection(authSigner = object : RelaySigner {
            override val deviceId = device
            override fun sign(message: ByteArray): ByteArray = error("protected identity unavailable")
        }).connect()
        assertEquals(RelayProblem.IDENTITY, awaitPhase(RelayPhase.FAILED).problem)
        assertEquals(1, server.requestCount)
    }

    @Test fun slowSigningCannotBlockStopAndItsLateResultCannotAuthenticate() {
        startServer()
        queue(exchanging())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val connection = connection(authSigner = object : RelaySigner {
            override val deviceId = device
            override fun sign(message: ByteArray): ByteArray {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return signer.sign(message).also { finished.countDown() }
            }
        })
        val stopper = Executors.newSingleThreadExecutor()
        try {
            connection.connect()
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            stopper.submit { connection.disconnect() }.get(500, TimeUnit.MILLISECONDS)
            awaitPhase(RelayPhase.STOPPED)
        } finally { release.countDown(); stopper.shutdownNow() }
        assertTrue(finished.await(3, TimeUnit.SECONDS))
        assertTrue(received.poll(2, TimeUnit.SECONDS) is RelayFrame.Hello)
        assertNull(received.poll(200, TimeUnit.MILLISECONDS))
        assertNull(statuses.poll(200, TimeUnit.MILLISECONDS))
    }

    @Test fun rejectsWrongSubprotocolBeforeSendingIdentity() {
        startServer()
        queue(exchanging(), "other.protocol")
        connection().connect()
        assertEquals(RelayProblem.PROTOCOL, awaitPhase(RelayPhase.FAILED).problem)
        assertNull(received.poll(200, TimeUnit.MILLISECONDS))
        assertEquals(0, signatures.get())
    }

    @Test fun readyBeforeChallengeCannotAuthenticateASession() {
        startServer()
        queue(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { webSocket.send(ready()) }
        })
        connection().connect()
        assertEquals(RelayProblem.PROTOCOL, awaitPhase(RelayPhase.FAILED).problem)
        assertEquals(0, signatures.get())
    }

    @Test fun readyForAnotherIdentityCannotAuthenticateASession() {
        startServer()
        queue(exchanging { it.send(ready(peer)); Unit })
        connection().connect()
        assertEquals(RelayProblem.PROTOCOL, awaitPhase(RelayPhase.FAILED).problem)
    }

    @Test fun readyForAnotherSessionCannotAuthenticateASession() {
        startServer()
        queue(exchanging { it.send(ready(sid = peer)); Unit })
        connection().connect()
        assertEquals(RelayProblem.PROTOCOL, awaitPhase(RelayPhase.FAILED).problem)
    }

    @Test fun secondChallengeCannotTurnClientIntoARepeatedSigningOracle() {
        startServer()
        queue(exchanging { it.send(socketChallenge(RelayFrame.Hello(device, ByteArray(32).toByteString())).encode()); Unit })
        connection().connect()
        assertEquals(RelayProblem.PROTOCOL, awaitPhase(RelayPhase.FAILED).problem)
        assertEquals(1, signatures.get())
    }

    @Test fun textAndOversizedBinaryAreRejected() {
        startServer()
        queue(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { webSocket.send("not a binary frame") }
        })
        queue(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { webSocket.send(ByteArray(MAX_RELAY_FRAME + 1).toByteString()) }
        })
        val connection = connection()
        connection.connect()
        assertEquals(RelayProblem.PROTOCOL, awaitPhase(RelayPhase.FAILED).problem)
        connection.connect()
        assertEquals(RelayProblem.PROTOCOL, awaitPhase(RelayPhase.FAILED).problem)
        assertEquals(0, signatures.get())
    }

    @Test fun authorizationFailureIsTerminalAndRequiresExplicitRetry() {
        startServer()
        queue(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                webSocket.send(RelayFrame.AuthRejected.encode())
            }
        })
        connection(timing = RelayTiming(baseRetryMs = 20, maxRetryMs = 40)).connect()
        assertEquals(RelayProblem.AUTHORIZATION, awaitPhase(RelayPhase.FAILED).problem)
        assertNull(statuses.poll(150, TimeUnit.MILLISECONDS))
        assertEquals(1, server.requestCount)
    }

    @Test fun authenticationTimeoutHasABoundedRetryBudgetAndFreshNonce() {
        startServer()
        repeat(2) { queue(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { received.offer(RelayFrame.decode(bytes)) }
        }) }
        connection(timing = RelayTiming(deadlineMs = 300, baseRetryMs = 30, maxRetryMs = 30, maxRetries = 1)).connect()
        val failure = awaitPhase(RelayPhase.FAILED)
        assertEquals(RelayProblem.RETRY_LIMIT, failure.problem)
        assertEquals(1, failure.retry)
        val first = received.poll(2, TimeUnit.SECONDS) as RelayFrame.Hello
        val second = received.poll(2, TimeUnit.SECONDS) as RelayFrame.Hello
        assertNotEquals(first.nonce, second.nonce)
        assertEquals(0, signatures.get())
        assertEquals(2, server.requestCount)
    }

    @Test fun disconnectCancelsScheduledReconnectAndOldCallbacksCannotReviveIt() {
        startServer()
        queue(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { webSocket.close(1012, "restart") }
        })
        val connection = connection(timing = RelayTiming(baseRetryMs = 800, maxRetryMs = 800))
        connection.connect()
        awaitPhase(RelayPhase.WAITING_TO_RETRY)
        connection.disconnect()
        awaitPhase(RelayPhase.STOPPED)
        assertNull(statuses.poll(900, TimeUnit.MILLISECONDS))
        assertEquals(1, server.requestCount)
        assertEquals(0, signatures.get())
    }

    @Test fun redirectCannotMoveAuthenticationToAnotherHostOrPath() {
        startServer()
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "https://localhost:${server.port}/elsewhere").build())
        connection().connect()
        assertEquals(RelayProblem.PROTOCOL, awaitPhase(RelayPhase.FAILED).problem)
        assertEquals(1, server.requestCount)
        assertEquals(0, signatures.get())
    }

    @Test fun rapidAuthenticatedFlapsDoNotResetTheRetryBudget() {
        startServer()
        repeat(2) { queue(exchanging { it.send(ready()); it.close(1012, "restart"); Unit }) }
        connection(timing = RelayTiming(baseRetryMs = 30, maxRetryMs = 30, stableMs = 60_000, maxRetries = 1)).connect()
        assertEquals(RelayProblem.RETRY_LIMIT, awaitPhase(RelayPhase.FAILED).problem)
        assertEquals(2, signatures.get())
        assertEquals(2, server.requestCount)
    }

    @Test fun staleChallengeNeverReachesTheSigner() {
        startServer()
        queue(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val hello = RelayFrame.decode(bytes) as RelayFrame.Hello
                webSocket.send(socketChallenge(hello, now.toULong()).encode())
            }
        })
        connection().connect()
        assertEquals(RelayProblem.PROTOCOL, awaitPhase(RelayPhase.FAILED).problem)
        assertEquals(0, signatures.get())
    }

    @Test fun deliveryRequiresTheEndToEndUpgradeAndDoesNotRetry() {
        startServer()
        queue(exchanging {
            it.send(ready())
            it.send(RelayFrame.Deliver(packet(peer, device)).encode())
        })
        connection(timing = RelayTiming(baseRetryMs = 20)).connect()
        awaitPhase(RelayPhase.CONNECTED)
        assertEquals(RelayProblem.UPGRADE_REQUIRED, awaitPhase(RelayPhase.FAILED).problem)
        assertNull(statuses.poll(150, TimeUnit.MILLISECONDS))
        assertEquals(1, server.requestCount)
    }

    @Test fun acknowledgementsMustMatchThePendingMessageAndDigest() {
        startServer()
        val remote = LinkedBlockingQueue<WebSocket>()
        val submissions = LinkedBlockingQueue<RelayPacket>()
        queue(exchanging(onSubmit = { socket, packet -> remote.offer(socket); submissions.offer(packet) }))
        val connection = connection()
        connection.connect()
        awaitPhase(RelayPhase.CONNECTED)
        val packet = packet()
        connection.sendPrepared(packet)
        val socket = checkNotNull(remote.poll(2, TimeUnit.SECONDS))
        assertEquals(packet, submissions.poll(2, TimeUnit.SECONDS))
        assertThrows(IllegalArgumentException::class.java) {
            connection.sendPrepared(packet.copy(protectedBody = "different".encodeUtf8()))
        }
        socket.send(RelayFrame.Forwarded(packet.messageId, packet.digest()).encode())
        val ack = acknowledgements.poll(2, TimeUnit.SECONDS)
        assertTrue(ack is RelayFrame.Forwarded)
        assertEquals(packet.digest(), ack?.digest)
        connection.sendPrepared(packet)
        assertEquals(packet, submissions.poll(2, TimeUnit.SECONDS))
        socket.send(RelayFrame.Unavailable(packet.messageId, ByteArray(32).toByteString()).encode())
        assertEquals(RelayProblem.PROTOCOL, awaitPhase(RelayPhase.FAILED).problem)
        assertNull(acknowledgements.poll())
    }
}
