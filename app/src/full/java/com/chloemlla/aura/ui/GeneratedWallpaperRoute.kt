package com.chloemlla.aura.ui

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.chloemlla.aura.ui.navigation.Screen
import com.chloemlla.aura.ui.screens.aigenerate.AiWallpaperScreen

internal fun NavGraphBuilder.generatedWallpaperRoute(navController: NavHostController) {
    composable(Screen.AiWallpaper.route) {
        AiWallpaperScreen(
            onBack = { navController.navigateUp() },
        )
    }
}
