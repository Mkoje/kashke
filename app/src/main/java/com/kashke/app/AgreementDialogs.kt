package com.kashke.app

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import com.kashke.app.data.LoanAgreement

@Composable
internal fun GraceDeadlineDetails(loan: LoanAgreement) {
    if (loan.graceGrantedAt != null) {
        Text(tr("Grace granted${loan.graceDays?.let { ": $it days" } ?: ""}. No further extension available."))
        Text(tr("Original deadline: ${loan.originalDueDate.substringBefore('T')}"))
    }
}

@Composable
internal fun AgreementActionDialog(
    title: String, labels: List<String>, initial: List<String>, busy: Boolean,
    onDismiss: () -> Unit, submissionError: () -> String? = { null }, onSubmit: suspend (List<String>) -> Boolean
) {
    val fields = remember { initial.toMutableStateList() }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!submitting) onDismiss() }, title = { Text(tr(title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (labels.isEmpty()) Text(tr("The offer will be removed for both people and your reserved funds will become available."))
                labels.forEachIndexed { index, label ->
                    OutlinedTextField(value = fields[index], onValueChange = { fields[index] = com.kashke.app.data.dialogInput(label, fields[index], it) },
                        label = { Text(tr(label)) }, enabled = !submitting && !busy, singleLine = true)
                }
                if (title == "Grant grace period") Text(tr("One extension only, up to 30 days. Days are added to the later of the original deadline and today."))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !submitting && !busy, onClick = {
                submitting = true; error = null
                scope.launch {
                    try { if (!onSubmit(fields.toList())) error = submissionError() ?: "Could not save. Check the values, then retry." }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = "Could not save. Please try again." }
                    finally { submitting = false }
                }
            }) { Text(tr(if (submitting) "Saving…" else "Confirm")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !submitting) { Text(tr("Back")) } })
}
