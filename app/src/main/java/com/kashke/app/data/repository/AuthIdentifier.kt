package com.kashke.app.data.repository

import com.kashke.app.data.registrationPhone

internal enum class IdentifierType { EMAIL, PHONE }
internal data class AuthIdentifier(val type: IdentifierType, val value: String)
internal fun authIdentifier(raw: String): AuthIdentifier = if ('@' in raw) {
    AuthIdentifier(IdentifierType.EMAIL, registrationEmail(raw))
} else {
    AuthIdentifier(IdentifierType.PHONE, registrationPhone(raw))
}
internal fun Throwable.isInvalidCredentials(): Boolean = generateSequence(this) { it.cause }
    .any { "invalid_credentials" in it.message.orEmpty().lowercase() ||
        "invalid login credentials" in it.message.orEmpty().lowercase() }
