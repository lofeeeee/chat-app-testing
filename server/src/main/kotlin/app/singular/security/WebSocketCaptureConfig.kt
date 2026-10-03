package app.singular.security

import org.aopalliance.intercept.MethodInterceptor
import org.springframework.aop.framework.ProxyFactory
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.graphql.server.webmvc.GraphQlWebSocketHandler
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.TextWebSocketHandler
import org.springframework.web.socket.server.support.WebSocketHttpRequestHandler
import org.springframework.web.socket.sockjs.frame.SockJsMessageCodec
import java.security.Principal

/**
 * Captures the closable WebSocket session into the session's own attribute map.
 *
 * Why this exists: Spring GraphQL hands its interceptors a `WebSocketSessionInfo` — a
 * read-only view with no close method — while the closable
 * `org.springframework.web.socket.WebSocketSession` stays private behind it. But the
 * view's `getAttributes()` is the *same* mutable map the real session carries, so a handler
 * running `afterConnectionEstablished` on the raw chain can stash the session where
 * [WebSocketSessionRegistry] reads it back. That is the only public-API route from "I
 * have a WebSocketSessionInfo" to "close this connection now".
 *
 * ## Why a BeanPostProcessor and not `WebSocketHandlerDecoratorFactory`
 *
 * The decorator-factory hook looked like the natural seam, but it is **STOMP-only**:
 * `WebSocketHandlerDecoratorFactory` is consulted exclusively by
 * `WebSocketMessageBrokerConfigurationSupport.decorateWebSocketHandler` — the message-broker
 * (STOMP) stack. This server uses Spring GraphQL's plain servlet WebSocket handler
 * (`GraphQlWebSocketHandler` via Boot's `GraphQlWebMvcAutoConfiguration`), which never
 * consults any decorator factory. So instead we post-process the handler bean itself.
 *
 * ## Why a proxy rather than returning the decorator directly
 *
 * Returning a bare decorator from the post-processor does not work, and fails at startup:
 *
 *     Bean named 'graphQlWebSocketHandler' is expected to be of type
 *     'GraphQlWebSocketHandler' but was actually of type 'CapturingHandler'
 *
 * Boot's `graphQlWebSocketMapping` takes the handler as a **`GraphQlWebSocketHandler`**, not as
 * a `WebSocketHandler`, so the replacement has to keep that type — and it then calls
 * `handler.initWebSocketHttpRequestHandler(...)` *on the bean*, which a plain delegate has no
 * way to answer. So the bean stays a `GraphQlWebSocketHandler` by being a CGLIB subclass proxy
 * of the real one.
 *
 * The proxy exists for exactly one interception. `initWebSocketHttpRequestHandler` builds the
 * request handler around `this`, and inside the target `this` is the target — so the socket
 * would reach the undecorated handler and the capture would never run. The advice lets the
 * target build it (keeping whatever Spring GraphQL configures on it), then returns an
 * equivalent request handler wrapped around [CapturingHandler], carrying the same handshake
 * handler and interceptors across. Everything the connection actually uses then routes through
 * the decorator, and nothing reimplements Spring's own setup.
 */
@Configuration
class WebSocketCaptureConfig {

    @Bean
    fun rawSessionCapture(): BeanPostProcessor = object : BeanPostProcessor {
        override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
            if (bean !is GraphQlWebSocketHandler) return bean

            val capturing = CapturingHandler(bean)
            return ProxyFactory(bean).apply {
                // A subclass proxy, not an interface proxy: the injection point asks for the
                // concrete type.
                isProxyTargetClass = true
                addAdvice(MethodInterceptor { invocation ->
                    val result = invocation.proceed()
                    if (invocation.method.name == "initWebSocketHttpRequestHandler" &&
                        result is WebSocketHttpRequestHandler
                    ) {
                        WebSocketHttpRequestHandler(capturing, result.handshakeHandler).apply {
                            setHandshakeInterceptors(result.handshakeInterceptors)
                        }
                    } else {
                        result
                    }
                })
            }.proxy
        }
    }

    /**
     * Forwards every call to the auto-configured GraphQL handler, except
     * `afterConnectionEstablished`, which additionally stores the raw session in its own
     * attribute map under [WebSocketSessionRegistry.RAW_SESSION_KEY].
     *
     * Implements the interfaces directly rather than extending
     * `GraphQlWebSocketHandler`: that class's constructor needs collaborators that are
     * private to the delegate, unreachable here. Plain delegation is the honest shape —
     * and `SubProtocolCapable` MUST be forwarded, or the `graphql-transport-ws`
     * subprotocol negotiation in the handshake silently breaks.
     */
    private class CapturingHandler(private val delegate: GraphQlWebSocketHandler) :
        WebSocketHandler, org.springframework.web.socket.SubProtocolCapable {

        override fun afterConnectionEstablished(session: WebSocketSession) {
            session.attributes[WebSocketSessionRegistry.RAW_SESSION_KEY] = session
            delegate.afterConnectionEstablished(session)
        }

        override fun handleMessage(
            session: WebSocketSession,
            message: org.springframework.web.socket.WebSocketMessage<*>,
        ) = delegate.handleMessage(session, message)

        override fun handleTransportError(session: WebSocketSession, exception: Throwable) =
            delegate.handleTransportError(session, exception)

        override fun afterConnectionClosed(session: WebSocketSession, closeStatus: CloseStatus) =
            delegate.afterConnectionClosed(session, closeStatus)

        override fun supportsPartialMessages(): Boolean = delegate.supportsPartialMessages()

        override fun getSubProtocols(): MutableList<String> = delegate.subProtocols
    }
}
