import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.Properties

/**
 * Is an Android SDK available?
 *
 * The Android Gradle Plugin fails the *configuration* phase when it can't find an SDK, which
 * would make `:composeApp:run` on desktop impossible for anyone who hasn't installed Android
 * Studio. Since the whole point of this module is that common code builds everywhere, the
 * Android target is opt-in on the SDK actually being there.
 *
 * Install the SDK (or set ANDROID_HOME) and the target reappears with no edit to this file.
 */
val androidSdkDir: String? = run {
    val fromLocalProperties = rootProject.file("local.properties")
        .takeIf { it.exists() }
        ?.let { file ->
            // Imported rather than fully qualified: in a Gradle .kts the `java` identifier
            // resolves to the JavaPluginExtension accessor, which shadows the package name.
            Properties().apply { file.inputStream().use { load(it) } }.getProperty("sdk.dir")
        }

    val candidates = listOfNotNull(
        fromLocalProperties,
        System.getenv("ANDROID_HOME"),
        System.getenv("ANDROID_SDK_ROOT"),
        System.getProperty("user.home") + "/AppData/Local/Android/Sdk",   // Windows
        System.getProperty("user.home") + "/Library/Android/sdk",         // macOS
        System.getProperty("user.home") + "/Android/Sdk",                 // Linux
    )
    candidates.firstOrNull { File(it).isDirectory }
}

val buildAndroid = androidSdkDir != null

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    // Applied below only when an SDK exists — see androidSdkDir.
    alias(libs.plugins.androidApplication) apply false
}

if (buildAndroid) {
    apply(plugin = "com.android.application")
    logger.lifecycle("Android SDK found at $androidSdkDir — building the Android target too.")
} else {
    logger.lifecycle("No Android SDK found — building desktop only. Set ANDROID_HOME to include Android.")
}

kotlin {
    jvmToolchain(21)

    if (buildAndroid) androidTarget()

    jvm("desktop")

    // iOS targets land in phase 5. Compose Multiplatform for iOS went Stable in 1.8.0, so this
    // is a scheduling decision rather than a technical blocker.

    sourceSets {
        val desktopMain by getting

        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.components.resources)

            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)

            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.websockets)
            implementation(libs.ktor.serialization.json)

            // Coil 3 is the Compose Multiplatform image loader. Its Ktor network layer reuses
            // the engine each platform already ships, rather than pulling in a second HTTP
            // stack purely to fetch pictures.
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor)
        }

        commonTest.dependencies {
            // One test, and it is the contract the whole theme rests on. See ThemeContrastTest.
            implementation(kotlin("test"))
        }

        if (buildAndroid) {
            androidMain.dependencies {
                implementation(compose.preview)
                implementation(libs.androidx.activity.compose)
                implementation(libs.ktor.client.okhttp)
                // Pure-Java QR encoder. `core` carries no Android dependencies, so desktop uses
                // the same artifact — one vendored jar covers both actuals.
                implementation(libs.zxing.core)
            }
        }

        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.ktor.client.cio)
            implementation(libs.zxing.core)
        }
    }
}

if (buildAndroid) {
    extensions.configure<com.android.build.api.dsl.ApplicationExtension>("android") {
        namespace = "app.singular.client"
        compileSdk = libs.versions.android.compileSdk.get().toInt()

        defaultConfig {
            applicationId = "app.singular.client"
            minSdk = libs.versions.android.minSdk.get().toInt()
            targetSdk = libs.versions.android.targetSdk.get().toInt()
            versionCode = 1
            versionName = "0.1.0"
        }

        buildTypes {
            getByName("release") { isMinifyEnabled = false }
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_21
            targetCompatibility = JavaVersion.VERSION_21
        }
    }
}

compose.desktop {
    application {
        mainClass = "app.singular.client.MainKt"

        nativeDistributions {
            // jlink strips the JDK to the modules actually used, which is what keeps the desktop
            // install around 70-90 MB instead of shipping a whole runtime.
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "Singular"
            packageVersion = "1.0.0"

            modules("java.net.http", "jdk.crypto.ec")
        }
    }
}

/**
 * Writes a `java @argfile` that launches the desktop app without Gradle.
 *
 * Two concurrent `gradlew :composeApp:run` invocations deadlock: `run` holds the build open for
 * the app's whole lifetime, and the second invocation blocks on the project lock waiting for it.
 * Running a second window for testing therefore needs a Gradle-free launch path.
 *
 * An argfile rather than a literal `-cp`: a Compose Desktop classpath runs to tens of thousands
 * of characters and Windows caps a command line at 8191. Paths are written with forward slashes
 * because Java argfiles treat a backslash as an escape character inside quotes.
 */
val exportLaunchArgs by tasks.registering {
    description = "Writes build/singular-args.txt for launching the app with `java @argfile`."
    group = "distribution"

    val jarTask = tasks.named("desktopJar")
    val runtime = configurations.named("desktopRuntimeClasspath")
    val outFile = layout.buildDirectory.file("singular-args.txt")

    dependsOn(jarTask)
    outputs.file(outFile)

    doLast {
        val entries = runtime.get().files + jarTask.get().outputs.files.files
        val classpath = entries.joinToString(File.pathSeparator) {
            it.absolutePath.replace('\\', '/')
        }
        outFile.get().asFile.writeText("-cp \"$classpath\"\napp.singular.client.MainKt\n")
    }
}
