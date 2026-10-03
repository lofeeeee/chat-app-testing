package app.singular.security

import app.singular.config.SingularProperties
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component

/**
 * Publishes `singular.trust-proxy` into [ClientInfoResolver] once at startup.
 *
 * The resolver is a framework-agnostic object (it is called from places without easy bean
 * access, and is trivially unit-testable that way), so configuration reaches it here rather
 * than through constructor injection. Setting it exactly once, before the first request, is
 * what makes the `@Volatile` field's simplicity safe: after startup it is effectively a
 * constant that only tests ever touch.
 */
@Component
class ProxyTrustConfig(
    private val props: SingularProperties,
) {
    @PostConstruct
    fun apply() {
        ClientInfoResolver.trustProxy = props.trustProxy
    }
}
