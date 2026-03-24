package dev.belaventsev.aiadvent

import android.content.ClipData
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
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(modifier: Modifier = Modifier, vm: ChatViewModel = viewModel()) {
    val uiState by vm.uiState.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val messages = when (val s = uiState) {
        is ChatUiState.Success -> s.messages
        is ChatUiState.Loading -> s.messages
        else -> emptyList()
    }
    val isLoading = uiState is ChatUiState.Loading

    // Автоскролл к низу при новых сообщениях или загрузке
    LaunchedEffect(messages.size, isLoading) {
        if (messages.isNotEmpty()) {
            val targetIndex = if (isLoading) messages.size else messages.lastIndex
            listState.animateScrollToItem(targetIndex)
        }
    }

    Column(modifier = modifier
        .fillMaxSize()
        .padding(16.dp)) {

        // История сообщений + индикатор загрузки
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { msg ->
                MessageBubble(message = msg)
            }
            if (isLoading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(strokeWidth = 2.dp)
                    }
                }
            }
        }

        // Ошибка
        if (uiState is ChatUiState.Error) {
            Text(
                text = (uiState as ChatUiState.Error).message,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Поле ввода
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 120.dp),
            placeholder = { Text("Введите сообщение…") },
            enabled = !isLoading
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Кнопки
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val text = input.trim()
                    if (text.isNotEmpty()) {
                        vm.ask(text)
                        input = ""
                    }
                },
                enabled = !isLoading && input.isNotBlank()
            ) {
                Text("Отправить")
            }

            OutlinedButton(onClick = { vm.reset() }) {
                Text("Сбросить")
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val isUser = message.role == "user"

    val containerColor = if (isUser)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.surfaceVariant

    val alignment = if (isUser) Alignment.End else Alignment.Start

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Surface(
            color = containerColor,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clickable {
                    scope.launch {
                        clipboard.setClipEntry(
                            ClipEntry(ClipData.newPlainText("message", message.content))
                        )
                    }
                }
        ) {
            Text(
                text = message.content,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
