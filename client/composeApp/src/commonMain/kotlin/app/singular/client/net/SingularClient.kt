package app.singular.client.net

import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.pingInterval
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.plugins.onUpload
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class GraphQlException(val errors: List<GraphQlError>) :
    Exception(errors.firstOrNull()?.message ?: "Request failed") {
    val code: String? get() = errors.firstOrNull()?.code
}

/**
 * A hand-rolled GraphQL client over Ktor.
 *
 * No Apollo, no codegen. The trade is real — operation strings and response wrappers are
 * hand-written and nothing checks them against the schema at build time — but it keeps the
 * dependency surface to Ktor plus kotlinx.serialization, with no build step that reaches out
 * over the network for a schema. Worth revisiting once the schema outgrows one file.
 */
class SingularClient(
    private val httpBaseUrl: String = DEFAULT_HTTP,
    private val wsBaseUrl: String = DEFAULT_WS,
) {
    private val http = HttpClient {
        install(ContentNegotiation) { json(codec) }
        install(WebSockets) {
            // Client-side keepalive. NATs and proxies drop idle TCP after 30–60s and the
            // presence/notification sockets can idle for minutes; without a client ping a
            // dead socket is only discovered on the next failed send. 20s keeps the
            // connection unambiguously alive and detects death within one interval —
            // comfortably under the reconnect backoff floor, so the reconnect path is what
            // answers a real outage rather than a stale socket silently draining events.
            // (A Duration-based extension property — hence the .seconds, not a raw Long.)
            pingInterval = kotlin.time.Duration.parse("20s")
        }
    }

    /**
     * Set after sign-in. In memory only.
     *
     * The refresh token is the one that must reach the OS keystore (Keychain / Android
     * Keystore / DPAPI) — never plain preferences, and never disk in the clear.
     */
    var accessToken: String? = null

    suspend inline fun <reified T> execute(
        operation: String,
        variables: JsonObject = JsonObject(emptyMap()),
    ): T {
        val envelope = codec.decodeFromString<GraphQlResponse<T>>(postRaw(operation, variables))
        envelope.errors?.takeIf { it.isNotEmpty() }?.let { throw GraphQlException(it) }
        return envelope.data
            ?: throw GraphQlException(listOf(GraphQlError("Server returned no data")))
    }

    suspend fun postRaw(operation: String, variables: JsonObject): String =
        http.post(httpBaseUrl) {
            contentType(ContentType.Application.Json)
            accessToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            setBody(GraphQlRequest(operation, variables))
        }.bodyAsText()

    /**
     * Subscribes over the `graphql-transport-ws` protocol — **multiplexed on one shared
     * socket**, not one socket per subscription.
     *
     * The protocol has always supported this: every `subscribe`/`next`/`complete` frame
     * carries a client-chosen `id`, and the server routes events back by it. The previous
     * client ignored the id entirely (hardcoded `"1"`) and opened a socket per watcher —
     * six-plus per client (message, typing, reaction, update, presence, notification), each
     * with its own handshake, ping loop and reconnect timer. One socket means one handshake,
     * one ping loop, one reconnect, and a fraction of the file descriptors, RAM and radio
     * wakeups — the single biggest resource win available to this client.
     *
     * The handshake still matters. The server rejects the socket unless `connection_init`
     * carries the bearer token, because browsers cannot set headers on a WebSocket upgrade —
     * so the token travels in the init payload instead. That also fixes the socket's
     * authorisation for its lifetime, which is exactly why access tokens are short-lived and
     * why the client must reconnect after a refresh.
     *
     * The returned flow is cold: collecting registers the operation on the shared transport,
     * cancelling unregisters it. When the shared socket dies, every live flow ends with an
     * exception — which is what the callers' reconnect loops already handle, unchanged from
     * the one-socket-per-subscription days.
     */
    fun subscribe(operation: String, variables: JsonObject): Flow<JsonObject> = channelFlow {
        val transport = SharedSockets.forUrl(wsBaseUrl) { accessToken }
        val id = operation   // operation strings are unique per watcher in this app; the
                             // previous collector unregisters before a re-subscribe can clash
        val message = buildJsonObject {
            put("id", id)
            put("type", "subscribe")
            put("payload", buildJsonObject {
                put("query", operation)
                put("variables", variables)
            })
        }

        transport.register(id, message, channel)

        awaitClose {
            transport.unregister(id)
        }
    }

    /**
     * Uploads bytes straight to object storage.
     *
     * Deliberately not a GraphQL call, and deliberately unauthenticated: the presigned URL
     * carries its own signature, and attaching our bearer token would hand it to a storage
     * host that has no business seeing it.
     *
     * [onUpload] reports bytes moved and total when the engine provides them, which is what
     * drives the composer's determinate progress bar. Nullable because a presigned PUT to some
     * storage backends is a single streamed request with no hook the engine exposes.
     */
    suspend fun putBytes(
        url: String,
        bytes: ByteArray,
        contentType: String,
        onUpload: ((sent: Long, total: Long) -> Unit)? = null,
    ): Boolean {
        val response = http.put(url) {
            setBody(ByteArrayContent(bytes, ContentType.parse(contentType)))
            onUpload?.let { report ->
                onUpload { sent: Long, total: Long? -> report(sent, total ?: bytes.size.toLong()) }
            }
        }
        return response.status.isSuccess()
    }

    /**
     * Downloads bytes from object storage — the audio behind a voice note.
     *
     * Same reasoning as [putBytes]: the presigned URL is its own credential, so no bearer token
     * travels with it. Coil already fetches images this way through its Ktor fetcher; audio
     * needs the bytes rather than a decoded bitmap, so it gets its own call.
     */
    suspend fun fetchBytes(url: String): ByteArray =
        http.get(url).readRawBytes()

    fun close() {
        SharedSockets.shutdown(wsBaseUrl)
        http.close()
    }

    companion object {
        const val DEFAULT_HTTP = "http://localhost:8080/graphql"
        const val DEFAULT_WS = "ws://localhost:8080/graphql"
        const val GRAPHQL_WS_PROTOCOL = "graphql-transport-ws"

        /** Public so the inline reified helpers above can reach it. */
        val codec = Json {
            ignoreUnknownKeys = true   // additive schema changes must not break older clients
            // False on purpose: the only thing this codec ever *encodes* is GraphQlRequest
            // (whose `variables` defaulting to {} would otherwise ship as a literal `"variables":{}`
            // on every call) and hand-built JsonObjects, which have no defaults to encode.
            // Response DTOs are decode-only, so they don't care. If a future DTO that *sends*
            // default-valued fields appears, it must either set them explicitly or this flag
            // needs revisiting — silently dropping a field the server needs is the failure
            // mode this comment exists to prevent.
            encodeDefaults = false
        }
    }
}

// ---------------------------------------------------------------------------
// The shared-socket transport
// ---------------------------------------------------------------------------

/**
 * One live socket per URL for the whole process, shared by every subscription.
 *
 * Keyed by URL rather than held per-client because the client object itself is rebuilt on
 * logout/login (`remember(client)` in App), and a socket outliving its client would be a
 * leak. The token provider closure reads the *current* client's token at connect time, so a
 * rebuilt client's fresh token is what authorises the next socket.
 */
private object SharedSockets {

    fun forUrl(url: String, tokenProvider: () -> String?): SharedSocket =
        synchronized(lock) { sockets.getOrPut(url) { SharedSocket(url, tokenProvider) } }

    fun shutdown(url: String) {
        val socket = synchronized(lock) { sockets.remove(url) } ?: return
        socket.shutdown()
    }

    private val lock = Any()
    private val sockets = HashMap<String, SharedSocket>()
}

/**
 * The multiplexed socket: one connection, many subscriptions, each identified by the
 * operation string it was registered under.
 *
 * ## Lifecycle
 *
 * The first `register` connects; the reader coroutine then owns the socket until it dies.
 * On death, every registered channel is closed *with a cause* — a silently idle flow is the
 * one failure mode this transport must never produce, because the callers' reconnect loops
 * only wake on completion or failure. Closing with a cause makes the failure legible both
 * ways: the collector's `catch` sees it, and `collect` returning normally would also re-loop.
 */
private class SharedSocket(
    private val url: String,
    private val tokenProvider: () -> String?,
) {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Registered operations: id -> where its events go. Guarded by [mutex]. */
    private val registrations = HashMap<String, SendChannel<JsonObject>>()

    private var reader: Job? = null
    private var socket: WebSocketSession? = null

    /** Completed when the server has acked `connection_init` — subscribing before it is a
     *  protocol violation and a conforming server closes the socket for it. */
    private var acked = CompletableDeferred<Unit>()

    /** Set when the socket has failed and no new registrations should join it. */
    @Volatile
    private var failed = false

    suspend fun register(id: String, subscribeMessage: JsonObject, channel: SendChannel<JsonObject>) {
        mutex.withLock {
            if (failed || reader?.isActive != true) {
                // A fresh generation: clear the failure flag so this registration's connect
                // is the one that revives the socket.
                failed = false
                connectLocked()
            }
            registrations[id] = channel
        }

        // Subscribe only after the ack (see [acked]). Awaiting outside the lock so the
        // reader — which completes the deferred — can never be blocked behind us. The
        // timeout turns an un-acking server into a failed registration (which the caller's
        // reconnect loop handles) rather than a collector parked forever.
        runCatching { kotlinx.coroutines.withTimeout(ACK_TIMEOUT_MS) { acked.await() } }
            .onFailure {
                mutex.withLock { registrations.remove(id) }
                channel.close(GraphQlException(listOf(GraphQlError("Connection not acknowledged"))))
                return
            }
        mutex.withLock {
            runCatching { socket?.sendJson(subscribeMessage) }
        }
    }

    fun unregister(id: String) {
        scope.launch {
            mutex.withLock {
                registrations.remove(id)
                // Tell the server to stop sending this operation's events. Best effort: a
                // dead socket has nothing to tell.
                runCatching {
                    socket?.sendJson(buildJsonObject {
                        put("id", id)
                        put("type", "complete")
                    })
                }
            }
        }
    }

    fun shutdown() {
        scope.launch {
            mutex.withLock {
                reader?.cancel()
                // cancel() — the non-suspending, non-deprecated member that forces the
                // socket's coroutine scope down immediately.
                runCatching { socket?.cancel() }
                socket = null
                registrations.values.forEach { reg ->
                    reg.close(CancellationException("Client closed"))
                }
                registrations.clear()
            }
        }
        // The transport's own scope dies with it: `SharedSockets` removed this instance at
        // shutdown, and a new client's `forUrl` call will build a fresh transport with a
        // fresh scope. Leaving the old scope alive would leak its dispatcher threads.
        // Deferred slightly so the closure block above runs first on this same scope.
        scope.launch {
            kotlinx.coroutines.delay(100)
            scope.cancel()
        }
    }

    /** Connects the shared socket. Callers hold [mutex]. */
    private fun connectLocked() {
        acked = CompletableDeferred()
        reader = scope.launch { runSocket() }
    }

    private companion object {
        /** How long register waits for `connection_ack` before failing the registration. */
        const val ACK_TIMEOUT_MS = 10_000L
    }

    private suspend fun runSocket() {
        val http = HttpClient {
            install(WebSockets) {
                pingInterval = kotlin.time.Duration.parse("20s")
            }
        }
        try {
            http.webSocket(
                urlString = url,
                request = { header(HttpHeaders.SecWebSocketProtocol, SingularClient.GRAPHQL_WS_PROTOCOL) },
            ) {
                mutex.withLock { socket = this }

                sendJson(buildJsonObject {
                    put("type", "connection_init")
                    put("payload", buildJsonObject {
                        tokenProvider()?.let { put("authorization", "Bearer $it") }
                    })
                })

                for (frame in incoming) {
                    val text = (frame as? Frame.Text)?.readText() ?: continue
                    val msg = SingularClient.codec.parseToJsonElement(text).jsonObject
                    val id = msg["id"]?.jsonPrimitive?.content

                    when (msg["type"]?.jsonPrimitive?.content) {
                        "connection_ack" -> acked.complete(Unit)

                        "next" -> msg["payload"]?.jsonObject?.get("data")?.let { data ->
                            mutex.withLock { registrations[id] }?.trySend(data.jsonObject)
                        }

                        "error" -> if (id != null) {
                            // One rejected operation fails only that operation's flow; the
                            // socket stays up for the others.
                            val channel = mutex.withLock { registrations.remove(id) }
                            channel?.close(
                                GraphQlException(listOf(GraphQlError("Subscription rejected: ${msg["payload"]}")))
                            )
                        }

                        // The server finished the operation. Close the flow *normally* so the
                        // caller's loop iterates and re-subscribes — removing the registration
                        // alone would leave the collector parked forever on an idle flow,
                        // the one failure mode this transport must never produce.
                        "complete" -> {
                            val channel = mutex.withLock { registrations.remove(id) }
                            channel?.close()
                        }

                        "ping" -> sendJson(buildJsonObject { put("type", "pong") })
                    }
                }
            }
        } catch (_: ClosedReceiveChannelException) {
            // Ordinary close — handled by the finally below like any other death.
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Connect-time failure — same death handling.
        } finally {
            runCatching { http.close() }
            died()
        }
    }

    /**
     * Marks the socket dead and fails every live registration, waking their reconnect loops.
     *
     * The cause is a plain [IllegalStateException], **not** `CancellationException` — the
     * callers' loops deliberately rethrow cancellation (that is how shutdown stops them), so
     * a cancelled-looking death would read as "shutting down" and permanently kill every
     * watcher instead of triggering the reconnect path this exists to trigger.
     */
    private suspend fun died() {
        val live = mutex.withLock {
            val channels = registrations.values.toList()
            registrations.clear()
            socket = null
            failed = true
            channels
        }
        live.forEach {
            it.close(IllegalStateException("WebSocket connection lost"))
        }
    }
}

private suspend fun WebSocketSession.sendJson(value: JsonObject) =
    send(Frame.Text(SingularClient.codec.encodeToString(JsonObject.serializer(), value)))
