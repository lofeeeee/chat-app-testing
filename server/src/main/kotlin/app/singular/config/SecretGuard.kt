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
 * ## Which profiles count as "development"
 *
 * Explicitly an allowlist of `dev` / `local` / `test` — NOT "anything that isn't `prod`".
 * Keying on the absence of `prod` was the original shape, and it has the exact failure mode
 * this class exists to prevent: a deployment that forgets to set the profile (or sets it to
 * `staging`, `eu-west`, anything) sails through with repository-printed secrets. The operator
 * must *declare* development, not merely fail to declare production.
 *
 * There is no flag to opt out: running with the dev secrets IS development, and everything
 * else must fail loudly.
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
        if (env.activeProfiles.isEmpty() || env.activeProfiles.any { it.lowercase() in DEV_PROFILES }) return

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
                "Development secrets are in use but no development profile is active " +
                    "(active: ${env.activeProfiles.toList()}). Development is an allowlist " +
                    "(dev/local/test), not the absence of prod. Set SINGULAR_TOKEN_SECRET and " +
                    "SINGULAR_PEPPER to real values and restart."
            )
        }
    }

    private companion object {
        /** Profiles that explicitly declare "this machine is a developer's machine". */
        val DEV_PROFILES = setOf("dev", "local", "test")

        const val DEV_TOKEN_SECRET = "dev-only-insecure-token-secret-change-me-now"
        const val DEV_PEPPER = "dev-only-insecure-pepper-change-me-now"
        val LOG = LoggerFactory.getLogger(SecretGuard::class.java)!!
    }
}
