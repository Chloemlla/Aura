package com.freevibe.ui

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.freevibe.ui.navigation.Screen
import com.freevibe.ui.screens.aigenerate.AiWallpaperScreen

internal fun NavGraphBuilder.generatedWallpaperRoute(navController: NavHostController) {
    composable(Screen.AiWallpaper.route) {
        AiWallpaperScreen(
            onBack = { navController.navigateUp() },
        )
    }
}
