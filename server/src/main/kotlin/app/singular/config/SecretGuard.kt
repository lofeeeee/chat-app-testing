package app.singular.config

import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationListener
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * Refuses to run with development secrets outside development.
 *
 * `SINGULAR_TOKEN_SECRET` and `SINGULAR_PEPPER` ship with known dev defaults. The README says
 * "replace before any deployment" — this turns that sentence into an enforced property, the
 * same way the Valkey-at-boot check turns "Valkey is mandatory" into one: a node that silently
 * runs with a secret printed in the repository is the deployment mistake nobody notices until
 * it is the incident.
 *
 * The check is keyed on the `prod` profile being active. There is no `dev`-profile opt-out to
 * toggle: running with the dev secrets IS development, and everything else must fail loudly.
 *
 * Both fail-fast checks live here rather than in [SingularProperties] `init` blocks because
 * properties bind before profiles' semantics matter — the application context is the first
 * place that can ask "which profiles are active?" and get an honest answer.
 */
@Component
class SecretGuard(
    private val props: SingularProperties,
    private val env: Environment,
) : ApplicationListener<ApplicationReadyEvent> {

    override fun onApplicationEvent(event: ApplicationReadyEvent) {
        if (!env.activeProfiles.contains("prod")) return

        val problems = buildList {
            if (props.auth.tokenSecret == DEV_TOKEN_SECRET) {
                add("SINGULAR_TOKEN_SECRET is still the development default — anyone can forge access tokens.")
            }
            if (props.crypto.pepper == DEV_PEPPER) {
                add("SINGULAR_PEPPER is still the development default — email blind indexes are guessable by anyone with the source.")
            }
        }
        if (problems.isNotEmpty()) {
            problems.forEach { LOG.error("REFUSING TO SERVE: {}", it) }
            throw IllegalStateException(
                "Production profile is active but development secrets are in use. " +
                    "Set SINGULAR_TOKEN_SECRET and SINGULAR_PEPPER to real values and restart."
            )
        }
    }

    private companion object {
        const val DEV_TOKEN_SECRET = "dev-only-insecure-token-secret-change-me-now"
        const val DEV_PEPPER = "dev-only-insecure-pepper-change-me-now"
        val LOG = LoggerFactory.getLogger(SecretGuard::class.java)!!
    }
}
