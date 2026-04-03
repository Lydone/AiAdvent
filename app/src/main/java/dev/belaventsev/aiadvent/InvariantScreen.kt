package dev.belaventsev.aiadvent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import dev.belaventsev.aiadvent.db.AppDatabase
import dev.belaventsev.aiadvent.db.InvariantEntity
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InvariantsScreen(
    db: AppDatabase,
    userId: String,
    onBack: () -> Unit
) {
    val invariants by db.invariantDao().observeAll(userId).collectAsState(initial = emptyList())
    var newText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Инварианты", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
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
        ) {
            if (invariants.isEmpty()) {
                Text(
                    "Нет инвариантов. Добавьте первый!",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(invariants) { invariant ->
                    InvariantCard(
                        invariant = invariant,
                        onDelete = {
                            scope.launch { db.invariantDao().deleteById(invariant.id) }
                        }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = newText,
                onValueChange = { newText = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Новый инвариант…") },
                singleLine = false,
                maxLines = 3
            )

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    val text = newText.trim()
                    if (text.isNotEmpty()) {
                        scope.launch {
                            db.invariantDao().insert(
                                InvariantEntity(userId = userId, text = text)
                            )
                            newText = ""
                        }
                    }
                },
                enabled = newText.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Добавить")
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun InvariantCard(
    invariant: InvariantEntity,
    onDelete: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                invariant.text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Удалить",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
