package com.example.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "mt_explorer_settings")

object SettingsKeys {
    val THEME = stringPreferencesKey("theme") // system, light, dark, amoled
    val LANGUAGE = stringPreferencesKey("language") // system, en, id
    val SHOW_HIDDEN = booleanPreferencesKey("show_hidden")
    val LIST_DENSITY = stringPreferencesKey("list_density") // compact, comfortable, grid
    val FONT_SIZE = intPreferencesKey("font_size")
    val SORT_BY = stringPreferencesKey("sort_by")
    val SORT_ORDER = stringPreferencesKey("sort_order")
    val ENABLE_TRASH = booleanPreferencesKey("enable_trash")
    val ALLOW_REMOTE_MGMT = booleanPreferencesKey("allow_remote_mgmt")
    val REMOTE_PASSWORD = stringPreferencesKey("remote_password")
    val INTRO_SHOWN = booleanPreferencesKey("intro_shown")
    val BATCH_RENAME_HISTORY = stringPreferencesKey("batch_rename_history")
    val ROOT_EXPLORER = booleanPreferencesKey("root_explorer")
    val MOUNT_SYSTEM_RW = booleanPreferencesKey("mount_system_rw")
}

enum class AppTheme(val key: String) {
    SYSTEM("system"), LIGHT("light"), DARK("dark"), AMOLED("amoled")
}

enum class ListDensity(val key: String) {
    COMPACT("compact"), COMFORTABLE("comfortable"), GRID("grid")
}

class SettingsManager(private val context: Context) {

    suspend fun getTheme(): AppTheme {
        val value = context.settingsDataStore.data.map { it[SettingsKeys.THEME] }.first()
        return AppTheme.entries.firstOrNull { it.key == value } ?: AppTheme.SYSTEM
    }

    suspend fun setTheme(theme: AppTheme) {
        context.settingsDataStore.edit { it[SettingsKeys.THEME] = theme.key }
    }

    suspend fun getLanguage(): String {
        return context.settingsDataStore.data.map { it[SettingsKeys.LANGUAGE] }.first() ?: "system"
    }

    suspend fun setLanguage(lang: String) {
        context.settingsDataStore.edit { it[SettingsKeys.LANGUAGE] = lang }
    }

    suspend fun getShowHidden(): Boolean {
        return context.settingsDataStore.data.map { it[SettingsKeys.SHOW_HIDDEN] }.first() ?: false
    }

    suspend fun setShowHidden(value: Boolean) {
        context.settingsDataStore.edit { it[SettingsKeys.SHOW_HIDDEN] = value }
    }

    suspend fun getDensity(): ListDensity {
        val value = context.settingsDataStore.data.map { it[SettingsKeys.LIST_DENSITY] }.first()
        return ListDensity.entries.firstOrNull { it.key == value } ?: ListDensity.COMFORTABLE
    }

    suspend fun setDensity(density: ListDensity) {
        context.settingsDataStore.edit { it[SettingsKeys.LIST_DENSITY] = density.key }
    }

    suspend fun getEnableTrash(): Boolean {
        return context.settingsDataStore.data.map { it[SettingsKeys.ENABLE_TRASH] }.first() ?: true
    }

    suspend fun setEnableTrash(value: Boolean) {
        context.settingsDataStore.edit { it[SettingsKeys.ENABLE_TRASH] = value }
    }

    suspend fun getRootExplorerEnabled(): Boolean {
        val dsVal = context.settingsDataStore.data.map { it[SettingsKeys.ROOT_EXPLORER] }.first()
        val enabled = dsVal ?: com.example.core.root.RootManager.isRootExplorerEnabled(context)
        com.example.core.root.RootManager.setRootExplorerEnabled(context, enabled)
        return enabled
    }

    suspend fun setRootExplorerEnabled(value: Boolean) {
        com.example.core.root.RootManager.setRootExplorerEnabled(context, value)
        context.settingsDataStore.edit { it[SettingsKeys.ROOT_EXPLORER] = value }
    }

    suspend fun getMountSystemRw(): Boolean {
        val dsVal = context.settingsDataStore.data.map { it[SettingsKeys.MOUNT_SYSTEM_RW] }.first()
        return dsVal ?: com.example.core.root.RootManager.isMountSystemRw(context)
    }

    suspend fun setMountSystemRw(value: Boolean) {
        com.example.core.root.RootManager.setMountSystemRw(context, value)
        context.settingsDataStore.edit { it[SettingsKeys.MOUNT_SYSTEM_RW] = value }
    }

    suspend fun getRemoteMgmtEnabled(): Boolean {
        return context.settingsDataStore.data.map { it[SettingsKeys.ALLOW_REMOTE_MGMT] }.first() ?: false
    }

    suspend fun setRemoteMgmtEnabled(value: Boolean) {
        context.settingsDataStore.edit { it[SettingsKeys.ALLOW_REMOTE_MGMT] = value }
    }

    suspend fun backupSettings(): Map<String, String> {
        val prefs = context.settingsDataStore.data.first()
        val map = mutableMapOf<String, String>()
        for ((key, value) in prefs.asMap()) {
            @Suppress("UNCHECKED_CAST")
            map[(key as Preferences.Key<*>).name] = value.toString()
        }
        return map
    }

    suspend fun saveBatchRenameHistory(entry: String) {
        context.settingsDataStore.edit {
            val current = it[SettingsKeys.BATCH_RENAME_HISTORY] ?: ""
            val lines = current.split("\n").filter { it.isNotBlank() }.takeLast(20)
            it[SettingsKeys.BATCH_RENAME_HISTORY] = (lines + entry).joinToString("\n")
        }
    }

    suspend fun getBatchRenameHistory(): List<String> {
        val value = context.settingsDataStore.data.map { it[SettingsKeys.BATCH_RENAME_HISTORY] }.first()
        return value?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
    }
}
