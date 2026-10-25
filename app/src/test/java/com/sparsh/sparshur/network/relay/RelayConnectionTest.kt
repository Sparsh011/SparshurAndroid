package com.sparsh.sparshur.network.relay

import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.Ed25519Verify
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
import okio.ByteString.Companion.encodeUtf8
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
    private val received = LinkedBlockingQueue<String>()
    private val signatures = AtomicInteger()
    private val testVector = JSONObject(javaClass.classLoader!!.getResourceAsStream("relay-auth-test-vectors.json")!!
        .bufferedReader().use { it.readText() })
    private val device = testVector.getString("device_id")
    private val session = testVector.getString("session_id")
    private val nonce = testVector.getString("challenge")
    private val signer = object : RelaySigner {
        override val deviceId = device
        override fun sign(message: ByteArray): ByteArray {
            signatures.incrementAndGet()
            return Ed25519Sign(RelayAuth.decoded(testVector.getString("seed"), 32)).sign(message)
        }
    }

    private fun startServer() {
        val identity = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        server.useHttps(identity.sslSocketFactory())
        server.start()
    }

    private fun endpoint() = RelayEndpoint.parse("wss://localhost:${server.port}${RelayEndpoint.PATH}")

    private fun trustedClient(): OkHttpClient {
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        // Trust this test CA only. Hostname validation and TLS 1.3 are still enforced.
        return RelayConnection.newClient().newBuilder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
    }

    private fun connection(client: OkHttpClient = trustedClient(), timing: RelayTiming = RelayTiming(), authSigner: RelaySigner = signer): RelayConnection =
        RelayConnection(endpoint(), authSigner, client, Executors.newSingleThreadScheduledExecutor(), timing, statuses::offer)
            .also(connections::add)

    private fun queue(listener: WebSocketListener, protocol: String = RelayEndpoint.SUBPROTOCOL) {
        server.enqueue(MockResponse.Builder().webSocketUpgrade(listener).addHeader("Sec-WebSocket-Protocol", protocol).build())
    }

    private fun challenge() = "{\"version\":1,\"type\":\"challenge\",\"session_id\":\"$session\",\"challenge\":\"$nonce\"}"
    private fun ready(id: String = device, sid: String = session) =
        "{\"version\":1,\"type\":\"ready\",\"device_id\":\"$id\",\"session_id\":\"$sid\"}"

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

    private fun exchanging(onAuth: (WebSocket) -> Unit = { it.send(ready()); Unit }) = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            received.offer(text)
            val frame = JSONObject(text)
            if (frame.getString("type") == "hello") webSocket.send(challenge()) else onAuth(webSocket)
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
        val hello = JSONObject(checkNotNull(received.poll(2, TimeUnit.SECONDS)))
        val auth = JSONObject(checkNotNull(received.poll(2, TimeUnit.SECONDS)))
        assertFalse(auth.has("seed"))
        assertFalse(auth.has("public_key")) // Must use an administrator-enrolled key.
        val transcript = RelayAuth.transcript(endpoint(), device, hello.getString("client_nonce"), session, nonce)
        Ed25519Verify(RelayAuth.decoded(testVector.getString("public_key"), 32))
            .verify(RelayAuth.decoded(auth.getString("signature"), 64), transcript)
        val upgrade = checkNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("/bridge/v1/ws", upgrade.url.encodedPath)
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
        val destination = RelayEndpoint.parse("wss://127.0.0.1:${server.port}${RelayEndpoint.PATH}")
        val connection = RelayConnection(destination, signer, trustedClient(), Executors.newSingleThreadScheduledExecutor(),
            RelayTiming(), statuses::offer).also(connections::add)
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
        assertEquals("hello", JSONObject(checkNotNull(received.poll(2, TimeUnit.SECONDS))).getString("type"))
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
            override fun onMessage(webSocket: WebSocket, text: String) { webSocket.send(ready()) }
        })
        connection().connect()
        assertEquals(RelayProblem.PROTOCOL, awaitPhase(RelayPhase.FAILED).problem)
        assertEquals(0, signatures.get())
    }

    @Test fun readyForAnotherIdentityCannotAuthenticateASession() {
        startServer()
        queue(exchanging { it.send(ready("33333333-3333-4333-8333-333333333333")); Unit })
        connection().connect()
        assertEquals(RelayProblem.PROTOCOL, awaitPhase(RelayPhase.FAILED).problem)
    }

    @Test fun readyForAnotherSessionCannotAuthenticateASession() {
        startServer()
        queue(exchanging { it.send(ready(sid = "44444444-4444-4444-8444-444444444444")); Unit })
        connection().connect()
        assertEquals(RelayProblem.PROTOCOL, awaitPhase(RelayPhase.FAILED).problem)
    }

    @Test fun secondChallengeCannotTurnClientIntoARepeatedSigningOracle() {
        startServer()
        queue(exchanging { it.send(challenge()); Unit })
        connection().connect()
        assertEquals(RelayProblem.PROTOCOL, awaitPhase(RelayPhase.FAILED).problem)
        assertEquals(1, signatures.get())
    }

    @Test fun binaryAndOversizeControlsAreRejected() {
        startServer()
        queue(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { webSocket.send("not a control".encodeUtf8()) }
        })
        queue(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { webSocket.send("a".repeat(2049)) }
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
            override fun onMessage(webSocket: WebSocket, text: String) {
                webSocket.send("{\"version\":1,\"type\":\"error\",\"code\":\"revoked\"}")
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
            override fun onMessage(webSocket: WebSocket, text: String) { received.offer(text) }
        }) }
        connection(timing = RelayTiming(deadlineMs = 300, baseRetryMs = 30, maxRetryMs = 30, maxRetries = 1)).connect()
        val failure = awaitPhase(RelayPhase.FAILED)
        assertEquals(RelayProblem.RETRY_LIMIT, failure.problem)
        assertEquals(1, failure.retry)
        val first = JSONObject(checkNotNull(received.poll(2, TimeUnit.SECONDS)))
        val second = JSONObject(checkNotNull(received.poll(2, TimeUnit.SECONDS)))
        assertNotEquals(first.getString("client_nonce"), second.getString("client_nonce"))
        assertEquals(0, signatures.get())
        assertEquals(2, server.requestCount)
    }

    @Test fun disconnectCancelsScheduledReconnectAndOldCallbacksCannotReviveIt() {
        startServer()
        queue(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { webSocket.close(1012, "restart") }
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
}
