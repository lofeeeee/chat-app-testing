package app.singular.client.ui

import java.time.LocalDate

/** Android actual: the JVM calendar Android ships. */
internal actual fun todayDateIso(): String = LocalDate.now().toString()
