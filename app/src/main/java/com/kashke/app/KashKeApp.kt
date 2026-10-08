package com.kashke.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ContactSupport
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wallet
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.kashke.app.data.LoanAgreement
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kashke.app.data.repository.AuthRepository
import com.kashke.app.data.repository.AuthFlowStageException
import com.kashke.app.data.repository.AuthenticatedIdentity
import com.kashke.app.data.repository.ProfileRepository
import com.kashke.app.data.repository.SupabaseClientProvider
import com.kashke.app.data.repository.SupabaseRepository
import com.kashke.app.data.repository.logStageFailure
import com.kashke.app.data.repository.runLoggedStage
import com.kashke.app.data.repository.safeDiagnosticMessage
import com.kashke.app.data.repository.toAuthMessage
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.SessionStatus
import com.kashke.app.data.Connection
import com.kashke.app.data.ConnectionStatus
import com.kashke.app.data.acceptedConnectionsForUser
import com.kashke.app.data.buildContactDirectory
import com.kashke.app.data.buildDiscoverInviteMessage
import com.kashke.app.data.batchContactPhoneNumbers
import com.kashke.app.data.otherConnectionParticipantId
import com.kashke.app.data.ContactProfileMatch
import com.kashke.app.data.DevicePhoneContact
import com.kashke.app.data.DiscoverAction
import com.kashke.app.data.DiscoverContactEntry
import com.kashke.app.data.discoverAction
import com.kashke.app.data.loadDiscoverFeature
import com.kashke.app.data.normalizeKenyanPhone
import com.kashke.app.data.searchDiscoverEntries
import com.kashke.app.data.unconnectedDiscoverContacts
import com.kashke.app.data.LoanOffer
import com.kashke.app.data.LoanOfferStatus
import com.kashke.app.data.LoanRequest
import com.kashke.app.data.LoanRequestStatus
import com.kashke.app.data.DirectRequestBorrowerSummary
import com.kashke.app.data.repository.SupabaseDataRepository
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kashke.app.data.Notification
import com.kashke.app.data.NotificationType
import com.kashke.app.data.numericInput
import com.kashke.app.data.phoneInput
import com.kashke.app.data.authIdentifierInput
import com.kashke.app.data.strongRegistrationPassword
import com.kashke.app.data.REGISTRATION_PASSWORD_HINT
import com.kashke.app.data.User
import com.kashke.app.data.hasCompleteProfile
import com.kashke.app.data.canGrantGrace
import com.kashke.app.data.MemberProfile
import com.kashke.app.data.canViewPrivateTrust
import com.kashke.app.data.readDevicePhoneContacts
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlin.math.roundToInt
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val android.content.Context.appSettingsDataStore by preferencesDataStore(name = "app_settings")
private val contactsPermissionPromptedKey = booleanPreferencesKey("contacts_permission_prompted")
private val rememberedAccountIdKey = stringPreferencesKey("remembered_account_id")
private val rememberedAccountUsernameKey = stringPreferencesKey("remembered_account_username")
private val rememberedAccountEmailKey = stringPreferencesKey("remembered_account_email")
private val rememberedAccountDisplayNameKey = stringPreferencesKey("remembered_account_display_name")

data class RememberedAccount(
    val id: String,
    val username: String,
    val email: String,
    val displayName: String
)

class KashKeViewModel(application: android.app.Application) : AndroidViewModel(application) {
    val repository = SupabaseDataRepository(viewModelScope, application)
    val authRepository: AuthRepository = SupabaseRepository()
    private val profileRepository = authRepository as? ProfileRepository
    private val authenticatedProfileState = MutableStateFlow<User?>(null)
    private val authErrorState = MutableStateFlow<String?>(null)
    private val authMessageState = MutableStateFlow<String?>(null)
    private val authLoadingState = MutableStateFlow(SupabaseClientProvider.isConfigured)
    private val appSessionState = MutableStateFlow(AppSessionState.NO_SESSION)
    private val contactsPermissionPromptedState = MutableStateFlow(true)
    private val rememberedAccountState = MutableStateFlow<RememberedAccount?>(null)
    private val lastUnlockedTimestampKey = longPreferencesKey("kashke_last_unlock_epoch_ms")
    val authenticatedProfile = authenticatedProfileState.asStateFlow()
    val authError = authErrorState.asStateFlow()
    val authMessage = authMessageState.asStateFlow()
    val authLoading = authLoadingState.asStateFlow()
    val authSessionState = appSessionState.asStateFlow()
    val hasAuthenticatedSession = authSessionState.map { it.isAuthenticated }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val appUnlocked = authSessionState.map { it.isUnlocked }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val contactsPermissionPrompted = contactsPermissionPromptedState.asStateFlow()
    val rememberedAccount = rememberedAccountState.asStateFlow()
    private val biometricKey = booleanPreferencesKey("biometric_enabled")
    val biometricEnabled = MutableStateFlow(true)
    fun setBiometricEnabled(enabled: Boolean) { viewModelScope.launch { appSettings.edit { it[biometricKey] = enabled } } }
    fun setLanguage(language: String) { viewModelScope.launch { appSettings.edit { it[stringPreferencesKey("language")] = language } } }
    suspend fun changePassword(current: String, replacement: String) = authRepository.changePassword(current, replacement)
    private val appSettings = application.appSettingsDataStore

    init {
        viewModelScope.launch {
            appSettings.data.map { it[contactsPermissionPromptedKey] ?: false }.collect {
                contactsPermissionPromptedState.value = it
            }
        }
        viewModelScope.launch {
            appSettings.data.collect { prefs ->
                biometricEnabled.value = prefs[biometricKey] ?: true
                AppLanguage.code = prefs[stringPreferencesKey("language")] ?: "en"
                val rememberedId = prefs[rememberedAccountIdKey].orEmpty()
                val rememberedUsername = prefs[rememberedAccountUsernameKey].orEmpty()
                val rememberedEmail = prefs[rememberedAccountEmailKey].orEmpty()
                val rememberedDisplayName = prefs[rememberedAccountDisplayNameKey].orEmpty()
                rememberedAccountState.value = if (rememberedId.isNotBlank() && rememberedUsername.isNotBlank()) {
                    RememberedAccount(
                        id = rememberedId,
                        username = rememberedUsername,
                        email = rememberedEmail,
                        displayName = rememberedDisplayName.ifBlank { rememberedUsername }
                    )
                } else null
            }
        }
        if (authRepository.isConfigured) {
            viewModelScope.launch {
                SupabaseClientProvider.client.auth.sessionStatus.collect { status ->
                    if (status is SessionStatus.NotAuthenticated) {
                        repository.clear()
                        authenticatedProfileState.value = null
                        appSessionState.value = AppSessionState.NO_SESSION
                        viewModelScope.launch {
                            appSettings.edit { it.remove(lastUnlockedTimestampKey) }
                        }
                    }
                }
            }
            viewModelScope.launch {
                try {
                    authRepository.restoreSession()?.let { identity ->
                        val storedUnlockTimestamp = appSettings.data.first()[lastUnlockedTimestampKey] ?: 0L
                        val shouldUnlock = storedUnlockTimestamp == 0L || !shouldLockAfterBackground(SystemClock.elapsedRealtime() - storedUnlockTimestamp)
                        initializeAuthenticatedApp(identity, unlockAfterSetup = shouldUnlock)
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    if (appSessionState.value.isAuthenticated) {
                        reportInitializationFailure(exception, "ACCOUNT INITIALIZATION")
                    } else {
                        authErrorState.value = exception.toAuthMessage("Could not restore the session.")
                    }
                } finally {
                    authLoadingState.value = false
                }
            }
        }
    }

    fun signIn(email: String, password: String) {
        if (authLoadingState.value) return
        viewModelScope.launch {
            authLoadingState.value = true
            authErrorState.value = null
            authMessageState.value = null
            appSessionState.value = AppSessionState.AUTHENTICATING_WITH_CREDENTIALS
            try {
                val identity = try {
                    authRepository.login(email, password)
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    if ((exception as? AuthFlowStageException)?.stage == "SESSION RESTORE") {
                        authMessageState.value = "Sign-in was accepted, but the session could not be restored. Check email confirmation, then retry."
                    } else {
                        authErrorState.value = exception.toAuthMessage("Sign in failed.")
                    }
                    appSessionState.value = AppSessionState.NO_SESSION
                    return@launch
                }
                initializeAuthenticatedApp(identity, unlockAfterSetup = true)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                reportInitializationFailure(exception, "ACCOUNT INITIALIZATION")
            } finally {
                authLoadingState.value = false
            }
        }
    }

    fun register(email: String, password: String) {
        if (authLoadingState.value) return
        viewModelScope.launch {
            authLoadingState.value = true
            authErrorState.value = null
            authMessageState.value = null
            appSessionState.value = AppSessionState.AUTHENTICATING_WITH_CREDENTIALS
            try {
                val identity = try {
                    authRepository.register(email, password)
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    if ((exception as? AuthFlowStageException)?.stage == "SESSION RESTORE") {
                        authMessageState.value = "Your account may have been created, but its session could not be restored. Try signing in with your email or phone number and password."
                    } else {
                        authErrorState.value = exception.toAuthMessage("Registration failed.")
                    }
                    appSessionState.value = AppSessionState.NO_SESSION
                    return@launch
                }
                if (identity == null) {
                    authMessageState.value = if (email.contains('@')) "Check your email to confirm your account, then sign in." else "Phone confirmation is required by the server. Password signup cannot finish here until that setting is updated."
                    appSessionState.value = AppSessionState.NO_SESSION
                } else {
                    initializeAuthenticatedApp(identity, unlockAfterSetup = true)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                reportInitializationFailure(exception, "ACCOUNT INITIALIZATION")
            } finally {
                authLoadingState.value = false
            }
        }
    }

    fun retryAccountSetup() {
        if (authLoadingState.value) return
        viewModelScope.launch {
            authLoadingState.value = true
            authErrorState.value = null
            authMessageState.value = null
            try {
                val identity = authRepository.restoreSession()
                if (identity == null) {
                    appSessionState.value = AppSessionState.NO_SESSION
                    authErrorState.value = "Your session expired. Sign in again to continue."
                } else {
                    val storedUnlockTimestamp = appSettings.data.first()[lastUnlockedTimestampKey] ?: 0L
                    val shouldUnlock = storedUnlockTimestamp == 0L || !shouldLockAfterBackground(SystemClock.elapsedRealtime() - storedUnlockTimestamp)
                    initializeAuthenticatedApp(identity, unlockAfterSetup = shouldUnlock)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (appSessionState.value.isAuthenticated) {
                    reportInitializationFailure(exception, "ACCOUNT INITIALIZATION")
                } else {
                    authErrorState.value = exception.toAuthMessage("Could not restore the session.")
                }
            } finally {
                authLoadingState.value = false
            }
        }
    }

    suspend fun isUsernameAvailable(username: String): Boolean = authRepository.isUsernameAvailable(username)

    fun rememberAuthenticatedAccount(identity: AuthenticatedIdentity, profile: User?) {
        val username = profile?.username?.removePrefix("@") ?: identity.email?.substringBefore('@') ?: "user"
        val displayName = profile?.name?.ifBlank { username } ?: username
        viewModelScope.launch {
            appSettings.edit {
                it[rememberedAccountIdKey] = identity.id
                it[rememberedAccountUsernameKey] = username
                it[rememberedAccountEmailKey] = identity.email?.takeIf { it.isNotBlank() } ?: profile?.phone.orEmpty()
                it[rememberedAccountDisplayNameKey] = displayName
            }
        }
    }

    fun markContactsPermissionPrompted() {
        contactsPermissionPromptedState.value = true
        viewModelScope.launch {
            appSettings.edit { it[contactsPermissionPromptedKey] = true }
        }
    }

    suspend fun findContactProfiles(phoneNumbers: List<String>): List<ContactProfileMatch> =
        repository.matchContactProfiles(phoneNumbers)

    suspend fun searchPublicProfiles(query: String): List<User> = repository.searchPublicProfiles(query)

    suspend fun loadPublicProfile(profileId: String): User? = repository.getPublicProfile(profileId)

    suspend fun loadMemberProfileDetails(profileId: String): MemberProfile? = repository.getMemberProfileDetails(profileId)

    suspend fun readContacts(): List<DevicePhoneContact> = readDevicePhoneContacts(getApplication())

    fun signOut() {
        if (authLoadingState.value) return
        repository.clear()
        authenticatedProfileState.value = null
        appSessionState.value = AppSessionState.LOGGED_OUT
        authLoadingState.value = true
        viewModelScope.launch {
            authLoadingState.value = true
            try {
                authRepository.logout()
                repository.clear()
                authenticatedProfileState.value = null
                appSessionState.value = AppSessionState.NO_SESSION
                authErrorState.value = null
                authMessageState.value = null
                appSettings.edit { it.remove(lastUnlockedTimestampKey) }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                authErrorState.value = exception.toAuthMessage("Sign out failed.")
            } finally {
                repository.clear()
                authenticatedProfileState.value = null
                appSessionState.value = AppSessionState.NO_SESSION
                authLoadingState.value = false
            }
        }
    }

    fun removeAccountFromDevice() {
        if (authLoadingState.value) return
        authLoadingState.value = true
        viewModelScope.launch {
            authErrorState.value = null
            try {
                authRepository.logout()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                authErrorState.value = exception.toAuthMessage("The local account was removed, but sign-out could not reach Database.")
            } finally {
                repository.clear()
                authenticatedProfileState.value = null
                appSessionState.value = AppSessionState.NO_SESSION
                appSettings.edit {
                    it.remove(rememberedAccountIdKey)
                    it.remove(rememberedAccountUsernameKey)
                    it.remove(rememberedAccountEmailKey)
                    it.remove(rememberedAccountDisplayNameKey)
                    it.remove(lastUnlockedTimestampKey)
                }
                authLoadingState.value = false
            }
        }
    }

    fun requestPasswordReset(email: String) {
        if (authLoadingState.value) return
        viewModelScope.launch {
            authLoadingState.value = true
            authErrorState.value = null
            authMessageState.value = null
            try {
                authRepository.resetPassword(email)
                authMessageState.value = "Password reset instructions were sent if that account exists."
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                authErrorState.value = exception.toAuthMessage("We couldn't send password reset instructions.")
            } finally {
                authLoadingState.value = false
            }
        }
    }

    fun lockApp() {
        if (appSessionState.value.isAuthenticated) {
            appSessionState.value = AppSessionState.AUTHENTICATED_LOCKED
        }
    }

    fun unlockApp(onResult: (Boolean) -> Unit = {}) {
        if (authLoadingState.value) return
        authLoadingState.value = true
        viewModelScope.launch {
            try {
                val identity = authRepository.restoreSession()
                val rememberedId = rememberedAccountState.value?.id
                if (identity == null || (rememberedId != null && identity.id != rememberedId)) {
                    appSessionState.value = AppSessionState.NO_SESSION
                    authErrorState.value = "Fingerprint verified, but this account needs password sign-in to restore its session."
                    onResult(false)
                    return@launch
                }
                initializeAuthenticatedApp(identity, unlockAfterSetup = true)
                onResult(appSessionState.value == AppSessionState.AUTHENTICATED_UNLOCKED)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                authErrorState.value = exception.toAuthMessage("Fingerprint was verified, but Kash.ke could not restore your session. Sign in with your password.")
                appSessionState.value = AppSessionState.NO_SESSION
                onResult(false)
            } finally {
                authLoadingState.value = false
            }
        }
    }

    private suspend fun initializeAuthenticatedApp(identity: AuthenticatedIdentity, unlockAfterSetup: Boolean = false) {
        authenticatedProfileState.value = null
        repository.clear()
        val profileStore = profileRepository
            ?: error("Database profile storage is not available in this build.")
        try {
            runLoggedStage("PROFILE CREATION RPC ensure_current_profile") {
                profileStore.ensureCurrentProfile()
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            reportInitializationFailure(exception, "PROFILE CREATION")
            return
        }
        val profile = try {
            runLoggedStage("PROFILE FETCH") {
                (profileStore.getCurrentProfile() ?: error("The authenticated profile row was not returned.")).also {
                    check(it.id == identity.id) { "The returned profile does not match the authenticated session." }
                }
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            reportInitializationFailure(exception, "PROFILE FETCH")
            return
        }
        try {
            repository.load(profile.id)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            reportInitializationFailure(exception, "DATA REPOSITORY LOAD")
            return
        }
        authenticatedProfileState.value = profile
        rememberAuthenticatedAccount(identity, profile)
        appSessionState.value = if (unlockAfterSetup) AppSessionState.AUTHENTICATED_UNLOCKED else AppSessionState.AUTHENTICATED_LOCKED
        if (unlockAfterSetup) {
            appSettings.edit { it[lastUnlockedTimestampKey] = SystemClock.elapsedRealtime() }
        }
        authErrorState.value = null
        authMessageState.value = null
    }

    private fun reportInitializationFailure(exception: Exception, fallbackStage: String) {
        val stage = (exception as? AuthFlowStageException)?.stage ?: fallbackStage
        if (exception !is AuthFlowStageException) logStageFailure(stage, exception)
        authErrorState.value = "We couldn't finish setting up your session. Your session is still active. Tap Retry session setup."
    }

    fun clearAuthMessage() {
        authErrorState.value = null
        authMessageState.value = null
    }

    val currentUser: StateFlow<User?> = combine(
        repository.users,
        repository.currentUserId
    ) { users, currentId ->
        users.firstOrNull { it.id == currentId }
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, null)


    suspend fun createRequest(
        amount: Int,
        purpose: String,
        description: String,
        periodDays: Int,
        directRequestTo: String? = null,
        aboveLimit: Boolean = false
    ): Boolean {
        val current = repository.getCurrentUserId()
        return if (directRequestTo == null) {
            repository.createLoanRequest(current, amount, purpose, description, periodDays)
        } else {
            repository.createDirectLoanRequest(
                current,
                amount,
                purpose,
                description,
                periodDays,
                directRequestTo,
                aboveLimit
            )
        }
    }

    suspend fun makeOffer(
        requestId: String,
        amount: Int,
        interestRate: Int,
        periodDays: Int,
        approveAboveLimit: Boolean = false
    ): Boolean {
        return repository.makeLoanOffer(
            requestId,
            repository.getCurrentUserId(),
            amount,
            interestRate,
            periodDays,
            approveAboveLimit
        )
    }

    suspend fun loadDirectRequestBorrowerSummary(requestId: String): DirectRequestBorrowerSummary? =
        repository.getDirectRequestBorrowerSummary(requestId)

    fun acceptOffer(offer: LoanOffer) {
        repository.acceptLoanOffer(offer, repository.getCurrentUserId())
    }

    fun rejectOffer(offerId: String) {
        repository.declineLoanOffer(offerId, repository.getCurrentUserId())
    }

    suspend fun editRequest(requestId: String, amount: Int, purpose: String, description: String, periodDays: Int): Boolean {
        return repository.editLoanRequest(
            requestId,
            repository.getCurrentUserId(),
            amount,
            purpose,
            description,
            periodDays
        )
    }

    suspend fun cancelRequest(requestId: String): Boolean {
        return repository.cancelLoanRequest(requestId, repository.getCurrentUserId())
    }

    fun sendConnectionRequest(userId: String) {
        val current = repository.getCurrentUserId()
        repository.sendConnectionRequest(current, userId)
    }

    fun acceptConnectionRequest(connectionId: String) {
        repository.acceptConnectionRequest(connectionId)
    }

    fun rejectConnectionRequest(connectionId: String) {
        repository.rejectConnectionRequest(connectionId)
    }

    fun declineOffer(offerId: String) {
        repository.declineLoanOffer(offerId, repository.getCurrentUserId())
    }

    suspend fun requestOfferTerms(offerId: String, interestRate: Int, periodDays: Int): Boolean {
        return repository.requestLoanOfferTerms(offerId, repository.getCurrentUserId(), interestRate, periodDays)
    }

    suspend fun respondToOfferTerms(offerId: String, acceptRequestedTerms: Boolean): Boolean {
        return repository.respondToLoanOfferTerms(offerId, repository.getCurrentUserId(), acceptRequestedTerms)
    }

    suspend fun repayLoan(loanId: String, amount: Int): Boolean {
        return repository.repayLoan(loanId, amount, repository.getCurrentUserId())
    }

    suspend fun submitOfferTermsRequest(offerId: String, interestRate: Int, periodDays: Int): Boolean {
        return repository.requestLoanOfferTerms(offerId, repository.getCurrentUserId(), interestRate, periodDays)
    }
}

private enum class DashboardPage {
    CONNECTION_REQUESTS,
    CONNECTIONS,
    NOTIFICATIONS,
    PROFILE,
    SETTINGS
}

private fun isNotificationPermissionGranted(context: android.content.Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
}

private fun openNotificationSettings(context: android.content.Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
    }
    context.startActivity(intent)
}

@Composable
fun KashKeApp(viewModel: KashKeViewModel = viewModel(), notificationTarget: NotificationTarget? = null, onNotificationConsumed: () -> Unit = {}) {
    if (!RequiredLocationPermission()) return
    val authSession by viewModel.authSessionState.collectAsState()
    LaunchedEffect(authSession) { if (authSession == AppSessionState.LOGGED_OUT) onNotificationConsumed() }
    val lifecycleOwner = LocalLifecycleOwner.current
    var backgroundedAt by remember { mutableStateOf<Long?>(null) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> backgroundedAt = SystemClock.elapsedRealtime()
                Lifecycle.Event.ON_START -> {
                    val stoppedAt = backgroundedAt
                    backgroundedAt = null
                    if (stoppedAt != null && shouldLockAfterBackground(SystemClock.elapsedRealtime() - stoppedAt)) {
                        viewModel.lockApp()
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val rememberedAccount by viewModel.rememberedAccount.collectAsState()
    val startupLoading by viewModel.authLoading.collectAsState()
    if (startupLoading && authSession == AppSessionState.NO_SESSION) {
        BrandedLaunchScreen()
        return
    }
    when (authSession) {
        AppSessionState.NO_SESSION, AppSessionState.LOGGED_OUT -> if (rememberedAccount != null) {
            AppLockScreen(viewModel, rememberedAccount)
        } else {
            SupabaseAuthScreen(
                viewModel = viewModel,
                isConfigured = viewModel.authRepository.isConfigured
            )
        }
        AppSessionState.AUTHENTICATED_LOCKED -> AppLockScreen(viewModel, rememberedAccount)
        AppSessionState.AUTHENTICATING_WITH_CREDENTIALS -> SupabaseAuthScreen(
            viewModel = viewModel,
            isConfigured = viewModel.authRepository.isConfigured
        )
        AppSessionState.AUTHENTICATED_UNLOCKED -> KashKeDashboard(viewModel = viewModel, onLogout = viewModel::lockApp, notificationTarget = notificationTarget, onNotificationConsumed = onNotificationConsumed)
    }
}

@Composable
private fun AppLockScreen(viewModel: KashKeViewModel, rememberedAccount: RememberedAccount? = null) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val isLoading by viewModel.authLoading.collectAsState()
    val error by viewModel.authError.collectAsState()
    val biometricEnabled by viewModel.biometricEnabled.collectAsState()
    val biometricAvailable = remember(activity, biometricEnabled) {
        biometricEnabled && activity != null && BiometricManager.from(activity).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }
    var usePassword by remember { mutableStateOf(shouldOfferCredentialFallback(biometricAvailable)) }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val targetAccount = rememberedAccount ?: RememberedAccount("", "", "", "")
    val authSession by viewModel.authSessionState.collectAsState()
    var autoPromptAttempted by remember(targetAccount.id) { mutableStateOf(false) }
    var promptActive by remember { mutableStateOf(false) }
    var fingerprintFailed by remember { mutableStateOf(false) }
    val biometricPrompt = remember(activity, viewModel) {
        activity?.let { promptActivity ->
            BiometricPrompt(promptActivity, ContextCompat.getMainExecutor(promptActivity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        promptActive = false
                        viewModel.unlockApp { restored -> if (!restored) usePassword = true }
                    }
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        promptActive = false
                    }
                    override fun onAuthenticationFailed() {
                        fingerprintFailed = true
                        promptActive = false
                    }
                })
        }
    }
    val startBiometricUnlock = {
        if (!promptActive && biometricPrompt != null) {
            fingerprintFailed = false
            promptActive = true
            biometricPrompt.authenticate(BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Kash.ke")
                .setSubtitle("Authenticate to access your existing session")
                .setNegativeButtonText(tr("Cancel"))
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build())
        }
    }
    LaunchedEffect(targetAccount.id, biometricAvailable, isLoading, authSession) {
        if (!autoPromptAttempted && biometricAvailable && targetAccount.id.isNotBlank() &&
            authSession.isLocked && !isLoading && !usePassword) {
            autoPromptAttempted = true
            startBiometricUnlock()
        }
    }
    LaunchedEffect(fingerprintFailed) {
        if (fingerprintFailed) biometricPrompt?.cancelAuthentication()
    }
    DisposableEffect(biometricPrompt) {
        onDispose { biometricPrompt?.cancelAuthentication() }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                tr(if (targetAccount.id.isNotBlank()) "Welcome back" else "Kash.ke is locked"),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            if (targetAccount.id.isNotBlank()) {
                Text(
                    tr("@${targetAccount.username.ifBlank { "user" }}"),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }
            Text(
                tr(if (targetAccount.id.isNotBlank()) {
                    if (biometricAvailable) "Use your fingerprint or password to continue." else "Enter your password to continue."
                } else if (biometricAvailable) {
                    "Unlock to continue your existing session."
                } else {
                    "Sign in with your email or verified phone to unlock your existing account."
                }),
                modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
                textAlign = TextAlign.Center
            )
            if (isLoading) {
                AppLoadingIndicator()
            } else {
                error?.let { AuthStatusBanner(it, isError = true) }
                if (error != null && targetAccount.id.isBlank()) {
                    OutlinedButton(
                        onClick = viewModel::retryAccountSetup,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(tr("Retry session setup")) }
                }
                if (biometricAvailable && targetAccount.id.isNotBlank() && !usePassword) {
                    Button(
                        onClick = startBiometricUnlock,
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) {
                        Icon(Icons.Default.Fingerprint, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text(tr("Unlock with fingerprint"))
                    }
                }
                if (targetAccount.id.isNotBlank() || usePassword) {
                    if (targetAccount.id.isNotBlank()) {
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text(tr("Password")) },
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(
                                        if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (passwordVisible) "Hide password" else "Show password"
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {
                                val email = targetAccount.email.ifBlank { "" }
                                if (email.isNotBlank()) {
                                    viewModel.signIn(email, password)
                                }
                                password = ""
                            },
                            enabled = password.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(52.dp)
                        ) { Text(tr("Continue")) }
                    }
                }
                if (biometricAvailable) {
                    if (targetAccount.id.isNotBlank()) {
                        TextButton(onClick = { usePassword = !usePassword }) {
                            Text(tr(if (usePassword) "Use fingerprint" else "Use password"))
                        }
                    } else if (usePassword) {
                        TextButton(onClick = { usePassword = false }) {
                            Text(tr("Use biometrics"))
                        }
                    }
                }
                if (targetAccount.id.isNotBlank()) {
                    TextButton(
                        onClick = { confirmRemove = true },
                        enabled = !isLoading,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(tr("Remove account from this device")) }
                }
            }
            if (targetAccount.id.isBlank()) {
                TextButton(onClick = viewModel::signOut, enabled = !isLoading) { Text(tr("Log out")) }
            }
        }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(tr("Remove account from this device?")) },
            text = { Text(tr("This clears the remembered account and any local app unlock state for this device. You will need to sign in again when you return.")) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeAccountFromDevice()
                    confirmRemove = false
                }) { Text(tr("Remove")) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(tr("Cancel")) } }
        )
    }
}

@Composable
fun SupabaseAuthScreen(viewModel: KashKeViewModel, isConfigured: Boolean) {
    var isRegistering by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var legalNotice by remember { mutableStateOf<String?>(null) }
    val isLoading by viewModel.authLoading.collectAsState()
    val error by viewModel.authError.collectAsState()
    val message by viewModel.authMessage.collectAsState()
    val hasAuthenticatedSession by viewModel.hasAuthenticatedSession.collectAsState()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF0C1F3D)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF0C1F3D), Color(0xFF173B48), Color(0xFFEAF4EF)))).safeDrawingPadding().imePadding().padding(horizontal = 24.dp, vertical = 28.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 460.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        tr("Kash.ke"),
                        style = MaterialTheme.typography.headlineLarge,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        tr(if (isRegistering) "Create your account" else "Welcome back"),
                        style = MaterialTheme.typography.headlineMedium, color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        tr(if (isRegistering) {
                            "Use your email or phone number and a password."
                        } else {
                            "Your community. Your goals. Keep moving forward."
                        }),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFCEE2DC)
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color.White.copy(alpha = 0.12f)).padding(5.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    AuthModeButton(
                        label = "Sign in",
                        selected = !isRegistering,
                        onClick = {
                            isRegistering = false
                            viewModel.clearAuthMessage()
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !isLoading
                    )
                    AuthModeButton(
                        label = "Sign up",
                        selected = isRegistering,
                        onClick = {
                            isRegistering = true
                            viewModel.clearAuthMessage()
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !isLoading
                    )
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (!isConfigured) {
                            AuthStatusBanner(
                                text = "Database is not configured..properties, rebuild, then try again.",
                                isError = true
                            )
                        }
                        error?.let { AuthStatusBanner(text = it, isError = true) }
                        message?.let { AuthStatusBanner(text = it, isError = false) }
                        if (hasAuthenticatedSession) {
                            Button(
                                onClick = viewModel::retryAccountSetup,
                                enabled = !isLoading,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(tr("Retry session setup"))
                            }
                            TextButton(
                                onClick = viewModel::lockApp,
                                enabled = !isLoading,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(tr("Lock app"))
                            }
                        }

                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = authIdentifierInput(email, it) },
                            label = { Text(tr("Email or phone number")) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            modifier = Modifier.fillMaxWidth(),
                            enabled = isConfigured && !isLoading,
                            shape = RoundedCornerShape(14.dp), singleLine = true
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { candidate -> if (!isRegistering || candidate.length <= 12) password = candidate },
                            label = { Text(tr("Password")) },
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (passwordVisible) "Hide password" else "Show password")
                                }
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                            enabled = isConfigured && !isLoading,
                            shape = RoundedCornerShape(14.dp), singleLine = true
                        )

                        if (isRegistering) Text(REGISTRATION_PASSWORD_HINT, style = MaterialTheme.typography.bodySmall)

                        Button(
                            onClick = {
                                if (isRegistering) {
                                    viewModel.register(email, password)
                                } else {
                                    viewModel.signIn(email, password)
                                }
                            },
                            enabled = isConfigured && !isLoading && runCatching { com.kashke.app.data.repository.authIdentifier(email) }.isSuccess &&
                                (if (isRegistering) strongRegistrationPassword(password) else password.isNotEmpty()),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                        ) {
                            if (isLoading) {
                                AppLoadingIndicator(modifier = Modifier.size(28.dp))
                            } else {
                                Text(tr(if (isRegistering) "Create account" else "Sign in"))
                            }
                        }

                        if (!isRegistering) {
                            TextButton(
                                onClick = {
                                    val targetEmail = email.trim()
                                    if (targetEmail.isNotBlank()) viewModel.requestPasswordReset(targetEmail)
                                },
                                enabled = !isLoading && email.contains('@'),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(tr("Forgot password?"))
                            }
                        }
                        if (isRegistering) {
                            Text(
                                text = tr("By creating an account, you agree to KashKe's Terms & Conditions and Privacy Policy."),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { legalNotice = "terms" }) {
                                    Text(tr("Terms & Conditions"))
                                }
                                TextButton(onClick = { legalNotice = "privacy" }) {
                                    Text(tr("Privacy Policy"))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    legalNotice?.let { notice ->
        AlertDialog(
            onDismissRequest = { legalNotice = null },
            title = { Text(tr(if (notice == "terms") "Terms & Conditions" else "Privacy Policy")) },
            text = { Text(legalContent(if (notice == "terms") "Terms & Conditions" else "Privacy Policy"), Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { legalNotice = null }) { Text(tr("Close")) } }
        )
    }
}

@Composable
private fun AuthModeButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val background = if (selected) Color(0xFFF5FAF7) else Color.Transparent
    val foreground = if (selected) Color(0xFF0C1F3D) else Color.White
    Text(
        text = tr(label),
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .background(background)
            .padding(vertical = 12.dp),
        color = foreground,
        textAlign = TextAlign.Center,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun AuthStatusBanner(text: String, isError: Boolean) {
    val background = if (isError) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val foreground = if (isError) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .padding(12.dp),
        color = foreground,
        style = MaterialTheme.typography.bodyMedium
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
fun KashKeDashboard(viewModel: KashKeViewModel, onLogout: () -> Unit, notificationTarget: NotificationTarget? = null, onNotificationConsumed: () -> Unit = {}) {
    val currentUser by viewModel.currentUser.collectAsState()
    if (currentUser == null) return
    val notificationContext = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) viewModel.repository.retry()
    }
    LaunchedEffect(currentUser!!.id) {
        val permissionPreferences = notificationContext.getSharedPreferences("notification_permission", android.content.Context.MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && !isNotificationPermissionGranted(notificationContext) &&
            !permissionPreferences.getBoolean("prompted", false)) {
            permissionPreferences.edit().putBoolean("prompted", true).apply()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val dataError by viewModel.repository.error.collectAsState()
    val dataBusy by viewModel.repository.busy.collectAsState()
    val notifications by viewModel.repository.notifications.collectAsState()
    val loanRequests by viewModel.repository.loanRequests.collectAsState()
    val loanOffers by viewModel.repository.loanOffers.collectAsState()
    val pagerState = rememberPagerState(
        initialPage = if (currentUser!!.hasCompleteProfile()) 0 else 4,
        pageCount = { 5 }
    )
    val selectedTab = pagerState.currentPage
    val navigationScope = rememberCoroutineScope()
    val selectTab: (Int) -> Unit = { index ->
        navigationScope.launch { pagerState.animateScrollToPage(index) }
    }
    var profileSectionToOpen by remember { mutableIntStateOf(0) }
    var profileEditRequest by remember(currentUser!!.id) { mutableIntStateOf(0) }
    var showRequestForm by remember { mutableStateOf(false) }
    var activePage by rememberSaveable { mutableStateOf<DashboardPage?>(null) }
    var activeProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = activePage != null) {
        activePage = null
        activeProfileId = null
        onNotificationConsumed()
    }
    var homeNotificationTarget by remember { mutableStateOf<NotificationTarget?>(null) }
    LaunchedEffect(notificationTarget, currentUser!!.id) {
        notificationTarget?.let { if (it.userId == currentUser!!.id) activePage = DashboardPage.NOTIFICATIONS else onNotificationConsumed() }
    }
    val mutedMembers by viewModel.repository.mutedMembers.collectAsState()
    val loanActionCount = loanRequests.count {
        it.directRequestTo == currentUser!!.id && it.borrowerId !in mutedMembers &&
            it.status in setOf(LoanRequestStatus.REQUESTED, LoanRequestStatus.PARTIALLY_FUNDED) &&
            it.remainingAmount > 0
    } + loanOffers.count {
        (it.borrowerId == currentUser!!.id && it.status == LoanOfferStatus.OFFERED) ||
            (it.lenderId == currentUser!!.id && it.status == LoanOfferStatus.TERMS_REQUESTED)
    }
    val openProfile: (String) -> Unit = { profileId ->
        activeProfileId = profileId
        activePage = DashboardPage.PROFILE
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (activePage != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = {
                                activePage = null
                                activeProfileId = null
                            }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                            Text(
                                tr(when (activePage) {
                                    DashboardPage.CONNECTION_REQUESTS -> "Connection requests"
                                    DashboardPage.CONNECTIONS -> "Connections"
                                    DashboardPage.NOTIFICATIONS -> "Notifications"
                                    DashboardPage.PROFILE -> "Member profile"
                                    DashboardPage.SETTINGS -> "Settings"
                                    null -> ""
                                })
                            )
                        }
                    } else if (currentUser != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(
                                painter = painterResource(id = R.drawable.kashke_symbol),
                                contentDescription = "Kash.ke logo",
                                modifier = Modifier.size(38.dp).background(Color(0xFFE8F0FA), CircleShape)
                            )
                            Spacer(modifier = Modifier.size(8.dp))
                            Text(tr("Hello ${currentUser!!.name.split(" ").first()}"))
                        }
                    }
                },
                actions = {
                    val unread = notifications.filter { it.userId == currentUser!!.id && !it.read }.size
                    IconButton(onClick = {
                        activePage = DashboardPage.NOTIFICATIONS
                    }) {
                        Box {
                            Icon(Icons.Default.Notifications, contentDescription = "Notifications")
                            if (unread > 0) {
                                CountBadge(unread, Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-5).dp))
                            }
                        }
                    }
                    IconButton(onClick = { activePage = DashboardPage.SETTINGS }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                    IconButton(onClick = onLogout, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.Lock, contentDescription = "Lock app")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                listOf(
                    "Home" to Icons.Default.Home,
                    "Discover" to Icons.Default.Search,
                    "Loans" to Icons.Default.CreditCard,
                    "Wallet" to Icons.Default.Wallet,
                    "Profile" to Icons.Default.Person
                ).forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = {
                            selectTab(index)
                            if (index == 4) profileSectionToOpen = 0
                            activePage = null
                            activeProfileId = null
                        },
                        icon = {
                            Box {
                                Icon(item.second, contentDescription = item.first)
                                if (index == 2 && loanActionCount > 0) {
                                    CountBadge(
                                        loanActionCount,
                                        Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-5).dp)
                                    )
                                }
                            }
                        },
                        label = { Text(tr(item.first)) }
                    )
                }
            }
        },
        floatingActionButton = {
            if (selectedTab == 0 && activePage == null) {
                Button(
                    onClick = { showRequestForm = true },
                    enabled = currentUser?.hasCompleteProfile() == true,
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text(tr("Request Support"))
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            if (currentUser?.hasCompleteProfile() == false) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("Complete your full name, username, email and phone number before making requests."), modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                        TextButton(onClick = {
                            activePage = null
                            activeProfileId = null
                            selectTab(4)
                            profileEditRequest++
                        }) { Text(tr("Edit profile")) }
                    }
                }
            }
            if (dataBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            dataError?.let { message ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(message, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { viewModel.repository.retry() }) { Text(tr("Retry")) }
                }
            }
            when (activePage) {
                DashboardPage.CONNECTION_REQUESTS -> ConnectionRequestsPage(viewModel, currentUser!!, openProfile)
                DashboardPage.CONNECTIONS -> ConnectionsPage(viewModel, currentUser!!, openProfile)
                DashboardPage.NOTIFICATIONS -> NotificationsPage(
                    viewModel,
                    currentUser!!,
                    onOpenConnectionRequests = { activePage = DashboardPage.CONNECTION_REQUESTS },
                    onOpenProfile = openProfile,
                    notificationTarget = homeNotificationTarget ?: notificationTarget,
                    onNotificationConsumed = { homeNotificationTarget = null; onNotificationConsumed() }
                )
                DashboardPage.PROFILE -> activeProfileId?.let { profileId ->
                    PublicProfilePage(viewModel, profileId)
                }
                DashboardPage.SETTINGS -> SettingsPage(viewModel, currentUser!!)
                null -> HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    key = { page -> "${currentUser!!.id}:$page" }
                ) { page ->
                    when (page) {
                    0 -> HomeTab(
                        viewModel,
                        currentUser!!,
                        onOpenNotifications = { notification ->
                            homeNotificationTarget = NotificationTarget(notification.id, currentUser!!.id)
                            activePage = DashboardPage.NOTIFICATIONS
                        },
                        onFindMore = { selectTab(1) },
                        onViewLoans = { selectTab(2) },
                        onOpenProfile = openProfile
                    )
                    1 -> DiscoverTab(viewModel, currentUser!!, onOpenProfile = { profileId ->
                        activeProfileId = profileId
                        activePage = DashboardPage.PROFILE
                    })
                    2 -> LoansTab(viewModel, currentUser!!, openProfile)
                    3 -> WalletTab(viewModel, currentUser!!)
                    4 -> ProfileTab(
                        viewModel,
                        currentUser!!,
                        onOpenConnections = { activePage = DashboardPage.CONNECTIONS },
                        onOpenProfile = openProfile,
                        initialSelectedProfileSection = profileSectionToOpen,
                        editRequest = profileEditRequest,
                        onEditRequestHandled = { profileEditRequest = 0 }
                    )
                    }
                }
            }
        }
    }

    if (showRequestForm && currentUser != null) {
        RequestSupportDialog(
            onDismiss = { showRequestForm = false },
            onSubmit = { amount, purpose, description, period ->
                viewModel.createRequest(amount, purpose, description, period).also { submitted ->
                    if (submitted) showRequestForm = false
                }
            },
            maxAmount = currentUser?.availableLimit ?: 0,
            onManageRequests = {
                showRequestForm = false
                profileSectionToOpen = 1
                selectTab(4)
            },
            onViewLoans = {
                showRequestForm = false
                selectTab(2)
            }
        )
    }
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String,
    leadingIcon: @Composable () -> Unit,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center
            ) {
                leadingIcon()
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(tr(title), fontWeight = FontWeight.SemiBold)
                if (subtitle.isNotBlank()) {
                    Text(
                        tr(subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
            Icon(Icons.Default.KeyboardArrowRight, contentDescription = null)
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = tr(title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
}

@Composable
fun SettingsPage(viewModel: KashKeViewModel, user: User) {
    val context = LocalContext.current
    var notificationPermissionGranted by remember { mutableStateOf(isNotificationPermissionGranted(context)) }
    val notificationPromptPreferences = remember(context) { context.getSharedPreferences("notification_permissions", android.content.Context.MODE_PRIVATE) }
    val settingsLifecycle = LocalLifecycleOwner.current
    DisposableEffect(settingsLifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) notificationPermissionGranted = isNotificationPermissionGranted(context)
        }
        settingsLifecycle.lifecycle.addObserver(observer)
        onDispose { settingsLifecycle.lifecycle.removeObserver(observer) }
    }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var settingsDialog by remember { mutableStateOf<String?>(null) }
    SettingsDialogs(viewModel, settingsDialog) { settingsDialog = null }
    val mutedMembers by viewModel.repository.mutedMembers.collectAsState()
    val biometricEnabled by viewModel.biometricEnabled.collectAsState()
    val themeMode = com.kashke.app.ui.theme.LocalAppThemeMode.current
    val setThemeMode = com.kashke.app.ui.theme.LocalSetAppThemeMode.current
    var showThemePicker by remember { mutableStateOf(false) }
    if (showThemePicker) {
        AlertDialog(
            onDismissRequest = { showThemePicker = false },
            title = { Text(tr("Appearance")) },
            text = {
                Column {
                    com.kashke.app.ui.theme.AppThemeMode.values().forEach { mode ->
                        Row(Modifier.fillMaxWidth().clickable { setThemeMode(mode); showThemePicker = false }
                            .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.RadioButton(selected = themeMode == mode,
                                onClick = { setThemeMode(mode); showThemePicker = false })
                            Text(tr(mode.label), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showThemePicker = false }) { Text(tr("Close")) } }
        )
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationPromptPreferences.edit().putBoolean("requested", true).apply()
        notificationPermissionGranted = granted
    }

    val requestNotificationPermission: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val activity = context as? FragmentActivity
            val showRationale = activity != null && ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                Manifest.permission.POST_NOTIFICATIONS
            )
            if (!showRationale && !notificationPermissionGranted && notificationPromptPreferences.getBoolean("requested", false)) {
                openNotificationSettings(context)
            } else if (showRationale) {
                showPermissionDialog = true
            } else {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            openNotificationSettings(context)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = tr("Settings"),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(tr("KashKe account"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(tr("Signed in as ${user.name.ifBlank { user.username }}"), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        item { SettingsSectionHeader("Notifications") }
        item {
            SettingsRow(
                title = tr("Notification preferences"),
                subtitle = if (notificationPermissionGranted) "Notifications are enabled" else "Tap to enable notifications",
                leadingIcon = { Icon(Icons.Default.Notifications, contentDescription = null) },
                onClick = {
                    if (notificationPermissionGranted) {
                        openNotificationSettings(context)
                    } else {
                        requestNotificationPermission()
                    }
                }
            )
        }
        item {
            SettingsRow(
                title = tr("Muted support requests"),
                subtitle = if (mutedMembers.isEmpty()) "No muted requests yet" else "${mutedMembers.size} muted members",
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.VolumeOff, contentDescription = null) },
                onClick = { settingsDialog = "Muted support requests" }
            )
        }

        item { SettingsSectionHeader("Appearance") }
        item {
            SettingsRow(
                title = tr("Theme"),
                subtitle = themeMode.label,
                leadingIcon = { Icon(Icons.Default.Palette, contentDescription = null) },
                onClick = { showThemePicker = true }
            )
        }
        item {
            SettingsRow(
                title = tr("Language"),
                subtitle = if (AppLanguage.code == "sw") "Kiswahili" else "English",
                leadingIcon = { Icon(Icons.Default.Language, contentDescription = null) },
                onClick = { settingsDialog = "Language" }
            )
        }

        item { SettingsSectionHeader("Privacy & Security") }
        item {
            SettingsRow(
                title = tr("Change password"),
                subtitle = tr("Use your current and new password"),
                leadingIcon = { Icon(Icons.Default.Security, contentDescription = null) },
                onClick = { settingsDialog = "Change password" }
            )
        }
        item {
            SettingsRow(
                title = tr("App lock"),
                subtitle = if (biometricEnabled) "Biometrics enabled" else "Password unlock",
                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                onClick = { settingsDialog = "App lock" }
            )
        }

        item { SettingsSectionHeader("Help & Legal") }
        item {
            SettingsRow(
                title = tr("Privacy Policy"),
                subtitle = tr("How we use and protect your information"),
                leadingIcon = { Icon(Icons.Default.Policy, contentDescription = null) },
                onClick = { settingsDialog = "Privacy Policy" }
            )
        }
        item {
            SettingsRow(
                title = tr("Terms & Conditions"),
                subtitle = tr("Using Kash.ke responsibly"),
                leadingIcon = { Icon(Icons.Default.Gavel, contentDescription = null) },
                onClick = { settingsDialog = "Terms & Conditions" }
            )
        }
        item {
            SettingsRow(
                title = tr("FAQ"),
                subtitle = tr("What Kash.ke does and how to use it"),
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Help, contentDescription = null) },
                onClick = { settingsDialog = "FAQ" }
            )
        }
        item {
            SettingsRow(
                title = tr("Contact Us"),
                subtitle = tr("Website, email, WhatsApp and contact form"),
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.ContactSupport, contentDescription = null) },
                onClick = { settingsDialog = "Contact Us" }
            )
        }
        item {
            SettingsRow(
                title = tr("About KashKe"),
                subtitle = tr("Version 1.0"),
                leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                onClick = { settingsDialog = "About KashKe" }
            )
        }
    }

    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = { Text(tr("Enable KashKe notifications")) },
            text = { Text(tr("KashKe uses notifications to keep you updated about support requests and important account activity.")) },
            confirmButton = {
                TextButton(onClick = {
                    showPermissionDialog = false
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }) { Text(tr("Allow")) }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionDialog = false }) { Text(tr("Not now")) }
            }
        )
    }


}

@Composable
fun HomeTab(
    viewModel: KashKeViewModel,
    user: User,
    onOpenNotifications: (Notification) -> Unit,
    onFindMore: () -> Unit,
    onViewLoans: () -> Unit,
    onOpenProfile: (String) -> Unit
) {
    val loanRequests by viewModel.repository.loanRequests.collectAsState()
    val connections by viewModel.repository.connections.collectAsState()
    val offers by viewModel.repository.loanOffers.collectAsState()
    val operationBusy by viewModel.repository.busy.collectAsState()
    var editingOffer by remember { mutableStateOf<LoanOffer?>(null) }
    var cancellingOffer by remember { mutableStateOf<LoanOffer?>(null) }
    val loans by viewModel.repository.loans.collectAsState()
    val notifications by viewModel.repository.notifications.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasContactsPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var deviceContacts by remember { mutableStateOf(emptyList<DevicePhoneContact>()) }
    var contactMatches by remember { mutableStateOf(emptyList<ContactProfileMatch>()) }
    var contactsLoading by remember { mutableStateOf(false) }
    var contactsError by remember { mutableStateOf<String?>(null) }
    var contactsReloadToken by remember { mutableIntStateOf(0) }
    var requestForDetails by remember { mutableStateOf<LoanRequest?>(null) }
    var requestForSupport by remember { mutableStateOf<LoanRequest?>(null) }
    var fadingRequestNotifications by remember { mutableStateOf(emptySet<String>()) }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasContactsPermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_CONTACTS
                ) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(hasContactsPermission, contactsReloadToken, user.id) {
        if (!shouldReadContacts(hasContactsPermission)) {
            deviceContacts = emptyList()
            contactMatches = emptyList()
            contactsError = null
            return@LaunchedEffect
        }
        contactsLoading = true
        contactsError = null
        try {
            val result = loadDiscoverFeature {
                val loadedContacts = viewModel.readContacts()
                val numbers = loadedContacts.mapNotNull { normalizeKenyanPhone(it.phoneNumber) }
                val matches = batchContactPhoneNumbers(numbers)
                    .flatMap { viewModel.findContactProfiles(it) }
                    .filter { it.profile.id != user.id }
                loadedContacts to matches
            }
            if (result.isSuccess) {
                val (loadedContacts, matches) = result.getOrThrow()
                deviceContacts = loadedContacts
                contactMatches = matches
            } else {
                val exception = result.exceptionOrNull()!!
                logStageFailure("HOME CONTACT MATCHING", exception)
                contactsError = "Kash.ke contacts could not be loaded. Please retry."
            }
        } finally {
            contactsLoading = false
        }
    }

    val connectedMemberIds = remember(connections, user.id) {
        acceptedConnectionsForUser(connections, user.id)
            .mapNotNull { otherConnectionParticipantId(it, user.id) }
            .toSet()
    }
    val mutedMembers by viewModel.repository.mutedMembers.collectAsState()
    val visibleRequests = loanRequests.filter { request ->
        request.borrowerId != user.id && request.borrowerId !in mutedMembers &&
            request.status in setOf(LoanRequestStatus.REQUESTED, LoanRequestStatus.PARTIALLY_FUNDED) &&
            request.remainingAmount > 0 &&
            (request.directRequestTo == user.id || request.borrowerId in connectedMemberIds)
    }
    val aboveLimitRequests = remember(visibleRequests) { visibleRequests.filter { it.aboveLimit } }
    val directRequestSummaries = rememberDirectRequestSummaries(viewModel, aboveLimitRequests)
    val unconnectedKashKeContacts = remember(deviceContacts, contactMatches, connections, user.id) {
        unconnectedDiscoverContacts(buildContactDirectory(deviceContacts, contactMatches), connections, user.id)
    }
    val activeBorrowing = loans.filter { it.borrowerId == user.id && isActiveLoan(it) }
    val activeLending = loans.filter { it.lenderId == user.id && isActiveLoan(it) }
    val publicProfileLookup = rememberPublicProfiles(
        viewModel,
        user.id,
        visibleRequests.map { it.borrowerId } + activeLending.map { it.borrowerId }
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    PrivateTrustGauge(user, viewModel.repository.getCurrentUserId())
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("Borrowing limit"))
                        Spacer(modifier = Modifier.weight(1f))
                        Text(tr("KSh ${user.borrowingLimit.formatKSh()}"), fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row {
                        Text(tr("Available"))
                        Spacer(modifier = Modifier.weight(1f))
                        Text(tr("KSh ${user.availableLimit.formatKSh()}"))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(tr("Your trust grows with how you treat the community."), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(tr("Your Community"), style = MaterialTheme.typography.titleMedium)
                Text(tr("${visibleRequests.size} requests"), color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
            }
        }
        directRequestSummaries.error?.let { message ->
            item {
                Text(message, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = directRequestSummaries.retry) { Text(tr("Retry limit lookup")) }
            }
        }
        if (publicProfileLookup.error != null) {
            item { PublicProfileLookupNotice(publicProfileLookup) }
        }

        if (visibleRequests.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = com.kashke.app.data.networkEmptyMessage(acceptedConnectionsForUser(connections, user.id).size),
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            items(visibleRequests, key = { "community:${it.id}" }) { request ->
                val existingOffer = findPendingSupportOffer(offers, request.id, user.id)
                val limitSummary = directRequestSummaries.summaries[request.id]
                val exceedsBy = limitSummary?.let {
                    (request.requestedAmount - it.availableLimit).coerceAtLeast(0)
                }
                val requestNotification = notifications.firstOrNull {
                    it.type == NotificationType.NEW_LOAN_REQUEST && it.relatedEntityId == request.id
                }
                if (requestNotification != null && !requestNotification.read) {
                    LaunchedEffect(requestNotification.id, requestNotification.read) {
                        delay(5_000)
                        fadingRequestNotifications = fadingRequestNotifications + requestNotification.id
                        delay(500)
                        viewModel.repository.markNotificationRead(requestNotification.id)
                        fadingRequestNotifications = fadingRequestNotifications - requestNotification.id
                    }
                }
                CommunityRequestCard(hasOffer = existingOffer != null) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        val borrowerName = limitSummary?.borrowerName?.takeIf { it.isNotBlank() }
                            ?: publicProfileLookup.name(request.borrowerId)
                        Text(
                            tr(if (existingOffer != null)
                                "You offered support of KSh ${existingOffer.amount.formatKSh()} to $borrowerName"
                            else "$borrowerName needs KSh ${request.requestedAmount.formatKSh()}"),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { onOpenProfile(request.borrowerId) }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        val borrowerProfile = if (request.borrowerId == user.id) user
                            else publicProfileLookup.profiles[request.borrowerId]
                        if (borrowerProfile != null) {
                            ReliabilityBar(borrowerProfile)
                        } else {
                            Text(
                                tr(if (publicProfileLookup.isLoading) "Loading reliability..." else "Reliability unavailable"),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (request.aboveLimit) {
                            Text(
                                exceedsBy?.let { "Personal request • KSh ${it.formatKSh()} above available borrowing limit" }
                                    ?: "Personal request • loading ${publicProfileLookup.name(request.borrowerId)}'s available limit",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        if (existingOffer == null) {
                        Text(tr("For: ${request.purpose}"), modifier = Modifier.padding(top = 4.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tr("Funded: KSh ${request.fundedAmount.formatKSh()} / ${request.requestedAmount.formatKSh()}"))
                            Spacer(modifier = Modifier.weight(1f))
                            Text(tr("${request.remainingAmount.formatKSh()} left"))
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { (request.fundedAmount.toFloat() / request.requestedAmount.toFloat().coerceAtLeast(1f)) },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.secondary
                        )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val offerExists = existingOffer != null
                            if (offerExists) {
                                val offer = requireNotNull(existingOffer)
                                OutlinedButton(
                                    onClick = { editingOffer = offer },
                                    enabled = !operationBusy
                                ) { Text(tr("Edit")) }
                                Button(
                                    onClick = { cancellingOffer = offer },
                                    enabled = !operationBusy
                                ) { Text(tr("Cancel")) }
                            } else {
                                OutlinedButton(
                                    onClick = { requestForDetails = request },
                                    enabled = !operationBusy
                                ) { Text(tr("Details")) }
                                val supportBalance = viewModel.repository.getAvailableToSupport(user.id)
                                Button(
                                    onClick = { requestForSupport = request },
                                    enabled = !operationBusy && minOf(request.remainingAmount, supportBalance) > 0 &&
                                        (!request.aboveLimit || exceedsBy != null)
                                ) { Text(tr("Support")) }
                            }
                        }
                    }
                }
            }
        }

        item {
            val unread = notifications.filter { it.userId == user.id && !it.read }
            if (unread.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(tr("Notifications"), style = MaterialTheme.typography.titleMedium)
                        unread.take(3).forEach { item ->
                            HomeNotificationRow(
                                notification = item,
                                isFading = item.id in fadingRequestNotifications,
                                onClick = {
                                    onOpenNotifications(item)
                                }
                            )
                        }
                    }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(tr("People you may know"), style = MaterialTheme.typography.titleMedium)
                        Text(
                            tr("${unconnectedKashKeContacts.size} contacts on Kash.ke"),
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                        )
                    }
                    TextButton(onClick = onFindMore) { Text(tr("Find more")) }
                }
            }
            when {
                contactsLoading -> item {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        AppLoadingIndicator()
                    }
                }
                contactsError != null -> item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(contactsError!!, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { contactsReloadToken++ }) { Text(tr("Retry")) }
                        }
                    }
                }
                !hasContactsPermission -> item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            tr("Allow contacts access in Discover to see people you know on Kash.ke."),
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
                unconnectedKashKeContacts.isEmpty() -> item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            tr("No unconnected contacts on Kash.ke yet. Find more people in Discover."),
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
            items(unconnectedKashKeContacts, key = { it.profile!!.id }) { entry ->
                val profile = entry.profile!!
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(entry.localName.take(1), fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.size(10.dp))
                        Column(
                            Modifier.weight(1f).clickable { onOpenProfile(profile.id) }
                        ) {
                            Text(profile.name, style = MaterialTheme.typography.titleSmall)
                            entry.phoneNumber?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            Text(profile.username, style = MaterialTheme.typography.bodySmall)
                        }
                        Button(onClick = { viewModel.sendConnectionRequest(profile.id) }) {
                            Text(tr("Connect"))
                        }
                    }
                }
            }

            if (activeBorrowing.isNotEmpty() || activeLending.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(tr("Active loans"), style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = onViewLoans) { Text(tr("View loans")) }
                    }
                }
                if (activeBorrowing.isNotEmpty()) {
                    item { Text(tr("Active borrowing"), style = MaterialTheme.typography.titleSmall) }
                    items(activeBorrowing, key = { "borrowed:${it.id}" }) { loan ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(tr("Outstanding: KSh ${outstandingLoanBalance(loan).formatKSh()}"), style = MaterialTheme.typography.titleMedium)
                                Text(tr("Loan total: KSh ${loan.totalRepayment.formatKSh()} • Paid: KSh ${loan.amountPaid.formatKSh()}"))
                                Text(tr("Due ${loan.dueDate.toDisplayDate()} \u2022 ${loan.status}"))
                                GraceDeadlineDetails(loan)
                            }
                        }
                    }
                }
                if (activeLending.isNotEmpty()) {
                    item { Text(tr("Active lending"), style = MaterialTheme.typography.titleSmall) }
                    items(activeLending, key = { "lent:${it.id}" }) { loan ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    tr("Lent to ${publicProfileLookup.name(loan.borrowerId)}"),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable { onOpenProfile(loan.borrowerId) }
                                )
                                Text(tr("Outstanding: KSh ${outstandingLoanBalance(loan).formatKSh()}"))
                                Text(tr("Due ${loan.dueDate.toDisplayDate()} \u2022 ${loan.status}"))
                            }
                        }
                    }
                }
            }
        }

    editingOffer?.let { offer ->
        EditSupportOfferDialog(viewModel, user, offer, loanRequests) { editingOffer = null }
    }
    cancellingOffer?.let { offer ->
        AgreementActionDialog("Cancel offer", emptyList(), emptyList(), operationBusy,
            onDismiss = { cancellingOffer = null }, submissionError = { viewModel.repository.error.value }) {
            viewModel.repository.cancelLoanOffer(offer.id).also { if (it) cancellingOffer = null }
        }
    }
    requestForDetails?.let { request ->
        LoanRequestDetailsDialog(
            request = request,
            borrowerName = directRequestSummaries.summaries[request.id]?.borrowerName
                ?.takeIf { it.isNotBlank() } ?: publicProfileLookup.name(request.borrowerId),
            aboveLimitExceedsBy = directRequestSummaries.summaries[request.id]?.let {
                (request.requestedAmount - it.availableLimit).coerceAtLeast(0)
            },
            onDismiss = { requestForDetails = null }
        )
    }
    requestForSupport?.let { request ->
        val available = viewModel.repository.getAvailableToSupport(user.id)
        val summary = directRequestSummaries.summaries[request.id]
        val exceedsBy = summary?.let { (request.requestedAmount - it.availableLimit).coerceAtLeast(0) }
        SupportOfferDialog(
            request = request,
            borrowerName = summary?.borrowerName ?: publicProfileLookup.name(request.borrowerId),
            borrowerReliability = publicProfileLookup.profiles[request.borrowerId]?.reliabilityScore,
            availableBalance = available,
            maxAmount = minOf(request.remainingAmount, available),
            maxPeriodDays = request.preferredPeriodDays,
            aboveLimitExceedsBy = exceedsBy,
            submissionError = { viewModel.repository.error.value },
            onDismiss = { requestForSupport = null },
            onSubmit = { amount, interestRate, periodDays, approveAboveLimit ->
                viewModel.makeOffer(request.id, amount, interestRate, periodDays, approveAboveLimit).also { offered ->
                    if (offered) requestForSupport = null
                }
            }
        )
    }
}

@Composable
private fun HomeNotificationRow(
    notification: Notification,
    isFading: Boolean,
    onClick: () -> Unit
) {
    AnimatedVisibility(visible = !isFading, exit = fadeOut(tween(500))) {
        TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(tr("${notification.title}: ${notification.message}"))
                Text(notification.createdAt.toDisplayTimestamp(), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun NotificationsPage(
    viewModel: KashKeViewModel,
    user: User,
    onOpenConnectionRequests: () -> Unit,
    onOpenProfile: (String) -> Unit,
    notificationTarget: NotificationTarget? = null,
    onNotificationConsumed: () -> Unit = {}
) {
    val notifications by viewModel.repository.notifications.collectAsState()
    val userNotifications = notifications
        .filter { it.userId == user.id }
        .sortedWith(compareBy<Notification> { it.read }.thenByDescending { it.createdAt })
    val mutedMembers by viewModel.repository.mutedMembers.collectAsState()
    val pendingConnectionRequests = viewModel.repository.connections.collectAsState().value.count {
        it.recipientId == user.id && it.status == ConnectionStatus.PENDING
    }
    val requests by viewModel.repository.loanRequests.collectAsState()
    val connections by viewModel.repository.connections.collectAsState()
    val loans by viewModel.repository.loans.collectAsState()

    val offers by viewModel.repository.loanOffers.collectAsState()
    var openedNotification by remember { mutableStateOf<Notification?>(null) }
    var requestForSupportId by remember { mutableStateOf<String?>(null) }
    val operationBusy by viewModel.repository.busy.collectAsState()
    val openedRequest = requests.firstOrNull { it.id == openedNotification?.relatedEntityId || it.id == requestForSupportId }
    val directRequestSummaries = rememberDirectRequestSummaries(viewModel, listOfNotNull(openedRequest))
    val publicProfileLookup = rememberPublicProfiles(viewModel, user.id, listOfNotNull(openedRequest?.borrowerId))
    val openNotification: (Notification) -> Unit = { notification ->
        viewModel.repository.markNotificationRead(notification.id)
        val connection = connections.firstOrNull { it.id == notification.relatedEntityId }
        if (connection != null) {
            if (connection.status == ConnectionStatus.PENDING && connection.recipientId == user.id) {
                onOpenConnectionRequests()
            } else {
                onOpenProfile(if (connection.requesterId == user.id) connection.recipientId else connection.requesterId)
            }
        } else {
            openedNotification = notification
        }
    }
    val listState = rememberLazyListState()
    var highlightedId by rememberSaveable { mutableStateOf<String?>(null) }
    var unavailable by remember { mutableStateOf(false) }
    var retryToken by remember { mutableIntStateOf(0) }
    LaunchedEffect(notificationTarget, retryToken) {
        if (notificationTarget?.userId != user.id) return@LaunchedEffect
        unavailable = false
        val refreshed = viewModel.repository.refreshNotificationsForTap()
        val rows = viewModel.repository.notifications.value.filter { it.userId == user.id }
        val target = ownedNotification(notificationTarget, user.id, rows)
        if (!refreshed || target == null) {
            unavailable = true
            highlightedId = null
            listState.scrollToItem(0)
            return@LaunchedEffect
        }
        if (viewModel.repository.getCurrentUserId() != user.id) { onNotificationConsumed(); return@LaunchedEffect }
        highlightedId = target.id
        val sorted = rows.sortedWith(compareBy<Notification> { it.read }.thenByDescending { it.createdAt })
        androidx.compose.runtime.withFrameNanos { }
        listState.scrollToItem(sorted.indexOfFirst { it.id == target.id } + 1)
        viewModel.repository.markNotificationRead(target.id)
        // Follow the record when marking it read moves it in the unread-first list.
        kotlinx.coroutines.withTimeoutOrNull(5000) {
            viewModel.repository.notifications.first { entries -> entries.any { it.id == target.id && it.read } }
        }
        val reordered = viewModel.repository.notifications.value.filter { it.userId == user.id }
            .sortedWith(compareBy<Notification> { it.read }.thenByDescending { it.createdAt })
        val index = reordered.indexOfFirst { it.id == target.id }
        androidx.compose.runtime.withFrameNanos { }
        if (index >= 0) listState.scrollToItem(index + 1)
        onNotificationConsumed()
        openNotification(target)
    }
    LaunchedEffect(highlightedId) { if (highlightedId != null) { delay(3500); highlightedId = null } }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            OutlinedButton(onClick = onOpenConnectionRequests, modifier = Modifier.fillMaxWidth()) {
                Text(tr("Connection requests"))
                if (pendingConnectionRequests > 0) {
                    Spacer(Modifier.width(8.dp))
                    CountBadge(pendingConnectionRequests)
                }
            }
            if (unavailable) {
                Text(tr("This notification is unavailable or could not be loaded."))
                TextButton(onClick = { retryToken++ }) { Text(tr("Retry")) }
            }
        }
        if (userNotifications.isEmpty()) {
            item { Text(tr("You have no notifications."), style = MaterialTheme.typography.bodyLarge) }
        }
        items(userNotifications, key = { it.id }) { notification ->
            val background = if (notification.id == highlightedId) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else if (notification.read) Color.Transparent else MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)
            val foreground = MaterialTheme.colorScheme.onSurface.copy(alpha = if (notification.read) 0.58f else 1f)

            val offer = notification.relatedEntityId?.let { relatedId -> offers.firstOrNull { it.id == relatedId } }
            val relatedRequest = notification.relatedEntityId?.let { relatedId ->
                requests.firstOrNull { it.id == relatedId }
            }
            val relatedConnection = notification.relatedEntityId?.let { relatedId ->
                connections.firstOrNull { it.id == relatedId }
            }
            val relatedLoan = notification.relatedEntityId?.let { relatedId ->
                loans.firstOrNull { it.id == relatedId }
            }
            Card(
                modifier = Modifier.fillMaxWidth().clickable {
                    openNotification(notification)
                },
                colors = CardDefaults.cardColors(containerColor = background)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(notification.title, color = foreground, fontWeight = FontWeight.SemiBold)
                    Text(notification.message, color = foreground)
                    Text(notification.createdAt.toDisplayTimestamp(), style = MaterialTheme.typography.bodySmall, color = foreground.copy(alpha = 0.7f))
                    TextButton(onClick = { openNotification(notification) }) {
                        Text(tr(when {
                            offer != null -> "View offer"
                            relatedRequest != null -> "View support request"
                            relatedLoan != null -> "View repayment"
                            relatedConnection != null -> "View connection"
                            else -> "View details"
                        }))
                    }
                    if (offer != null && offer.borrowerId == user.id && offer.status == LoanOfferStatus.OFFERED &&
                        notification.type in setOf(NotificationType.LOAN_OFFER, NotificationType.LOAN_OFFER_UPDATED)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                            Button(onClick = {
                                viewModel.acceptOffer(offer)
                                viewModel.repository.markNotificationRead(notification.id)
                            }) {
                                Text(tr("Accept"))
                            }
                            OutlinedButton(onClick = {
                                viewModel.rejectOffer(offer.id)
                                viewModel.repository.markNotificationRead(notification.id)
                            }) {
                                Text(tr("Reject"))
                            }
                        }
                    }
                }
            }
        }
    }
    openedNotification?.let { notification ->
        val offer = offers.firstOrNull { it.id == notification.relatedEntityId }
        val request = requests.firstOrNull { it.id == notification.relatedEntityId }
        val loan = loans.firstOrNull { it.id == notification.relatedEntityId }
        AlertDialog(
            onDismissRequest = { openedNotification = null },
            title = { Text(tr(when {
                offer != null -> "Support offer"
                request != null -> "Support request"
                loan != null -> "Repayment details"
                else -> notification.title
            })) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(notification.message)
                    when {
                        offer != null -> {
                            Text(tr("Amount: KSh ${offer.amount.formatKSh()}"))
                            Text(tr("Interest: ${offer.interestRate}%"))
                            Text(tr("Repayment period: ${offer.repaymentPeriodDays} days"))
                            Text(tr("Total repayment: KSh ${offer.totalRepayment.formatKSh()}"))
                            Text(tr("Status: ${offer.status}"))
                            requests.firstOrNull { it.id == offer.loanRequestId }?.let {
                                Text(tr("Purpose: ${it.purpose}"))
                                Text(it.description)
                            }
                            if (offer.borrowerId == user.id && offer.status == LoanOfferStatus.OFFERED) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { viewModel.acceptOffer(offer); openedNotification = null }) { Text(tr("Accept")) }
                                    OutlinedButton(onClick = { viewModel.rejectOffer(offer.id); openedNotification = null }) { Text(tr("Reject")) }
                                }
                            }
                        }
                        request != null -> {
                            Text(tr("Purpose: ${request.purpose}"))
                            Text(request.description)
                            Text(tr("Requested: KSh ${request.requestedAmount.formatKSh()}"))
                            Text(tr("Funded: KSh ${request.fundedAmount.formatKSh()}"))
                            Text(tr("Remaining: KSh ${request.remainingAmount.formatKSh()}"))
                            Text(tr("Repayment period: ${request.preferredPeriodDays} days"))
                            Text(tr("Status: ${request.status}"))
                            val active = request.status in setOf(LoanRequestStatus.REQUESTED, LoanRequestStatus.PARTIALLY_FUNDED) &&
                                request.remainingAmount > 0
                            val connected = acceptedConnectionsForUser(connections, user.id).any {
                                otherConnectionParticipantId(it, user.id) == request.borrowerId
                            }
                            if (active && request.borrowerId != user.id && request.borrowerId !in mutedMembers && (request.directRequestTo == user.id || connected)) {
                                val pendingOffer = findPendingSupportOffer(offers, request.id, user.id)
                                if (pendingOffer != null) {
                                    Text(tr("You already have a pending offer for this request."))
                                } else {
                                    directRequestSummaries.error?.let { message ->
                                        Text(message, color = MaterialTheme.colorScheme.error)
                                        TextButton(onClick = directRequestSummaries.retry) { Text(tr("Retry limit lookup")) }
                                    }
                                    val available = viewModel.repository.getAvailableToSupport(user.id)
                                    if (available <= 0) Text(tr("No balance available to support this request."))
                                    Button(
                                        onClick = { requestForSupportId = request.id; openedNotification = null },
                                        enabled = !operationBusy && available > 0 &&
                                            (!request.aboveLimit || directRequestSummaries.summaries[request.id] != null)
                                    ) { Text(tr("Offer support")) }
                                }
                            }
                        }
                        loan != null -> {
                            Text(tr("Principal: KSh ${loan.principal.formatKSh()}"))
                            Text(tr("Interest: KSh ${loan.interestAmount.formatKSh()}"))
                            Text(tr("Total repayment: KSh ${loan.totalRepayment.formatKSh()}"))
                            Text(tr("Amount paid: KSh ${loan.amountPaid.formatKSh()}"))
                            Text(tr("Outstanding: KSh ${outstandingLoanBalance(loan).formatKSh()}"))
                            Text(tr("Due: ${loan.dueDate.toDisplayDate()}"))
                            Text(tr("Status: ${loan.status}"))
                            GraceDeadlineDetails(loan)
                        }
                        notification.relatedEntityId != null -> Text(tr("The linked record is no longer available."))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { openedNotification = null }) { Text(tr("Close")) } }
        )
    }
    requests.firstOrNull { it.id == requestForSupportId }?.let { request ->
        val available = viewModel.repository.getAvailableToSupport(user.id)
        val summary = directRequestSummaries.summaries[request.id]
        SupportOfferDialog(
            request = request,
            borrowerName = summary?.borrowerName ?: publicProfileLookup.name(request.borrowerId),
            borrowerReliability = publicProfileLookup.profiles[request.borrowerId]?.reliabilityScore,
            availableBalance = available,
            maxAmount = minOf(request.remainingAmount, available),
            maxPeriodDays = request.preferredPeriodDays,
            aboveLimitExceedsBy = summary?.let { (request.requestedAmount - it.availableLimit).coerceAtLeast(0) },
            submissionError = { viewModel.repository.error.value },
            onDismiss = { requestForSupportId = null },
            onSubmit = { amount, rate, days, approveAboveLimit ->
                viewModel.makeOffer(request.id, amount, rate, days, approveAboveLimit).also { offered ->
                    if (offered) requestForSupportId = null
                }
            }
        )
    }
}

@Composable
fun ConnectionRequestsPage(viewModel: KashKeViewModel, user: User, onOpenProfile: (String) -> Unit) {
    val connections by viewModel.repository.connections.collectAsState()
    val incoming = connections.filter {
        it.recipientId == user.id && it.status == ConnectionStatus.PENDING
    }
    val publicProfileLookup = rememberPublicProfiles(
        viewModel,
        user.id,
        incoming.map { it.requesterId }
    )

    DiscoverTab(viewModel, user, onOpenProfile, contactsOnly = true, header = {
        item { Text(tr("Connection requests"), style = MaterialTheme.typography.titleMedium) }
        if (publicProfileLookup.error != null) {
            item { PublicProfileLookupNotice(publicProfileLookup) }
        }
        if (incoming.isEmpty()) {
            item { Text(tr("No pending connection requests."), style = MaterialTheme.typography.bodyLarge) }
        }
        items(incoming) { connection ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        publicProfileLookup.name(connection.requesterId),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onOpenProfile(connection.requesterId) }
                    )
                    Text(tr("Phone unavailable"), color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
                    Text(tr("Requested ${connection.createdAt}"), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                        Button(onClick = { viewModel.acceptConnectionRequest(connection.id) }) {
                            Text(tr("Accept"))
                        }
                        OutlinedButton(onClick = { viewModel.rejectConnectionRequest(connection.id) }) {
                            Text(tr("Reject"))
                        }
                    }
                }
            }
        }
    })
}

@Composable
fun ConnectionsPage(viewModel: KashKeViewModel, user: User, onOpenProfile: (String) -> Unit) {
    val connections by viewModel.repository.connections.collectAsState()
    val acceptedConnections = remember(connections, user.id) {
        acceptedConnectionsForUser(connections, user.id)
    }
    val connectedProfileIds = remember(acceptedConnections, user.id) {
        acceptedConnections.mapNotNull { otherConnectionParticipantId(it, user.id) }.distinct()
    }
    var connectedProfiles by remember(user.id) { mutableStateOf<Map<String, User>>(emptyMap()) }
    var profileLookupError by remember(user.id) { mutableStateOf<String?>(null) }
    var profileRetryToken by remember(user.id) { mutableIntStateOf(0) }

    LaunchedEffect(user.id, connectedProfileIds, profileRetryToken) {
        if (connectedProfileIds.isEmpty()) {
            connectedProfiles = emptyMap()
            profileLookupError = null
            return@LaunchedEffect
        }
        connectedProfiles = emptyMap()
        profileLookupError = null
        try {
            connectedProfiles = viewModel.repository.getPublicProfiles(connectedProfileIds).associateBy { it.id }
        } catch (exception: CancellationException) {
            throw exception
        }
        catch (exception: Exception) {
            profileLookupError = "Connection profiles couldn't be loaded. Check your connection and retry."
        }
    }
    val unresolvedCount = acceptedConnections.count { connection ->
        otherConnectionParticipantId(connection, user.id)?.let { it !in connectedProfiles } ?: true
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        profileLookupError?.let { message ->
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(message, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { profileRetryToken++ }) { Text(tr("Retry")) }
                    }
                }
            }
        }
        if (acceptedConnections.isEmpty()) {
            item { Text(tr("You have no connections yet."), style = MaterialTheme.typography.bodyLarge) }
        }
        if (acceptedConnections.isNotEmpty() && unresolvedCount > 0 && profileLookupError == null) {
            item {
                Text(
                    tr("$unresolvedCount connection profile${if (unresolvedCount == 1) "" else "s"} couldn't be found."),
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                )
            }
        }
        items(acceptedConnections, key = { it.id }) { connection ->
            val connectedUserId = otherConnectionParticipantId(connection, user.id)
            val person = connectedUserId?.let(connectedProfiles::get)
            Card(
                modifier = Modifier.fillMaxWidth().clickable(enabled = connectedUserId != null) {
                    connectedUserId?.let(onOpenProfile)
                }
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(42.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(person?.name?.take(1) ?: "?", fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.size(12.dp))
                    Column {
                        if (person != null) {
                            Text(person.name, style = MaterialTheme.typography.titleMedium)
                            Text(person.username, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
                        } else {
                            Text(tr("Name unavailable"), style = MaterialTheme.typography.titleMedium)
                            Text(
                                tr("Accepted connection • ID ${connectedUserId?.take(8) ?: "unknown"}"),
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DiscoverTab(viewModel: KashKeViewModel, user: User, onOpenProfile: (String) -> Unit, contactsOnly: Boolean = false, header: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {}) {
    val connections by viewModel.repository.connections.collectAsState()
    val permissionPrompted by viewModel.contactsPermissionPrompted.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasContactsPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED)
    }
    var deviceContacts by remember { mutableStateOf(emptyList<DevicePhoneContact>()) }
    var contactMatches by remember { mutableStateOf(emptyList<ContactProfileMatch>()) }
    var contactsLoading by remember { mutableStateOf(false) }
    var contactsError by remember { mutableStateOf<String?>(null) }
    var contactMatchError by remember { mutableStateOf<String?>(null) }
    var contactsReloadToken by remember { mutableIntStateOf(0) }
    var searchResults by remember { mutableStateOf(emptyList<User>()) }
    var searchLoading by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasContactsPermission = granted
        contactsError = if (granted) null else "Contacts access is off. Search still works without it."
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasContactsPermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_CONTACTS
                ) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(hasContactsPermission, permissionPrompted) {
        if (!hasContactsPermission && !permissionPrompted) {
            viewModel.markContactsPermissionPrompted()
            permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    LaunchedEffect(hasContactsPermission, contactsReloadToken) {
        if (!shouldReadContacts(hasContactsPermission)) {
            deviceContacts = emptyList()
            contactMatches = emptyList()
            contactMatchError = null
            return@LaunchedEffect
        }
        contactsLoading = true
        contactsError = null
        contactMatchError = null
        val loadedContacts = try {
            viewModel.readContacts()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logStageFailure("DISCOVER LOCAL CONTACT QUERY", exception)
            contactsError = "Could not read contacts right now. Search is still available."
            contactsLoading = false
            return@LaunchedEffect
        }
        deviceContacts = loadedContacts
        contactMatches = emptyList()
        try {
            val matchResult = loadDiscoverFeature {
                val numbers = loadedContacts.mapNotNull { normalizeKenyanPhone(it.phoneNumber) }
                batchContactPhoneNumbers(numbers).flatMap { viewModel.findContactProfiles(it) }
            }
            if (matchResult.isSuccess) {
                contactMatches = matchResult.getOrThrow().filter { it.profile.id != user.id }
            } else {
                logStageFailure("DISCOVER CONTACT MATCH RPC", matchResult.exceptionOrNull()!!)
                contactMatchError = "Contacts loaded, but Kash.ke matching is unavailable. Retry."
            }
        } finally {
            contactsLoading = false
        }
    }

    LaunchedEffect(query) {
        searchResults = emptyList()
        searchError = null
        if (query.isBlank()) {
            searchLoading = false
            return@LaunchedEffect
        }
        delay(350)
        searchLoading = true
        try {
            val searchResult = loadDiscoverFeature { viewModel.searchPublicProfiles(query) }
            if (searchResult.isSuccess) {
                searchResults = searchResult.getOrThrow().filter { it.id != user.id }
            } else {
                logStageFailure("DISCOVER PUBLIC DIRECTORY SEARCH", searchResult.exceptionOrNull()!!)
                searchError = "Search is unavailable right now. Please try again."
            }
        } finally {
            searchLoading = false
        }
    }

    val contactEntries = remember(deviceContacts, contactMatches) {
        buildContactDirectory(deviceContacts, contactMatches)
    }
    val visibleEntries = remember(query, contactEntries, searchResults) {
        searchDiscoverEntries(query, contactEntries, searchResults)
    }
    var connectedExpanded by remember { mutableStateOf(false) }
    var pendingExpanded by remember { mutableStateOf(false) }
    var contactsExpanded by remember { mutableStateOf(true) }

    val sectionForEntry: (DiscoverContactEntry) -> DiscoverAction = { entry ->
        val profile = entry.profile
        val existing = connections.firstOrNull {
            profile != null &&
                ((it.requesterId == user.id && it.recipientId == profile.id) ||
                    (it.recipientId == user.id && it.requesterId == profile.id))
        }
        discoverAction(profile != null, existing?.status)
    }

    val connectedEntries = remember(visibleEntries, connections, user.id, query) {
        if (query.isBlank()) visibleEntries.filter { sectionForEntry(it) == DiscoverAction.CONNECTED } else emptyList()
    }
    val pendingEntries = remember(visibleEntries, connections, user.id, query) {
        if (query.isBlank()) visibleEntries.filter { sectionForEntry(it) == DiscoverAction.PENDING } else emptyList()
    }
    val contactEntriesNotConnectedOrPending = remember(visibleEntries, connections, user.id, query) {
        if (query.isBlank()) visibleEntries.filter {
            sectionForEntry(it) !in setOf(DiscoverAction.CONNECTED, DiscoverAction.PENDING)
        } else emptyList()
    }

    val renderDiscoverCard: @Composable (DiscoverContactEntry) -> Unit = { entry ->
        val profile = entry.profile
        val action = sectionForEntry(entry)
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f).clickable(enabled = profile != null) {
                        profile?.let { onOpenProfile(it.id) }
                    },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(entry.localName.take(1), fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(profile?.name ?: entry.localName, style = MaterialTheme.typography.titleMedium)
                        entry.phoneNumber?.let { phoneNumber ->
                            Text(
                                phoneNumber,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                            )
                        }
                        if (profile != null) {
                            Text(profile.username, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
                            ReliabilityBar(profile)
                        }
                    }
                }
                if (query.isBlank() && profile == null && (contactsLoading || contactMatchError != null)) {
                    if (contactMatchError != null) {
                        TextButton(onClick = { contactsReloadToken++ }) { Text(tr("Retry")) }
                    } else {
                        Text(tr("Checking"))
                    }
                } else when (action) {
                    DiscoverAction.CONNECTED -> Text(tr("Connected"))
                    DiscoverAction.PENDING -> Text(tr("Pending"))
                    DiscoverAction.CONNECT -> Button(onClick = { profile?.let { viewModel.sendConnectionRequest(it.id) } }) {
                        Text(tr("Connect"))
                    }
                    DiscoverAction.INVITE -> OutlinedButton(onClick = {
                        launchDiscoverInvite(context, entry)
                    }) { Text(tr("Invite")) }
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        if (!contactsOnly) OutlinedTextField(
            value = query,
            onValueChange = { candidate -> if (candidate.length <= 80 && candidate.all { it.isLetterOrDigit() || it in " ._@+" }) query = candidate },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(tr("Search members and contacts")) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true
        )
        Spacer(modifier = Modifier.height(12.dp))
        LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            header()
            if (query.isBlank() && !hasContactsPermission) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(tr("Allow contacts access to see which people in your address book use Kash.ke. Contact names stay on this device."))
                            OutlinedButton(onClick = {
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.fromParts("package", context.packageName, null)
                                    }
                                )
                            }) { Text(tr("Open app settings")) }
                        }
                    }
                }
            }
            if (query.isBlank() && contactsLoading || query.isNotBlank() && searchLoading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                        AppLoadingIndicator()
                    }
                }
            }
            val visibleError = if (query.isBlank()) contactsError ?: contactMatchError else searchError
            visibleError?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            if (query.isBlank() && contactMatchError != null) {
                item {
                    TextButton(onClick = { contactsReloadToken++ }) { Text(tr("Retry contact matching")) }
                }
            }
            if (!contactsLoading && !searchLoading && visibleEntries.isEmpty() && visibleError == null) {
                item {
                    Text(
                        tr(if (query.isBlank()) "No phone contacts with Kenyan mobile numbers were found."
                        else "No Kash.ke members found."),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
            if (query.isBlank()) {
                listOf(
                    Triple("Connected", connectedEntries, connectedExpanded) to { connectedExpanded = !connectedExpanded },
                    Triple("Pending", pendingEntries, pendingExpanded) to { pendingExpanded = !pendingExpanded },
                    Triple("Contacts", contactEntriesNotConnectedOrPending, contactsExpanded) to {
                        contactsExpanded = !contactsExpanded
                    }
                ).filter { !contactsOnly || it.first.first == "Contacts" }.forEach { (section, onToggle) ->
                    val (title, entries, expanded) = section
                    item(key = "section:$title") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onToggle() }
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                tr("$title (${entries.size})"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                                contentDescription = if (expanded) "Collapse $title" else "Expand $title"
                            )
                        }
                    }
                    if (expanded) {
                        items(entries, key = { entry ->
                            "${title}:${entry.profile?.id ?: entry.contactId}:${entry.localName}"
                        }) { entry ->
                            renderDiscoverCard(entry)
                        }
                    }
                }
            } else {
                items(visibleEntries, key = { entry ->
                    entry.profile?.let { "profile:${it.id}" } ?: "contact:${entry.contactId}"
                }) { entry ->
                    renderDiscoverCard(entry)
                }
            }
        }
    }
}

private fun launchDiscoverInvite(context: android.content.Context, entry: DiscoverContactEntry) {
    val normalized = normalizeKenyanPhone(entry.phoneNumber)
    if (normalized == null) {
        Toast.makeText(context, "This contact has no usable phone number.", Toast.LENGTH_SHORT).show()
        return
    }

    val message = buildDiscoverInviteMessage(entry.localName)
    val whatsappLink = Uri.parse("https://wa.me/$normalized?text=${Uri.encode(message)}")
    for (packageName in listOf("com.whatsapp", "com.whatsapp.w4b")) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, whatsappLink).apply {
                    setPackage(packageName)
                }
            )
            return
        } catch (exception: ActivityNotFoundException) {
            android.util.Log.i("KashKeDiscover", "WhatsApp invite handler unavailable: $packageName")
        } catch (exception: SecurityException) {
            android.util.Log.w("KashKeDiscover", "Could not open WhatsApp invite handler: $packageName", exception)
        }
    }

    try {
        context.startActivity(
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$normalized")).apply {
                putExtra("sms_body", message)
            }
        )
    } catch (exception: ActivityNotFoundException) {
        android.util.Log.w("KashKeDiscover", "No SMS invite handler is available.", exception)
        Toast.makeText(context, "WhatsApp and SMS are unavailable on this device.", Toast.LENGTH_LONG).show()
    } catch (exception: SecurityException) {
        android.util.Log.w("KashKeDiscover", "Could not open SMS invite handler.", exception)
        Toast.makeText(context, "Could not open the SMS app.", Toast.LENGTH_LONG).show()
    }
}

@Composable
private fun PublicProfilePage(viewModel: KashKeViewModel, profileId: String) {
    val mutedMembers by viewModel.repository.mutedMembers.collectAsState()
    var confirmDisconnect by remember { mutableStateOf(false) }
    if (confirmDisconnect) AlertDialog(onDismissRequest = { confirmDisconnect = false }, title = { Text(tr("Disconnect member?")) }, text = { Text(tr("Existing agreements and repayment obligations remain active.")) }, confirmButton = { TextButton(onClick = { viewModel.repository.disconnectMember(profileId); confirmDisconnect = false }) { Text(tr("Disconnect")) } }, dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text(tr("Cancel")) } })
    val currentUser by viewModel.currentUser.collectAsState()
    val connections by viewModel.repository.connections.collectAsState()
    // Account-scoped state prevents a prior viewer's asynchronous response from
    // surviving logout/account switching. MemberProfile has no private fields.
    var profile by remember(currentUser?.id, profileId) { mutableStateOf<MemberProfile?>(null) }
    var loading by remember(currentUser?.id, profileId) { mutableStateOf(true) }
    var failed by remember(currentUser?.id, profileId) { mutableStateOf(false) }
    var retryToken by remember(currentUser?.id, profileId) { mutableIntStateOf(0) }
    var showDirectRequest by remember(currentUser?.id, profileId) { mutableStateOf(false) }

    LaunchedEffect(currentUser?.id, profileId, retryToken) {
        if (currentUser == null) return@LaunchedEffect
        do {
            try {
                profile = viewModel.loadMemberProfileDetails(profileId)
                failed = false
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                failed = true
            } finally {
                loading = false
            }
            delay(15_000)
        } while (true)
    }

    when {
        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AppLoadingIndicator()
        }
        failed && profile == null -> Column(Modifier.padding(20.dp)) {
            Text(tr("This member's profile could not be loaded."))
            TextButton(onClick = { retryToken++ }) { Text(tr("Retry")) }
        }
        profile == null -> Text(tr("This profile is no longer available."), modifier = Modifier.padding(20.dp))
        else -> {
            val member = profile!!
            val connection = connections.firstOrNull {
                (it.requesterId == currentUser?.id && it.recipientId == member.id) ||
                    (it.recipientId == currentUser?.id && it.requesterId == member.id)
            }
            val connected = connection?.status == ConnectionStatus.ACCEPTED
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (failed) {
                    item {
                        Text(tr("Could not refresh this profile. Showing the last available details."), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { retryToken++ }) { Text(tr("Retry")) }
                    }
                }
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(member.name, style = MaterialTheme.typography.headlineMedium)
                            Text(tr("@${member.username.removePrefix("@")}"), color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
                            Text(member.phone.takeIf { it.isNotBlank() } ?: "Phone number not provided")
                            if (member.bio.isNotBlank()) Text(member.bio)
                            if (member.location.isNotBlank()) Text(member.location)
                            ReliabilityBar(member.reliabilityScore, member.reliabilitySampleSize)
                            Text(tr("Member since: ${member.memberSince}"))
                            if (currentUser != null && currentUser?.id != member.id) {
                                OutlinedButton(onClick = { viewModel.repository.muteMember(member.id, member.id !in mutedMembers) }) { Text(tr(if (member.id in mutedMembers) "Unmute support requests" else "Mute support requests")) }
                                if (connected) TextButton(onClick = { confirmDisconnect = true }) { Text(tr("Disconnect")) }
                            }
                            when {
                                currentUser?.id == member.id -> Text(tr("This is how your profile appears to other members."))
                                connected -> Button(
                                    onClick = { showDirectRequest = true },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(tr("Request support directly"))
                                }
                                connection?.status == ConnectionStatus.PENDING -> Text(tr("Connection request pending"))
                                connection == null || connection.status in setOf(ConnectionStatus.REMOVED, ConnectionStatus.REJECTED) -> Button(
                                    onClick = { viewModel.sendConnectionRequest(member.id) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(tr("Connect to request support"))
                                }
                                else -> Text(tr("This connection request was declined."))
                            }
                        }
                    }
                }
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(tr("Current borrowing"), style = MaterialTheme.typography.titleMedium)
                            Text(tr("Current debts: KSh ${member.currentDebtTotal.formatKSh()} across ${member.currentDebtCount} agreements"))
                            Text(tr("Allowed borrowing limit: KSh ${member.borrowingLimit.formatKSh()}"))
                            Text(tr("Available to borrow: KSh ${member.availableLimit.formatKSh()}"))
                            Text(tr("Debt totals include unpaid principal and interest."), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(tr("Borrowing history"), style = MaterialTheme.typography.titleMedium)
                            Text(tr("Previous transactions: ${member.previousTransactionCount}"))
                            Text(tr("Loans borrowed: ${member.borrowingCount}"))
                            Text(tr("Fully repaid: ${member.completedBorrowingCount}"))
                            if (member.borrowingCount == 0) {
                                Text(tr("No borrowing history yet."))
                            } else {
                                Text(tr("Average borrowed: ${member.averageBorrowed?.let { "KSh ${it.formatKSh()}" } ?: "Unavailable"}"), fontWeight = FontWeight.SemiBold)
                                Text(tr("Borrowing range: ${member.minimumBorrowed?.let { "KSh ${it.formatKSh()}" } ?: "Unavailable"} - ${member.maximumBorrowed?.let { "KSh ${it.formatKSh()}" } ?: "Unavailable"}"))
                                Text(tr("Amounts reflect the principal of each funded loan agreement."), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            if (showDirectRequest && currentUser != null && connected) {
                DirectRequestDialog(
                    availableLimit = currentUser!!.availableLimit,
                    recipientName = member.name,
                    onDismiss = { showDirectRequest = false },
                    onSubmit = { amount, purpose, description, periodDays, aboveLimit ->
                        viewModel.createRequest(
                            amount,
                            purpose,
                            description,
                            periodDays,
                            directRequestTo = member.id,
                            aboveLimit = aboveLimit
                        ).also { sent ->
                            if (sent) showDirectRequest = false
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun LoansTab(viewModel: KashKeViewModel, user: User, onOpenProfile: (String) -> Unit) {
    val actionScope = rememberCoroutineScope()
    val operationBusy by viewModel.repository.busy.collectAsState()
    var graceLoan by remember { mutableStateOf<LoanAgreement?>(null) }
    var editingOffer by remember { mutableStateOf<LoanOffer?>(null) }
    var cancellingOffer by remember { mutableStateOf<LoanOffer?>(null) }
    var policyNow by remember { mutableStateOf(java.time.Instant.now()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); policyNow = java.time.Instant.now() } }
    val loanRequests by viewModel.repository.loanRequests.collectAsState()
    val offers by viewModel.repository.loanOffers.collectAsState()
    val loans by viewModel.repository.loans.collectAsState()
    val directRequests = remember(loanRequests, user.id) {
        loanRequests.filter {
            it.directRequestTo == user.id &&
                it.status in setOf(LoanRequestStatus.REQUESTED, LoanRequestStatus.PARTIALLY_FUNDED) &&
                it.remainingAmount > 0
        }
    }
    val directRequestSummaries = rememberDirectRequestSummaries(viewModel, directRequests.filter { it.aboveLimit })
    val relatedProfileIds = remember(loanRequests, offers, loans, user.id) {
        buildList {
            loanRequests.filter { it.directRequestTo == user.id }.forEach { add(it.borrowerId) }
            offers.filter { it.borrowerId == user.id || it.lenderId == user.id }.forEach {
                add(if (it.borrowerId == user.id) it.lenderId else it.borrowerId)
            }
            loans.filter { it.borrowerId == user.id || it.lenderId == user.id }.forEach {
                add(if (it.borrowerId == user.id) it.lenderId else it.borrowerId)
            }
        }.filter { it != user.id }.distinct().sorted()
    }
    val publicProfileLookup = rememberPublicProfiles(viewModel, user.id, relatedProfileIds)
    var selectedTab by remember { mutableIntStateOf(0) }
    var showingHistory by remember { mutableStateOf(false) }
    var offerForDirectRequest by remember { mutableStateOf<LoanRequest?>(null) }
    var offerForTerms by remember { mutableStateOf<LoanOffer?>(null) }
    var loanForPartialPayment by remember { mutableStateOf<com.kashke.app.data.LoanAgreement?>(null) }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        DebtOverviewPanel(loans, user.id, publicProfileLookup::name, onOpenProfile) { owedToUser ->
            selectedTab = if (owedToUser) 1 else 0
            showingHistory = false
        }
        listOf("Active borrowing", "Active lending", "Borrowing history", "Lending history")
            .withIndex().toList().chunked(2).forEach { tabs ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tabs.forEach { (index, title) ->
                        FilterChip(
                            modifier = Modifier.weight(1f),
                            selected = selectedTab == index % 2 && showingHistory == (index >= 2),
                            onClick = { selectedTab = index % 2; showingHistory = index >= 2 },
                            label = { Text(tr(title)) }
                        )
                    }
                }
            }
        Spacer(modifier = Modifier.height(12.dp))
        if (publicProfileLookup.error != null) {
            PublicProfileLookupNotice(publicProfileLookup)
        }

        when (selectedTab) {
            0 -> {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!showingHistory) {
                        items(loanRequests.filter { it.borrowerId == user.id }) { request ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(tr("${request.purpose}"), style = MaterialTheme.typography.titleMedium)
                                    Text(tr("KSh ${request.requestedAmount.formatKSh()} • ${request.status}"))
                                    Text(tr("Funded: KSh ${request.fundedAmount.formatKSh()} • Remaining: KSh ${request.remainingAmount.formatKSh()}"))
                                }
                            }
                        }
                    }
                    val borrowedLoans = loans.filter {
                        it.borrowerId == user.id && if (showingHistory) isLoanHistoryEntry(it) else isActiveLoan(it)
                    }
                    if (borrowedLoans.isNotEmpty()) {
                        item { Text(tr(if (showingHistory) "Borrowing history" else "Active borrowing"), style = MaterialTheme.typography.titleMedium) }
                    }
                    items(borrowedLoans) { loan ->
                        val outstanding = outstandingLoanBalance(loan)
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    tr("From ${publicProfileLookup.name(loan.lenderId)}"),
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable { onOpenProfile(loan.lenderId) }
                                )
                                Text(tr("Loan total: KSh ${loan.totalRepayment.formatKSh()}"), style = MaterialTheme.typography.titleMedium)
                                Text(tr("Principal: KSh ${loan.principal.formatKSh()} • Interest: KSh ${loan.interestAmount.formatKSh()}"))
                                Text(tr("Amount paid: KSh ${loan.amountPaid.formatKSh()} • Outstanding: KSh ${outstanding.formatKSh()}"))
                                Text(tr("Due ${loan.dueDate.toDisplayDate()} \u2022 ${loan.status}"))
                                GraceDeadlineDetails(loan)
                                if (showingHistory) {
                                    Text(tr("Completed ${loan.completedAt?.toDisplayDate() ?: loan.dueDate.toDisplayDate()}"))
                                } else {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(
                                            onClick = { loanForPartialPayment = loan },
                                            enabled = outstanding > 0,
                                            modifier = Modifier.weight(1f)
                                        ) { Text(tr("Partial payment")) }
                                        Button(
                                            onClick = { actionScope.launch { viewModel.repayLoan(loan.id, outstanding) } },
                                            enabled = outstanding > 0,
                                            modifier = Modifier.weight(1f)
                                        ) { Text(tr("Full payment")) }
                                    }
                                }
                            }
                        }
                    }
                    val pendingOffers = offers.filter {
                        it.borrowerId == user.id && it.status == LoanOfferStatus.OFFERED
                    }
                    val termsRequested = offers.filter {
                        it.borrowerId == user.id && it.status == LoanOfferStatus.TERMS_REQUESTED
                    }
                    if (!showingHistory && pendingOffers.isNotEmpty()) {
                        item {
                            Text(tr("Incoming offers"), style = MaterialTheme.typography.titleMedium)
                        }
                        items(pendingOffers) { offer ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        tr("${publicProfileLookup.name(offer.lenderId)} wants to support you"),
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.clickable { onOpenProfile(offer.lenderId) }
                                    )
                                    Text(tr("KSh ${offer.amount.formatKSh()} • ${offer.interestRate}% for ${offer.repaymentPeriodDays} days"))
                                    Text(tr("Repayment total: KSh ${offer.totalRepayment.formatKSh()}"))
                                    Text(tr("Offered ${offer.createdAt.toDisplayTimestamp()}"), style = MaterialTheme.typography.bodySmall)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = { viewModel.acceptOffer(offer) }) { Text(tr("Accept")) }
                                        OutlinedButton(onClick = { viewModel.declineOffer(offer.id) }) { Text(tr("Decline")) }
                                    }
                                    TextButton(onClick = { offerForTerms = offer }) {
                                        Text(tr("Request different terms"))
                                    }
                                }
                            }
                        }
                    }
                    if (!showingHistory && termsRequested.isNotEmpty()) {
                        item { Text(tr("Waiting for revised terms"), style = MaterialTheme.typography.titleMedium)                         }
                        items(termsRequested) { offer ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        tr("${publicProfileLookup.name(offer.lenderId)} is reviewing your request"),
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.clickable { onOpenProfile(offer.lenderId) }
                                    )
                                    Text(tr("Requested: ${offer.requestedInterestRate}% for ${offer.requestedRepaymentPeriodDays} days"))
                                    Text(tr("Original offer remains locked until the terms are resolved."))
                                }
                            }
                        }
                    }
                }
            }
            else -> {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    directRequestSummaries.error?.let { message ->
                        item {
                            Text(message, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = directRequestSummaries.retry) { Text(tr("Retry limit lookup")) }
                        }
                    }
                    if (!showingHistory && directRequests.isNotEmpty()) {
                        item { Text(tr("Direct requests"), style = MaterialTheme.typography.titleMedium) }
                        items(directRequests.filter { request -> offers.none {
                            it.loanRequestId == request.id && it.lenderId == user.id &&
                                it.status in setOf(LoanOfferStatus.OFFERED, LoanOfferStatus.TERMS_REQUESTED)
                        } }, key = { "direct:${it.id}" }) { request ->
                            val summary = directRequestSummaries.summaries[request.id]
                            val exceedsBy = summary?.let {
                                (request.requestedAmount - it.availableLimit).coerceAtLeast(0)
                            }
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        tr("${summary?.borrowerName?.takeIf { it.isNotBlank() } ?: publicProfileLookup.name(request.borrowerId)} requested support"),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.clickable { onOpenProfile(request.borrowerId) }
                                    )
                                    Text(tr("KSh ${request.remainingAmount.formatKSh()} remaining • ${request.purpose}"))
                                    Text(request.description)
                                    Text(tr("Preferred repayment period: ${request.preferredPeriodDays} days"))
                                    if (request.aboveLimit) {
                                        Text(
                                            exceedsBy?.let {
                                                "Above the available borrowing limit by KSh ${it.formatKSh()}"
                                            } ?: "Loading ${publicProfileLookup.name(request.borrowerId)}'s available limit",
                                            color = MaterialTheme.colorScheme.error
                                        )
                                        Text(
                                            tr("This is a personal, above-limit request. Review the excess before making an offer."),
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    Button(
                                        onClick = { offerForDirectRequest = request },
                                        enabled = viewModel.repository.getAvailableToSupport(user.id) > 0 &&
                                            (!request.aboveLimit || exceedsBy != null)
                                    ) {
                                        Text(tr("Review support offer"))
                                    }
                                }
                            }
                        }
                    }
                    val termsRequests = offers.filter {
                        it.lenderId == user.id && it.status == LoanOfferStatus.TERMS_REQUESTED
                    }
                    if (!showingHistory && termsRequests.isNotEmpty()) {
                        item { Text(tr("Requested term changes"), style = MaterialTheme.typography.titleMedium) }
                        items(termsRequests) { offer ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        tr("${publicProfileLookup.name(offer.borrowerId)} requested revised terms"),
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.clickable { onOpenProfile(offer.borrowerId) }
                                    )
                                    Text(tr("KSh ${offer.amount.formatKSh()} • ${offer.requestedInterestRate}% for ${offer.requestedRepaymentPeriodDays} days"))
                                    Text(tr("Original: ${offer.interestRate}% for ${offer.repaymentPeriodDays} days"))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = { actionScope.launch {  viewModel.respondToOfferTerms(offer.id, true)  } }) {
                                            Text(tr("Send revised offer"))
                                        }
                                        OutlinedButton(onClick = { actionScope.launch {  viewModel.respondToOfferTerms(offer.id, false)  } }) {
                                            Text(tr("Keep original"))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    val openOffers = offers.filter {
                        it.lenderId == user.id && it.status in setOf(LoanOfferStatus.OFFERED, LoanOfferStatus.TERMS_REQUESTED)
                    }
                    if (!showingHistory && openOffers.isNotEmpty()) {
                        item { Text(tr("Locked support offers"), style = MaterialTheme.typography.titleMedium) }
                        items(openOffers) { offer ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        tr("KSh ${offer.amount.formatKSh()} offered to ${publicProfileLookup.name(offer.borrowerId)}"),
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.clickable { onOpenProfile(offer.borrowerId) }
                                    )
                                    Text(tr("${offer.interestRate}% for ${offer.repaymentPeriodDays} days • ${offer.status}"))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = { editingOffer = offer }, enabled = !operationBusy && offer.status == LoanOfferStatus.OFFERED) { Text(tr("Edit offer")) }
                                        TextButton(onClick = { cancellingOffer = offer }, enabled = !operationBusy) { Text(tr("Cancel offer")) }
                                    }
                                }
                            }
                        }
                    }
                    val lentLoans = loans.filter {
                        it.lenderId == user.id && if (showingHistory) isLoanHistoryEntry(it) else isActiveLoan(it)
                    }
                    if (lentLoans.isNotEmpty()) {
                        item { Text(tr(if (showingHistory) "Lending history" else "Active lending"), style = MaterialTheme.typography.titleMedium) }
                    }
                    items(lentLoans) { loan ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    tr("Loan to ${publicProfileLookup.name(loan.borrowerId)}"),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable { onOpenProfile(loan.borrowerId) }
                                )
                                Text(tr("Principal: KSh ${loan.principal.formatKSh()} • Interest: KSh ${loan.interestAmount.formatKSh()}"))
                                Text(tr("Total: KSh ${loan.totalRepayment.formatKSh()} • Paid: KSh ${loan.amountPaid.formatKSh()}"))
                                Text(tr("Outstanding: KSh ${outstandingLoanBalance(loan).formatKSh()} • ${loan.status}"))
                                Text(tr("Due ${loan.dueDate.toDisplayDate()}"))
                                GraceDeadlineDetails(loan)
                                if (showingHistory) {
                                    Text(tr("Completed ${loan.completedAt?.toDisplayDate() ?: loan.dueDate.toDisplayDate()}"))
                                }
                                if (!showingHistory) {
                                    OutlinedButton(onClick = { graceLoan = loan }, enabled = !operationBusy && loan.canGrantGrace(policyNow)) {
                                        Text(tr("Grant grace period"))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    graceLoan?.let { loan ->
        AgreementActionDialog("Grant grace period", listOf("Additional days (1–30)"), listOf("7"), operationBusy,
            onDismiss = { graceLoan = null }, submissionError = { viewModel.repository.error.value }) { fields ->
            val days = fields[0].toIntOrNull()
            if (days == null || days !in 1..30) false
            else viewModel.repository.grantGracePeriod(loan.id, days).also { if (it) graceLoan = null }
        }
    }
    editingOffer?.let { offer ->
        EditSupportOfferDialog(viewModel, user, offer, loanRequests) { editingOffer = null }
    }
    cancellingOffer?.let { offer ->
        AgreementActionDialog("Cancel offer", emptyList(), emptyList(), operationBusy,
            onDismiss = { cancellingOffer = null }, submissionError = { viewModel.repository.error.value }) {
            viewModel.repository.cancelLoanOffer(offer.id).also { if (it) cancellingOffer = null }
        }
    }
    offerForDirectRequest?.let { request ->
        val available = viewModel.repository.getAvailableToSupport(user.id)
        val summary = directRequestSummaries.summaries[request.id]
        val exceedsBy = summary?.let { (request.requestedAmount - it.availableLimit).coerceAtLeast(0) }
        SupportOfferDialog(
            request = request,
            borrowerName = summary?.borrowerName ?: publicProfileLookup.name(request.borrowerId),
            borrowerReliability = publicProfileLookup.profiles[request.borrowerId]?.reliabilityScore,
            availableBalance = available,
            maxAmount = minOf(request.remainingAmount, available),
            maxPeriodDays = request.preferredPeriodDays,
            aboveLimitExceedsBy = exceedsBy,
            submissionError = { viewModel.repository.error.value },
            onDismiss = { offerForDirectRequest = null },
            onSubmit = { amount, rate, days, approveAboveLimit ->
                viewModel.makeOffer(request.id, amount, rate, days, approveAboveLimit).also { offered ->
                    if (offered) offerForDirectRequest = null
                }
            }
        )
    }
    offerForTerms?.let { offer ->
        RequestTermsDialog(
            offer = offer,
            onDismiss = { offerForTerms = null },
            onSubmit = { rate, days ->
                viewModel.submitOfferTermsRequest(offer.id, rate, days).also { sent ->
                    if (sent) offerForTerms = null
                }
            }
        )
    }
    loanForPartialPayment?.let { loan ->
        PartialPaymentDialog(
            loan = loan,
            onDismiss = { loanForPartialPayment = null },
            onPay = { amount ->
                actionScope.launch {
                    if (viewModel.repayLoan(loan.id, amount)) loanForPartialPayment = null
                }
            }
        )
    }
}

@Composable
private fun PartialPaymentDialog(
    loan: com.kashke.app.data.LoanAgreement,
    onDismiss: () -> Unit,
    onPay: (Int) -> Unit
) {
    var amount by remember(loan.id) { mutableStateOf("") }
    var validationError by remember(loan.id) { mutableStateOf<String?>(null) }
    val outstanding = outstandingLoanBalance(loan)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Partial payment")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Outstanding balance: KES ${outstanding.formatKSh()}"))
                OutlinedTextField(
                    value = amount,
                    onValueChange = {
                        amount = numericInput(amount, it)
                        validationError = null
                    },
                    label = { Text(tr("Amount (KES)")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    isError = validationError != null
                )
                validationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text(tr("Payments use whole KES amounts in this account."), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(onClick = {
                val parsed = parseWholeKshAmount(amount)
                validationError = when {
                    amount.isBlank() -> "Amount is required."
                    parsed == null -> "Enter a valid positive whole-KES amount."
                    parsed > outstanding -> "Amount cannot exceed the outstanding balance."
                    else -> null
                }
                if (parsed != null && parsed <= outstanding && parsed > 0) onPay(parsed)
            }) { Text(tr("Pay")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } }
    )
}

@Composable
fun WalletTab(viewModel: KashKeViewModel, user: User) {
    val transactions by viewModel.repository.transactions.collectAsState()
    val offers by viewModel.repository.loanOffers.collectAsState()
    val wallet = viewModel.repository.getWalletMetrics(user.id)
    val lockedFunds = offers.filter {
        it.lenderId == user.id && it.status in setOf(LoanOfferStatus.OFFERED, LoanOfferStatus.TERMS_REQUESTED)
    }.sumOf { it.amount }
    val txs = transactions.filter { it.senderId == user.id || it.recipientId == user.id }
    var addMoneyVisible by remember { mutableStateOf(false) }
    val depositScope = rememberCoroutineScope()
    var depositBusy by remember { mutableStateOf(false) }
    var depositPending by remember { mutableStateOf(false) }
    var depositPhone by remember(user.id) { mutableStateOf(user.phone) }
    var depositStatus by remember { mutableStateOf<String?>(null) }
    suspend fun checkDeposit() {
        val result = com.kashke.app.data.repository.MpesaDeposits.latest()
        depositPending = result.status == "PENDING"
        depositStatus = when (result.status) {
            "COMPLETED" -> "KSh ${result.amount} received. M-Pesa receipt: ${result.receipt}."
            "FAILED" -> "Payment was not completed. You can try again."
            "PENDING" -> "Payment pending. Complete the M-Pesa prompt on your phone. If you already paid, wait for confirmation."
            else -> null
        }
        if (result.status == "COMPLETED") viewModel.repository.retry()
    }
    LaunchedEffect(user.id) {
        try { checkDeposit() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
    }
    LaunchedEffect(depositPending) {
        if (depositPending) repeat(30) {
            delay(4000)
            try { checkDeposit() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            if (!depositPending) return@LaunchedEffect
        }
    }
    var withdrawVisible by remember { mutableStateOf(false) }
    var addAmount by remember { mutableStateOf("") }
    var withdrawAmount by remember { mutableStateOf("") }
    var addMessage by remember { mutableStateOf<String?>(null) }
    var withdrawMessage by remember { mutableStateOf<String?>(null) }
    val availableBalance = wallet["available"].orZero()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(tr("Kash Wallet"), style = MaterialTheme.typography.titleLarge)
                    Text(tr("Wallet balance: KSh ${wallet["balance"].orZero().formatKSh()}"))
                    Text(tr("Available to support: KSh ${wallet["available"].orZero().formatKSh()}"))
                    Text(tr("Locked in offers: KSh ${lockedFunds.formatKSh()}"))
                    Text(tr("Pending: KSh ${wallet["pending"].orZero().formatKSh()}"))
                    Text(tr("Outstanding lending: KSh ${wallet["outstandingLending"].orZero().formatKSh()}"))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                        Button(onClick = { addMoneyVisible = true; addAmount = ""; addMessage = null }, enabled = !depositBusy && !depositPending) { Text(tr("Add Money")) }
                        OutlinedButton(onClick = { withdrawVisible = true; withdrawAmount = ""; withdrawMessage = null }) { Text(tr("Withdraw")) }
                    }
                }
            }
        }
        item {
            Text(tr("Transactions"), style = MaterialTheme.typography.titleMedium)
            depositStatus?.let { Text(it) }
            TextButton(onClick = {
                depositScope.launch {
                    depositBusy = true
                    try { checkDeposit() }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { depositStatus = "Could not refresh deposit status. Please try again." }
                    finally { depositBusy = false }
                }
            }, enabled = !depositBusy) { Text(tr("Check deposit status")) }
        }
        items(txs.take(10)) { tx ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(tx.description, fontWeight = FontWeight.SemiBold)
                    Text(tr("KSh ${tx.amount.formatKSh()}"), style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
                    Text(tr("${tx.type.toString().lowercase(Locale.getDefault()).replace('_', ' ').replaceFirstChar { it.titlecase(Locale.getDefault()) }} • ${tx.createdAt.toDisplayDate()}"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }

    if (addMoneyVisible) {
        val parsedAdd = addAmount.toIntOrNull()
        val addInvalid = parsedAdd == null || parsedAdd !in 1..150000 ||
            com.kashke.app.data.repository.normalizeDepositPhone(depositPhone) == null
        AlertDialog(
            onDismissRequest = { if (!depositBusy) addMoneyVisible = false },
            title = { Text(tr("Deposit with M-Pesa")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("Amount (KES)"))
                    OutlinedTextField(
                        value = addAmount,
                        onValueChange = { addAmount = numericInput(addAmount, it, 6) },
                        label = { Text(tr("KES")) },
                        singleLine = true,
                        enabled = !depositBusy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedTextField(value = depositPhone, onValueChange = { depositPhone = phoneInput(depositPhone, it) },
                        label = { Text(tr("M-Pesa phone number")) }, singleLine = true, enabled = !depositBusy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                    Text(tr("Enter KSh 1 to 150,000 in whole shillings. Complete the M-Pesa prompt on your phone. Your wallet updates after payment is confirmed."))
                    addMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        depositScope.launch {
                            depositBusy = true
                            addMessage = null
                            try {
                                val result = com.kashke.app.data.repository.MpesaDeposits.start(parsedAdd!!, depositPhone)
                                depositPending = result.status == "PENDING"
                                depositStatus = "Payment pending. Complete the M-Pesa prompt on your phone."
                                addMoneyVisible = false
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) {
                                addMessage = "Unable to confirm the request. Close this dialog and check deposit status before trying again."
                                try { checkDeposit() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                            } finally { depositBusy = false }
                        }
                    },
                    enabled = !addInvalid && !depositBusy && !depositPending
                ) { Text(tr(if (depositBusy) "Sending..." else "Send M-Pesa prompt")) }
            },
            dismissButton = { TextButton(onClick = { addMoneyVisible = false }, enabled = !depositBusy) { Text(tr("Close")) } }
        )
    }

    if (withdrawVisible) {
        val parsedWithdraw = withdrawAmount.toDoubleOrNull()
        val withdrawInvalid = when {
            withdrawAmount.isBlank() -> true
            parsedWithdraw == null -> true
            parsedWithdraw <= 0.0 -> true
            parsedWithdraw > availableBalance.toDouble() -> true
            else -> false
        }
        AlertDialog(
            onDismissRequest = { withdrawVisible = false },
            title = { Text(tr("Withdraw")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("Available balance: KSh ${availableBalance.formatKSh()}"))
                    OutlinedTextField(
                        value = withdrawAmount,
                        onValueChange = { withdrawAmount = numericInput(withdrawAmount, it, 10) },
                        label = { Text(tr("KES")) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    Text(tr("No wallet balance changes are made on the client. Server-authoritative processing is required before funds move."))
                    withdrawMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        when {
                            withdrawAmount.isBlank() -> withdrawMessage = "Enter an amount to withdraw."
                            parsedWithdraw == null -> withdrawMessage = "Enter a valid numeric amount."
                            parsedWithdraw <= 0.0 -> withdrawMessage = "Amount must be greater than zero."
                            parsedWithdraw > availableBalance.toDouble() -> withdrawMessage = "Amount exceeds your available balance."
                            else -> withdrawMessage = "Withdrawal processing is not configured yet."
                        }
                    },
                    enabled = !withdrawInvalid
                ) { Text(tr("Withdraw")) }
            },
            dismissButton = { TextButton(onClick = { withdrawVisible = false }) { Text(tr("Cancel")) } }
        )
    }
}

@Composable
fun ProfileTab(
    viewModel: KashKeViewModel,
    user: User,
    onOpenConnections: () -> Unit,
    onOpenProfile: (String) -> Unit,
    initialSelectedProfileSection: Int = 0,
    editRequest: Int = 0,
    onEditRequestHandled: () -> Unit = {}
) {
    // Defense in depth: even an incorrectly routed foreign User cannot render
    // the personal gauge or trust events. Only the typed member page is used.
    if (!canViewPrivateTrust(viewModel.repository.getCurrentUserId(), user.id)) {
        PublicProfilePage(viewModel, user.id)
        return
    }
    val actionScope = rememberCoroutineScope()
    val trustEvents by viewModel.repository.trustEvents.collectAsState()
    val connections by viewModel.repository.connections.collectAsState()
    val requests by viewModel.repository.loanRequests.collectAsState()
    val loans by viewModel.repository.loans.collectAsState()
    val connectionCount = acceptedConnectionsForUser(connections, user.id).size
    val events = trustEvents.filter { it.userId == user.id }
    val myRequests = requests.filter {
        it.borrowerId == user.id && it.status != LoanRequestStatus.CANCELLED
    }
    val outstandingLoans = loans.filter {
        it.borrowerId == user.id && it.status != LoanRequestStatus.REPAID
    }
    val publicProfileLookup = rememberPublicProfiles(
        viewModel,
        user.id,
        outstandingLoans.map { it.lenderId }
    )
    var selectedProfileSection by remember(user.id, initialSelectedProfileSection) {
        mutableIntStateOf(initialSelectedProfileSection)
    }
    var editingProfile by remember(user.id) { mutableStateOf(!user.hasCompleteProfile()) }
    LaunchedEffect(editRequest) {
        if (editRequest > 0) {
            editingProfile = true
            onEditRequestHandled()
        }
    }
    var requestToEdit by remember { mutableStateOf<LoanRequest?>(null) }
    var requestToCancel by remember { mutableStateOf<LoanRequest?>(null) }
    var walletVisible by remember(user.id) { mutableStateOf(DEFAULT_WALLET_VISIBLE) }
    val canViewWallet = canRevealWallet(user.id, viewModel.repository.getCurrentUserId())

    if (editingProfile) {
        val completion = com.kashke.app.data.profileCompletionFields(user)
        AgreementActionDialog("Complete profile", completion.map { it.first },
            completion.map { it.second }, false,
            onDismiss = { editingProfile = false }, submissionError = { viewModel.repository.error.value }) { fields ->
            val values = completion.map { it.first }.zip(fields).toMap()
            val name = values["Full name"] ?: user.name
            val username = values["Username"] ?: user.username.removePrefix("@")
            val email = values["Email"] ?: user.email
            val phone = values["Phone number"] ?: user.phone
            if (listOf(name, username, email, phone).any { it.isBlank() }) false
            else viewModel.repository.saveProfile(name, username, email, phone).also { if (it) editingProfile = false }
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (publicProfileLookup.error != null) {
            item { PublicProfileLookupNotice(publicProfileLookup) }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    TextButton(onClick = { editingProfile = true }) { Text(tr("Edit profile")) }
                    Text(user.name, style = MaterialTheme.typography.headlineMedium)
                    Text(user.username, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
                    if (user.email.isNotBlank()) Text(user.email)
                    TextButton(onClick = onOpenConnections) {
                        Text(tr("$connectionCount connections"))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    PrivateTrustGauge(user, viewModel.repository.getCurrentUserId())
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(tr("Borrowing limit: KSh ${user.borrowingLimit.formatKSh()}"))
                    Text(tr("Available: KSh ${user.availableLimit.formatKSh()}"))
                    if (canViewWallet) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tr("Wallet Balance"))
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                tr(if (walletVisible) String.format(Locale.getDefault(), "KES %,.2f", user.walletBalance.toDouble())
                                else "******"),
                                fontWeight = FontWeight.SemiBold
                            )
                            IconButton(onClick = { walletVisible = !walletVisible }) {
                                Icon(
                                    if (walletVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (walletVisible) "Hide wallet balance" else "Show wallet balance"
                                )
                            }
                        }
                    }
                    Text(tr("Member since: ${user.memberSince}"))
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(tr("Repayment reliability"), style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(12.dp))
                    ReliabilityBar(user)
                    Text(tr("Kash Trust is private. Support earns 2 points; full repayment earns 2 before the original deadline or 1 within approved grace. Delays deduct 10 points daily and reduce your limit."), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Trust events", "Requests", "Debts").forEachIndexed { index, label ->
                    FilterChip(
                        selected = selectedProfileSection == index,
                        onClick = { selectedProfileSection = index },
                        label = { Text(tr(label)) }
                    )
                }
            }
        }
        when (selectedProfileSection) {
            0 -> {
                if (events.isEmpty()) {
                    item { Text(tr("No trust events yet.")) }
                }
                items(events) { event ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.AutoMirrored.Filled.TrendingUp, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                            Spacer(modifier = Modifier.size(12.dp))
                            Column {
                                Text(event.description, fontWeight = FontWeight.SemiBold)
                                Text(tr("${event.points} points • ${event.createdAt}"))
                            }
                        }
                    }
                }
            }
            1 -> {
                if (myRequests.isEmpty()) {
                    item { Text(tr("You have no support requests.")) }
                }
                items(myRequests) { request ->
                    val canEdit = request.status == LoanRequestStatus.REQUESTED && request.fundedAmount == 0
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(request.purpose, style = MaterialTheme.typography.titleMedium)
                            Text(tr("KSh ${request.requestedAmount.formatKSh()} • ${request.status}"))
                            Text(tr("Supported: KSh ${request.fundedAmount.formatKSh()} • Remaining: KSh ${request.remainingAmount.formatKSh()}"))
                            Text(request.description)
                            if (canEdit) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                                    OutlinedButton(onClick = { requestToEdit = request }) { Text(tr("Edit")) }
                                    TextButton(onClick = { requestToCancel = request }) { Text(tr("Cancel request")) }
                                }
                            } else {
                                Text(tr("Editing and cancellation are locked after support begins."), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            else -> {
                if (outstandingLoans.isEmpty()) {
                    item { Text(tr("You have no outstanding loans.")) }
                }
                items(outstandingLoans) { loan ->
                    val outstanding = loan.totalRepayment - loan.amountPaid
                    val paymentAmount = minOf(outstanding, user.walletBalance)
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                tr("${publicProfileLookup.name(loan.lenderId)} • ${loan.status}"),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { onOpenProfile(loan.lenderId) }
                            )
                            Text(tr("Principal: KSh ${loan.principal.formatKSh()} • Repaid: KSh ${loan.amountPaid.formatKSh()}"))
                            Text(tr("Still owed: KSh ${outstanding.formatKSh()} • Due ${loan.dueDate.toDisplayDate()}"))
                            Button(
                                onClick = { actionScope.launch {  viewModel.repayLoan(loan.id, paymentAmount)  } },
                                enabled = paymentAmount > 0
                            ) {
                                Text(tr(if (paymentAmount == outstanding) "Repay in full" else "Pay KSh ${paymentAmount.formatKSh()}"))
                            }
                        }
                    }
                }
            }
        }
    }

    requestToEdit?.let { request ->
        EditLoanRequestDialog(
            request = request,
            maxAmount = user.availableLimit,
            onDismiss = { requestToEdit = null },
            onSubmit = { amount, purpose, description, period ->
                viewModel.editRequest(request.id, amount, purpose, description, period).also { saved ->
                    if (saved) requestToEdit = null
                }
            }
        )
    }
    requestToCancel?.let { request ->
        AlertDialog(
            onDismissRequest = { requestToCancel = null },
            title = { Text(tr("Cancel support request?")) },
            text = { Text(tr("This request has not received any support and will be removed from the community feed.")) },
            confirmButton = {
                Button(onClick = { actionScope.launch { 
                    if (viewModel.cancelRequest(request.id)) requestToCancel = null
                 } }) { Text(tr("Cancel request")) }
            },
            dismissButton = { TextButton(onClick = { requestToCancel = null }) { Text(tr("Keep request")) } }
        )
    }
}

@Composable
fun EditLoanRequestDialog(
    request: LoanRequest,
    maxAmount: Int,
    onDismiss: () -> Unit,
    onSubmit: suspend (Int, String, String, Int) -> Boolean
) {
    val actionScope = rememberCoroutineScope()
    var amount by remember(request.id) { mutableStateOf(request.requestedAmount.toString()) }
    var purpose by remember(request.id) { mutableStateOf(request.purpose) }
    var description by remember(request.id) { mutableStateOf(request.description) }
    var period by remember(request.id) { mutableIntStateOf(request.preferredPeriodDays) }
    var error by remember(request.id) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Edit support request")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = numericInput(amount, it, 10) },
                    label = { Text(tr("Amount (max KSh ${maxAmount.formatKSh()})")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(value = purpose, onValueChange = { purpose = it.filter(::isAllowedRequestTextCharacter).take(20) }, label = { Text(tr("Purpose")) })
                OutlinedTextField(value = description, onValueChange = { description = it.filter(::isAllowedRequestTextCharacter).take(150) }, label = { Text(tr("Description")) })
                OutlinedTextField(
                    value = period.toString(),
                    onValueChange = { period = numericInput(period.toString(), it, 3).toIntOrNull() ?: period },
                    label = { Text(tr("Repayment period (days)")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = { actionScope.launch { 
                val amountValue = amount.toIntOrNull()
                when {
                    amountValue == null || amountValue !in 1..maxAmount -> error = "Amount must be between KSh 1 and KSh ${maxAmount.formatKSh()}."
                    purpose.isBlank() || description.isBlank() -> error = "Purpose and description are required."
                    period !in 7..365 -> error = "Repayment period must be between 7 and 365 days."
                    onSubmit(amountValue, purpose.trim(), description.trim(), period) -> error = null
                    else -> error = "This request can no longer be edited."
                }
             } }) { Text(tr("Save changes")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } }
    )
}

@Composable
fun LoanRequestDetailsDialog(
    request: LoanRequest,
    borrowerName: String,
    aboveLimitExceedsBy: Int? = null,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Support request")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(borrowerName, style = MaterialTheme.typography.titleMedium)
                Text(tr("Request ID: ${request.id}"), style = MaterialTheme.typography.bodySmall)
                Text(tr("Purpose: ${request.purpose}"))
                Text(tr("Description: ${request.description}"))
                Text(tr("Requested: KSh ${request.requestedAmount.formatKSh()}"))
                Text(tr("Funded: KSh ${request.fundedAmount.formatKSh()}"))
                Text(tr("Remaining: KSh ${request.remainingAmount.formatKSh()}"))
                Text(tr("Repayment period: ${request.preferredPeriodDays} days"))
                Text(tr("Direct request: ${if (request.directRequestTo == null) "No" else "Yes"}"))
                Text(tr("Above borrowing limit: ${if (request.aboveLimit) "Yes" else "No"}"))
                if (request.aboveLimit) {
                    Text(
                        aboveLimitExceedsBy?.let { "Exceeds available limit by KSh ${it.formatKSh()}" }
                            ?: "Available-limit excess could not be loaded",
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Text(tr("Status: ${request.status}"))
                Text(tr("Created: ${request.createdAt}  •  Expires: ${request.expiresAt}"))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Close")) } }
    )
}

@Composable
private fun EditSupportOfferDialog(
    viewModel: KashKeViewModel,
    user: User,
    offer: LoanOffer,
    requests: List<LoanRequest>,
    onDismiss: () -> Unit
) {
    val request = requests.firstOrNull { it.id == offer.loanRequestId }
    if (request == null) {
        AlertDialog(onDismissRequest = onDismiss, title = { Text(tr("Offer unavailable")) },
            text = { Text(tr("Refresh your loans to load the request before editing this offer.")) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Close")) } })
        return
    }
    val borrowerProfiles = rememberPublicProfiles(viewModel, user.id, listOf(offer.borrowerId))
    val available = (viewModel.repository.getAvailableToSupport(user.id).toLong() + offer.amount)
        .coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    SupportOfferDialog(
        request = request,
        borrowerName = borrowerProfiles.name(offer.borrowerId),
        borrowerReliability = borrowerProfiles.profiles[offer.borrowerId]?.reliabilityScore,
        availableBalance = available,
        maxAmount = minOf(request.remainingAmount, available),
        maxPeriodDays = request.preferredPeriodDays,
        aboveLimitExceedsBy = null,
        submissionError = { viewModel.repository.error.value },
        onDismiss = onDismiss,
        initialOffer = offer,
        onSubmit = { amount, rate, days, _ ->
            viewModel.repository.editLoanOffer(offer.id, amount, rate, days).also { if (it) onDismiss() }
        }
    )
}

@Composable
fun SupportOfferDialog(
    request: LoanRequest,
    borrowerName: String,
    availableBalance: Int,
    maxAmount: Int,
    maxPeriodDays: Int,
    aboveLimitExceedsBy: Int?,
    submissionError: () -> String?,
    onDismiss: () -> Unit,
    initialOffer: LoanOffer? = null,
    borrowerReliability: Int? = null,
    onSubmit: suspend (Int, Int, Int, Boolean) -> Boolean
) {
    val actionScope = rememberCoroutineScope()
    var amount by remember(request.id, initialOffer?.id) { mutableIntStateOf(initialOffer?.amount ?: minOf(5000, maxAmount).coerceIn(1, maxAmount.coerceAtLeast(1))) }
    var interestRate by remember(request.id, initialOffer?.id) { mutableIntStateOf(initialOffer?.interestRate ?: 2) }
    var periodDays by remember(request.id, initialOffer?.id) { mutableIntStateOf(initialOffer?.repaymentPeriodDays ?: maxPeriodDays.coerceIn(7, 365)) }
    var error by remember(request.id) { mutableStateOf<String?>(null) }
    var approveAboveLimit by remember(request.id) { mutableStateOf(initialOffer != null) }
    var submitting by remember(request.id) { mutableStateOf(false) }
    val maxInterestRate = if (request.aboveLimit) 15 else 7
    val safeMaxAmount = maxAmount.coerceAtLeast(1)
    val usage = (amount.toFloat() / availableBalance.coerceAtLeast(1)).coerceIn(0f, 1f)
    val usageColor = when {
        usage <= 0.5f -> lerp(Color(0xFF2E7D32), Color(0xFFF9A825), usage * 2f)
        else -> lerp(Color(0xFFF9A825), Color(0xFFC62828), (usage - 0.5f) * 2f)
    }

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(tr(if (initialOffer != null) "Edit offer" else "Support $borrowerName")) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (borrowerReliability != null && borrowerReliability in 1..40) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            tr("$borrowerName's repayment reliability is $borrowerReliability%. There's a higher risk of late or missed repayment."),
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                Text(tr("Available balance: KSh ${availableBalance.formatKSh()}"))
                Text(tr("Request remaining: KSh ${request.remainingAmount.formatKSh()}"))
                if (request.aboveLimit && initialOffer == null) {
                    Text(
                        aboveLimitExceedsBy?.let {
                            "$borrowerName is KSh ${it.formatKSh()} above their available borrowing limit."
                        } ?: "$borrowerName's available borrowing limit could not be verified. Retry before making an offer.",
                        color = MaterialTheme.colorScheme.error
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = approveAboveLimit,
                            onCheckedChange = { approveAboveLimit = it },
                            enabled = aboveLimitExceedsBy != null
                        )
                        Text(
                            tr("I understand the disclosed excess and approve this above-limit personal request."),
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Text(tr("Support amount: KSh ${amount.formatKSh()}"), style = MaterialTheme.typography.titleMedium)
                if (maxAmount > 1) {
                    Slider(
                        value = amount.toFloat().coerceIn(1f, safeMaxAmount.toFloat()),
                        onValueChange = { amount = it.roundToInt().coerceIn(1, safeMaxAmount) },
                        valueRange = 1f..safeMaxAmount.toFloat()
                    )
                }
                Text(tr("${(usage * 100).roundToInt()}% of available balance"))
                LinearProgressIndicator(
                    progress = { usage },
                    modifier = Modifier.fillMaxWidth(),
                    color = usageColor
                )
                Text(tr("Balance after offer: KSh ${(availableBalance - amount).coerceAtLeast(0).formatKSh()}"))
                Text(tr("Interest: $interestRate% (max $maxInterestRate%)"), style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = interestRate.toFloat().coerceIn(0f, maxInterestRate.toFloat()),
                    onValueChange = { interestRate = it.roundToInt().coerceIn(0, maxInterestRate) },
                    valueRange = 0f..maxInterestRate.toFloat(),
                    steps = maxInterestRate - 1
                )
                Text(tr("Repayment period: $periodDays days"))
                if (maxPeriodDays > 7) {
                    Slider(
                        value = periodDays.toFloat().coerceIn(7f, maxPeriodDays.toFloat()),
                        onValueChange = { periodDays = it.roundToInt().coerceIn(7, maxPeriodDays) },
                        valueRange = 7f..maxPeriodDays.toFloat(),
                        steps = (maxPeriodDays - 8).coerceAtLeast(0)
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                onClick = { actionScope.launch { 
                    if (submitting) return@launch
                    submitting = true
                    error = null
                    try {
                    when {
                        initialOffer == null && request.aboveLimit && aboveLimitExceedsBy == null ->
                            error = "$borrowerName's available borrowing limit could not be verified. Retry."
                        request.aboveLimit && !approveAboveLimit ->
                            error = "Confirm that you understand and approve the disclosed above-limit request."
                        amount !in 1..maxAmount ->
                            error = "This amount is no longer available. Choose a lower amount."
                        onSubmit(amount, interestRate, periodDays, approveAboveLimit) -> error = null
                        else -> error = submissionError()
                            ?: "The support offer could not be confirmed. Refresh your offers before trying again."
                    }
                    } finally { submitting = false }
                 } },
                enabled = !submitting && maxAmount > 0 && amount in 1..maxAmount &&
                    (initialOffer != null || !request.aboveLimit || (approveAboveLimit && aboveLimitExceedsBy != null))
            ) {
                Text(tr(if (submitting) "Saving..." else if (initialOffer != null) "Save changes" else "Send support offer"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !submitting) { Text(tr("Cancel")) } }
    )
}

@Composable
fun RequestTermsDialog(
    offer: LoanOffer,
    onDismiss: () -> Unit,
    onSubmit: suspend (Int, Int) -> Boolean
) {
    val actionScope = rememberCoroutineScope()
    var interestRate by remember(offer.id) { mutableIntStateOf(offer.interestRate) }
    var periodDays by remember(offer.id) { mutableIntStateOf(offer.repaymentPeriodDays) }
    var error by remember(offer.id) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Request different terms")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("Current offer: ${offer.interestRate}% for ${offer.repaymentPeriodDays} days"))
                Text(tr("Requested interest: $interestRate%"))
                if (offer.interestRate > 0) {
                    Slider(
                        value = interestRate.toFloat(),
                        onValueChange = { interestRate = it.roundToInt().coerceIn(0, offer.interestRate) },
                        valueRange = 0f..offer.interestRate.toFloat(),
                        steps = (offer.interestRate - 1).coerceAtLeast(0)
                    )
                }
                Text(tr("Requested repayment period: $periodDays days"))
                Slider(
                    value = periodDays.toFloat(),
                    onValueChange = { periodDays = it.roundToInt().coerceIn(7, 365) },
                    valueRange = 7f..365f,
                    steps = 357
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = { actionScope.launch { 
                if (onSubmit(interestRate, periodDays)) error = null
                else error = "Your terms request could not be sent."
             } }) {
                Text(tr("Send terms request"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } }
    )
}

@Composable
fun RequestSupportDialog(
    onDismiss: () -> Unit,
    maxAmount: Int,
    onManageRequests: () -> Unit,
    onViewLoans: () -> Unit,
    onSubmit: suspend (Int, String, String, Int) -> Boolean
) {
    val actionScope = rememberCoroutineScope()
    var amount by remember { mutableStateOf("") }
    var purpose by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var period by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Request Support")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (maxAmount < 1) {
                    Text(tr("You don't have any borrowing limit available right now."))
                    Button(onClick = onManageRequests, modifier = Modifier.fillMaxWidth()) {
                        Text(tr("Manage requests"))
                    }
                    OutlinedButton(onClick = onViewLoans, modifier = Modifier.fillMaxWidth()) {
                        Text(tr("Loans"))
                    }
                } else {
                    OutlinedTextField(value = amount, onValueChange = { amount = numericInput(amount, it, 10) }, label = { Text(tr("Amount (max KSh ${maxAmount.formatKSh()})")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(
                        value = purpose,
                        onValueChange = { purpose = it.filter(::isAllowedRequestTextCharacter).take(20) },
                        label = { Text(tr("Purpose")) }
                    )
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it.filter(::isAllowedRequestTextCharacter).take(150) },
                        label = { Text(tr("Description")) }
                    )
                    OutlinedTextField(value = period, onValueChange = { period = numericInput(period, it, 3) }, label = { Text(tr("Preferred repayment period (days)")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            Button(onClick = { actionScope.launch {
                val amountValue = amount.toIntOrNull()
                val periodValue = period.toIntOrNull()
                when {
                    amountValue == null || amountValue !in 1..maxAmount -> error = "Enter an amount between KSh 1 and KSh ${maxAmount.formatKSh()}."
                    purpose.isBlank() -> error = "Enter a purpose for this request."
                    description.isBlank() -> error = "Add a short description for supporters."
                    periodValue == null || periodValue !in 7..365 -> error = "Choose a repayment period between 7 and 365 days."
                    onSubmit(amountValue, purpose.trim().toSentenceCase(), description.trim().toSentenceCase(), periodValue) -> error = null
                    else -> error = "Request could not be submitted. Check your available borrowing limit."
                }
            } }, enabled = maxAmount >= 1) {
                Text(tr("Create request"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } }
    )
}

@Composable
private fun DirectRequestDialog(
    availableLimit: Int,
    recipientName: String,
    onDismiss: () -> Unit,
    onSubmit: suspend (Int, String, String, Int, Boolean) -> Boolean
) {
    val actionScope = rememberCoroutineScope()
    var amount by remember { mutableStateOf("") }
    var purpose by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var period by remember { mutableStateOf("30") }
    var acknowledgedAboveLimit by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val amountValue = amount.toIntOrNull()
    val excess = amountValue?.let { (it - availableLimit).coerceAtLeast(0) } ?: 0
    val aboveLimit = excess > 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Request support from $recipientName")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("Your available borrowing limit is KSh ${availableLimit.formatKSh()}."))
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = numericInput(amount, it, 10) },
                    label = { Text(tr("Amount (KSh)")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                if (aboveLimit) {
                    Text(
                        tr("This personal request exceeds your available limit by KSh ${excess.formatKSh()}. $recipientName will see the same disclosure and must explicitly approve it."),
                        color = MaterialTheme.colorScheme.error
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = acknowledgedAboveLimit,
                            onCheckedChange = { acknowledgedAboveLimit = it }
                        )
                        Text(
                            tr("I understand this request is above my limit."),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                OutlinedTextField(
                    value = purpose,
                    onValueChange = { purpose = it.filter(::isAllowedRequestTextCharacter).take(20) },
                    label = { Text(tr("Purpose")) }
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.filter(::isAllowedRequestTextCharacter).take(150) },
                    label = { Text(tr("Description")) }
                )
                OutlinedTextField(
                    value = period,
                    onValueChange = { period = numericInput(period, it, 3) },
                    label = { Text(tr("Preferred repayment period (days)")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = { actionScope.launch {
                val periodDays = period.toIntOrNull()
                when {
                    amountValue == null || amountValue < 1 ->
                        error = "Enter a valid amount greater than zero."
                    purpose.isBlank() -> error = "Enter a purpose for this request."
                    description.isBlank() -> error = "Add a short description for the supporter."
                    periodDays == null || periodDays !in 7..365 ->
                        error = "Choose a repayment period between 7 and 365 days."
                    aboveLimit && !acknowledgedAboveLimit ->
                        error = "Confirm that you understand the above-limit disclosure."
                    onSubmit(
                        amountValue,
                        purpose.trim().toSentenceCase(),
                        description.trim().toSentenceCase(),
                        periodDays,
                        aboveLimit
                    ) -> error = null
                    else -> error = "The direct request could not be sent. Refresh and try again."
                }
            } }) {
                Text(tr("Send direct request"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } }
    )
}

private data class DirectRequestSummaryState(
    val summaries: Map<String, DirectRequestBorrowerSummary> = emptyMap(),
    val error: String? = null,
    val retry: () -> Unit = {}
)

// Reliability v2 is server-derived, separate from private milestone points.
@Composable
private fun ReliabilityBar(person: User) {
    ReliabilityBar(person.reliabilityScore, person.reliabilitySampleSize)
}

@Composable
private fun PrivateTrustGauge(person: User, viewerId: String?) {
    if (!canViewPrivateTrust(viewerId, person.id)) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(tr("Trust points"), style = MaterialTheme.typography.titleMedium)
        Text(tr("${person.trustScore} / 1000 points"), style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary)
        LinearProgressIndicator(progress = { person.trustScore.coerceIn(0, 1000) / 1000f }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ReliabilityBar(score: Int?, sampleSize: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (score == null || sampleSize == 0) {
            Text(tr("Reliability: inconclusive"), style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(progress = { 0f }, modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.outline,
                trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            Text(tr("No settled or due borrowing history yet."), style = MaterialTheme.typography.bodySmall)
        } else {
            val progress = score.coerceIn(0, 100) / 100f
            val barColor = lerp(Color(0xFFC62828), Color(0xFF2E7D32), progress)
            Text(tr(if (score < 20) "Reliability: high risk ($score%)" else "Repayment reliability: $score%"), style = MaterialTheme.typography.bodySmall,
                color = if (score < 20) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = barColor)
            Text(tr("Based on $sampleSize settled or due agreements"), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private data class PublicProfileLookupState(
    val profiles: Map<String, User> = emptyMap(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val retry: () -> Unit = {}
) {
    fun name(profileId: String): String =
        profiles[profileId]?.name?.takeIf { it.isNotBlank() }
            ?: if (isLoading) "Loading name..." else "Name unavailable"
}

@Composable
private fun rememberPublicProfiles(
    viewModel: KashKeViewModel,
    userId: String,
    requestedProfileIds: List<String>
): PublicProfileLookupState {
    val profileIds = remember(requestedProfileIds, userId) {
        requestedProfileIds.filter { it != userId }.distinct().sorted()
    }
    var retryToken by remember(userId) { mutableIntStateOf(0) }
    var profiles by remember(userId, profileIds) { mutableStateOf<Map<String, User>>(emptyMap()) }
    var isLoading by remember(userId, profileIds) { mutableStateOf(profileIds.isNotEmpty()) }
    var error by remember(userId, profileIds) { mutableStateOf<String?>(null) }

    LaunchedEffect(userId, profileIds, retryToken) {
        profiles = emptyMap()
        error = null
        isLoading = profileIds.isNotEmpty()
        if (profileIds.isNotEmpty()) {
            try {
                profiles = viewModel.repository.getPublicProfiles(profileIds).associateBy { it.id }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                error = "Names couldn't be loaded. Check your connection and retry."
                logStageFailure("PUBLIC PROFILE LOOKUP", exception)
            } finally {
                isLoading = false
            }
        }
    }
    return PublicProfileLookupState(
        profiles = profiles,
        isLoading = isLoading,
        error = error,
        retry = { retryToken++ }
    )
}

@Composable
private fun PublicProfileLookupNotice(state: PublicProfileLookupState) {
    state.error?.let { message ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
            TextButton(onClick = state.retry) { Text(tr("Retry")) }
        }
    }
}

@Composable
private fun rememberDirectRequestSummaries(
    viewModel: KashKeViewModel,
    requests: List<LoanRequest>
): DirectRequestSummaryState {
    val requestIds = remember(requests) {
        requests.filter { it.aboveLimit }.map { it.id }.distinct().sorted()
    }
    var retryToken by remember { mutableIntStateOf(0) }
    var summaries by remember { mutableStateOf<Map<String, DirectRequestBorrowerSummary>>(emptyMap()) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(requestIds, retryToken) {
        summaries = emptyMap()
        error = null
        try {
            summaries = requestIds.associateWith { requestId ->
                viewModel.loadDirectRequestBorrowerSummary(requestId)
                    ?: kotlin.error("The request's limit disclosure is unavailable.")
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            error = "Above-limit request details could not be loaded. Retry before making an offer."
            logStageFailure("DIRECT REQUEST BORROWER SUMMARY", exception)
        }
    }
    return DirectRequestSummaryState(
        summaries = summaries,
        error = error,
        retry = { retryToken++ }
    )
}

@Composable
private fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .widthIn(min = 16.dp)
            .height(16.dp)
            .clip(CircleShape)
            .background(Color.Red)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = tr(if (count > 99) "99+" else count.toString()),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
fun ProgressRow(label: String, percent: Int) {
    Column(modifier = Modifier.padding(bottom = 10.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(tr(label))
            Spacer(modifier = Modifier.weight(1f))
            Text(tr("${percent}%"))
        }
        Spacer(modifier = Modifier.height(6.dp))
        androidx.compose.material3.LinearProgressIndicator(
            progress = { percent / 100f },
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary
        )
    }
}

private fun Int.formatKSh(): String = String.format(Locale.getDefault(), "%d", this)

private fun Long.formatKSh(): String = String.format(Locale.getDefault(), "%,d", this)

private fun isAllowedRequestTextCharacter(character: Char): Boolean =
    character.isLetterOrDigit() || character == ' ' || character == ',' || character == '.'

private fun String.toSentenceCase(): String {
    var capitalizeNextLetter = true
    return lowercase(Locale.ROOT).map { character ->
        when {
            character.isLetter() && capitalizeNextLetter -> {
                capitalizeNextLetter = false
                character.uppercaseChar()
            }
            character == '.' -> {
                capitalizeNextLetter = true
                character
            }
            character.isLetter() -> {
                capitalizeNextLetter = false
                character
            }
            else -> character
        }
    }.joinToString("")
}

private fun String.toDisplayTimestamp(): String {
    val displayFormat = DateTimeFormatter.ofPattern("MMM d, yyyy 'at' h:mm a", Locale.getDefault())
    return runCatching { java.time.OffsetDateTime.parse(this).atZoneSameInstant(java.time.ZoneId.systemDefault()).format(displayFormat) }
        .recoverCatching { LocalDateTime.parse(this).format(displayFormat) }
        .getOrElse {
            runCatching {
                LocalDate.parse(this).format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault()))
            }.getOrDefault(this)
        }
}

private fun String.toDisplayDate(): String = runCatching {
    java.time.OffsetDateTime.parse(this).atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDate().toString()
}.getOrDefault(substringBefore('T'))

private fun Int?.orZero(): Int = this ?: 0

// Keep card defaults inside a restartable component rather than the inline lazy-item body.
@Composable
private fun CommunityRequestCard(
    hasOffer: Boolean,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    val defaults = CardDefaults.cardColors()
    val background = if (!hasOffer) defaults.containerColor
        else if (MaterialTheme.colorScheme.background.luminance() > 0.5f)
            Color(0xFFF2F2F2) else Color(0xFF292929)
    val colors = CardDefaults.cardColors(containerColor = background)
    Card(modifier = Modifier.fillMaxWidth(), colors = colors, content = content)
}

@Composable
private fun DebtOverviewPanel(
    loans: List<LoanAgreement>,
    userId: String,
    name: (String) -> String,
    onOpenProfile: (String) -> Unit,
    onViewLoans: (Boolean) -> Unit
) {
    val receivables = remember(loans, userId) { personDebts(loans, userId, true) }
    val payables = remember(loans, userId) { personDebts(loans, userId, false) }
    var open by remember(userId) { mutableStateOf(false) }
    var owedToUser by remember(userId) { mutableStateOf(true) }
    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Debt overview"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { open = true }) { Text(tr("View people")) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f).clickable { owedToUser = true; open = true }) {
                    Text(tr("Owed to you"), style = MaterialTheme.typography.bodySmall)
                    Text(tr("KSh ${receivables.sumOf { it.outstanding }.formatKSh()}"), fontWeight = FontWeight.SemiBold)
                }
                Column(Modifier.weight(1f).clickable { owedToUser = false; open = true }) {
                    Text(tr("You owe"), style = MaterialTheme.typography.bodySmall)
                    Text(tr("KSh ${payables.sumOf { it.outstanding }.formatKSh()}"), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
    if (open) {
        val people = if (owedToUser) receivables else payables
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(tr("Debt overview")) },
            text = {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = owedToUser, onClick = { owedToUser = true }, label = { Text(tr("Owed to you")) })
                        FilterChip(selected = !owedToUser, onClick = { owedToUser = false }, label = { Text(tr("You owe")) })
                    }
                    Text(tr("Total: KSh ${people.sumOf { it.outstanding }.formatKSh()}"),
                        style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 8.dp))
                    Text(tr("Remaining repayments, including interest. Pending offers are excluded."),
                        style = MaterialTheme.typography.bodySmall)
                    if (people.isEmpty()) {
                        Text(tr(if (owedToUser) "Nobody owes you on an active loan." else "You have no outstanding loans."),
                            modifier = Modifier.padding(top = 16.dp))
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 320.dp).padding(top = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(people, key = { it.userId }) { person ->
                                Column(Modifier.fillMaxWidth().clickable { open = false; onOpenProfile(person.userId) }) {
                                    Text(name(person.userId), fontWeight = FontWeight.SemiBold)
                                    Text(tr("KSh ${person.outstanding.formatKSh()}"), style = MaterialTheme.typography.titleMedium)
                                    Text(tr("${person.agreements} ${if (person.agreements == 1) "loan" else "loans"}"),
                                        style = MaterialTheme.typography.bodySmall)
                                    if (person.defaulted > 0) Text(tr("Defaulted: KSh ${person.defaulted.formatKSh()}"),
                                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false; onViewLoans(owedToUser) }) { Text(tr("View loans")) } },
            dismissButton = { TextButton(onClick = { open = false }) { Text(tr("Close")) } }
        )
    }
}
