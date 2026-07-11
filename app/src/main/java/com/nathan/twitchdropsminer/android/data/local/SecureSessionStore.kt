package com.nathan.twitchdropsminer.android.data.local

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import androidx.core.content.edit
import com.nathan.twitchdropsminer.android.data.model.StoredTwitchSession
import java.time.Instant

class SecureSessionStore(context: Context) {
    private val appContext = context.applicationContext

    private val preferences by lazy {
        val keyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)

        EncryptedSharedPreferences.create(
            "secure_session",
            keyAlias,
            appContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun saveBackendSessionLabel(label: String) {
        preferences.edit { putString("backend_session_label", label) }
    }

    fun backendSessionLabel(): String? =
        preferences.getString("backend_session_label", null)

    fun saveTwitchSession(session: StoredTwitchSession) {
        preferences.edit {
            putString("twitch_access_token", session.accessToken)
            putString("twitch_user_id", session.userId)
            putString("twitch_device_id", session.deviceId)
            putString("twitch_saved_at", session.savedAt.toString())
        }
    }

    fun twitchSession(): StoredTwitchSession? {
        val token = preferences.getString("twitch_access_token", null) ?: return null
        val userId = preferences.getString("twitch_user_id", null) ?: return null
        val deviceId = preferences.getString("twitch_device_id", null) ?: return null
        val savedAt = preferences.getString("twitch_saved_at", null)
            ?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: Instant.EPOCH
        return StoredTwitchSession(
            accessToken = token,
            userId = userId,
            deviceId = deviceId,
            savedAt = savedAt,
        )
    }

    fun clear() {
        preferences.edit { clear() }
    }
}
