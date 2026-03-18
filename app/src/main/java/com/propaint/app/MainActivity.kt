package com.propaint.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.propaint.app.ui.screens.GalleryScreen
import com.propaint.app.ui.screens.PaintScreen
import com.propaint.app.ui.theme.ProPaintTheme
import com.propaint.app.viewmodel.PaintViewModel

sealed class AppScreen {
    object Gallery : AppScreen()
    object Paint   : AppScreen()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        // Keep screen on while painting
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Immersive mode - hide system bars for maximum canvas space
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // Hardware acceleration
        window.setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        )

        setContent {
            ProPaintTheme {
                val vm: PaintViewModel = viewModel()
                var screen by remember { mutableStateOf<AppScreen>(AppScreen.Gallery) }

                when (screen) {
                    AppScreen.Gallery -> GalleryScreen(
                        vm     = vm,
                        onOpen = { screen = AppScreen.Paint },
                    )
                    AppScreen.Paint -> PaintScreen(
                        vm        = vm,
                        onGallery = { screen = AppScreen.Gallery },
                    )
                }
            }
        }
    }
}
