package com.kashke.app.data.repository

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kashke.app.data.*
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Server-authoritative data. The flows are a disposable UI cache, never persistence. */
private val Context.repaymentDataStore by preferencesDataStore(name = "repayment_operations")

internal fun repositoryRowOrderColumn(table: String): String = when (table) {
    "wallets" -> "user_id"
    "support_request_mutes" -> "muted_user_id"
    else -> "id"
}

internal fun publicProfileDirectoryParams(
    query: String,
    phoneNumbers: List<String>,
    profileIds: List<String>
): JsonObject = buildJsonObject {
    put("p_query", query)
    put("p_phone_numbers", JsonArray(phoneNumbers.map(::JsonPrimitive)))
    put("p_profile_ids", JsonArray(profileIds.map(::JsonPrimitive)))
}

internal data class RepositoryAuthGuard(
    val valid: Boolean,
    val authInitialized: Boolean,
    val sessionPresent: Boolean,
    val userPresent: Boolean,
    val sessionUserId: String?,
    val currentUserId: String?,
    val expectedUserId: String
)

internal data class ProfileHydrationState(
    val count: Int,
    val containsCurrentUser: Boolean,
    val valid: Boolean
)

internal fun evaluateRepositoryAuth(
    expectedUserId: String,
    authInitialized: Boolean,
    sessionUserId: String?,
    currentUserId: String?
): RepositoryAuthGuard {
    val sessionPresent = sessionUserId != null
    val userPresent = currentUserId != null
    val valid = authInitialized && sessionPresent && userPresent &&
        expectedUserId.isNotBlank() && sessionUserId == expectedUserId && currentUserId == expectedUserId
    return RepositoryAuthGuard(
        valid = valid,
        authInitialized = authInitialized,
        sessionPresent = sessionPresent,
        userPresent = userPresent,
        sessionUserId = sessionUserId,
        currentUserId = currentUserId,
        expectedUserId = expectedUserId
    )
}

internal fun evaluateProfileHydration(expectedUserId: String, users: List<User>): ProfileHydrationState {
    val containsCurrentUser = users.any { it.id == expectedUserId }
    return ProfileHydrationState(
        count = users.size,
        containsCurrentUser = containsCurrentUser,
        valid = expectedUserId.isNotBlank() && containsCurrentUser
    )
}

internal fun availableSupportBalance(balance: Int, lockedBalance: Int): Int =
    (balance - lockedBalance).coerceAtLeast(0)

internal suspend fun loadAuthenticatedProfileRows(
    currentId: String,
    selectProfileById: suspend (String) -> List<JsonObject>
): List<JsonObject> = if (currentId.isBlank()) emptyList() else {
    selectProfileById(currentId).filter { it["id"]?.jsonPrimitive?.content == currentId }
}

internal fun User.withSavedProfile(name: String, username: String, email: String, phone: String): User =
    copy(name = name.trim(), username = "@" + username.trim().lowercase(java.util.Locale.ROOT),
        email = email.trim().lowercase(java.util.Locale.ROOT), phone = phone)

internal fun authenticatedProfileUsers(
    profileRows: List<JsonObject>,
    currentId: String,
    email: String,
    walletBalance: Int,
    authPhone: String = ""
): List<User> = profileRows.map { row ->
    val mine = row["id"]?.jsonPrimitive?.content == currentId
    val profileEmail = row["profile_email"]?.jsonPrimitive?.contentOrNull
        ?.takeIf { it.isNotBlank() } ?: publicAuthEmail(email).orEmpty()
    DatabaseRecords.user(row, if (mine) profileEmail else "", if (mine) walletBalance else 0).let { user ->
        if (mine && user.phone.isBlank()) user.copy(phone = authPhone) else user
    }
}

internal fun publicProfileUser(row: JsonObject): User = DatabaseRecords.user(row, "", 0)

internal fun contactProfileMatch(row: JsonObject): ContactProfileMatch? {
    val matchedPhone = row["matched_phone"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
        ?: return null
    return ContactProfileMatch(publicProfileUser(row), matchedPhone)
}

internal class PublicProfileDirectory(
    private val loadRows: suspend (String, List<String>, List<String>) -> List<JsonObject>
) {
    suspend fun search(query: String): List<User> =
        loadRows(query.trim(), emptyList(), emptyList()).map(::publicProfileUser)

    suspend fun matchContacts(phoneNumbers: List<String>): List<ContactProfileMatch> =
        loadRows("", phoneNumbers.distinct(), emptyList()).mapNotNull(::contactProfileMatch)

    suspend fun getProfile(profileId: String): User? =
        getProfiles(listOf(profileId)).firstOrNull { it.id == profileId }

    suspend fun getProfiles(profileIds: List<String>): List<User> =
        profileIds.distinct().chunked(100).flatMap { ids ->
            loadRows("", emptyList(), ids).map(::publicProfileUser)
        }
}

internal class RepositoryInvariantException(message: String) : IllegalStateException(message)

class SupabaseDataRepository(private val scope: CoroutineScope, context: Context) {
    private val notificationContext = context.applicationContext
    private val client get() = SupabaseClientProvider.client
    private val mutex = Mutex()
    private val publicProfiles = PublicProfileDirectory { query, phoneNumbers, profileIds ->
        publicProfileRows(query, phoneNumbers, profileIds)
    }
    private var refreshJob: Job? = null
    private var generation = 0
    private val idState = MutableStateFlow("")
    private val usersState = MutableStateFlow<List<User>>(emptyList())
    private val connectionsState = MutableStateFlow<List<Connection>>(emptyList())
    private val requestsState = MutableStateFlow<List<LoanRequest>>(emptyList())
    private val offersState = MutableStateFlow<List<LoanOffer>>(emptyList())
    private val loansState = MutableStateFlow<List<LoanAgreement>>(emptyList())
    private val eventsState = MutableStateFlow<List<TrustEvent>>(emptyList())
    private val transactionsState = MutableStateFlow<List<Transaction>>(emptyList())
    private val notificationsState = MutableStateFlow<List<Notification>>(emptyList())
    private val errorState = MutableStateFlow<String?>(null)
    private val busyState = MutableStateFlow(false)
    private var lockedBalance = 0
    private val repaymentStore = context.applicationContext.repaymentDataStore
    val currentUserId = idState.asStateFlow()
    val users = usersState.asStateFlow()
    val connections = connectionsState.asStateFlow()
    private val mutedMembersState = MutableStateFlow<Set<String>>(emptySet())
    val mutedMembers = mutedMembersState.asStateFlow()
    fun muteMember(memberId: String, muted: Boolean) = launchOperation("set_support_request_mute", "p_member_id" to memberId, "p_muted" to muted)
    fun disconnectMember(memberId: String) = launchOperation("disconnect_member", "p_member_id" to memberId)
    suspend fun submitContactRequest(id: String,name: String,email: String,subject: String,message: String): String {
        require(com.kashke.app.data.validContactForm(name,email,subject,message)) { "Invalid contact form." }
        val expectedUser = idState.value
        check(expectedUser.isNotBlank() && client.auth.currentUserOrNull()?.id == expectedUser) { "Sign in first." }
        val saved = client.postgrest.rpc("submit_contact_request", params("p_submission_id" to id,"p_full_name" to name,"p_reply_email" to email,"p_subject" to subject,"p_message" to message)).decodeAs<String>()
        check(saved == id && expectedUser == idState.value) { "Could not confirm submission." }
        return saved
    }
    val loanRequests = requestsState.asStateFlow()
    val loanOffers = offersState.asStateFlow()
    val loans = loansState.asStateFlow()
    val trustEvents = eventsState.asStateFlow()
    val transactions = transactionsState.asStateFlow()
    val notifications = notificationsState.asStateFlow()
    val error = errorState.asStateFlow()
    val busy = busyState.asStateFlow()

    private suspend fun waitForRepositoryAuthState(expectedUserId: String): RepositoryAuthGuard {
        val authInitialized = runLoggedStage("AUTH_INIT") {
            client.auth.awaitInitialization()
            true
        }
        val sessionUserId = client.auth.currentSessionOrNull()?.user?.id
        val currentUserId = client.auth.currentUserOrNull()?.id
        val guard = evaluateRepositoryAuth(expectedUserId, authInitialized, sessionUserId, currentUserId)
        Log.d(
            "KashKeRuntime",
            "stage=SESSION_RESTORED authInitialized=${guard.authInitialized} sessionPresent=${guard.sessionPresent} userPresent=${guard.userPresent}"
        )
        return guard
    }

    private fun logRepositoryStage(stage: String, guard: RepositoryAuthGuard) {
        Log.d(
            "KashKeRuntime",
            "stage=$stage authInitialized=${guard.authInitialized} sessionPresent=${guard.sessionPresent} userPresent=${guard.userPresent}"
        )
    }

    private fun logProfileState(stage: String, expectedUserId: String, users: List<User>) {
        val state = evaluateProfileHydration(expectedUserId, users)
        Log.d(
            "KashKeRuntime",
            "stage=$stage count=${state.count} containsCurrentUser=${state.containsCurrentUser}"
        )
    }

    suspend fun load(userId: String) {
        try {
            logRepositoryStage("REPOSITORY_LOAD_START", evaluateRepositoryAuth(userId, authInitialized = false, sessionUserId = null, currentUserId = null))
            clear()
            idState.value = userId
            val authGuard = waitForRepositoryAuthState(userId)
            logRepositoryStage("REPOSITORY_AUTH_CHECK", authGuard)
            runLoggedStage("DATABASE REPOSITORY LOAD") {
                check(authGuard.valid) { "Supabase auth validation failed for repository startup." }
            }
            mutex.withLock { refresh() }
            val hydrationState = evaluateProfileHydration(userId, usersState.value)
            logProfileState("USER_STATE", userId, usersState.value)
            logRepositoryStage("REPOSITORY_LOAD_SUCCESS", evaluateRepositoryAuth(
                expectedUserId = userId,
                authInitialized = true,
                sessionUserId = client.auth.currentSessionOrNull()?.user?.id,
                currentUserId = client.auth.currentUserOrNull()?.id
            ))
            runLoggedStage("DATABASE REPOSITORY LOAD") {
                if (idState.value != userId) {
                    throw RepositoryInvariantException("Repository user id changed during startup refresh.")
                }
                if (!hydrationState.valid) {
                    throw RepositoryInvariantException("Current authenticated profile was not hydrated into the repository state.")
                }
            }
            refreshJob = scope.launch {
                while (isActive) {
                    delay(15_000)
                    retryRefresh()
                }
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logRepositoryStage(
                "REPOSITORY_LOAD_FAILURE",
                evaluateRepositoryAuth(
                    expectedUserId = userId,
                    authInitialized = true,
                    sessionUserId = client.auth.currentSessionOrNull()?.user?.id,
                    currentUserId = client.auth.currentUserOrNull()?.id
                )
            )
            logStageFailure("REPOSITORY_LOAD_FAILURE", exception)
            throw exception
        }
    }

    fun clear() {
        com.kashke.app.PushRegistration.clear(notificationContext)
        com.kashke.app.SystemNotifications.clear(notificationContext)
        generation++
        refreshJob?.cancel()
        refreshJob = null
        idState.value = ""
        usersState.value = emptyList()
        connectionsState.value = emptyList()
        mutedMembersState.value = emptySet()
        requestsState.value = emptyList()
        offersState.value = emptyList()
        loansState.value = emptyList()
        eventsState.value = emptyList()
        transactionsState.value = emptyList()
        notificationsState.value = emptyList()
        lockedBalance = 0
        errorState.value = null
        busyState.value = false
    }

    suspend fun refreshNotificationsForTap(): Boolean {
        return try {
            mutex.withLock { if (idState.value.isEmpty()) return false else refresh() }
            true
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { false }
    }

    fun retry() { scope.launch { retryRefresh() } }

    private suspend fun retryRefresh() {
        try {
            mutex.withLock { if (idState.value.isNotEmpty()) refresh() }
            errorState.value = null
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { errorState.value = "We couldn't refresh your data. Please try again." }
    }

    // Page through RLS-filtered rows instead of silently truncating at the API row limit.
    private suspend fun rows(table: String): List<JsonObject> {
        val result = mutableListOf<JsonObject>()
        var offset = 0L
        while (true) {
            val page = client.from(table).select {
                order(repositoryRowOrderColumn(table), Order.ASCENDING)
                range(offset, offset + 499)
            }.decodeList<JsonObject>()
            result.addAll(page)
            if (page.size < 500) return result
            offset += 500
        }
    }

    private suspend fun refresh() {
        val revision = generation
        val currentId = idState.value
        val authGuard = waitForRepositoryAuthState(currentId)
        logRepositoryStage("REPOSITORY_AUTH_CHECK", authGuard)
        runLoggedStage("DATABASE SESSION VALIDATION") {
            check(authGuard.valid) { "Supabase auth validation failed for repository refresh." }
        }
        runLoggedStage("RPC refresh_account_state") { client.postgrest.rpc("refresh_account_state") }
        val profileRows = runLoggedStage("DATABASE SELECT authenticated profile") {
            loadAuthenticatedProfileRows(currentId) { profileId ->
                client.from("profiles").select {
                    filter { eq("id", profileId) }
                }.decodeList<JsonObject>()
            }
        }
        Log.d(
            "KashKeRuntime",
            "stage=PROFILE_ROWS count=${profileRows.size} containsCurrentUser=${profileRows.any { it.text("id") == currentId }}"
        )
        val walletRows = runLoggedStage("DATABASE SELECT wallets") { rows("wallets") }
        val wallet = walletRows.firstOrNull { it.text("user_id") == currentId }
        val userList = runLoggedStage("DATABASE MAP profiles") {
            authenticatedProfileUsers(
                profileRows,
                currentId,
                client.auth.currentUserOrNull()?.email.orEmpty(),
                wallet?.number("balance") ?: 0,
                client.auth.currentUserOrNull()?.phone.orEmpty()
            )
        }
        logProfileState("USER_STATE", currentId, userList)
        val hydrationState = evaluateProfileHydration(currentId, userList)
        if (!hydrationState.valid) {
            throw RepositoryInvariantException("Authenticated profile is missing from the hydrated repository state.")
        }
        // Publish the authoritative points and wallet before unrelated fetches.
        // A later feed failure must not leave a saved repayment's profile stale.
        if (revision != generation || currentId != idState.value) return
        lockedBalance = wallet?.number("locked_balance") ?: 0
        usersState.value = userList
        val requestList = runLoggedStage("DATABASE MAP loan_requests") {
            runLoggedStage("DATABASE SELECT loan_requests") { rows("loan_requests") }
                .map(DatabaseRecords::request)
        }
        val offerList = runLoggedStage("DATABASE MAP loan_offers") {
            runLoggedStage("DATABASE SELECT loan_offers") { rows("loan_offers") }
                .map { DatabaseRecords.offer(it, requestList) }
        }
        // Community actions must hydrate even if a later, unrelated feed fails.
        if (revision != generation || currentId != idState.value) return
        requestsState.value = requestList
        offersState.value = offerList
        val loanList = runLoggedStage("DATABASE MAP loans") {
            runLoggedStage("DATABASE SELECT loans") { rows("loans") }
                .map { DatabaseRecords.loan(it, requestList) }
        }
        val connectionList = runLoggedStage("DATABASE MAP connections") {
            runLoggedStage("DATABASE SELECT connections") { rows("connections") }
                .map(DatabaseRecords::connection)
        }
        Log.d(
            "KashKeRuntime",
            "stage=CONNECTION_STATE rows=${connectionList.size} accepted=${connectionList.count { it.status == ConnectionStatus.ACCEPTED }} pending=${connectionList.count { it.status == ConnectionStatus.PENDING }}"
        )
        val eventList = runLoggedStage("DATABASE MAP trust_events") {
            runLoggedStage("DATABASE SELECT trust_events") { rows("trust_events") }
                .map(DatabaseRecords::event)
        }
        val transactionList = runLoggedStage("DATABASE MAP transactions") {
            runLoggedStage("DATABASE SELECT transactions") { rows("transactions") }
                .map(DatabaseRecords::transaction)
        }
        val mutedMembers = rows("support_request_mutes").mapNotNull { it["muted_user_id"]?.jsonPrimitive?.contentOrNull }.toSet()
        val notificationList = runLoggedStage("DATABASE MAP notifications") {
            runLoggedStage("DATABASE SELECT notifications") { rows("notifications") }
                .map(DatabaseRecords::notification)
                .sortedByDescending { it.createdAt }
        }
        // A logout or account switch invalidates in-flight requests before they can publish.
        if (revision != generation || currentId != idState.value) return
        requestsState.value = requestList
        offersState.value = offerList
        loansState.value = loanList
        connectionsState.value = connectionList
        mutedMembersState.value = mutedMembers
        eventsState.value = eventList
        transactionsState.value = transactionList
        notificationsState.value = notificationList
        com.kashke.app.PushRegistration.register(notificationContext, scope, currentId)
        com.kashke.app.SystemNotifications.deliver(notificationContext, currentId, notificationList)
    }

    /**
    * Required non-null columns: id/full_name/username/phone/bio/location/account_status/
    * created_at as strings. Public reliability_score is nullable; its sample
    * size defaults to zero. Private trust_score is absent. profile_image is a
    * string defaulting to empty when omitted; borrowing_limit and available_limit
    * are integers defaulting to zero; completed_loans and other User metrics
    * default to zero. matched_phone is nullable text for search/profile rows and
    * must be non-null text for a contact row to become a match.
     */
    private suspend fun publicProfileRows(
        query: String = "",
        phoneNumbers: List<String> = emptyList(),
        profileIds: List<String> = emptyList()
    ): List<JsonObject> {
        val params = publicProfileDirectoryParams(query, phoneNumbers, profileIds)
        return try {
            client.postgrest.rpc("public_profile_directory", params).decodeList()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            val context = safeRpcDiagnosticContext(
                "public_profile_directory",
                params,
                "response=decode_failed phones=${phoneNumbers.size} profileIds=${profileIds.size} queryLength=${query.length}"
            )
            logStageFailure(context, exception)
            throw exception
        }
    }

    suspend fun searchPublicProfiles(query: String): List<User> = publicProfiles.search(query)

    suspend fun matchContactProfiles(phoneNumbers: List<String>): List<ContactProfileMatch> =
        publicProfiles.matchContacts(phoneNumbers)

    suspend fun getPublicProfile(profileId: String): User? =
        publicProfiles.getProfile(profileId)

    suspend fun getMemberProfileDetails(profileId: String): MemberProfile? {
        val viewerId = idState.value
        val requestGeneration = generation
        check(viewerId.isNotBlank() && client.auth.currentUserOrNull()?.id == viewerId) { "Sign in first" }
        val result = client.postgrest.rpc(
            "member_profile_details", params("p_profile_id" to profileId)
        ).decodeList<MemberProfile>().singleOrNull()
        // Reject responses that completed after logout/account switching.
        check(requestGeneration == generation && idState.value == viewerId &&
            client.auth.currentUserOrNull()?.id == viewerId) { "Account changed" }
        check(result == null || result.id == profileId) { "Unexpected member profile" }
        return result
    }

    suspend fun getPublicProfiles(profileIds: List<String>): List<User> {
        val uniqueIds = profileIds.distinct()
        val profiles = publicProfiles.getProfiles(uniqueIds)
        Log.d(
            "KashKeRuntime",
            "stage=CONNECTION_PROFILES requested=${uniqueIds.size} returned=${profiles.size} missing=${(uniqueIds.size - profiles.map { it.id }.distinct().size).coerceAtLeast(0)}"
        )
        return profiles
    }

    private suspend fun operation(name: String, params: JsonObject, actingUserId: String = idState.value, onSaved: () -> Unit = {}): Boolean {
        if (busyState.value) {
            errorState.value = "Another action is in progress. Wait for it to finish, then try again."
            return false
        }
        val revision = generation
        busyState.value = true
        try {
            return mutex.withLock {
                if (revision != generation || actingUserId.isEmpty() || actingUserId != idState.value || client.auth.currentUserOrNull()?.id != actingUserId) {
                    if (revision == generation) errorState.value = "Your session has changed. Sign in again before continuing."
                    return@withLock false
                }
                errorState.value = null
                if (name in setOf("create_loan_request", "edit_loan_request", "send_connection_request", "request_loan_offer_terms") && usersState.value.firstOrNull { it.id == actingUserId }?.hasCompleteProfile() != true) {
                    errorState.value = "Complete your full name, username, email and phone number in Profile before making requests."
                    return@withLock false
                }
                try {
                    val result = runLoggedStage("RPC $name") { client.postgrest.rpc(name, params) }
                    if (revision != generation || actingUserId != idState.value) return@withLock false
                    onSaved()
                    // A saved mutation stays successful if its follow-up fetch fails.
                    val offerMutation = name in setOf("create_loan_offer", "edit_loan_offer", "cancel_loan_offer")
                    var offerStateConfirmed = !offerMutation
                    try {
                        if (offerMutation) {
                            // Read the exact persisted row before refreshing the rest of the feed.
                            // Creation returns its real ID; edit/cancel use the existing ID.
                            val offerId = if (name == "create_loan_offer") result.decodeAs<String>()
                                else params.getValue("p_offer_id").jsonPrimitive.content
                            val savedOffer = client.from("loan_offers").select {
                                filter { eq("id", offerId) }
                            }.decodeList<JsonObject>().singleOrNull()?.let {
                                DatabaseRecords.offer(it, requestsState.value)
                            }
                            check(savedOffer != null || name == "cancel_loan_offer") {
                                "Saved offer could not be loaded"
                            }
                            if (revision != generation || actingUserId != idState.value) return@withLock false
                            offersState.value = offersState.value.filterNot { it.id == offerId } + listOfNotNull(savedOffer)
                            offerStateConfirmed = true
                        }
                        refresh()
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        if (!offerStateConfirmed) {
                            if (revision == generation) errorState.value = "Your offer was saved, but could not be loaded. Refresh Community before trying again."
                            return@withLock false
                        }
                        if (revision == generation) errorState.value = "Saved. We couldn't refresh your data. Tap Retry."
                    }
                    true
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (revision == generation) errorState.value = if (name == "create_loan_offer")
                        e.toSupportOfferMessage()
                    else e.toAgreementMessage()
                    false
                }
            }
        } finally {
            if (revision == generation) busyState.value = false
        }
    }

    private fun params(vararg values: Pair<String, Any?>) = buildJsonObject {
        values.forEach { (key, value) -> when (value) {
            null -> put(key, JsonNull)
            is Number -> put(key, value)
            is Boolean -> put(key, value)
            else -> put(key, value.toString())
        } }
    }
    private fun launchOperation(name: String, vararg values: Pair<String, Any?>, actingUserId: String = idState.value) {
        scope.launch { operation(name, params(*values), actingUserId) }
    }

    suspend fun createLoanRequest(borrowerId: String, amount: Int, purpose: String, description: String, preferredPeriodDays: Int): Boolean =
        operation("create_loan_request", params("p_amount" to amount, "p_purpose" to purpose, "p_description" to description, "p_period_days" to preferredPeriodDays), borrowerId)
    suspend fun createDirectLoanRequest(
        borrowerId: String,
        amount: Int,
        purpose: String,
        description: String,
        preferredPeriodDays: Int,
        recipientId: String,
        aboveLimit: Boolean
    ): Boolean = operation(
        "create_loan_request",
        params(
            "p_amount" to amount,
            "p_purpose" to purpose,
            "p_description" to description,
            "p_period_days" to preferredPeriodDays,
            "p_direct_request_to" to recipientId,
            "p_above_limit_requested" to aboveLimit
        ),
        borrowerId
    )
    suspend fun editLoanRequest(requestId: String, borrowerId: String, amount: Int, purpose: String, description: String, preferredPeriodDays: Int): Boolean =
        operation("edit_loan_request", params("p_request_id" to requestId, "p_amount" to amount, "p_purpose" to purpose, "p_description" to description, "p_period_days" to preferredPeriodDays), borrowerId)
    suspend fun cancelLoanRequest(requestId: String, borrowerId: String): Boolean = operation("cancel_loan_request", params("p_request_id" to requestId), borrowerId)
    suspend fun makeLoanOffer(
        requestId: String,
        lenderId: String,
        amount: Int,
        interestRate: Int,
        repaymentPeriodDays: Int,
        approveAboveLimit: Boolean
    ): Boolean = operation(
        "create_loan_offer",
        params(
            "p_request_id" to requestId,
            "p_amount" to amount,
            "p_interest_rate" to interestRate,
            "p_period_days" to repaymentPeriodDays,
            "p_approve_above_limit" to approveAboveLimit
        ),
        lenderId
    )
    suspend fun getDirectRequestBorrowerSummary(requestId: String): DirectRequestBorrowerSummary? {
        val rows = client.postgrest.rpc(
            "get_direct_request_borrower_summary",
            params("p_request_id" to requestId)
        ).decodeList<JsonObject>()
        return rows.singleOrNull()?.let { row ->
            DirectRequestBorrowerSummary(
                borrowerId = row.text("borrower_id"),
                borrowerName = row.text("full_name"),
                availableLimit = row.number("available_limit")
            )
        }
    }
    suspend fun requestLoanOfferTerms(offerId: String, borrowerId: String, interestRate: Int, repaymentPeriodDays: Int): Boolean =
        operation("request_loan_offer_terms", params("p_offer_id" to offerId, "p_interest_rate" to interestRate, "p_period_days" to repaymentPeriodDays), borrowerId)
    suspend fun respondToLoanOfferTerms(offerId: String, lenderId: String, acceptRequestedTerms: Boolean): Boolean =
        operation("respond_loan_offer_terms", params("p_offer_id" to offerId, "p_accept_terms" to acceptRequestedTerms), lenderId)
    suspend fun repayLoan(loanId: String, amount: Int, borrowerId: String): Boolean {
        if (busyState.value || borrowerId != idState.value) return false
        val key = stringPreferencesKey("$borrowerId:$loanId:$amount")
        // Persist before sending. An ambiguous response can be retried after restart
        // using the same receipt; keys are isolated by authenticated user and terms.
        var operationId = ""
        try {
            repaymentStore.edit { preferences ->
                operationId = preferences[key] ?: java.util.UUID.randomUUID().toString()
                preferences[key] = operationId
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            errorState.value = "We couldn't safely prepare this payment. Please try again."
            return false
        }
        val saved = operation("repay_loan_once", params("p_loan_id" to loanId, "p_amount" to amount, "p_operation_id" to operationId), borrowerId)
        if (saved) {
            try { repaymentStore.edit { it.remove(key) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { /* Retaining a confirmed receipt is safe: the server deduplicates it. */ }
        }
        return saved
    }
    fun acceptLoanOffer(offer: LoanOffer, borrowerId: String) = launchOperation("accept_loan_offer_reviewed", "p_offer_id" to offer.id,
        "p_expected_amount" to offer.amount, "p_expected_interest_rate" to offer.interestRate, "p_expected_period_days" to offer.repaymentPeriodDays, actingUserId = borrowerId)
    fun declineLoanOffer(offerId: String, borrowerId: String) = launchOperation("decline_loan_offer", "p_offer_id" to offerId, actingUserId = borrowerId)
    fun sendConnectionRequest(requesterId: String, recipientId: String) = launchOperation("send_connection_request", "p_recipient_id" to recipientId, actingUserId = requesterId)
    fun acceptConnectionRequest(connectionId: String) = launchOperation("respond_connection_request", "p_connection_id" to connectionId, "p_accept" to true)
    fun rejectConnectionRequest(connectionId: String) = launchOperation("respond_connection_request", "p_connection_id" to connectionId, "p_accept" to false)
    suspend fun grantGracePeriod(loanId: String, extraDays: Int): Boolean = operation("grant_grace_period", params("p_loan_id" to loanId, "p_extra_days" to extraDays))
    suspend fun editLoanOffer(offerId: String, amount: Int, rate: Int, days: Int): Boolean = operation("edit_loan_offer", params("p_offer_id" to offerId, "p_amount" to amount, "p_interest_rate" to rate, "p_period_days" to days))
    suspend fun cancelLoanOffer(offerId: String): Boolean = operation("cancel_loan_offer", params("p_offer_id" to offerId))
    suspend fun saveProfile(name: String, username: String, email: String, phone: String): Boolean {
        try { registrationDetails(name, username, phone, email) }
        catch (e: IllegalArgumentException) { errorState.value = e.message; return false }
        val cleanPhone = try { registrationPhone(phone) } catch (e: IllegalArgumentException) {
            errorState.value = "Enter a valid Kenyan mobile number."
            return false
        }
        val actingUserId = idState.value
        return operation("complete_member_profile", params("p_full_name" to name, "p_username" to username, "p_email" to email, "p_phone" to cleanPhone), actingUserId) {
            // The RPC has committed these fields. Keep completion current even
            // when the subsequent wallet/feed refresh is unavailable.
            usersState.value = usersState.value.map { user ->
                if (user.id == actingUserId) user.withSavedProfile(name, username, email, cleanPhone) else user
            }
        }
    }
    fun markExternallyRecovered(loanId: String) = launchOperation("mark_recovered_externally", "p_loan_id" to loanId)
    fun markNotificationRead(notificationId: String) {
        val revision = generation
        scope.launch {
            mutex.withLock {
                if (idState.value.isEmpty() || revision != generation) return@withLock
                try {
                    client.from("notifications").update({ set("read", true) }) { filter { eq("id", notificationId); eq("user_id", idState.value) } }
                    if (revision == generation) notificationsState.value = notificationsState.value.map { if (it.id == notificationId) it.copy(read = true) else it }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { errorState.value = "We couldn't update this notification. Please try again." }
            }
        }
    }

    fun getCurrentUserId() = idState.value
    fun getUser(userId: String) = usersState.value.firstOrNull { it.id == userId }
    fun getUsers() = usersState.value
    fun getAvailableToSupport(userId: String): Int {
        val user = getUser(userId) ?: return 0
        return availableSupportBalance(user.walletBalance, getLockedSupportFunds(userId))
    }
    fun getLockedSupportFunds(userId: String) = if (userId == idState.value) lockedBalance else 0
    fun getWalletMetrics(userId: String): Map<String, Int> {
        val user = getUser(userId) ?: return emptyMap()
        val balance = user.walletBalance
        val locked = getLockedSupportFunds(userId)
        val available = availableSupportBalance(balance, locked)
        val sent = transactionsState.value.filter { it.senderId == userId }.sumOf { it.amount }
        val received = transactionsState.value.filter { it.recipientId == userId }.sumOf { it.amount }
        return mapOf("balance" to balance, "available" to available, "locked" to locked,
            "pending" to 0, "outstandingLending" to loansState.value.filter { it.lenderId == userId && it.status !in setOf(LoanRequestStatus.REPAID, LoanRequestStatus.RECOVERED_EXTERNALLY) }.sumOf { it.principal - it.principalPaid },
            "totalSupported" to user.totalSupported, "totalReceived" to received, "totalSent" to sent, "netWallet" to received - sent)
    }
}
