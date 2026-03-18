package dev.belaventsev.aiadvent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.belaventsev.aiadvent.ui.theme.AiAdventTheme

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
    val uiState by vm.uiState.collectAsState()
    var input by remember { mutableStateOf("") }
    var additionPrompt by remember { mutableStateOf("") }
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            input,
            { input = it },
            Modifier.fillMaxWidth(),
            label = { Text("Вопрос") })
        OutlinedTextField(
            additionPrompt,
            { additionPrompt = it },
            Modifier.fillMaxWidth(),
            label = { Text("Системный промпт") },
        )
        Button(
            {
                vm.ask(input, additionPrompt)
                input = ""
            },
            Modifier.fillMaxWidth()
        ) { Text("Отправить") }
        when (val s = uiState) {
            is ChatUiState.Loading -> CircularProgressIndicator()
            is ChatUiState.Success -> Text(s.answer)
            is ChatUiState.Error -> Text(s.message)
            else -> Unit
        }
    }
}
