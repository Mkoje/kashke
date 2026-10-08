package com.kashke.app.data.repository

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.kashke.app.BuildConfig
import io.github.jan.supabase.gotrue.SessionManager
import io.github.jan.supabase.gotrue.user.UserSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val sessionJson = Json { encodeDefaults = true }

class EncryptedSessionManager(context: Context) : SessionManager {
    private val preferences = context.applicationContext.getSharedPreferences(
        "supabase_encrypted_session",
        Context.MODE_PRIVATE
    )
    private val legacyPreferences = context.applicationContext.getSharedPreferences(
        "${context.packageName}_preferences",
        Context.MODE_PRIVATE
    )
    private val legacySessionKeys = listOf(
        "sb-${BuildConfig.SUPABASE_URL.removeSuffix("/").replace('/', '-').replace('.', '-')}-session",
        "session"
    ).distinct()

    override suspend fun saveSession(session: UserSession) = withContext(Dispatchers.IO) {
        storeEncryptedSession(session)
    }

    override suspend fun loadSession(): UserSession? = withContext(Dispatchers.IO) {
        val initializationVector = preferences.getString(INITIALIZATION_VECTOR_KEY, null)
            ?: return@withContext migrateLegacySession()
        val encrypted = preferences.getString(ENCRYPTED_SESSION_KEY, null)
            ?: return@withContext migrateLegacySession()
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(GCM_TAG_BITS, Base64.getDecoder().decode(initializationVector))
            )
            val clearText = cipher.doFinal(Base64.getDecoder().decode(encrypted)).toString(StandardCharsets.UTF_8)
            sessionJson.decodeFromString<UserSession>(clearText)
        } catch (exception: Exception) {
            deleteStoredSession()
            null
        }
    }

    override suspend fun deleteSession() = withContext(Dispatchers.IO) {
        deleteStoredSession()
    }

    private fun deleteStoredSession() {
        val secureCleared = preferences.edit()
            .remove(INITIALIZATION_VECTOR_KEY)
            .remove(ENCRYPTED_SESSION_KEY)
            .commit()
        val legacyCleared = clearLegacySessions()
        check(secureCleared && legacyCleared) {
            "Could not clear the stored authentication session."
        }
    }

    private fun clearLegacySessions(): Boolean {
        val editor = legacyPreferences.edit()
        legacySessionKeys.forEach { editor.remove(it) }
        return editor.commit()
    }

    private fun migrateLegacySession(): UserSession? {
        val legacySession = legacySessionKeys.firstNotNullOfOrNull { legacyPreferences.getString(it, null) }
            ?: return null
        val session = try {
            sessionJson.decodeFromString<UserSession>(legacySession)
        } catch (exception: Exception) {
            check(clearLegacySessions()) { "Could not clear the invalid legacy authentication session." }
            null
        }
        if (session == null) return null

        try {
            storeEncryptedSession(session)
        } catch (exception: Exception) {
            check(clearLegacySessions()) { "Could not clear the legacy authentication session." }
            throw exception
        }
        if (!clearLegacySessions()) {
            preferences.edit().remove(INITIALIZATION_VECTOR_KEY).remove(ENCRYPTED_SESSION_KEY).commit()
            error("Could not clear the legacy authentication session.")
        }
        return session
    }

    private fun storeEncryptedSession(session: UserSession) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(sessionJson.encodeToString(session).toByteArray(StandardCharsets.UTF_8))
        val saved = preferences.edit()
            .putString(INITIALIZATION_VECTOR_KEY, Base64.getEncoder().encodeToString(cipher.iv))
            .putString(ENCRYPTED_SESSION_KEY, Base64.getEncoder().encodeToString(encrypted))
            .commit()
        check(saved) { "Could not securely save the authentication session." }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "kashke_supabase_session"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val GCM_TAG_BITS = 128
        const val INITIALIZATION_VECTOR_KEY = "session_iv"
        const val ENCRYPTED_SESSION_KEY = "session_ciphertext"
    }
}