package dev.aiauto.android.provider

object SensitiveLogRedactor {
    private const val REDACTED = "[REDACTED]"

    private val bearerPattern = Regex(
        pattern = """(?i)(Authorization\s*:\s*Bearer\s+|Bearer\s+)[^\s,"}]+""",
    )
    private val jsonKeyPattern = Regex(
        pattern = """(?i)("(?:api[_-]?key|token|secret|password)"\s*:\s*")""" +
            """(?:\\.|[^"\\])*(")""",
    )

    fun redact(message: String, vararg secrets: String): String {
        var safe = bearerPattern.replace(message) { match ->
            match.groupValues[1] + REDACTED
        }
        safe = jsonKeyPattern.replace(safe) { match ->
            match.groupValues[1] + REDACTED + match.groupValues[2]
        }
        secrets
            .filter { it.isNotBlank() }
            .sortedByDescending(String::length)
            .forEach { secret -> safe = safe.replace(secret, REDACTED) }
        return safe
    }
}
