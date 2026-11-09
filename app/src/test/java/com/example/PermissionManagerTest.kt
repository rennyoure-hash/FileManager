package com.example

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.core.permission.PermissionManager
import com.example.core.permission.PermissionRequestType
import com.example.core.permission.StorageAccessTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PermissionManagerTest {

    @Test
    fun testLegacyPermissionsArray() {
        val permissions = PermissionManager.getLegacyPermissions()
        assertTrue(permissions.isNotEmpty())
        assertTrue(permissions.contains(android.Manifest.permission.READ_EXTERNAL_STORAGE))
    }

    @Test
    fun testCreateOpenDocumentTreeIntent() {
        val intent = PermissionManager.createOpenDocumentTreeIntent()
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, intent.action)

        val flags = intent.flags
        assertTrue((flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
        assertTrue((flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0)
        assertTrue((flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0)
    }

    @Test
    fun testCreateManageAllFilesIntent() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = PermissionManager.createManageAllFilesIntent(context)
        assertNotNull(intent.action)
    }

    @Test
    fun testRecommendedRequestType() {
        val recommended = PermissionManager.getRecommendedRequestType()
        assertTrue(
            recommended == PermissionRequestType.MANAGE_ALL_FILES ||
            recommended == PermissionRequestType.LEGACY_STORAGE
        )
    }

    @Test
    fun testStorageAccessTierEvaluation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tier = PermissionManager.getStorageAccessTier(context)
        assertNotNull(tier)
    }

    @Test
    fun testSafUriEncodingAndDecoding() {
        val originalUri = android.net.Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADownload")
        val encoded = PermissionManager.encodeSafUri(originalUri)
        val decoded = PermissionManager.decodeSafUri(encoded)
        assertEquals(originalUri, decoded)
    }

    @Test
    fun testSafDirectoryInfo() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val testUri = android.net.Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADownload")
        val info = PermissionManager.getSafDirectoryInfo(context, testUri)
        assertNotNull(info)
        assertNotNull(info.name)
        assertTrue(info.name.isNotEmpty())
        assertEquals(testUri, info.uri)
    }

    @Test
    fun testSafPersistAndRevoke() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val testUri = android.net.Uri.parse("content://com.android.externalstorage.documents/tree/primary%3APictures")

        PermissionManager.persistDocumentTreeUri(context, testUri)
        val uris = PermissionManager.getPersistedSafUris(context)
        assertTrue(uris.contains(testUri))
        assertTrue(PermissionManager.hasSafAccess(context))

        PermissionManager.revokeSafAccess(context, testUri)
        val afterRevoke = PermissionManager.getPersistedSafUris(context)
        assertTrue(!afterRevoke.contains(testUri))
    }
}
