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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    modifier: Modifier = Modifier,
    vm: ChatViewModel = viewModel(),
    onNavigateToSettings: () -> Unit
) {
    val state by vm.uiState.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size, state.isLoading) {
        if (state.messages.isNotEmpty()) {
            val target = if (state.isLoading) state.messages.size else state.messages.lastIndex
            listState.animateScrollToItem(target)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (state.strategy) {
                            is ContextStrategyType.SlidingWindow -> "Sliding Window"
                            is ContextStrategyType.StickyFacts -> "Sticky Facts"
                            is ContextStrategyType.Branching -> "Branching"
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp)) {

            if (state.strategy is ContextStrategyType.Branching) {
                BranchBar(state.currentBranchId, state.branches, vm::switchBranch, vm::createBranch)
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.messages) { MessageBubble(it) }
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

            if (state.strategy is ContextStrategyType.StickyFacts && state.facts != null) {
                FactsPanel(state.facts!!)
            }

            if (state.totalSpent > 0) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Text(
                        "Потрачено токенов: ${state.totalSpent}",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }

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

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun BranchBar(
    currentBranchId: String,
    branches: List<BranchInfo>,
    onSwitch: (String) -> Unit,
    onCreate: (String) -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            AssistChip(onClick = { menuExpanded = true }, label = { Text(currentBranchId) })
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                branches.forEach { branch ->
                    DropdownMenuItem(
                        text = { Text(branch.name) },
                        onClick = { onSwitch(branch.id); menuExpanded = false }
                    )
                }
            }
        }
        TextButton(onClick = { showDialog = true }) { Text("+ Ветка") }
    }

    if (showDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Новая ветка") },
            text = {
                OutlinedTextField(
                    name,
                    { name = it },
                    placeholder = { Text("Имя ветки") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (name.isNotBlank()) {
                            onCreate(name.trim()); showDialog = false
                        }
                    },
                    enabled = name.isNotBlank()
                ) { Text("Создать") }
            },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun FactsPanel(facts: String) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(
                if (expanded) "▼ Факты" else "▶ Факты",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.clickable { expanded = !expanded }
            )
            AnimatedVisibility(expanded) {
                Text(
                    facts,
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
        Modifier.fillMaxWidth(),
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
                item.message.content,
                Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        if (!isUser && item.totalTokens > 0) {
            Text(
                "контекст: ${item.promptTokens}  ·  ответ: ${item.completionTokens}  ·  итого: ${item.totalTokens}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp)
            )
        }
    }
}
