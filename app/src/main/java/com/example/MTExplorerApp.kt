package com.example

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp

class MTExplorerApp : Application() {
    override fun onCreate() {
        super.onCreate()
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
