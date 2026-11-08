package com.example.core.firebase

import android.content.Context
import com.example.R
import com.google.firebase.Firebase
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class CloudRepository(val db: FirebaseFirestore) {

    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    fun observeBookmarks(userId: String): Flow<List<CloudBookmark>> {
        val collection = db.collection("users").document(userId).collection("bookmarks")
        return collection.snapshots().map { snapshot ->
            snapshot.toObjects(CloudBookmark::class.java)
        }.catch { error ->
            if (error is CancellationException) throw error
            emit(emptyList())
        }
    }

    suspend fun addBookmark(userId: String, name: String, path: String) {
        val docRef = db.collection("users").document(userId).collection("bookmarks").document()
        val payload = mapOf(
            "userId" to userId,
            "name" to name,
            "path" to path,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        docRef.set(payload).await()
    }

    suspend fun deleteBookmark(userId: String, bookmarkId: String) {
        db.collection("users").document(userId).collection("bookmarks").document(bookmarkId).delete().await()
    }

    suspend fun savePreferences(userId: String, email: String, displayName: String, showHidden: Boolean, sortBy: String) {
        val docRef = db.collection("users").document(userId).collection("profile").document("settings")
        val payload = mapOf(
            "userId" to userId,
            "email" to email,
            "displayName" to displayName,
            "showHidden" to showHidden,
            "sortBy" to sortBy,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        docRef.set(payload).await()
    }

    fun observeProfile(userId: String): Flow<UserProfile?> {
        val docRef = db.collection("users").document(userId).collection("profile").document("settings")
        return docRef.snapshots().map { snapshot ->
            if (snapshot.exists()) snapshot.toObject(UserProfile::class.java) else null
        }.catch { error ->
            if (error is CancellationException) throw error
            emit(null)
        }
    }
}
