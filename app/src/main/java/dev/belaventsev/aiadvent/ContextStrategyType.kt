package dev.belaventsev.aiadvent

sealed class ContextStrategyType {
    data class SlidingWindow(val windowSize: Int = 6) : ContextStrategyType()
    data class StickyFacts(val windowSize: Int = 6) : ContextStrategyType()
    data class Branching(val currentBranchId: String = "main") : ContextStrategyType()
}
