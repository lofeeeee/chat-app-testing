package app.singular.client.ui

import java.time.LocalDate

/** Desktop actual: the JVM's own calendar. */
internal actual fun todayDateIso(): String = LocalDate.now().toString()
