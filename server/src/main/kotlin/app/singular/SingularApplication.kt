package app.singular

import app.singular.config.SingularProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

@SpringBootApplication
@EnableConfigurationProperties(SingularProperties::class)
class SingularApplication

fun main(args: Array<String>) {
    runApplication<SingularApplication>(*args)
}
