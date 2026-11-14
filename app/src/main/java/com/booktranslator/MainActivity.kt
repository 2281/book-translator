package com.booktranslator

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.booktranslator.ui.catalog.CatalogScreen
import com.booktranslator.ui.reader.ReaderScreen
import com.booktranslator.ui.theme.BookTranslatorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BookTranslatorTheme {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = "catalog") {
                    composable("catalog") {
                        CatalogScreen(
                            onOpenBook = { bookId -> navController.navigate("reader/$bookId") }
                        )
                    }
                    composable(
                        route = "reader/{bookId}",
                        arguments = listOf(
                            navArgument("bookId") { type = NavType.LongType }
                        )
                    ) { backStackEntry ->
                        ReaderScreen(
                            bookId = backStackEntry.arguments?.getLong("bookId") ?: 0L,
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }
}
