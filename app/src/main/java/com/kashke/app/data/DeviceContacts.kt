package com.kashke.app.data

import android.content.Context
import android.content.pm.PackageManager
import android.Manifest
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun mapDevicePhoneContact(contactId: String?, displayName: String?, phoneNumber: String?): DevicePhoneContact? {
    val id = contactId?.trim().orEmpty()
    val number = phoneNumber?.trim().orEmpty()
    if (id.isBlank() || normalizeKenyanPhone(number) == null) return null
    return DevicePhoneContact(id, displayName?.trim().takeUnless { it.isNullOrBlank() } ?: "Unknown contact", number)
}

suspend fun readDevicePhoneContacts(context: Context): List<DevicePhoneContact> = withContext(Dispatchers.IO) {
    check(ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
        "READ_CONTACTS permission was not granted"
    }
    val projection = arrayOf(
        ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
        ContactsContract.CommonDataKinds.Phone.NUMBER
    )
    val cursor = context.contentResolver.query(
        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        projection,
        null,
        null,
        "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE"
    ) ?: error("Contacts provider returned a null cursor")

    cursor.use {
        val idIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
        val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
        val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
        check(idIndex >= 0 && numberIndex >= 0) { "Contacts provider omitted required projected columns" }

        val seenNumbers = mutableSetOf<String>()
        val results = mutableListOf<DevicePhoneContact>()
        while (it.moveToNext()) {
            try {
                val contact = mapDevicePhoneContact(
                    it.getString(idIndex),
                    if (nameIndex >= 0) it.getString(nameIndex) else null,
                    it.getString(numberIndex)
                ) ?: continue
                val normalized = normalizeKenyanPhone(contact.phoneNumber) ?: continue
                if (seenNumbers.add(normalized)) results += contact
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                android.util.Log.w("KashKeDiscover", "Skipping an unreadable contact row: ${exception.javaClass.simpleName}")
            }
        }
        results
    }
}