package com.propaint.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.propaint.app.engine.CanvasProjectManager
import com.propaint.app.ui.screens.PaintScreen
import com.propaint.app.ui.theme.ProPaintTheme
import com.propaint.app.viewmodel.PaintViewModel

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PROJECT_ID = "project_id"
    }

    private val viewModel: PaintViewModel by viewModels()
    private var projectId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel.setAppContext(applicationContext)
        projectId = intent.getStringExtra(EXTRA_PROJECT_ID)

        // プロジェクトからキャンバスを読み込み
        projectId?.let { pid ->
            val doc = CanvasProjectManager.openProject(applicationContext, pid)
            if (doc != null) {
                viewModel.loadDocument(doc)
            }
        }

        // イマーシブモード
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContent {
            ProPaintTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    PaintScreen(viewModel = viewModel)
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // プロジェクトに保存 (プロジェクト経由で開いた場合)
        val pid = projectId
        if (pid != null) {
            viewModel.saveToProject(applicationContext, pid)
        }
        viewModel.triggerAutoSave()
    }
}
