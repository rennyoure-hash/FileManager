package com.example

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp

class MTExplorerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(this)
                Log.d("MTExplorerApp", "FirebaseApp initialized successfully")
            }
        } catch (t: Throwable) {
            Log.e("MTExplorerApp", "FirebaseApp initialization error: ${t.message}", t)
        }
    }
}
