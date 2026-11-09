package com.example

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.FileProvider
import com.example.core.fs.VEntry
import com.example.core.permission.PermissionManager
import com.example.core.permission.PermissionRequestType
import com.example.feature.auth.ui.SignInScreen
import com.example.feature.browser.FileManagerViewModel
import com.example.feature.browser.ui.BrowserScreen
import com.example.ui.theme.MTExplorerTheme
import java.io.File
import java.util.ArrayList

class MainActivity : ComponentActivity() {

    private val viewModel: FileManagerViewModel by viewModels()

    private val manageAllFilesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.updatePermissionStatus()
    }

    private val legacyPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.updatePermissionStatus()
    }

    private val openDocumentTreeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val info = PermissionManager.getSafDirectoryInfo(this, uri)
            viewModel.onSafFolderGranted(uri)
            Toast.makeText(
                this,
                "SAF: Read & Write granted for \"${info.name}\"",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MTExplorerTheme {
                val currentUser by viewModel.currentUser.collectAsState()
                val authLoading by viewModel.authLoading.collectAsState()
                val authError by viewModel.authError.collectAsState()

                if (currentUser == null) {
                    SignInScreen(
                        isLoading = authLoading,
                        errorMessage = authError,
                        onSignInClicked = { activity ->
                            viewModel.signInWithGoogle(activity)
                        }
                    )
                } else {
                    BrowserScreen(
                        viewModel = viewModel,
                        onRequestPermission = { requestStoragePermission() },
                        onRequestSafFallback = { requestSafFallback() },
                        onOpenFile = { entry -> openFileWithExternalApp(entry) },
                        onShareFile = { entries -> shareFiles(entries) },
                        onOpenTools = {
                            startActivity(Intent(this@MainActivity, com.example.feature.tools.ToolsActivity::class.java).apply {
                                putExtra("tool", "HUB")
                            })
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.updatePermissionStatus()
    }

    private fun requestStoragePermission() {
        when (PermissionManager.getRecommendedRequestType()) {
            PermissionRequestType.MANAGE_ALL_FILES -> {
                manageAllFilesLauncher.launch(PermissionManager.createManageAllFilesIntent(this))
            }
            PermissionRequestType.LEGACY_STORAGE -> {
                legacyPermissionLauncher.launch(PermissionManager.getLegacyPermissions())
            }
            PermissionRequestType.STORAGE_ACCESS_FRAMEWORK -> {
                openDocumentTreeLauncher.launch(null)
            }
        }
    }

    private fun requestSafFallback() {
        openDocumentTreeLauncher.launch(null)
    }

    private fun openFileWithExternalApp(entry: VEntry) {
        val file = File(entry.path.normalized)
        if (!file.exists()) {
            Toast.makeText(this, "File does not exist", Toast.LENGTH_SHORT).show()
            return
        }

        // Internal viewers for supported types
        val ext = file.extension.lowercase()
        val internalIntent = when (ext) {
            "txt", "md", "log", "java", "kt", "py", "json", "xml", "css", "js", "ts", "sh", "c", "cpp", "h", "gradle", "yml", "yaml", "properties" -> {
                Intent(this, com.example.feature.viewer.ViewerActivity::class.java).apply {
                    putExtra("feature", "TEXT")
                    putExtra("path", file.absolutePath)
                }
            }
            "bin", "dat" -> {
                Intent(this, com.example.feature.viewer.ViewerActivity::class.java).apply {
                    putExtra("feature", "HEX")
                    putExtra("path", file.absolutePath)
                }
            }
            "jpg", "jpeg", "png", "webp", "gif", "bmp" -> {
                Intent(this, com.example.feature.viewer.ViewerActivity::class.java).apply {
                    putExtra("feature", "IMAGE")
                    putExtra("path", file.absolutePath)
                    putExtra("files", arrayOf(file.absolutePath))
                    putExtra("index", 0)
                }
            }
            "mp4", "mkv", "avi", "mov", "webm", "mp3", "wav", "ogg", "flac", "m4a" -> {
                Intent(this, com.example.feature.viewer.ViewerActivity::class.java).apply {
                    putExtra("feature", "MEDIA")
                    putExtra("path", file.absolutePath)
                }
            }
            "zip", "tar", "gz", "bz2", "xz", "7z", "rar", "zst", "tgz" -> {
                Intent(this, com.example.feature.archive.ArchiveActivity::class.java).apply {
                    putExtra("path", file.absolutePath)
                }
            }
            "apk", "apks", "xapk" -> {
                // Apk info via Tools
                Intent(this, com.example.feature.tools.ToolsActivity::class.java).apply {
                    putExtra("tool", "APPS")
                }
            }
            else -> null
        }

        if (internalIntent != null) {
            startActivity(internalIntent)
            return
        }

        try {
            val uri: Uri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, entry.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            startActivity(Intent.createChooser(intent, "Open with"))
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot open file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareFiles(entries: List<VEntry>) {
        if (entries.isEmpty()) return
        try {
            val files = entries.filter { !it.isDirectory }.map { File(it.path.normalized) }.filter { it.exists() }
            if (files.isEmpty()) {
                Toast.makeText(this, "No files to share", Toast.LENGTH_SHORT).show()
                return
            }

            if (files.size == 1) {
                val file = files.first()
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = entries.first().mimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "Share file"))
            } else {
                val uris = ArrayList<Uri>()
                for (file in files) {
                    uris.add(FileProvider.getUriForFile(this, "$packageName.fileprovider", file))
                }
                val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "*/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "Share ${files.size} files"))
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot share: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
