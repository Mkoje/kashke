package com.kashke.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.kashke.app.data.repository.toAuthMessage

@Composable
internal fun SettingsDialogs(viewModel: KashKeViewModel, page: String?, onDismiss: () -> Unit) {
    if (page == null) return
    val biometrics by viewModel.biometricEnabled.collectAsState()
    val muted by viewModel.repository.mutedMembers.collectAsState()
    var names by remember { mutableStateOf<Map<String,String>>(emptyMap()) }
    var lookupFailed by remember { mutableStateOf(false) }
    LaunchedEffect(muted) {
        try { names = viewModel.repository.getPublicProfiles(muted.toList()).associate { it.id to it.name }; lookupFailed = false }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { lookupFailed = true }
    }
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var success by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(tr(page)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (page) {
                "Contact Us" -> ContactUs(viewModel) { busy = it }
                "Language" -> listOf("en" to "English", "sw" to "Kiswahili").forEach { (code, label) ->
                    Row(Modifier.fillMaxWidth().clickable { viewModel.setLanguage(code) }) {
                        RadioButton(selected = AppLanguage.code == code, onClick = { viewModel.setLanguage(code) })
                        Text(label, Modifier.padding(top = 12.dp))
                    }
                }
                "App lock" -> {
                    Text(tr("Use biometric authentication to unlock Kash.ke. Password unlock remains available."))
                    Row { Text(tr("Biometric authentication"), Modifier.weight(1f)); Switch(checked = biometrics, onCheckedChange = viewModel::setBiometricEnabled) }
                }
                "Muted support requests" -> {
                    Text(tr("Mute a member from their profile to hide their support requests and silence new request notifications. Existing agreements remain visible."))
                    if (muted.isEmpty()) Text(tr("No muted requests yet"))
                    if (lookupFailed) Text(tr("Some member names could not be loaded."))
                    muted.forEach { id -> Row {
                        Text(names[id] ?: "Member ${id.take(8)}", Modifier.weight(1f))
                        TextButton(onClick = { viewModel.repository.muteMember(id, false) }) { Text(tr("Unmute")) }
                    } }
                }
                "Change password" -> {
                    if (success) Text(tr("Password changed successfully.")) else {
                        OutlinedTextField(current, { current = it }, label = { Text(tr("Current password")) }, visualTransformation = PasswordVisualTransformation(), enabled = !busy, singleLine = true)
                        OutlinedTextField(replacement, { replacement = it }, label = { Text(tr("New password")) }, visualTransformation = PasswordVisualTransformation(), enabled = !busy, singleLine = true)
                        Text(tr(com.kashke.app.data.REGISTRATION_PASSWORD_HINT))
                        OutlinedTextField(confirmation, { confirmation = it }, label = { Text(tr("Confirm new password")) }, visualTransformation = PasswordVisualTransformation(), enabled = !busy, singleLine = true)
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Button(enabled = !busy && current.isNotBlank() && com.kashke.app.data.strongRegistrationPassword(replacement) && replacement == confirmation && current != replacement, onClick = {
                            busy = true; error = null
                            scope.launch {
                                try { viewModel.changePassword(current, replacement); success = true; current = ""; replacement = ""; confirmation = "" }
                                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                catch (e: Exception) { error = e.toAuthMessage("Could not change password. Check your current password and try again.") }
                                finally { busy = false }
                            }
                        }) { Text(tr(if (busy) "Saving…" else "Change password")) }
                    }
                }
                else -> Text(legalContent(page))
            }
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(tr("Close")) } })
}

internal fun legalContent(page: String): String = if (AppLanguage.code == "sw" && page in setOf("Privacy Policy", "Terms & Conditions")) swahiliLegalContent(page) else when (page) {
    "Privacy Policy" -> """
        Kash.ke Privacy Policy
        Updated 6 October 2026

        Kash.ke helps members connect, request financial support, agree on support terms, and track repayments.

        Information we use
        We process account identifiers, profile details, connections, support requests, offers, agreements, repayment and wallet records, app preferences, and notification device tokens. Passwords are handled by our authentication provider. Contact forms collect your name, reply email, subject and message to handle your enquiry. Submissions are stored securely and forwarded privately to our support inbox through our email provider. If you permit contact access, phone numbers are used to find members you know. Approximate location permission is required to enter the app; this version does not upload or store device coordinates. Biometric checks happen through Android; Kash.ke does not receive your fingerprint or biometric templates.

        How information is used and shared
        We use information to operate accounts, match contacts, display member profiles, manage agreements and payments, send updates, and protect the service. Other members can see the profile and borrowing details shown in the app. Agreement participants can see relevant agreement records. Authentication, hosting, notification and payment providers process the information needed to supply those services. We do not sell personal information.

        Your choices
        You may edit your profile, mute support requests, disconnect members, change your password, select a language, and disable biometric unlocking. Android controls contact, notification and location permissions. Denying location permission prevents app access. Disconnecting or muting does not cancel existing agreements.

        Retention and protection
        We retain information needed to provide the service, resolve disputes and meet applicable recordkeeping duties. Account deletion and access or correction requests should be directed to support@kash.ke. No system can guarantee complete security. Do not share passwords or unlock credentials.

        Children and updates
        The service is intended for adults aged 18 and over. We may update this policy and will communicate material changes.
    """.trimIndent()
    "Terms & Conditions" -> """
        Kash.ke Terms & Conditions
        Updated 6 October 2026

        Using Kash.ke
        Kash.ke lets members connect, request and offer financial support, review terms, and track agreements and repayments. You must be at least 18, provide accurate information, keep your account secure, and use the service lawfully.

        Support agreements
        Review the amount, interest, total repayment, repayment period, and any above-limit disclosure before accepting. Accept only terms you understand and can meet. Funding and repayment obligations follow the terms accepted by the participants. Kash.ke does not guarantee funding, repayment, another member's conduct, or the accuracy of a member's profile or reliability indicators.

        Payments and records
        Check payment details and status before repeating a transaction. Provider charges and processing delays may apply where disclosed. Report discrepancies promptly. Do not treat an unconfirmed payment as settled.

        Member controls
        Muting hides a member's support requests and silences new request alerts. Disconnecting ends the connection. Neither action forgives debt, cancels an agreement, or removes repayment obligations. Location permission is required for app access. You may disable biometrics and use password unlock.

        Responsible use
        Do not commit fraud, harass members, impersonate others, misuse personal information, interfere with the app, or attempt unauthorized access. We may restrict access to investigate abuse or protect members, subject to applicable law. Keep records of your agreements and raise disputes with the other participant and support@kash.ke.

        Availability and changes
        Features may change and outages may occur. These terms do not exclude rights that applicable law protects. Material changes should be communicated before they take effect.
    """.trimIndent()
    "FAQ" -> tr("What does Kash.ke do?\nKash.ke helps you connect with people you know, request financial support, offer support, and track repayments.\n\nHow do I use Kash.ke?\n1. Create an account and complete your profile.\n2. Allow location access and optionally sync contacts.\n3. Find members in Discover and connect.\n4. Create a support request or request support directly from a connection.\n5. Review offers and accept agreed terms.\n6. Follow your agreements and repayments in the app.\n\nYou can mute requests or disconnect a member from their profile. Manage language, biometrics and your password in Settings. Existing debts remain due after muting or disconnecting.")
    "About KashKe" -> tr("Kash.ke connects your community for financial support and repayment tracking. Version 1.0.")
    else -> tr("Support contact details have not been published yet. They will appear here when available.")
}
