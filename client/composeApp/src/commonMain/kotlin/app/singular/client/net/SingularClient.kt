package app.singular.client.net

import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
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
        install(WebSockets)
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
     * Subscribes over the `graphql-ws` protocol.
     *
     * The handshake matters. The server rejects the socket unless `connection_init` carries the
     * bearer token, because browsers cannot set headers on a WebSocket upgrade — so the token
     * travels in the init payload instead. That also fixes the socket's authorisation for its
     * lifetime, which is exactly why access tokens are short-lived and why the client must
     * reconnect after a refresh.
     *
     * `channelFlow`, not `flow`: Ktor runs the session block in its own coroutine, and a plain
     * flow would fail its context-preservation check on the first emission.
     */
    fun subscribe(operation: String, variables: JsonObject): Flow<JsonObject> = channelFlow {
        val downstream = this

        http.webSocket(
            urlString = wsBaseUrl,
            request = { header(HttpHeaders.SecWebSocketProtocol, GRAPHQL_WS_PROTOCOL) },
        ) {
            sendJson(buildJsonObject {
                put("type", "connection_init")
                put("payload", buildJsonObject {
                    accessToken?.let { put("authorization", "Bearer $it") }
                })
            })

            try {
                for (frame in incoming) {
                    val text = (frame as? Frame.Text)?.readText() ?: continue
                    val msg = codec.parseToJsonElement(text).jsonObject

                    when (msg["type"]?.jsonPrimitive?.content) {
                        // Subscribe only after the ack. Sending early is a protocol violation
                        // and a conforming server closes the socket for it.
                        "connection_ack" -> sendJson(buildJsonObject {
                            put("id", SUBSCRIPTION_ID)
                            put("type", "subscribe")
                            put("payload", buildJsonObject {
                                put("query", operation)
                                put("variables", variables)
                            })
                        })

                        "next" -> msg["payload"]?.jsonObject?.get("data")?.let {
                            downstream.send(it.jsonObject)
                        }

                        "error" -> throw GraphQlException(
                            listOf(GraphQlError("Subscription rejected: ${msg["payload"]}"))
                        )

                        "complete" -> return@webSocket

                        "ping" -> sendJson(buildJsonObject { put("type", "pong") })
                    }
                }
            } catch (_: ClosedReceiveChannelException) {
                // Ordinary close. The caller reconnects; nothing worth surfacing to the UI.
            }
        }
    }

    /**
     * Uploads bytes straight to object storage.
     *
     * Deliberately not a GraphQL call, and deliberately unauthenticated: the presigned URL
     * carries its own signature, and attaching our bearer token would hand it to a storage
     * host that has no business seeing it.
     */
    suspend fun putBytes(url: String, bytes: ByteArray, contentType: String): Boolean {
        val response = http.put(url) {
            setBody(ByteArrayContent(bytes, ContentType.parse(contentType)))
        }
        return response.status.isSuccess()
    }

    fun close() = http.close()

    companion object {
        const val DEFAULT_HTTP = "http://localhost:8080/graphql"
        const val DEFAULT_WS = "ws://localhost:8080/graphql"
        const val SUBSCRIPTION_ID = "1"
        const val GRAPHQL_WS_PROTOCOL = "graphql-transport-ws"

        /** Public so the inline reified helpers above can reach it. */
        val codec = Json {
            ignoreUnknownKeys = true   // additive schema changes must not break older clients
            encodeDefaults = true
        }
    }
}

private suspend fun io.ktor.websocket.WebSocketSession.sendJson(value: JsonObject) =
    send(Frame.Text(SingularClient.codec.encodeToString(JsonObject.serializer(), value)))
