package com.example.feature.viewer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import com.example.ui.theme.MTExplorerTheme
import java.io.File

/**
 * Hosts TextViewer, HexEditor, ImageViewer, MediaPlayer, ArchiveViewer
 * via intent extras: "feature" = TEXT | HEX | IMAGE | MEDIA | ARCHIVE, "path" = file path
 */
class ViewerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val feature = intent.getStringExtra("feature") ?: "TEXT"
        val path = intent.getStringExtra("path") ?: return
        val file = File(path)
        val extraFiles = intent.getStringArrayExtra("files")?.map { File(it) } ?: listOf(file)
        val initialIndex = intent.getIntExtra("index", 0)

        setContent {
            MTExplorerTheme {
                when (feature) {
                    "TEXT" -> TextViewerScreen(file = file, onBack = { finish() })
                    "HEX" -> HexEditorScreen(file = file, onBack = { finish() })
                    "IMAGE" -> ImageViewerScreen(files = extraFiles, initialIndex = initialIndex, onBack = { finish() })
                    "MEDIA" -> MediaPlayerScreen(file = file, onBack = { finish() })
                    else -> TextViewerScreen(file = file, onBack = { finish() })
                }
            }
        }
    }
}
