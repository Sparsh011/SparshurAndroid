package com.sparsh.sparshur.network.relay

import java.io.IOException
import java.net.ProtocolException
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.TlsVersion
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

enum class RelayPhase { STOPPED, CONNECTING, AUTHENTICATING, CONNECTED, WAITING_TO_RETRY, FAILED }
enum class RelayProblem { NETWORK, TLS, AUTHORIZATION, PROTOCOL, IDENTITY, RETRY_LIMIT, UPGRADE_REQUIRED }
data class RelayStatus(val phase: RelayPhase, val problem: RelayProblem? = null, val retry: Int = 0)

internal data class RelayTiming(
    val deadlineMs: Long = 10_000,
    val baseRetryMs: Long = 1_000,
    val maxRetryMs: Long = 30_000,
    val stableMs: Long = 30_000,
    val maxRetries: Int = 6
)

internal fun interface RelayPacketHandler {
    fun handle(packet: RelayPacket)
}

internal class RelayUpgradeRequired : Exception()

/** Foreground transport for already prepared relay packets. */
class RelayConnection internal constructor(
    private val configuration: RelayConfiguration,
    private val signer: RelaySigner,
    private val client: OkHttpClient,
    private val timer: ScheduledExecutorService,
    private val timing: RelayTiming,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    private val handlePacket: RelayPacketHandler = RelayPacketHandler { throw RelayUpgradeRequired() },
    private val onAcknowledgement: (RelayFrame.Acknowledgement) -> Unit = {},
    private val onStatus: (RelayStatus) -> Unit
) : AutoCloseable {
    constructor(configuration: RelayConfiguration, signer: RelaySigner, onStatus: (RelayStatus) -> Unit) : this(
        configuration, signer, newClient(), Executors.newSingleThreadScheduledExecutor(), RelayTiming(), onStatus = onStatus
    )

    private val lock = Any()
    private val random = SecureRandom()
    private var socket: WebSocket? = null
    private var deadline: ScheduledFuture<*>? = null
    private var retryTask: ScheduledFuture<*>? = null
    private var stableTask: ScheduledFuture<*>? = null
    private var generation = 0L
    private var retries = 0
    private var active = false
    private var disposed = false
    private var phase = RelayPhase.STOPPED
    private data class Pending(val digest: ByteString, val attempts: Int)
    private val pending = mutableMapOf<String, Pending>()

    init { RelayAuth.uuid(signer.deviceId) }

    fun connect() = synchronized(lock) {
        check(!disposed)
        stopAttempt()
        active = true
        retries = 0
        open()
    }

    fun disconnect() = synchronized(lock) {
        active = false
        stopAttempt()
        publish(RelayStatus(RelayPhase.STOPPED))
    }

    private fun publish(status: RelayStatus) {
        phase = status.phase
        onStatus(status)
    }

    private fun stopAttempt() {
        generation++
        deadline?.cancel(false)
        retryTask?.cancel(false)
        stableTask?.cancel(false)
        deadline = null
        retryTask = null
        stableTask = null
        socket?.cancel()
        socket = null
        pending.clear()
    }

    private fun open() {
        if (!active || disposed) return
        val attempt = ++generation
        val nonce = ByteArray(32).also(random::nextBytes).toByteString()
        var issued: RelayChallenge? = null
        publish(RelayStatus(RelayPhase.CONNECTING, retry = retries))
        deadline = timer.schedule({ failed(attempt, RelayProblem.NETWORK, true) }, timing.deadlineMs, TimeUnit.MILLISECONDS)
        val request = Request.Builder().url(configuration.endpoint.httpUrl)
            .header("Sec-WebSocket-Protocol", RelayEndpoint.SUBPROTOCOL).build()
        try {
            socket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = synchronized(lock) {
                    if (!current(attempt)) { webSocket.cancel(); return@synchronized }
                    if (response.header("Sec-WebSocket-Protocol") != RelayEndpoint.SUBPROTOCOL ||
                        response.handshake?.tlsVersion != TlsVersion.TLS_1_3) {
                        failed(attempt, RelayProblem.PROTOCOL, false)
                        return@synchronized
                    }
                    publish(RelayStatus(RelayPhase.AUTHENTICATING, retry = retries))
                    deadline?.cancel(false)
                    deadline = timer.schedule({ failed(attempt, RelayProblem.NETWORK, true) }, timing.deadlineMs, TimeUnit.MILLISECONDS)
                    enqueue(attempt, webSocket, RelayFrame.Hello(signer.deviceId, nonce))
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    failed(attempt, RelayProblem.PROTOCOL, false)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    var challengeToSign: RelayChallenge? = null
                    synchronized(lock) {
                        if (!current(attempt)) return
                        try {
                            when (val frame = RelayFrame.decode(bytes)) {
                                is RelayFrame.Challenge -> {
                                    check(phase == RelayPhase.AUTHENTICATING && issued == null)
                                    frame.value.validate(configuration, nonce, now())
                                    issued = frame.value
                                    challengeToSign = frame.value
                                }
                                is RelayFrame.Ready -> {
                                    check(phase == RelayPhase.AUTHENTICATING && issued?.sessionId == frame.sessionId &&
                                        frame.deviceId == signer.deviceId)
                                    deadline?.cancel(false)
                                    publish(RelayStatus(RelayPhase.CONNECTED, retry = retries))
                                    stableTask = timer.schedule({ synchronized(lock) {
                                        if (current(attempt) && phase == RelayPhase.CONNECTED) retries = 0
                                    } }, timing.stableMs, TimeUnit.MILLISECONDS)
                                }
                                RelayFrame.AuthRejected -> failed(attempt, RelayProblem.AUTHORIZATION, false)
                                is RelayFrame.Deliver -> {
                                    check(phase == RelayPhase.CONNECTED && frame.packet.recipientId == signer.deviceId)
                                    handlePacket.handle(frame.packet)
                                }
                                is RelayFrame.Acknowledgement -> {
                                    check(phase == RelayPhase.CONNECTED)
                                    acknowledge(frame)
                                }
                                else -> failed(attempt, RelayProblem.PROTOCOL, false)
                            }
                        } catch (_: RelayUpgradeRequired) {
                            failed(attempt, RelayProblem.UPGRADE_REQUIRED, false)
                        } catch (_: Exception) {
                            failed(attempt, RelayProblem.PROTOCOL, false)
                        }
                    }
                    // Keystore work must not stop a disconnect or deadline.
                    val challenge = challengeToSign ?: return
                    val signature = try {
                        signer.sign(RelayAuth.transcript(configuration, signer.deviceId, challenge)).toByteString()
                    } catch (_: Exception) {
                        failed(attempt, RelayProblem.IDENTITY, false)
                        return
                    }
                    synchronized(lock) {
                        if (!current(attempt) || phase != RelayPhase.AUTHENTICATING) return
                        if (signature.size != 64) {
                            failed(attempt, RelayProblem.IDENTITY, false)
                            return
                        }
                        try {
                            challenge.validate(configuration, nonce, now())
                            enqueue(attempt, webSocket, RelayFrame.Authenticate(signer.deviceId, challenge, signature))
                        } catch (_: Exception) {
                            failed(attempt, RelayProblem.PROTOCOL, false)
                        }
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    // Never display/log arbitrary remote close reasons or HTTP error bodies.
                    val retry = code in setOf(1001, 1012, 1013)
                    val problem = if (code in setOf(4401, 4403, 4409)) RelayProblem.AUTHORIZATION else RelayProblem.PROTOCOL
                    failed(attempt, if (retry) RelayProblem.NETWORK else problem, retry)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { failed(attempt, RelayProblem.NETWORK, true) }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val code = response?.code
                    response?.body?.close()
                    when {
                        hasTlsFailure(t) -> failed(attempt, RelayProblem.TLS, false)
                        code in setOf(401, 403, 409) -> failed(attempt, RelayProblem.AUTHORIZATION, false)
                        code in setOf(429, 503) -> failed(attempt, RelayProblem.NETWORK, true)
                        code != null && code !in setOf(429, 503) -> failed(attempt, RelayProblem.PROTOCOL, false)
                        t is ProtocolException -> failed(attempt, RelayProblem.PROTOCOL, false)
                        t is IOException -> failed(attempt, RelayProblem.NETWORK, true)
                        else -> failed(attempt, RelayProblem.PROTOCOL, false)
                    }
                }
            })
        } catch (_: Exception) {
            failed(attempt, RelayProblem.PROTOCOL, false)
        }
    }

    private fun current(attempt: Long): Boolean = active && !disposed && attempt == generation

    internal fun sendPrepared(packet: RelayPacket) = synchronized(lock) {
        val connection = checkNotNull(socket)
        check(phase == RelayPhase.CONNECTED)
        require(packet.senderId == signer.deviceId)
        val digest = packet.digest()
        val previous = pending[packet.messageId]
        require(previous == null || previous.digest == digest)
        // OkHttp serializes writes. Its queued bytes include a write in progress.
        check(connection.queueSize() == 0L && pending.values.sumOf { it.attempts } < 32)
        pending[packet.messageId] = Pending(digest, (previous?.attempts ?: 0) + 1)
        check(enqueue(generation, connection, RelayFrame.Submit(packet)))
    }

    private fun acknowledge(frame: RelayFrame.Acknowledgement) {
        val entry = checkNotNull(pending[frame.messageId])
        check(entry.digest == frame.digest)
        if (entry.attempts == 1) pending.remove(frame.messageId)
        else pending[frame.messageId] = entry.copy(attempts = entry.attempts - 1)
        onAcknowledgement(frame)
    }

    private fun enqueue(attempt: Long, webSocket: WebSocket, frame: RelayFrame): Boolean {
        val data = frame.encode()
        val accepted = webSocket.queueSize() + data.size <= MAX_RELAY_FRAME && webSocket.send(data)
        if (!accepted) failed(attempt, RelayProblem.NETWORK, true)
        return accepted
    }

    private fun failed(attempt: Long, problem: RelayProblem, retry: Boolean) = synchronized(lock) {
        if (!current(attempt)) return@synchronized
        stopAttempt()
        if (!retry || retries >= timing.maxRetries) {
            active = false
            publish(RelayStatus(RelayPhase.FAILED, if (retry) RelayProblem.RETRY_LIMIT else problem, retries))
        } else {
            retries++
            val cap = minOf(timing.maxRetryMs, timing.baseRetryMs * (1L shl minOf(retries - 1, 20)))
            val delay = cap / 2 + (random.nextDouble() * (cap - cap / 2)).toLong()
            publish(RelayStatus(RelayPhase.WAITING_TO_RETRY, problem, retries))
            retryTask = timer.schedule({ synchronized(lock) { if (active && !disposed) open() } }, delay, TimeUnit.MILLISECONDS)
        }
    }

    override fun close() = synchronized(lock) {
        if (disposed) return@synchronized
        disconnect()
        disposed = true
        timer.shutdownNow()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    companion object {
        /** A failed IPv6 route can be the primary error with a TLS error suppressed on IPv4. */
        internal fun hasTlsFailure(failure: Throwable): Boolean {
            val pending = java.util.ArrayDeque<Throwable>().apply { add(failure) }
            val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
            while (pending.isNotEmpty() && seen.size < 64) {
                val next = pending.removeFirst()
                if (!seen.add(next)) continue
                if (next is SSLException) return true
                next.cause?.let(pending::add)
                next.suppressed.forEach(pending::add)
            }
            return false
        }

        internal fun newClient(): OkHttpClient = OkHttpClient.Builder()
            .connectionSpecs(listOf(ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS).tlsVersions(TlsVersion.TLS_1_3).build()))
            .protocols(listOf(Protocol.HTTP_1_1))
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).writeTimeout(10, TimeUnit.SECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .build()
    }
}
