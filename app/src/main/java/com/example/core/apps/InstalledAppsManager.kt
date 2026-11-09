package com.example.core.apps

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class InstalledApp(
    val packageName: String,
    val appName: String,
    val versionName: String?,
    val versionCode: Long,
    val sourceDir: String,
    val iconPath: String? = null,
    val isSystemApp: Boolean,
    val firstInstallTime: Long,
    val lastUpdateTime: Long,
    val apkSizeBytes: Long = 0L,
    val hasNativeLibs: Boolean = false
)

class InstalledAppsManager(private val packageManager: PackageManager) {

    suspend fun listInstalledApps(includeSystem: Boolean = false): List<InstalledApp> = withContext(Dispatchers.IO) {
        val packages = packageManager.getInstalledPackages(PackageManager.GET_META_DATA)
        packages.mapNotNull { info ->
            val appInfo = info.applicationInfo ?: return@mapNotNull null
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (!includeSystem && isSystem) return@mapNotNull null
            val label = try {
                appInfo.loadLabel(packageManager).toString()
            } catch (_: Exception) { info.packageName }
            val apkFile = File(appInfo.sourceDir)
            val versionName = try { info.versionName } catch (_: Exception) { null }
            val versionCode = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
            } catch (_: Exception) { 0L }
            val hasLibs = try {
                File(File(appInfo.sourceDir).parent ?: "", "lib").listFiles()?.isNotEmpty() == true
            } catch (_: Exception) { false }

            InstalledApp(
                packageName = info.packageName,
                appName = label,
                versionName = versionName,
                versionCode = versionCode,
                sourceDir = appInfo.sourceDir,
                isSystemApp = isSystem,
                firstInstallTime = info.firstInstallTime,
                lastUpdateTime = info.lastUpdateTime,
                apkSizeBytes = apkFile.length(),
                hasNativeLibs = hasLibs
            )
        }.sortedBy { it.appName.lowercase() }
    }

    suspend fun extractApk(app: InstalledApp, outputDir: File): File? = withContext(Dispatchers.IO) {
        try {
            outputDir.mkdirs()
            val src = File(app.sourceDir)
            val dest = File(outputDir, "${app.appName.replace(Regex("[^a-zA-Z0-9._-]"), "_")}.apk")
            java.io.FileInputStream(src).use { input ->
                java.io.FileOutputStream(dest).use { output ->
                    input.copyTo(output)
                }
            }
            dest
        } catch (_: Exception) { null }
    }

    fun getApkInfo(packageName: String): InstalledApp? {
        return try {
            val info = packageManager.getPackageInfo(packageName, PackageManager.GET_META_DATA)
            val appInfo = info.applicationInfo ?: return null
            InstalledApp(
                packageName = info.packageName,
                appName = appInfo.loadLabel(packageManager).toString(),
                versionName = info.versionName,
                versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong(),
                sourceDir = appInfo.sourceDir,
                isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                firstInstallTime = info.firstInstallTime,
                lastUpdateTime = info.lastUpdateTime,
                apkSizeBytes = File(appInfo.sourceDir).length()
            )
        } catch (_: Exception) { null }
    }
}
