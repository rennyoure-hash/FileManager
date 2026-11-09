package com.example.feature.viewer

import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.MediaController
import android.widget.VideoView
import android.media.MediaPlayer
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaPlayerScreen(
    file: File,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val isVideo = file.extension.lowercase() in listOf("mp4", "mkv", "avi", "mov", "webm", "flv", "3gp", "ts")

    var audioPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var duration by remember { mutableIntStateOf(0) }
    var position by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }

    // Audio player lifecycle
    DisposableEffect(file) {
        if (!isVideo) {
            val player = MediaPlayer().apply {
                try {
                    setDataSource(context, android.net.Uri.fromFile(file))
                    setOnPreparedListener { duration = it.duration; isPlaying = true; it.start() }
                    setOnCompletionListener { isPlaying = false }
                    setOnErrorListener { _, _, _ -> error = "Playback failed"; true }
                    prepareAsync()
                } catch (e: Exception) {
                    error = "Error: ${e.message}"
                }
            }
            audioPlayer = player
            onDispose { player.release() }
        } else {
            onDispose {}
        }
    }

    // Position ticker for audio
    LaunchedEffect(isPlaying) {
        while (true) {
            if (isPlaying && audioPlayer != null) {
                try { position = audioPlayer?.currentPosition ?: 0 } catch (_: Exception) {}
            }
            kotlinx.coroutines.delay(200)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(file.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "Back")
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            if (isVideo) {
                AndroidView(
                    factory = { ctx ->
                        VideoView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            )
                            setVideoPath(file.absolutePath)
                            setMediaController(MediaController(ctx).apply { setAnchorView(this@apply) })
                            requestFocus()
                            start()
                        }
                    },
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
            } else {
                Box(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = "Audio",
                            modifier = Modifier.size(64.dp) // fallback if no play icon
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(file.name, fontSize = 16.sp)
                        Text(
                            formatTime(position) + " / " + formatTime(duration),
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontSize = 13.sp
                        )
                        if (error != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(error!!, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Button(
                        onClick = {
                            if (isPlaying) {
                                audioPlayer?.pause()
                                isPlaying = false
                            } else {
                                audioPlayer?.start()
                                isPlaying = true
                            }
                        }
                    ) {
                        Text(if (isPlaying) "Pause" else "Play")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { audioPlayer?.seekTo(0) }) {
                        Text("Restart")
                    }
                }
            }
        }
    }
}

private fun formatTime(ms: Int): String {
    val totalSec = ms / 1000
    return String.format("%02d:%02d", totalSec / 60, totalSec % 60)
}
