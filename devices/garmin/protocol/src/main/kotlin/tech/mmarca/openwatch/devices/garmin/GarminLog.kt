package tech.mmarca.openwatch.devices.garmin

/**
 * Protocol logging for the Garmin stack, debug builds only. It logs
 * addresses, names, settings contents and raw dumps, none of which belongs
 * in a shipped logcat. Without an installed sink, logging is a no-op. The
 * app's `installLogcatSink` routes it to logcat in a debug build.
 */
public object GarminLog {

    @Volatile
    private var sink: ((String) -> Unit)? = null

    /** Whether anything is listening — lets callers skip expensive formatting. */
    public val enabled: Boolean get() = sink != null

    /** Replaces the sink: the app's logcat sink, or a test's that asserts on protocol logs. Every line is redacted first. */
    public fun installSink(newSink: ((String) -> Unit)?) {
        sink = newSink?.let { target ->
            { message -> target(redactSensitiveValues(message)) }
        }
    }

    public fun log(message: String) {
        sink?.invoke(message)
    }

    /** The same, for a message that is expensive to build. A release build never builds it. */
    public inline fun logLazy(message: () -> String) {
        if (enabled) log(message())
    }

    private fun redactSensitiveValues(message: String): String {
        var redacted = BearerCredential.replace(message, "Bearer [redacted]")
        redacted = SensitiveAssignment.replace(redacted) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}[redacted]"
        }
        return OpaqueCredential.replace(redacted) { match ->
            val token = match.value
            // GATT UUIDs and separator lines are diagnostics, not secrets.
            if (GattUuid.containsMatchIn(token) || token.none { it.isLetterOrDigit() }) token else "[redacted]"
        }
    }

    private val GattUuid = Regex("""[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}""")

    private val SensitiveAssignment = Regex(
        pattern = """(?i)\b(authorization|access[_-]?token|refresh[_-]?token|client[_-]?secret|auth(?:entication)?[_-]?key|password|credentials?)\b(\s*[:=]\s*)(?:\"[^\"]*\"|'[^']*'|[^\s,;&}]+)""",
    )
    private val BearerCredential = Regex(
        pattern = """(?i)\bBearer\s+[A-Za-z0-9._~+/=-]+""",
    )
    private val OpaqueCredential = Regex(
        pattern = """(?<![A-Za-z0-9])[A-Za-z0-9+/=_-]{32,}(?![A-Za-z0-9])""",
    )
}
