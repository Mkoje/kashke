package com.kashke.app.data.repository

import com.kashke.app.data.registrationPhone
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun registrationDetails(fullName: String, username: String, phone: String, email: String) = buildJsonObject {
    val cleanUsername = username.trim().removePrefix("@").lowercase(java.util.Locale.ROOT)
    require(com.kashke.app.data.validName(fullName)) { "Use 2–80 letters and spaces for your full name." }
    require(cleanUsername.matches(Regex("^[a-z0-9_]{3,24}$")) &&
        !cleanUsername.matches(Regex("^user_[a-f0-9]{19}$"))) { "Choose a username with 3–24 letters, numbers or underscores." }
    put("full_name", fullName.trim())
    put("username", cleanUsername)
    put("phone", registrationPhone(phone))
    put("profile_email", registrationEmail(email))
}

internal fun registrationEmail(raw: String): String {
    val email = raw.trim().lowercase(java.util.Locale.ROOT)
    require(com.kashke.app.data.isValidProfileEmail(email) && publicAuthEmail(email) != null) {
        "Enter a valid email address."
    }
    return email
}

/** Development-only Auth identifier; never a public profile email or mailbox. */
internal fun internalAuthEmail(phone: String): String =
    registrationPhone(phone).removePrefix("+") + "@auth.kashke.local"

internal fun publicAuthEmail(email: String?): String? =
    email?.takeUnless { it.endsWith("@auth.kashke.local", ignoreCase = true) }
