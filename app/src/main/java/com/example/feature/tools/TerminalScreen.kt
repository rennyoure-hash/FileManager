package com.example.feature.tools

import android.os.Process
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    workingDir: File = File("/storage/emulated/0"),
    onBack: () -> Unit
) {
    var lines by remember { mutableStateOf(listOf("MT Explorer Terminal", "Type 'help' for commands", "pwd: ${workingDir.absolutePath}", "")) }
    var currentDir by remember { mutableStateOf(workingDir) }
    var command by remember { mutableStateOf("") }
    var isRunning by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var shouldExit by remember { mutableStateOf(false) }

    LaunchedEffect(lines.size) {
        listState.animateScrollToItem(maxOf(0, lines.size - 1))
    }
    LaunchedEffect(shouldExit) {
        if (shouldExit) onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Terminal — ${currentDir.name}") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") } }
            )
        },
        containerColor = Color.Black
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().background(Color.Black).padding(12.dp)
            ) {
                items(lines) { line ->
                    Text(
                        text = line,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = Color.White
                    )
                }
            }
            HorizontalDivider(color = Color.DarkGray)
            Row(Modifier.padding(8.dp).fillMaxWidth()) {
                Text(
                    text = "${currentDir.name} $ ",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = Color.Cyan,
                    modifier = Modifier.padding(end = 8.dp)
                )
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("command...", color = Color.Gray) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = Color.White
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent
                    )
                )
                IconButton(
                    onClick = {
                        val cmd = command.trim()
                        if (cmd.isEmpty() || isRunning) return@IconButton
                        command = ""
                        isRunning = true
                        lines = lines + "${currentDir.name} $ $cmd"
                        scope.launch {
                            if (cmd == "clear") {
                                lines = listOf("MT Explorer Terminal", "Type 'help' for commands", "")
                            } else if (cmd == "exit") {
                                shouldExit = true
                            } else {
                                val output = runCommand(cmd, currentDir) { newDir ->
                                    currentDir = newDir
                                }
                                lines = lines + output
                            }
                            isRunning = false
                        }
                    },
                    enabled = !isRunning
                ) {
                    Icon(
                        if (isRunning) Icons.Filled.Close else Icons.Filled.PlayArrow,
                        "Run",
                        tint = Color.Cyan
                    )
                }
            }
        }
    }
}

private suspend fun runCommand(
    cmd: String,
    dir: File,
    onDirChange: (File) -> Unit
): String = withContext(Dispatchers.IO) {
    val parts = cmd.trim().split("\\s+".toRegex())
    val command = parts[0]
    val args = parts.drop(1)

    return@withContext when (command) {
        "help" -> "Commands: ls, cd <dir>, pwd, cat <file>, echo <text>, clear, mkdir <dir>, rmdir -y <dir>, rm -y <file>, touch <file>, whoami, exit (rm/rmdir need -y for safety)"
        "pwd" -> dir.absolutePath
        "ls", "ll" -> {
            val files = dir.listFiles()?.sortedBy { !it.isDirectory } ?: emptyList()
            if (files.isEmpty()) "(empty)" else files.joinToString("\n") { file ->
                val size = if (file.isDirectory) "<dir>" else "${file.length()}"
                "${if (file.isDirectory) "d" else "-"} $size ${file.name}"
            }
        }
        "cd" -> {
            if (args.isEmpty()) {
                onDirChange(File("/storage/emulated/0"))
                ""
            } else {
                val target = args[0]
                val newDir = when (target) {
                    ".." -> dir.parentFile ?: dir
                    "." -> dir
                    "~" -> File("/storage/emulated/0")
                    "/" -> File("/")
                    else -> File(dir, target)
                }
                if (newDir.exists() && newDir.isDirectory) {
                    onDirChange(newDir)
                    ""
                } else "cd: no such directory: $target"
            }
        }
        "cat" -> {
            if (args.isEmpty()) "cat: missing operand"
            else {
                val f = File(dir, args[0])
                if (!f.exists()) "cat: ${args[0]}: No such file"
                else try {
                    f.readText().take(10000)
                } catch (e: Exception) { "cat: ${e.message}" }
            }
        }
        "echo" -> args.joinToString(" ")
        "mkdir" -> {
            if (args.isEmpty()) "mkdir: missing operand"
            else {
                val f = File(dir, args[0])
                if (f.mkdir().not()) "mkdir: cannot create directory '${args[0]}'" else ""
            }
        }
        "rmdir" -> {
            if (args.isEmpty()) "rmdir: missing operand"
            else if (args[0] != "-y" && args[0] != "--yes") {
                "rmdir: use 'rmdir -y <dir>' to confirm (safety)"
            } else {
                val target = args.getOrNull(1) ?: "missing operand"
                val f = File(dir, target)
                if (!f.exists()) "rmdir: $target: No such file or directory"
                else if (f.delete().not()) "rmdir: failed to remove '$target'" else ""
            }
        }
        "rm" -> {
            if (args.isEmpty()) "rm: missing operand"
            else if (args[0] != "-y" && args[0] != "--yes") {
                "rm: use 'rm -y <file>' to confirm (safety)"
            } else {
                val target = args.getOrNull(1) ?: "missing operand"
                val f = File(dir, target)
                if (!f.exists()) "rm: $target: No such file"
                else if (f.delete().not()) "rm: failed to remove '$target'" else ""
            }
        }
        "touch" -> {
            if (args.isEmpty()) "touch: missing operand"
            else {
                val f = File(dir, args[0])
                if (f.exists()) f.setLastModified(System.currentTimeMillis()) else f.createNewFile()
                ""
            }
        }
        "whoami" -> "u0_a${Process.myUid() % 10000}"
        "exit" -> "exit"
        else -> "sh: $command: command not found (try 'help')"
    }
}
