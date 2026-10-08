package com.kashke.app.data

internal fun numericInput(previous: String, candidate: String, maxLength: Int = 10): String =
    candidate.takeIf { it.length <= maxLength && it.all { c -> c in '0'..'9' } } ?: previous

internal fun phoneInput(previous: String, candidate: String): String {
    val prefix = when {
        candidate.startsWith('+') -> "+254"
        candidate.startsWith('0') -> "0"
        else -> "254"
    }
    val partialPrefix = prefix.startsWith(candidate)
    val suffix = candidate.removePrefix(prefix)
    val valid = partialPrefix || (candidate.startsWith(prefix) && suffix.length <= 9 &&
        suffix.matches(Regex("[17][0-9]{0,8}")))
    return candidate.takeIf { valid } ?: previous
}

internal fun strictKenyanPhone(value: String): String? = when {
    value.matches(Regex("0[17][0-9]{8}")) -> "254" + value.drop(1)
    value.matches(Regex("\\+254[17][0-9]{8}")) -> value.drop(1)
    value.matches(Regex("254[17][0-9]{8}")) -> value
    else -> null
}

internal fun emailInput(previous: String, candidate: String): String = candidate.takeIf {
    it.length <= 254 && it.all { c -> c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '.' || c == '@' }
} ?: previous

internal fun authIdentifierInput(previous: String, candidate: String): String =
    if (candidate.startsWith('+') || (candidate.isNotEmpty() && candidate.all { it in '0'..'9' }))
        phoneInput(previous, candidate)
    else emailInput(previous, candidate)

internal fun validEmail(value: String): Boolean {
    if (value.length !in 6..254 || !value.matches(Regex("[A-Za-z0-9]+(?:\\.[A-Za-z0-9]+)*@[A-Za-z0-9]+(?:\\.[A-Za-z0-9]+)+"))) return false
    val parts = value.split('@')
    return parts[0].length <= 64 && parts[1].split('.').all { it.length <= 63 } &&
        parts[1].substringAfterLast('.').matches(Regex("[A-Za-z]{2,63}"))
}

internal fun nameInput(previous: String, candidate: String): String = candidate.takeIf {
    it.length <= 80 && it.all { c -> c.isLetter() || c == ' ' }
} ?: previous
internal fun validName(value: String): Boolean = value.trim().length in 2..80 &&
    value.all { it.isLetter() || it == ' ' }

internal fun strongRegistrationPassword(password: String): Boolean = password.length in 8..12 &&
    password.all { it.code in 33..126 } && password.any { it in 'A'..'Z' } &&
    password.any { it in 'a'..'z' } && password.any { it in '0'..'9' } &&
    password.any { !it.isLetterOrDigit() }

internal const val REGISTRATION_PASSWORD_HINT = "Use 8–12 characters with uppercase, lowercase, a number and a symbol. No spaces."

internal fun dialogInput(label: String, previous: String, candidate: String): String = when {
    label == "Full name" -> nameInput(previous, candidate)
    label == "Username" -> candidate.takeIf { it.length <= 24 && it.all { c -> c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' } } ?: previous
    label == "Email" -> emailInput(previous, candidate)
    label == "Phone number" -> phoneInput(previous, candidate)
    label.contains("days", ignoreCase = true) -> numericInput(previous, candidate, 3)
    else -> numericInput(previous, candidate)
}
