package com.example.feature.viewer

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.ui.theme.MTExplorerTheme
import java.io.File

/**
 * Fase 8b.6: Hosts TextViewer, HexEditor, ImageViewer, MediaPlayer, and SQLite/Text editors
 * with `WindowManager.LayoutParams.FLAG_SECURE` enforced to prevent screenshots/Recent-Apps leaks
 * of sensitive system or vault files.
 */
open class ViewerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Fase 8b.6: Always apply FLAG_SECURE on file/text/hex/SQLite viewer & editor activities
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        val feature = intent.getStringExtra("feature") ?: defaultFeature()
        val path = intent.getStringExtra("path") ?: return
        val file = File(path)
        val extraFiles = intent.getStringArrayExtra("files")?.map { File(it) } ?: listOf(file)
        val initialIndex = intent.getIntExtra("index", 0)

        setContent {
            MTExplorerTheme {
                when (feature) {
                    "TEXT" -> TextViewerScreen(file = file, onBack = { finish() })
                    "HEX", "SQLITE" -> HexEditorScreen(file = file, onBack = { finish() })
                    "IMAGE" -> ImageViewerScreen(files = extraFiles, initialIndex = initialIndex, onBack = { finish() })
                    "MEDIA" -> MediaPlayerScreen(file = file, onBack = { finish() })
                    else -> TextViewerScreen(file = file, onBack = { finish() })
                }
            }
        }
    }

    protected open fun defaultFeature(): String = "TEXT"
}

/**
 * Fase 8b.6: Dedicated TextEditorActivity with `FLAG_SECURE` enforced via [ViewerActivity].
 */
class TextEditorActivity : ViewerActivity() {
    override fun defaultFeature(): String = "TEXT"
}

/**
 * Fase 8b.6: Dedicated SqliteViewerActivity with `FLAG_SECURE` enforced via [ViewerActivity].
 */
class SqliteViewerActivity : ViewerActivity() {
    override fun defaultFeature(): String = "SQLITE"
}
