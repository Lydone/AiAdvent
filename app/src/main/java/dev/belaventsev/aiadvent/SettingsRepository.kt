package dev.belaventsev.aiadvent

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(context: Context) {

    private val dataStore = context.dataStore

    private companion object {
        val STRATEGY_KEY = stringPreferencesKey("strategy_type")
        val WINDOW_SIZE_KEY = intPreferencesKey("window_size")
    }

    val strategyType: Flow<ContextStrategyType> = dataStore.data.map { prefs ->
        val windowSize = prefs[WINDOW_SIZE_KEY] ?: 6
        when (prefs[STRATEGY_KEY]) {
            "sliding_window" -> ContextStrategyType.SlidingWindow(windowSize)
            "sticky_facts" -> ContextStrategyType.StickyFacts(windowSize)
            "branching" -> ContextStrategyType.Branching()
            else -> ContextStrategyType.SlidingWindow(windowSize)
        }
    }

    suspend fun setStrategy(type: ContextStrategyType) {
        dataStore.edit { prefs ->
            when (type) {
                is ContextStrategyType.SlidingWindow -> {
                    prefs[STRATEGY_KEY] = "sliding_window"
                    prefs[WINDOW_SIZE_KEY] = type.windowSize
                }

                is ContextStrategyType.StickyFacts -> {
                    prefs[STRATEGY_KEY] = "sticky_facts"
                    prefs[WINDOW_SIZE_KEY] = type.windowSize
                }

                is ContextStrategyType.Branching -> {
                    prefs[STRATEGY_KEY] = "branching"
                }
            }
        }
    }
}
