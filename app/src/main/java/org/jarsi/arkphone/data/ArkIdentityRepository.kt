package org.jarsi.arkphone.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * This device's ARK account. The device token is shown by the worker exactly
 * once at registration and can never be recovered, re-issued or rotated
 * (worker/docs/protocol.md §2) — it is persisted before any UI is shown.
 */
data class ArkIdentity(
    val code: String,
    val nickname: String,
    val deviceToken: String,
)

interface ArkIdentityRepository {
    /** Null until this device has registered. */
    val identity: Flow<ArkIdentity?>

    suspend fun save(identity: ArkIdentity)

    /** The FCM registration token the worker has already been told about. */
    val syncedFcmToken: Flow<String?>

    suspend fun setSyncedFcmToken(token: String)
}

@Singleton
class DataStoreArkIdentityRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : ArkIdentityRepository {

    private object Keys {
        val CODE = stringPreferencesKey("ark_code")
        val NICKNAME = stringPreferencesKey("ark_nickname")
        val DEVICE_TOKEN = stringPreferencesKey("ark_device_token")
        val SYNCED_FCM_TOKEN = stringPreferencesKey("ark_synced_fcm_token")
    }

    private val preferences: Flow<Preferences> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }

    override val identity: Flow<ArkIdentity?> = preferences.map { stored ->
        val code = stored[Keys.CODE]
        val deviceToken = stored[Keys.DEVICE_TOKEN]
        if (code.isNullOrBlank() || deviceToken.isNullOrBlank()) {
            null
        } else {
            ArkIdentity(code, stored[Keys.NICKNAME].orEmpty(), deviceToken)
        }
    }

    override suspend fun save(identity: ArkIdentity) {
        dataStore.edit {
            it[Keys.CODE] = identity.code
            it[Keys.NICKNAME] = identity.nickname
            it[Keys.DEVICE_TOKEN] = identity.deviceToken
        }
    }

    override val syncedFcmToken: Flow<String?> =
        preferences.map { it[Keys.SYNCED_FCM_TOKEN]?.takeIf(String::isNotBlank) }

    override suspend fun setSyncedFcmToken(token: String) {
        dataStore.edit { it[Keys.SYNCED_FCM_TOKEN] = token }
    }
}
