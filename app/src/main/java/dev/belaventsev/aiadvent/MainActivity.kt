@file:OptIn(ExperimentalMaterial3Api::class)

package dev.belaventsev.aiadvent

import android.content.ClipData
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.belaventsev.aiadvent.ui.theme.AiAdventTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AiAdventTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ChatScreen(Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun ChatScreen(modifier: Modifier = Modifier, vm: ChatViewModel = viewModel()) {
    val uiState = vm.uiState.collectAsState().value
    var input by remember { mutableStateOf("") }
    var selectedModel by remember { mutableStateOf(ChatViewModel.MODELS.first()) }
    var expanded by remember { mutableStateOf(false) }

    val messages = when (val s = uiState) {
        is ChatUiState.Success -> s.messages
        is ChatUiState.Loading -> s.messages
        else -> emptyList()
    }

    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = selectedModel.substringBefore(":"),
                onValueChange = {},
                readOnly = true,
                label = { Text("Модель") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor()
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                ChatViewModel.MODELS.forEach { model ->
                    DropdownMenuItem(
                        text = { Text(model.substringBefore(":")) },
                        onClick = { selectedModel = model; expanded = false }
                    )
                }
            }
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(messages) { MessageBubble(it) }
        }

        when (uiState) {
            is ChatUiState.Loading -> CircularProgressIndicator()
            is ChatUiState.Error -> Text(uiState.message)
            is ChatUiState.Success -> {
                Text("⏱ Время: ${uiState.elapsedMs} мс")
                Text("🔢 Токены: ${uiState.tokensUsed}")
            }
            else -> Unit
        }

        OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth(), label = { Text("Сообщение") })

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { vm.ask(input, selectedModel); input = "" },
                enabled = uiState !is ChatUiState.Loading && input.isNotBlank(),
                modifier = Modifier.weight(1f)
            ) { Text("Отправить") }

            OutlinedButton({ vm.reset() }, Modifier.weight(1f)) { Text("Сбросить") }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == "user"
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    Text(
        text = "${if (isUser) "Вы" else "AI"}: ${message.content}",
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isUser) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(8.dp)
            )
            .padding(8.dp)
            .clickable {
                scope.launch {
                    clipboard.setClipEntry(
                        ClipEntry(ClipData.newPlainText("message", message.content))
                    )
                    Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
                }
            }
    )
}
