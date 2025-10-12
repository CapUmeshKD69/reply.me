package me.reply.app.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserSettingsRepository @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("user_settings", Context.MODE_PRIVATE)
    companion object {
        private const val KEY_USER_NAME = "key_user_name"
        private const val KEY_API_KEY = "key_api_key"
    }
    fun saveUserName(name: String) {
        prefs.edit().putString(KEY_USER_NAME, name).apply()
    }

    fun getUserName(): String? {
        return prefs.getString(KEY_USER_NAME, null)
    }

    fun saveApiKey(apiKey: String) {
        prefs.edit().putString(KEY_API_KEY, apiKey).apply()
    }
    fun getApiKey(): String? {
        // Return the key, or null if it's not set or is just an empty string
        return prefs.getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() }
    }

    fun hasApiKey(): Boolean {
        return getApiKey() != null
    }
}

