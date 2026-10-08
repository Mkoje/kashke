package com.kashke.app.data

data class DevicePhoneContact(
    val contactId: String,
    val displayName: String,
    val phoneNumber: String
)

data class ContactProfileMatch(
    val profile: User,
    val matchedPhone: String
)

internal fun batchContactPhoneNumbers(phoneNumbers: List<String>, batchSize: Int = 100): List<List<String>> {
    require(batchSize > 0)
    return phoneNumbers.distinct().chunked(batchSize)
}

internal suspend fun <T> loadDiscoverFeature(action: suspend () -> T): Result<T> = try {
    Result.success(action())
} catch (exception: kotlinx.coroutines.CancellationException) {
    throw exception
} catch (exception: Exception) {
    Result.failure(exception)
}

data class DiscoverContactEntry(
    val localName: String,
    val contactId: String,
    val profile: User?,
    val phoneNumber: String? = null
)

enum class DiscoverAction {
    CONNECT,
    PENDING,
    CONNECTED,
    INVITE
}

internal fun acceptedConnectionsForUser(
    connections: List<Connection>,
    userId: String
): List<Connection> = connections.filter { connection ->
    connection.status == ConnectionStatus.ACCEPTED &&
        (connection.requesterId == userId || connection.recipientId == userId)
}

internal fun otherConnectionParticipantId(connection: Connection, userId: String): String? = when (userId) {
    connection.requesterId -> connection.recipientId
    connection.recipientId -> connection.requesterId
    else -> null
}

internal fun normalizeKenyanPhone(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val digits = raw.filter(Char::isDigit)
    if (digits.isEmpty()) return null
    return when {
        digits.matches(Regex("^0[17][0-9]{8}$")) -> "254${digits.drop(1)}"
        digits.matches(Regex("^[17][0-9]{8}$")) -> "254$digits"
        digits.matches(Regex("^254[17][0-9]{8}$")) -> digits
        raw.trim().startsWith("+") && digits.length in 8..15 -> digits
        raw.trim().startsWith("00") && digits.length in 10..17 -> digits.drop(2)
        else -> null
    }
}

internal fun buildContactDirectory(
    deviceContacts: List<DevicePhoneContact>,
    matches: List<ContactProfileMatch>
): List<DiscoverContactEntry> {
    val contactsByPhone = linkedMapOf<String, DevicePhoneContact>()
    deviceContacts.forEach { contact ->
        normalizeKenyanPhone(contact.phoneNumber)?.let { normalized ->
            contactsByPhone.putIfAbsent(normalized, contact.copy(displayName = contact.displayName.ifBlank { "Unknown contact" }))
        }
    }

    val registered = matches.mapNotNull { match ->
        val normalized = normalizeKenyanPhone(match.matchedPhone) ?: return@mapNotNull null
        contactsByPhone[normalized]?.let { contact ->
            DiscoverContactEntry(contact.displayName, contact.contactId, match.profile, contact.phoneNumber)
        }
    }.groupBy { it.profile?.id }.values.mapNotNull { sameProfile ->
        sameProfile.minByOrNull { it.localName.lowercase() }
    }
    val registeredContactIds = registered.mapTo(mutableSetOf()) { it.contactId }
    val registeredNumbers = matches.mapNotNullTo(mutableSetOf()) { normalizeKenyanPhone(it.matchedPhone) }
    val invitations = contactsByPhone.entries
        .filter { (number, contact) -> number !in registeredNumbers && contact.contactId !in registeredContactIds }
        .distinctBy { it.value.contactId }
        .map { (_, contact) -> DiscoverContactEntry(contact.displayName, contact.contactId, null, contact.phoneNumber) }

    return (registered + invitations).sortedWith(
        compareBy<DiscoverContactEntry> { it.profile == null }
            .thenBy { it.localName.lowercase() }
            .thenBy { it.contactId }
    )
}

internal fun buildDiscoverInviteMessage(contactName: String?): String {
    val safeName = contactName?.trim()?.ifBlank { "friend" } ?: "friend"
    return "Hey $safeName! Kash.ke helps you lend and borrow with people you trust and manage repayments. Join me: https://kash.ke | Google Play: https://play.google.com/store/apps/details?id=com.kashke.app"
}

internal fun searchDiscoverEntries(
    query: String,
    contactEntries: List<DiscoverContactEntry>,
    databaseProfiles: List<User>
): List<DiscoverContactEntry> = if (query.isBlank()) {
    contactEntries
} else {
    val normalizedQuery = query.trim()
    val digitsQuery = normalizedQuery.filter(Char::isDigit)
    val matchingContacts = contactEntries.filter { entry ->
        entry.localName.contains(normalizedQuery, ignoreCase = true) ||
            (digitsQuery.isNotEmpty() && entry.phoneNumber.orEmpty().filter(Char::isDigit).contains(digitsQuery))
    }
    val contactProfileIds = matchingContacts.mapNotNullTo(mutableSetOf()) { it.profile?.id }
    val matchingProfiles = databaseProfiles
        .filterNot { it.id in contactProfileIds }
        .map { DiscoverContactEntry(it.name, it.id, it) }
    val sortMatches = compareBy<DiscoverContactEntry> { it.localName.lowercase() }
        .thenBy { it.phoneNumber.orEmpty() }
        .thenBy { it.contactId }
    matchingContacts.sortedWith(sortMatches) + matchingProfiles.sortedWith(sortMatches)
}

internal fun unconnectedDiscoverContacts(
    entries: List<DiscoverContactEntry>,
    connections: List<Connection>,
    currentUserId: String
): List<DiscoverContactEntry> = entries.filter { entry ->
    val profileId = entry.profile?.id
    profileId != null && connections.none { connection ->
        (connection.requesterId == currentUserId && connection.recipientId == profileId ||
            connection.recipientId == currentUserId && connection.requesterId == profileId) &&
            connection.status in setOf(ConnectionStatus.ACCEPTED, ConnectionStatus.PENDING)
    }
}

internal fun discoverAction(isRegistered: Boolean, status: ConnectionStatus?): DiscoverAction = when {
    !isRegistered -> DiscoverAction.INVITE
    status == ConnectionStatus.ACCEPTED -> DiscoverAction.CONNECTED
    status == ConnectionStatus.PENDING -> DiscoverAction.PENDING
    else -> DiscoverAction.CONNECT
}