package com.kashke.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import com.kashke.app.data.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import java.util.UUID

@Composable
internal fun ContactUs(viewModel: KashKeViewModel, onBusyChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profile by viewModel.authenticatedProfile.collectAsState()
    var name by rememberSaveable { mutableStateOf(profile?.name.orEmpty().take(80)) }
    var email by rememberSaveable { mutableStateOf(profile?.email.orEmpty().take(254)) }
    var subject by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf("") }
    var submissionId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var success by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun open(intent: Intent) {
        try { context.startActivity(intent) }
        catch (_: ActivityNotFoundException) { error = tr("No app is available to open this link.") }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(tr("Get in touch"), style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = { open(Intent(Intent.ACTION_VIEW, Uri.parse("https://kash.ke"))) }) { Text("kash.ke") }
        listOf("info@kash.ke", "support@kash.ke").forEach { address ->
            TextButton(onClick = { open(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$address"))) }) { Text(address) }
        }
        TextButton(onClick = { open(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/254712203900"))) }) { Text("WhatsApp +254712203900") }
        HorizontalDivider()
        Text(tr("Send us a message"), style = MaterialTheme.typography.titleMedium)
        Text(tr("Use letters, numbers and common punctuation. Do not include passwords, PINs or payment credentials."))
        if (success) {
            Text(tr("Your message has been saved. Our support team will respond using your email address."))
            TextButton(onClick = { success = false; subject = ""; message = ""; submissionId = UUID.randomUUID().toString(); attempted = false }) { Text(tr("Send another message")) }
        } else {
            // Freeze a retried payload after an uncertain network result to prevent accidental duplicate submissions.
            val editable = !busy && !attempted
            OutlinedTextField(name, { name = contactNameInput(name,it) }, label = { Text(tr("Full name")) }, singleLine = true, enabled = editable, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(email, { email = contactEmailInput(email,it) }, label = { Text(tr("Reply email")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), singleLine = true, enabled = editable, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(subject, { subject = contactSubjectInput(subject,it) }, label = { Text(tr("Subject")) }, singleLine = true, enabled = editable, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(message, { message = contactMessageInput(message,it) }, label = { Text(tr("Message")) }, minLines = 4, enabled = editable, modifier = Modifier.fillMaxWidth(), supportingText = { Text("${message.length}/4000") })
            Text(tr("Name: 2–80 characters. Subject: 3–120. Message: 10–4000. A valid reply email is required."), style = MaterialTheme.typography.bodySmall)
            Button(enabled = !busy && validContactForm(name,email,subject,message), onClick = {
                busy = true; onBusyChange(true); attempted = true; error = null
                scope.launch {
                    try { viewModel.repository.submitContactRequest(submissionId,name,email,subject,message); success = true }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        val cause = generateSequence(e as Throwable?) { it.cause }.mapNotNull { it.message }.joinToString(" ")
                        error = tr(when {
                            cause.contains("Too many messages",true) -> "Too many messages. Please try again in an hour."
                            cause.contains("wait 30 seconds",true) -> "Please wait 30 seconds before sending another message."
                            else -> "We could not confirm your submission. Tap Retry to check or submit the same message."
                        })
                    }
                    finally { busy = false; onBusyChange(false) }
                }
            }) { Text(tr(if (busy) "Saving..." else if (attempted) "Retry" else "Send message")) }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
