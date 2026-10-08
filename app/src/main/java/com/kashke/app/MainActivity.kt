package com.kashke.app

import android.os.Bundle
import android.content.Intent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.kashke.app.ui.theme.KashKeTheme

class MainActivity : FragmentActivity() {
    private var notificationTarget by mutableStateOf<NotificationTarget?>(null)
    private fun readTarget(intent: Intent) {
        notificationTarget = notificationTargetFrom(intent.getStringExtra("notification_id"), intent.getStringExtra("notification_user_id"))
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readTarget(intent)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("notification_id", notificationTarget?.id)
        outState.putString("notification_user_id", notificationTarget?.userId)
        super.onSaveInstanceState(outState)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) notificationTarget = notificationTargetFrom(savedInstanceState.getString("notification_id"), savedInstanceState.getString("notification_user_id"))
        else readTarget(intent)
        enableEdgeToEdge()
        setContent {
            KashKeTheme {
                KashKeApp(notificationTarget = notificationTarget, onNotificationConsumed = { notificationTarget = null; intent.removeExtra("notification_id"); intent.removeExtra("notification_user_id") })
            }
        }
    }
}
