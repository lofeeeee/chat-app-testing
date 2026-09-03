plugins {
    kotlin("jvm") version "2.1.21"
    kotlin("plugin.spring") version "2.1.21"
    id("org.springframework.boot") version "3.4.5"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "app.singular"
version = "0.1.0"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-graphql")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // application.yml exposes health/info/metrics; without this those endpoints 404.
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:1.10.1")

    // Argon2id lives in spring-security-crypto but needs BouncyCastle's implementation.
    implementation("org.springframework.security:spring-security-crypto")
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")

    // S3-compatible object storage. The AWS SDK talks to MinIO with an endpoint override,
    // so the same code runs against self-hosted MinIO now and real S3 later with no rewrite.
    implementation(platform("software.amazon.awssdk:bom:2.31.7"))
    implementation("software.amazon.awssdk:s3")
    implementation("software.amazon.awssdk:s3-transfer-manager")
    // Reads and rewrites image metadata; used to strip EXIF GPS before anything is served.
    implementation("com.drewnoakes:metadata-extractor:2.19.0")

    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.graphql:spring-graphql-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("io.mockk:mockk:1.13.16")
}

tasks.withType<Test> { useJUnitPlatform() }

// Virtual threads: one platform thread no longer parks per in-flight request, which is what
// makes a blocking JDBC stack viable under a few thousand concurrent WebSocket clients.
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    jvmArgs = listOf("-XX:+UseZGC", "-XX:+ZGenerational")
}
