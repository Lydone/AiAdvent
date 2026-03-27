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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(modifier: Modifier = Modifier, vm: ChatViewModel = viewModel()) {
    val state by vm.uiState.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size, state.isLoading) {
        if (state.messages.isNotEmpty()) {
            val target = if (state.isLoading) state.messages.size else state.messages.lastIndex
            listState.animateScrollToItem(target)
        }
    }

    Column(modifier = modifier
        .fillMaxSize()
        .padding(16.dp)) {

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(state.messages) { item -> MessageBubble(item) }
            if (state.isLoading) {
                item {
                    Box(Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp), Alignment.Center) {
                        CircularProgressIndicator(strokeWidth = 2.dp)
                    }
                }
            }
        }

        state.error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }

        TokenPanel(
            totalSpent = state.totalSpent,
            useCompression = state.useCompression,
            summaryText = state.summaryText,
            onToggle = vm::toggleCompression
        )

        Spacer(Modifier.height(4.dp))

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 120.dp),
            placeholder = { Text("Введите сообщение…") },
            enabled = !state.isLoading
        )

        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val text = input.trim()
                    if (text.isNotEmpty()) {
                        vm.ask(text); input = ""
                    }
                },
                enabled = !state.isLoading && input.isNotBlank()
            ) { Text("Отправить") }

            OutlinedButton(onClick = vm::reset) { Text("Сбросить") }
        }
    }
}

@Composable
private fun TokenPanel(
    totalSpent: Int,
    useCompression: Boolean,
    summaryText: String?,
    onToggle: () -> Unit
) {
    var summaryExpanded by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    if (totalSpent > 0) {
                        Text(
                            "Потрачено токенов: $totalSpent",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    if (summaryText != null) {
                        Text(
                            text = if (summaryExpanded) "▼ Summary" else "▶ Summary",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { summaryExpanded = !summaryExpanded }
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Сжатие", style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.width(4.dp))
                    Switch(checked = useCompression, onCheckedChange = { onToggle() })
                }
            }

            AnimatedVisibility(visible = summaryExpanded && summaryText != null) {
                Text(
                    text = summaryText.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(item: MessageWithTokens) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val isUser = item.message.role == "user"

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Surface(
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clickable {
                    scope.launch {
                        clipboard.setClipEntry(
                            androidx.compose.ui.platform.ClipEntry(
                                ClipData.newPlainText("message", item.message.content)
                            )
                        )
                    }
                }
        ) {
            Text(
                text = item.message.content,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        if (!isUser && item.totalTokens > 0) {
            Text(
                text = "контекст: ${item.promptTokens}  ·  ответ: ${item.completionTokens}  ·  итого: ${item.totalTokens}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp)
            )
        }
    }
}
