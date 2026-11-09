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

    suspend fun getRemoteMgmtEnabled(): Boolean {
        return context.settingsDataStore.data.map { it[SettingsKeys.ALLOW_REMOTE_MGMT] }.first() ?: false
    }

    suspend fun setRemoteMgmtEnabled(value: Boolean) {
        context.settingsDataStore.edit { it[SettingsKeys.ALLOW_REMOTE_MGMT] = value }
    }

    suspend fun backupSettings(): Map<String, String> {
        val prefs = context.settingsDataStore.data.first()
        val map = mutableMapOf<String, String>()
        for ((key, value) in prefs) {
            map[key.name] = value.toString()
        }
        return map
    }
}
