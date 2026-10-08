package com.kashke.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.kashke.app.data.Notification

/** Displays the existing server notifications; delivery receipts prevent repeated alerts. */
object SystemNotifications {
    private const val CHANNEL = "kashke_activity"
    private const val TAG = "kashke_activity"

    fun initialize(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Account activity", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Support requests, offers and account updates"
            }
        )
    }

    @Synchronized
    fun deliver(context: Context, userId: String, notifications: List<Notification>) {
        if (!canShow(context)) return
        val preferences = context.getSharedPreferences("notification_delivery", Context.MODE_PRIVATE)
        val key = "delivered_$userId"
        val delivered = preferences.getStringSet(key, emptySet()).orEmpty().toMutableSet()
        val eligible = notifications.filter { it.userId == userId && !it.read && it.id !in delivered }
            .sortedBy { it.createdAt }
        // Avoid a burst of historical alerts on first install; the full history stays in the app.
        val batch = if (delivered.isEmpty()) eligible.takeLast(3) else eligible
        if (delivered.isEmpty()) delivered.addAll(eligible.dropLast(3).map { it.id })
        batch.forEach {
                if (show(context, it.id, it.userId, it.title, it.message)) delivered.add(it.id)
            }
        preferences.edit().putStringSet(key, delivered).apply()
    }

    fun clear(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.filter { it.tag?.startsWith("$TAG:") == true }.forEach { manager.cancel(it.tag, it.id) }
    }

    private fun canShow(context: Context): Boolean {
        initialize(context)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        return context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL).importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun brandIcon(context: Context): Bitmap {
        val symbol = BitmapFactory.decodeResource(context.resources, R.drawable.kashke_symbol)
        val bitmap = Bitmap.createBitmap(symbol.width, symbol.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawCircle(symbol.width / 2f, symbol.height / 2f, symbol.width / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        canvas.drawBitmap(symbol, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG))
        return bitmap
    }

    private fun show(context: Context, id: String, userId: String, title: String, message: String): Boolean {
        if (!canShow(context)) return false
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            data = Uri.Builder().scheme("kashke").authority("notification").appendPath(userId).appendPath(id).build()
            putExtra("notification_id", id)
            putExtra("notification_user_id", userId)
        }
        val pending = PendingIntent.getActivity(context, id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(brandIcon(context))
            .setContentTitle(title).setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(pending).setAutoCancel(true).build()
        return try {
            NotificationManagerCompat.from(context).notify("$TAG:$id", 0, notification)
            true
        } catch (_: SecurityException) { false }
    }
}
