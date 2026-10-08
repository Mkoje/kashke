package com.kashke.app

data class NotificationTarget(val id: String, val userId: String)
fun notificationTargetFrom(id: String?, userId: String?): NotificationTarget? {
    val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    return if (id != null && userId != null && uuid.matches(id) && uuid.matches(userId)) NotificationTarget(id, userId) else null
}

fun ownedNotification(target: NotificationTarget, accountId: String, rows: List<com.kashke.app.data.Notification>): com.kashke.app.data.Notification? =
    if (target.userId != accountId) null else rows.firstOrNull { it.id == target.id && it.userId == accountId }
