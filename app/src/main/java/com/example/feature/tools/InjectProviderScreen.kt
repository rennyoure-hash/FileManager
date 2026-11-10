package com.example.feature.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.inject.ApkInjector
import com.example.core.inject.InjectorSigner
import com.example.core.permission.PermissionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Inject Documents Provider"
 *
 * Patches a third-party APK so it hosts our DocumentsProvider. Once that app is
 * installed, the provider lives in the target app's own process and therefore has
 * access to that app's private data directory (/data/data/<pkg>) - something no
 * other app can reach. We then pick that folder through SAF and browse/edit it
 * like any other storage location.
 */
@Composable
fun InjectProviderScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var injectedApps by remember { mutableStateOf<List<InjectedProvider>>(emptyList()) }

    fun refreshApps() {
        injectedApps = findInjectedProviders(context)
    }

    LaunchedEffect(Unit) { refreshApps() }

    val apkPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            status = "Injecting provider..."
            val result = withContext(Dispatchers.IO) { runInject(context, uri) }
            busy = false
            status = result
        }
    }

    val treePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { PermissionManager.persistDocumentTreeUri(context, uri) }
            }
            status = "Folder granted. It is now listed under 'SAF:' in the storage roots sheet."
            refreshApps()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            "Inject Documents Provider",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Patches an APK so it hosts our DocumentsProvider. After installing the patched " +
                "app you can open its private data directory (/data/data/<pkg>) from here - " +
                "no root required. The patched app is signed with our own key, so the original " +
                "app has to be uninstalled first (its data goes away with it).",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(status, fontSize = 12.sp)
        } else if (status.isNotEmpty()) {
            Text(status, fontSize = 12.sp)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    apkPicker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
                },
                enabled = !busy
            ) { Text("Pick APK to inject") }

            OutlinedButton(
                onClick = {
                    scope.launch {
                        status = withContext(Dispatchers.IO) { openInjectorOutput(context) }
                    }
                },
                enabled = !busy
            ) { Text("Open last output") }
        }

        Text(
            "Apps hosting the injected provider",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )

        if (injectedApps.isEmpty()) {
            Text("Belum ada. Inject provider ke sebuah APK lalu install hasilnya.", fontSize = 12.sp)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(injectedApps) { item ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        ListItem(
                            headlineContent = { Text(item.packageName, fontWeight = FontWeight.SemiBold) },
                            supportingContent = { Text(item.authority, fontSize = 11.sp) },
                            trailingContent = {
                                Button(
                                    onClick = {
                                        val initial = DocumentsContract.buildDocumentUri(
                                            item.authority,
                                            "/"
                                        )
                                        treePicker.launch(initial)
                                    }
                                ) { Text("Open data") }
                            }
                        )
                    }
                }
            }
        }
    }
}

data class InjectedProvider(
    val packageName: String,
    val authority: String,
    val appLabel: String
)

/** Finds installed apps that expose our injected provider. */
private fun findInjectedProviders(context: Context): List<InjectedProvider> {
    val pm = context.packageManager
    val result = mutableListOf<InjectedProvider>()
    runCatching {
        pm.getInstalledPackages(0).forEach { info ->
            val providers = runCatching { info.providers }.getOrNull() ?: return@forEach
            providers.forEach { provider ->
                val authority = provider.authority ?: return@forEach
                if (!authority.contains(ApkInjectorAuthority.MARKER)) return@forEach
                val label = runCatching {
                    pm.getApplicationLabel(info.applicationInfo).toString()
                }.getOrDefault(info.packageName)
                result.add(InjectedProvider(info.packageName, authority, label))
            }
        }
    }
    return result
}

/** Authority marker so we can recognise apps we injected into. */
object ApkInjectorAuthority {
    const val MARKER = ".mtprovider.docs"
    const val PROVIDER_CLASS = "com.example.mtprovider.MtDocumentsProvider"

    fun forPackage(pkg: String): String = "$pkg$MARKER"
}

private fun runInject(context: Context, uri: Uri): String {
    return try {
        val source = copyToCache(context, uri) ?: return "Gagal membaca APK"
        val dex = context.assets.open("provider.dex").use { it.readBytes() }

        val targetPkg = readPackageName(context, source)
            ?: return "Tidak bisa membaca package name dari APK"

        val authority = ApkInjectorAuthority.forPackage(targetPkg)
        val outDir = File(
            context.getExternalFilesDir(null) ?: context.filesDir,
            "injected"
        ).apply { mkdirs() }
        val patched = File(outDir, "$targetPkg-patched.apk")
        val unsigned = File(context.cacheDir, "$targetPkg-unsigned.apk")

        ApkInjector.inject(
            inputApk = source,
            outputApk = unsigned,
            providerDex = dex,
            providerClass = ApkInjectorAuthority.PROVIDER_CLASS,
            authority = authority
        )

        val key = InjectorSigner.loadKey(context)
        InjectorSigner.sign(unsigned, patched, key, minSdkVersion = 24)
        unsigned.delete()

        "Selesai: ${patched.absolutePath}\n" +
            "Install APK ini ( uninstall app asli dulu ), lalu buka 'Apps hosting the injected provider' di bawah."
    } catch (t: Throwable) {
        "Gagal: ${t.message ?: t.javaClass.simpleName}"
    }
}

private fun copyToCache(context: Context, uri: Uri): File? {
    return try {
        val target = File(context.cacheDir, "input-${System.currentTimeMillis()}.apk")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { out -> input.copyTo(out) }
        }
        target
    } catch (_: Throwable) {
        null
    }
}

/** Reads the package name straight out of the binary manifest. */
private fun readPackageName(context: Context, apk: File): String? {
    return try {
        java.util.zip.ZipFile(apk).use { zf ->
            val entry = zf.getEntry("AndroidManifest.xml") ?: return null
            val bytes = zf.getInputStream(entry).use { it.readBytes() }
            com.example.core.inject.AxmlProviderInjector.readPackageName(bytes)
        }
    } catch (_: Throwable) {
        null
    }
}

private fun openInjectorOutput(context: Context): String {
    val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "injected")
    val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
    return if (files.isEmpty()) {
        "Belum ada APK hasil inject."
    } else {
        "Output terakhir:\n${files.first().absolutePath}\n\n" +
            "Buka folder app Android/data/<package>/files/injected lewat file manager."
    }
}
