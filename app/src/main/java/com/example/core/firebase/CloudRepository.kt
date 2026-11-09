package com.example.core.firebase

import android.content.Context
import android.util.Log
import com.example.R
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class CloudRepository(val db: FirebaseFirestore?) {

    constructor(context: Context) : this(
        try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(context)
            }
            val dbId = try {
                context.applicationContext.getString(R.string.firestore_database_id)
            } catch (_: Throwable) {
                null
            }
            if (!dbId.isNullOrBlank()) {
                FirebaseFirestore.getInstance(FirebaseApp.getInstance(), dbId)
            } else {
                FirebaseFirestore.getInstance()
            }
        } catch (t: Throwable) {
            Log.e("CloudRepository", "FirebaseFirestore initialization failed: ${t.message}", t)
            null
        }
    )

    companion object {
        /** Returns null when Firebase is unavailable (e.g. missing google-services.json). */
        fun createOrNull(context: Context): CloudRepository? = runCatching {
            CloudRepository(context)
        }.onFailure {
            android.util.Log.w("CloudRepository", "Firestore unavailable; cloud sync disabled", it)
        }.getOrNull()
    }

    fun observeBookmarks(userId: String): Flow<List<CloudBookmark>> {
        val database = db ?: return flowOf(emptyList())
        val collection = database.collection("users").document(userId).collection("bookmarks")
        return collection.snapshots().map { snapshot ->
            snapshot.toObjects(CloudBookmark::class.java)
        }.catch { error ->
            if (error is CancellationException) throw error
            emit(emptyList())
        }
    }

    suspend fun addBookmark(userId: String, name: String, path: String) {
        val database = db ?: return
        try {
            val docRef = database.collection("users").document(userId).collection("bookmarks").document()
            val payload = mapOf(
                "userId" to userId,
                "name" to name,
                "path" to path,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
        } catch (t: Throwable) {
            Log.w("CloudRepository", "Failed to add bookmark: ${t.message}")
        }
    }

    suspend fun deleteBookmark(userId: String, bookmarkId: String) {
        val database = db ?: return
        try {
            database.collection("users").document(userId).collection("bookmarks").document(bookmarkId).delete().await()
        } catch (t: Throwable) {
            Log.w("CloudRepository", "Failed to delete bookmark: ${t.message}")
        }
    }

    suspend fun savePreferences(userId: String, email: String, displayName: String, showHidden: Boolean, sortBy: String) {
        val database = db ?: return
        try {
            val docRef = database.collection("users").document(userId).collection("profile").document("settings")
            val payload = mapOf(
                "userId" to userId,
                "email" to email,
                "displayName" to displayName,
                "showHidden" to showHidden,
                "sortBy" to sortBy,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
        } catch (t: Throwable) {
            Log.w("CloudRepository", "Failed to save preferences: ${t.message}")
        }
    }

    fun observeProfile(userId: String): Flow<UserProfile?> {
        val database = db ?: return flowOf(null)
        val docRef = database.collection("users").document(userId).collection("profile").document("settings")
        return docRef.snapshots().map { snapshot ->
            if (snapshot.exists()) snapshot.toObject(UserProfile::class.java) else null
        }.catch { error ->
            if (error is CancellationException) throw error
            emit(null)
        }
    }
}
