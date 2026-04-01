package dev.belaventsev.aiadvent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.belaventsev.aiadvent.db.AppDatabase
import dev.belaventsev.aiadvent.ui.theme.AiAdventTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val db = AppDatabase.getInstance(this)

        setContent {
            AiAdventTheme {
                val navController = rememberNavController()

                NavHost(navController = navController, startDestination = "profiles") {

                    composable("profiles") {
                        ProfileScreen(
                            db = db,
                            onProfileSelected = { userId ->
                                navController.navigate("chat/$userId")
                            }
                        )
                    }

                    composable(
                        route = "chat/{userId}",
                        arguments = listOf(navArgument("userId") { type = NavType.StringType })
                    ) {
                        ChatScreen(onBack = { navController.popBackStack() })
                    }
                }
            }
        }
    }
}
