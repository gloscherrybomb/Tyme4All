package com.tymewear.run.android

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.tymewear.run.domain.tymewear.CredentialStore
import com.tymewear.run.domain.tymewear.InMemoryCredentialStore
import java.security.KeyStore
import timber.log.Timber

/**
 * The Tymewear sign-in, encrypted on this phone with a key held in the Android Keystore.
 * Open it with [open], which survives a broken file or key.
 */
class EncryptedCredentialStore private constructor(context: Context) : CredentialStore {
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        FILE,
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override val email: String? get() = prefs.getString(KEY_EMAIL, null)
    override val password: String? get() = prefs.getString(KEY_PASSWORD, null)
    override var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) { prefs.edit().apply { if (value == null) remove(KEY_TOKEN) else putString(KEY_TOKEN, value) }.apply() }

    override fun save(email: String, password: String) {
        prefs.edit().putString(KEY_EMAIL, email).putString(KEY_PASSWORD, password).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_EMAIL).remove(KEY_PASSWORD).remove(KEY_TOKEN).apply()
    }

    companion object {
        private const val FILE = "tymewear_credentials"
        private const val MASTER_KEY_ALIAS = "_androidx_security_master_key_"
        private const val KEY_EMAIL = "email"
        private const val KEY_PASSWORD = "password"
        private const val KEY_TOKEN = "token"

        /**
         * The encrypted store. If the file or the key cannot be opened (a key lost after a restore,
         * a corrupt file), both are deleted and it is opened once more; if that fails too, an
         * in-memory store is used and the user signs in again.
         */
        fun open(context: Context): CredentialStore {
            val app = context.applicationContext
            try {
                return EncryptedCredentialStore(app).also { it.email }
            } catch (e: Exception) {
                Timber.w("Tymewear credential store unreadable, resetting: ${e.javaClass.simpleName}")
            }
            reset(app)
            return try {
                EncryptedCredentialStore(app).also { it.email }
            } catch (e: Exception) {
                Timber.w("Tymewear credential store unavailable, keeping the sign-in in memory: ${e.javaClass.simpleName}")
                InMemoryCredentialStore()
            }
        }

        private fun reset(context: Context) {
            try { context.deleteSharedPreferences(FILE) } catch (e: Exception) { Timber.w("Could not delete the credential file: ${e.javaClass.simpleName}") }
            try {
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(MASTER_KEY_ALIAS)
            } catch (e: Exception) {
                Timber.w("Could not delete the credential key: ${e.javaClass.simpleName}")
            }
        }
    }
}
