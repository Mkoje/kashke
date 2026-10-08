package com.kashke.app.data.repository

import com.kashke.app.data.User
import com.kashke.app.data.hasCompleteProfile
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.SignOutScope
import io.github.jan.supabase.gotrue.providers.builtin.Email
import io.github.jan.supabase.gotrue.providers.builtin.Phone
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Locale

class SupabaseRepository(
    suppliedClient: SupabaseClient? = null
) : AuthRepository, ProfileRepository {
    private val client by lazy { suppliedClient ?: SupabaseClientProvider.client }
    private val currentUserState = MutableStateFlow<AuthenticatedIdentity?>(null)

    override val currentUser: StateFlow<AuthenticatedIdentity?> = currentUserState
    override val isConfigured: Boolean = SupabaseClientProvider.isConfigured

    override suspend fun restoreSession(): AuthenticatedIdentity? {
        return runLoggedStage("SESSION RESTORE") {
            client.auth.awaitInitialization()
            val user = client.auth.currentSessionOrNull()?.user
            user?.let { AuthenticatedIdentity(it.id, publicAuthEmail(it.email), it.phone) }.also { currentUserState.value = it }
        }
    }

    override suspend fun register(identifier: String, password: String): AuthenticatedIdentity? {
        val target = authIdentifier(identifier)
        require(com.kashke.app.data.strongRegistrationPassword(password)) { com.kashke.app.data.REGISTRATION_PASSWORD_HINT }
        // Existing provisioning accepts metadata. The reserved placeholder keeps
        // profile completion mandatory; Auth supplies the authoritative user ID.
        val details = buildJsonObject {
            put("full_name", "Member")
            put("username", "user_" + java.util.UUID.randomUUID().toString().replace("-", "").take(19))
            if (target.type == IdentifierType.PHONE) put("phone", target.value)
            else put("profile_email", target.value)
        }
        runLoggedStage("AUTH SIGNUP "+target.type.name) {
            when (target.type) {
                IdentifierType.EMAIL -> client.auth.signUpWith(Email) {
                    email = target.value; this.password = password; data = details
                }
                IdentifierType.PHONE -> client.auth.signUpWith(Phone) {
                    phone = target.value; this.password = password; data = details
                }
            }
        }
        val identity = restoreSession() ?: return null
        check(if (target.type == IdentifierType.EMAIL) identity.email == target.value
            else identity.phone?.let { com.kashke.app.data.registrationPhone(it) } == target.value) {
            "Registration did not return the expected authenticated session."
        }
        return identity
    }

    override suspend fun changePassword(currentPassword: String, newPassword: String) {
        require(com.kashke.app.data.strongRegistrationPassword(newPassword)) { com.kashke.app.data.REGISTRATION_PASSWORD_HINT }
        require(currentPassword != newPassword) { "Choose a different new password." }
        val user = client.auth.currentUserOrNull() ?: error("Sign in first.")
        val identifier = user.email?.takeIf { it.isNotBlank() } ?: user.phone ?: error("Account identifier unavailable.")
        val identity = login(identifier, currentPassword)
        check(identity.id == user.id) { "Account identity changed. Sign in again." }
        client.auth.updateUser { password = newPassword }
    }

    override suspend fun isUsernameAvailable(username: String): Boolean =
        runLoggedStage("USERNAME CHECK RPC is_username_available") {
            client.postgrest.rpc("is_username_available", buildJsonObject {
                put("p_username", username.trim().removePrefix("@").lowercase(Locale.US))
            }).decodeAs<Boolean>()
        }

    override suspend fun login(email: String, password: String): AuthenticatedIdentity {
        require(password.isNotBlank()) { "Password is required." }
        val target = authIdentifier(email)
        runLoggedStage("AUTH SIGNIN "+target.type.name) {
            when (target.type) {
                IdentifierType.EMAIL -> client.auth.signInWith(Email) {
                    this.email = target.value; this.password = password
                }
                IdentifierType.PHONE -> try {
                    client.auth.signInWith(Phone) {
                        phone = target.value; this.password = password
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) {
                    // Read-only compatibility for existing development identities.
                    // Never fall back on network errors or create a synthetic user.
                    if (!e.isInvalidCredentials()) throw e
                    client.auth.signInWith(Email) {
                        this.email = internalAuthEmail(target.value); this.password = password
                    }
                }
            }
        }
        return restoreSession() ?: error("Sign-in did not return an authenticated session.")
    }

    override suspend fun logout() {
        try { client.auth.signOut(SignOutScope.LOCAL) }
        finally {
            withContext(NonCancellable) {
                client.auth.clearSession()
                currentUserState.value = null
            }
        }
    }

    override suspend fun resetPassword(email: String) {
        val cleanEmail = email.trim().lowercase(Locale.US)
        require(com.kashke.app.data.validEmail(cleanEmail)) { "Enter a valid email address." }
        require(publicAuthEmail(cleanEmail) != null) { "Password reset is unavailable for development phone accounts." }
        runLoggedStage("PASSWORD RESET") { client.auth.resetPasswordForEmail(cleanEmail) }
    }

    override suspend fun ensureCurrentProfile() {
        check(restoreSession() != null) { "Sign in before creating a profile." }
        client.postgrest.rpc("ensure_current_profile")
        // Registration details survive email confirmation in Auth metadata.
        // Persist them before loading the dashboard, including its request gate.
        if (getCurrentProfile()?.hasCompleteProfile() == true) return
        val metadata = client.auth.currentUserOrNull()?.userMetadata ?: return
        val fullName = metadata["full_name"]?.jsonPrimitive?.contentOrNull ?: return
        val username = metadata["username"]?.jsonPrimitive?.contentOrNull ?: return
        val phone = metadata["phone"]?.jsonPrimitive?.contentOrNull ?: return
        val email = metadata["profile_email"]?.jsonPrimitive?.contentOrNull ?: return
        val details = registrationDetails(fullName, username, phone, email)
        runLoggedStage("SIGNUP PROFILE SAVE") {
            client.postgrest.rpc("complete_member_profile", buildJsonObject {
                put("p_full_name", details.getValue("full_name"))
                put("p_username", details.getValue("username"))
                put("p_email", details.getValue("profile_email"))
                put("p_phone", details.getValue("phone"))
            })
        }
        check(getCurrentProfile()?.hasCompleteProfile() == true) { "The saved signup profile is incomplete. Please retry account setup." }
    }

    override suspend fun getCurrentProfile(): User? {
        val identity = restoreSession() ?: return null
        return runLoggedStage("PROFILE FETCH SELECT profiles") {
            val row = client.from("profiles").select { filter { eq("id", identity.id) } }.decodeSingle<ProfileRecord>()
            row.toUser(identity.email.orEmpty(), identity.phone.orEmpty())
        }
    }

    override suspend fun updateCurrentProfile(fullName: String, username: String, phone: String,
        bio: String, location: String, profileImage: String) {
        val identity = restoreSession() ?: error("Sign in to update your profile.")
        require(com.kashke.app.data.validName(fullName)) { "Use 2–80 letters and spaces for your full name." }
        require(username.matches(Regex("^[A-Za-z0-9_]{3,24}$"))) { "Username is invalid." }
        // The authenticated phone identifier is immutable through profile editing.
        client.from("profiles").update({
            set("full_name", fullName.trim()); set("username", username.trim().lowercase(Locale.US))
            set("bio", bio.trim()); set("location", location.trim()); set("profile_image", profileImage.trim())
        }) { filter { eq("id", identity.id) } }
    }
}

@Serializable
private data class ProfileRecord(
    val id: String,
    @SerialName("full_name") val fullName: String,
    val username: String,
    val phone: String = "",
    @SerialName("profile_email") val profileEmail: String? = null,
    val bio: String = "",
    val location: String = "",
    @SerialName("profile_image") val profileImage: String = "",
    @SerialName("trust_score") val trustScore: Int = 0,
    @SerialName("borrowing_limit") val borrowingLimit: Int = 2000,
    @SerialName("available_limit") val availableLimit: Int = 2000,
    @SerialName("account_status") val accountStatus: String = "ACTIVE",
    @SerialName("created_at") val createdAt: String
) {
    fun toUser(email: String, authPhone: String) = User(id = id, name = fullName,
        username = if (username.startsWith("@")) username else "@$username", phone = phone.ifBlank { authPhone }, email = profileEmail?.takeIf { it.isNotBlank() } ?: email,
        profileImage = profileImage, bio = bio, location = location, trustScore = trustScore,
        borrowingLimit = borrowingLimit, availableLimit = availableLimit, accountStatus = accountStatus,
        memberSince = createdAt.substringBefore('T'), walletBalance = 0)
}
