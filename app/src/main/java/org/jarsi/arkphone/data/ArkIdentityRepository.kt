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

    /** The FCM registration token the worker already holds for the current identity. */
    val syncedFcmToken: Flow<String?>

    /**
     * Records that the worker holds [token] for [identity]. False, and nothing
     * written, when the stored identity is no longer [identity] — the marker
     * must never claim a POST made under another account.
     */
    suspend fun markFcmTokenSynced(identity: ArkIdentity, token: String): Boolean
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
        val SYNCED_FCM_ACCOUNT = stringPreferencesKey("ark_synced_fcm_account")
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

    // The marker is bound to the account it was posted for: left behind by
    // another identity it would stop this one from ever posting its own.
    override val syncedFcmToken: Flow<String?> = preferences.map { stored ->
        stored[Keys.SYNCED_FCM_TOKEN]
            ?.takeIf { it.isNotBlank() && stored[Keys.SYNCED_FCM_ACCOUNT] == stored[Keys.CODE] }
    }

    override suspend fun markFcmTokenSynced(identity: ArkIdentity, token: String): Boolean {
        var written = false
        // One edit: the identity check and the write cannot be separated by
        // a restore's edit, which once left the new identity marked as
        // synced for a token the worker held for the old one.
        dataStore.edit {
            if (it[Keys.CODE] != identity.code || it[Keys.DEVICE_TOKEN] != identity.deviceToken) return@edit
            it[Keys.SYNCED_FCM_TOKEN] = token
            it[Keys.SYNCED_FCM_ACCOUNT] = identity.code
            written = true
        }
        return written
    }
}
