package app.singular.config

import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Valkey (Redis-compatible) wiring.
 *
 * Valkey is a hard dependency on purpose. The alternative — degrading to the old node-local
 * in-memory fanout when the server can't be reached — sounds friendlier but is the worse
 * failure: a multi-node deployment where one node quietly stopped sharing events is a
 * split-brain that produces "works on my node" bugs nobody can reproduce. Not starting,
 * loudly, is the recoverable failure. docker-compose always runs Valkey, so in development
 * the requirement costs nothing.
 *
 * Two connections, one client:
 *   * [commands] — plain connection for shared volatile state (presence heartbeats, locks,
 *     future shared rate-limit counters).
 *   * [pubSub] — the single pub/sub connection every [app.singular.event.FanoutBus]
 *     subscription multiplexes over. Lettuce supports arbitrarily many channels on one
 *     pub/sub connection, which is why fanout doesn't need a connection pool.
 */
@Configuration
class ValkeyConfig(private val props: SingularProperties) {

    @Bean(destroyMethod = "shutdown")
    fun redisClient(): RedisClient {
        val client = RedisClient.create(RedisURI.create(props.valkey.uri))

        // Fail fast, with a message that names the knob to turn. A stack trace from deep in
        // Netty on startup is a riddle; this line is an instruction.
        val ping = runCatching {
            val probe = client.connect()
            try {
                probe.sync().ping()
            } finally {
                probe.close()
            }
        }.getOrElse {
            throw IllegalStateException(
                "Cannot reach Valkey at ${props.valkey.uri} — is the container up? " +
                    "Start it with `docker compose up -d`, or point singular.valkey.uri " +
                    "(VALKEY_URI) at the right host. The server refuses to start without it: " +
                    "silently falling back to single-node fanout in a multi-node deployment " +
                    "would split-brain.",
                it,
            )
        }
        LOG.info("Valkey at {} answered PING with {}", props.valkey.uri, ping)
        return client
    }

    /** Commands connection for shared volatile state. Blocking API is fine on virtual threads. */
    @Bean(destroyMethod = "close")
    fun commands(client: RedisClient): io.lettuce.core.api.StatefulRedisConnection<String, String> =
        client.connect()

    /** The one pub/sub connection every subscription multiplexes over. */
    @Bean(destroyMethod = "close")
    fun pubSub(client: RedisClient): StatefulRedisPubSubConnection<String, String> =
        client.connectPubSub()

    private companion object {
        val LOG = LoggerFactory.getLogger(ValkeyConfig::class.java)!!
    }
}
