package com.example.inventory.mobile.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

interface TokenStore {

    fun accessToken(): String?

    fun refreshToken(): String?

    /** True while the stored session is a temporary-password session. Survives app restarts. */
    fun mustChangePassword(): Boolean = false

    fun save(accessToken: String, refreshToken: String, mustChangePassword: Boolean = false)

    fun clear()
}

class EncryptedTokenStore(context: Context) : TokenStore {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            "auth_tokens",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun accessToken(): String? = prefs.getString(KEY_ACCESS, null)

    override fun refreshToken(): String? = prefs.getString(KEY_REFRESH, null)

    override fun mustChangePassword(): Boolean = prefs.getBoolean(KEY_MUST_CHANGE, false)

    override fun save(accessToken: String, refreshToken: String, mustChangePassword: Boolean) {
        prefs.edit()
            .putString(KEY_ACCESS, accessToken)
            .putString(KEY_REFRESH, refreshToken)
            .putBoolean(KEY_MUST_CHANGE, mustChangePassword)
            .commit()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_MUST_CHANGE = "must_change_password"
    }
}

class InMemoryTokenStore(
    private var access: String? = null,
    private var refresh: String? = null,
    private var mustChange: Boolean = false,
) : TokenStore {

    override fun accessToken(): String? = access

    override fun refreshToken(): String? = refresh

    override fun mustChangePassword(): Boolean = mustChange

    override fun save(accessToken: String, refreshToken: String, mustChangePassword: Boolean) {
        access = accessToken
        refresh = refreshToken
        mustChange = mustChangePassword
    }

    override fun clear() {
        access = null
        refresh = null
        mustChange = false
    }
}