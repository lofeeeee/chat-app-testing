package app.singular.security

import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.graphql.server.webmvc.GraphQlWebSocketHandler
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.TextWebSocketHandler
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
 * consults any decorator factory. So instead we post-process the handler bean itself,
 * wrapping it in a forwarding handler whose `afterConnectionEstablished` captures the
 * session. Every `WebSocketHandler` method is forwarded; nothing else changes.
 *
 * The wrap happens *after* Boot's autoconfiguration has already built the
 * `WebSocketHttpRequestHandler` mapping that references the original bean — which is fine,
 * because the mapping holds whatever the processor returns for the bean name, and the
 * post-processor runs before the mapping bean is initialized and injected.
 */
@Configuration
class WebSocketCaptureConfig {

    @Bean
    fun rawSessionCapture(): BeanPostProcessor = object : BeanPostProcessor {
        override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
            if (bean is GraphQlWebSocketHandler) {
                return CapturingHandler(bean)
            }
            return bean
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
