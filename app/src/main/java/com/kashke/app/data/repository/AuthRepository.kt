package com.kashke.app.data.repository

import kotlinx.coroutines.flow.StateFlow

data class AuthenticatedIdentity(
    val id: String,
    val email: String?,
    val phone: String? = null
)

interface AuthRepository {
    val currentUser: StateFlow<AuthenticatedIdentity?>
    val isConfigured: Boolean

    suspend fun restoreSession(): AuthenticatedIdentity?
    suspend fun register(identifier: String, password: String): AuthenticatedIdentity?
    suspend fun isUsernameAvailable(username: String): Boolean
    suspend fun login(email: String, password: String): AuthenticatedIdentity
    suspend fun resetPassword(email: String)
    suspend fun changePassword(currentPassword: String, newPassword: String) {
        error("Password changes are unavailable.")
    }
    suspend fun logout()
}

interface ProfileRepository {
    suspend fun ensureCurrentProfile()
    suspend fun getCurrentProfile(): com.kashke.app.data.User?
    suspend fun updateCurrentProfile(
        fullName: String,
        username: String,
        phone: String,
        bio: String,
        location: String,
        profileImage: String
    )
}
