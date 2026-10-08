package com.kashke.app

import android.app.Instrumentation
import android.os.Bundle

/** Installed only in the separate test APK; never exposed by the shipping app. */
class NotificationHarness : Instrumentation() {
    private lateinit var args: Bundle
    override fun onCreate(arguments: Bundle) { args = arguments; super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        try {
            val method = SystemNotifications::class.java.getDeclaredMethod("show", android.content.Context::class.java, String::class.java, String::class.java, String::class.java, String::class.java)
            method.isAccessible = true
            val user = requireNotNull(args.getString("user"))
            for (i in 1..2) {
                val id = requireNotNull(args.getString("id$i"))
                check(method.invoke(SystemNotifications, targetContext, id, user, "Notification verification $i", "Tap to open the existing notification record.") == true)
            }
            result.putString("result", "Posted two temporary alerts; no delivery receipts or financial records changed.")
            finish(android.app.Activity.RESULT_OK, result)
        } catch (e: Exception) { result.putString("error", e.toString()); finish(android.app.Activity.RESULT_CANCELED, result) }
    }
}
