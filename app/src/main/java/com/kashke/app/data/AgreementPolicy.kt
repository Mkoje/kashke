package com.kashke.app.data

import java.time.Instant
import java.time.Duration

internal fun User.hasCompleteProfile(): Boolean = name.isNotBlank() &&
    username.removePrefix("@").matches(Regex("^[a-zA-Z0-9_]{3,24}$")) &&
    !username.removePrefix("@").matches(Regex("^user_[a-f0-9]{19}$")) && isValidProfileEmail(email) &&
    normalizeKenyanPhone(phone)?.matches(Regex("^254[17][0-9]{8}$")) == true

internal fun isValidProfileEmail(email: String): Boolean =
    validEmail(email)

internal fun LoanAgreement.canGrantGrace(now: Instant = Instant.now()): Boolean {
    val deadline = runCatching { Instant.parse(originalDueDate) }.getOrNull() ?: return false
    return graceGrantedAt == null && status in setOf(LoanRequestStatus.ACTIVE,
        LoanRequestStatus.DUE_SOON, LoanRequestStatus.OVERDUE) && amountPaid < totalRepayment &&
        !now.isBefore(deadline.minus(Duration.ofDays(3))) && now.isBefore(deadline.plus(Duration.ofDays(3)))
}

internal fun registrationPhone(raw: String): String {
    val normalized = strictKenyanPhone(raw)
    require(normalized != null && normalized.matches(Regex("^254[17][0-9]{8}$"))) {
        "Enter a valid Kenyan mobile number."
    }
    return "+$normalized"
}

data class PhoneCountry(val iso: String, val dialCode: String, val flag: String)
val supportedPhoneCountries = listOf(PhoneCountry("KE", "+254", "🇰🇪"))
// Future country rollout: enable only after server validation and Auth support are expanded.
// suspend fun countryFromGeolocation(resolveCountryIso: suspend () -> String): PhoneCountry =
//     supportedPhoneCountries.firstOrNull { it.iso == resolveCountryIso() }
//         ?: supportedPhoneCountries.first()
// Future geolocation resolver (enable location permission in the manifest and
// request runtime consent before calling; do not enable for the Kenya launch):
// suspend fun geolocatedCountryIso(context: android.content.Context): String? =
//     kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
//         val manager = context.getSystemService(android.content.Context.LOCATION_SERVICE)
//             as android.location.LocationManager
//         val location = manager.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
//             ?: manager.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
//             ?: return@withContext null
//         android.location.Geocoder(context, java.util.Locale.US)
//             .getFromLocation(location.latitude, location.longitude, 1)
//             ?.firstOrNull()?.countryCode
//     }
// Add future countries to supportedPhoneCountries after backend rollout, then:
// val iso = geolocatedCountryIso(context)
// val selectedCountry = supportedPhoneCountries.firstOrNull { it.iso == iso }
//     ?: supportedPhoneCountries.first()

internal fun profileCompletionFields(user: User): List<Pair<String, String>> = buildList {
    val editing = user.hasCompleteProfile()
    if (editing || user.name.isBlank() || user.name == "Member")
        add("Full name" to user.name.takeUnless { it == "Member" }.orEmpty())
    val username = user.username.removePrefix("@")
    if (editing || !username.matches(Regex("^[a-zA-Z0-9_]{3,24}$")) || username.matches(Regex("^user_[a-f0-9]{19}$")))
        add("Username" to username.takeUnless { it.matches(Regex("^user_[a-f0-9]{19}$")) }.orEmpty())
    if (!isValidProfileEmail(user.email)) add("Email" to "")
    if (normalizeKenyanPhone(user.phone)?.matches(Regex("^254[17][0-9]{8}$")) != true) add("Phone number" to "")
}
internal fun networkEmptyMessage(acceptedCount: Int): String = if (acceptedCount < 5)
    "Make a few connections to get started."
else "Your network is quiet right now. New requests from mutual connections will appear here."
