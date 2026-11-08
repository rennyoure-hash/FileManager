package com.example.core.firebase

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@IgnoreExtraProperties
data class CloudBookmark(
    @DocumentId val id: String = "",
    val userId: String = "",
    val name: String = "",
    val path: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class UserProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val email: String = "",
    val displayName: String = "",
    val showHidden: Boolean = false,
    val sortBy: String = "NAME",
    @ServerTimestamp val updatedAt: Timestamp? = null
)
