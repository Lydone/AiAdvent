package dev.belaventsev.aiadvent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    currentStrategy: ContextStrategyType,
    onStrategyChanged: (ContextStrategyType) -> Unit,
    onBack: () -> Unit
) {
    var windowSize by remember(currentStrategy) {
        mutableIntStateOf(
            when (currentStrategy) {
                is ContextStrategyType.SlidingWindow -> currentStrategy.windowSize
                is ContextStrategyType.StickyFacts -> currentStrategy.windowSize
                is ContextStrategyType.Branching -> 6
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Стратегия управления контекстом", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            StrategyOption(
                title = "Sliding Window",
                description = "Последние N сообщений, остальное отбрасывается",
                selected = currentStrategy is ContextStrategyType.SlidingWindow,
                onClick = { onStrategyChanged(ContextStrategyType.SlidingWindow(windowSize)) }
            )
            StrategyOption(
                title = "Sticky Facts",
                description = "Ключевые факты + последние N сообщений",
                selected = currentStrategy is ContextStrategyType.StickyFacts,
                onClick = { onStrategyChanged(ContextStrategyType.StickyFacts(windowSize)) }
            )
            StrategyOption(
                title = "Branching",
                description = "Ветвление диалога от контрольных точек",
                selected = currentStrategy is ContextStrategyType.Branching,
                onClick = { onStrategyChanged(ContextStrategyType.Branching()) }
            )

            if (currentStrategy !is ContextStrategyType.Branching) {
                Spacer(Modifier.height(16.dp))
                Text(
                    "Размер окна: $windowSize сообщений",
                    style = MaterialTheme.typography.titleSmall
                )
                Slider(
                    value = windowSize.toFloat(),
                    onValueChange = { windowSize = it.toInt() },
                    onValueChangeFinished = {
                        val updated = when (currentStrategy) {
                            is ContextStrategyType.SlidingWindow -> ContextStrategyType.SlidingWindow(
                                windowSize
                            )

                            is ContextStrategyType.StickyFacts -> ContextStrategyType.StickyFacts(
                                windowSize
                            )

                            else -> currentStrategy
                        }
                        onStrategyChanged(updated)
                    },
                    valueRange = 2f..20f,
                    steps = 17
                )
            }
        }
    }
}

@Composable
private fun StrategyOption(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onClick)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
