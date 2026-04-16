package dev.belaventsev.aiadvent

import android.content.ClipData
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RagScreen(
    onBack: () -> Unit,
    vm: RagViewModel = viewModel()
) {
    val state by vm.uiState.collectAsState()
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RAG тест", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            contentDescription = "Назад"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(scrollState)
        ) {
            // Question input
            OutlinedTextField(
                value = state.question,
                onValueChange = vm::updateQuestion,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 80.dp),
                placeholder = { Text("Задайте вопрос по документам…") },
                enabled = !state.isLoadingPlain && !state.isLoadingRag,
                maxLines = 4
            )

            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = vm::askWithoutRag,
                    enabled = !state.isLoadingPlain && !state.isLoadingRag && state.question.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) { Text("Без RAG") }

                Button(
                    onClick = vm::askWithRag,
                    enabled = !state.isLoadingPlain && !state.isLoadingRag && state.question.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) { Text("С RAG") }
            }

            Spacer(Modifier.height(4.dp))

            OutlinedButton(
                onClick = vm::clear,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Очистить ответы") }

            state.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            Spacer(Modifier.height(16.dp))

            // Plain answer
            AnswerBlock(
                title = "Без RAG",
                isLoading = state.isLoadingPlain,
                answer = state.plainAnswer,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))

            // RAG answer
            AnswerBlock(
                title = "С RAG",
                isLoading = state.isLoadingRag,
                answer = state.ragAnswer,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                sources = state.ragSources,
                context = state.ragContext
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AnswerBlock(
    title: String,
    isLoading: Boolean,
    answer: String?,
    containerColor: Color,
    contentColor: Color,
    sources: List<String> = emptyList(),
    context: String = ""
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var showContext by remember { mutableStateOf(false) }

    Surface(
        color = containerColor,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = contentColor
            )
            Spacer(Modifier.height(4.dp))

            when {
                isLoading -> {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(strokeWidth = 2.dp)
                    }
                }

                answer == null -> {
                    Text(
                        "—",
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = 0.6f)
                    )
                }

                else -> {
                    Text(
                        answer,
                        style = MaterialTheme.typography.bodyMedium,
                        color = contentColor,
                        modifier = Modifier.clickable {
                            scope.launch {
                                clipboard.setClipEntry(
                                    ClipEntry(ClipData.newPlainText("answer", answer))
                                )
                            }
                        }
                    )

                    if (sources.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Источники (${sources.size}):",
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor
                        )
                        sources.forEach { source ->
                            Text(
                                "• $source",
                                style = MaterialTheme.typography.labelSmall,
                                color = contentColor.copy(alpha = 0.8f)
                            )
                        }
                    }

                    if (context.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (showContext) "▼ Скрыть контекст" else "▶ Показать контекст",
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor,
                            modifier = Modifier.clickable { showContext = !showContext }
                        )
                        AnimatedVisibility(showContext) {
                            Text(
                                context,
                                style = MaterialTheme.typography.bodySmall,
                                color = contentColor.copy(alpha = 0.8f),
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
