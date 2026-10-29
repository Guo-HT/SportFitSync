package com.sportfit.sync.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sportfit.sync.cache.CachePolicy
import com.sportfit.sync.domain.Platform
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "sportfit-settings",
)

/** User-tunable behaviour, persisted with DataStore. */
data class AppSettings(
    val cachePolicy: CachePolicy = CachePolicy(),
    val defaultSource: Platform = Platform.GARMIN,
    val defaultTarget: Platform = Platform.COROS,
    /** Upper bound on activities pulled per run. */
    val listLimit: Int = 200,
    /** Background scheduling is opt-in and off by default. */
    val backgroundSyncEnabled: Boolean = false,
    val backgroundIntervalHours: Int = 6,
    /** Warn before starting a Coros sync, because it signs the phone app out. */
    val warnCorosSingleSession: Boolean = true,
)

class SettingsStore(private val context: Context) {

    private object Keys {
        val deleteTemp = booleanPreferencesKey("cache.delete_temp_after_success")
        val activityDays = intPreferencesKey("cache.activity_retention_days")
        val logDays = intPreferencesKey("cache.log_retention_days")
        val jobLimit = intPreferencesKey("cache.job_history_limit")
        val defaultSource = stringPreferencesKey("sync.default_source")
        val defaultTarget = stringPreferencesKey("sync.default_target")
        val listLimit = intPreferencesKey("sync.list_limit")
        val backgroundEnabled = booleanPreferencesKey("sync.background_enabled")
        val backgroundHours = intPreferencesKey("sync.background_interval_hours")
        val warnCoros = booleanPreferencesKey("sync.warn_coros_single_session")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        AppSettings(
            cachePolicy = CachePolicy(
                deleteTempAfterSuccess = prefs[Keys.deleteTemp] ?: true,
                activityRetentionDays = prefs[Keys.activityDays] ?: 7,
                logRetentionDays = prefs[Keys.logDays] ?: 30,
                jobHistoryLimit = prefs[Keys.jobLimit] ?: 100,
            ),
            defaultSource = Platform.fromKey(prefs[Keys.defaultSource]) ?: Platform.GARMIN,
            defaultTarget = Platform.fromKey(prefs[Keys.defaultTarget]) ?: Platform.COROS,
            listLimit = prefs[Keys.listLimit] ?: 200,
            backgroundSyncEnabled = prefs[Keys.backgroundEnabled] ?: false,
            backgroundIntervalHours = prefs[Keys.backgroundHours] ?: 6,
            warnCorosSingleSession = prefs[Keys.warnCoros] ?: true,
        )
    }

    /** Snapshot for synchronous callers such as the cache policy provider. */
    suspend fun current(): AppSettings = settings.first()

    suspend fun setCachePolicy(policy: CachePolicy) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.deleteTemp] = policy.deleteTempAfterSuccess
            prefs[Keys.activityDays] = policy.activityRetentionDays
            prefs[Keys.logDays] = policy.logRetentionDays
            prefs[Keys.jobLimit] = policy.jobHistoryLimit
        }
    }

    suspend fun setDefaults(source: Platform, target: Platform, listLimit: Int) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.defaultSource] = source.key
            prefs[Keys.defaultTarget] = target.key
            prefs[Keys.listLimit] = listLimit
        }
    }

    suspend fun setBackgroundSync(enabled: Boolean, intervalHours: Int) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.backgroundEnabled] = enabled
            prefs[Keys.backgroundHours] = intervalHours
        }
    }

    suspend fun setWarnCorosSingleSession(enabled: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[Keys.warnCoros] = enabled }
    }
}
