@file:OptIn(ExperimentalMaterial3Api::class)

package dev.belaventsev.aiadvent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.ExperimentalMaterial3Api
import dev.belaventsev.aiadvent.ui.theme.AiAdventTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AiAdventTheme {
                AppNavigation()
            }
        }
    }
}
