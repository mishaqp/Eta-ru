package io.github.mangi.eta.config

internal enum class PowerAssistantTarget(
    val persistedValue: String,
) {
    ETA("eta"),
    GEMINI("gemini"),
    SYSTEM("system"),
    ;

    companion object {
        fun resolve(
            persistedValue: String?,
            legacyPowerKeyTakeover: Boolean,
        ): PowerAssistantTarget = when (persistedValue) {
            "oem" -> SYSTEM
            else -> entries.firstOrNull { it.persistedValue == persistedValue }
        } ?: if (legacyPowerKeyTakeover) {
            GEMINI
        } else {
            SYSTEM
        }
    }
}
