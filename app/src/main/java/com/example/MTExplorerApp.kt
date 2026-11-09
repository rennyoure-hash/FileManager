package com.example

import android.app.Application
import android.util.Log
import com.example.core.tools.InAppDiagnosticLogger
import com.example.core.tools.SecurityHardeningPolicy
import com.google.firebase.FirebaseApp

class MTExplorerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            InAppDiagnosticLogger.installCrashHandler(this)
            val cleaned = SecurityHardeningPolicy.cleanupResidualTempFilesOnStartup(this)
            InAppDiagnosticLogger.logEvent(this, "INFO", "MTExplorerApp started (cleaned $cleaned residual temp files)")
        } catch (_: Throwable) {
        }
        try {
            com.example.core.root.RootManager.configurePersistentShell()
        } catch (t: Throwable) {
            Log.w("MTExplorerApp", "Root shell builder setup skipped: ${t.message}")
        }
        try {
            if (FirebaseApp.getApps(this).isEmpty()) {
                val app = FirebaseApp.initializeApp(this)
                if (app != null) {
                    Log.d("MTExplorerApp", "FirebaseApp initialized successfully")
                } else {
                    Log.w("MTExplorerApp", "No Firebase config found; cloud features disabled")
                }
            }
        } catch (t: Throwable) {
            Log.e("MTExplorerApp", "FirebaseApp initialization error: ${t.message}", t)
        }
    }
}
