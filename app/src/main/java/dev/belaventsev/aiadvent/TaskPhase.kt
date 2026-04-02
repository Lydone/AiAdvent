package dev.belaventsev.aiadvent

enum class TaskPhase(val label: String) {
    IDLE("idle"),
    PLANNING("planning"),
    EXECUTION("execution"),
    VALIDATION("validation"),
    DONE("done");

    companion object {
        fun fromString(value: String): TaskPhase =
            entries.firstOrNull { it.label == value.lowercase().trim() } ?: IDLE

        /** Allowed transitions for the state machine */
        val transitions = mapOf(
            IDLE to setOf(PLANNING),
            PLANNING to setOf(EXECUTION, IDLE),
            EXECUTION to setOf(VALIDATION, IDLE),
            VALIDATION to setOf(EXECUTION, DONE, IDLE),
            DONE to setOf(IDLE)
        )
    }
}
