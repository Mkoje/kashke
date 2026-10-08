package com.kashke.app
import org.junit.Assert.*
import org.junit.Test
class NotificationTargetTest {
    private val id = "10000000-0000-0000-0000-000000000001"
    private val user = "20000000-0000-0000-0000-000000000001"
    @Test fun acceptsCompleteIdentifiers() { assertEquals(NotificationTarget(id,user), notificationTargetFrom(id,user)) }
    @Test fun rejectsMissingOrMalformedIdentifiers() {
        for (bad in listOf(null, "", "test", "../other", id + " extra")) {
            assertNull(notificationTargetFrom(bad,user)); assertNull(notificationTargetFrom(id,bad))
        }
    }
    @Test fun rejectsForeignRowsEvenWhenIntentClaimsCurrentAccount() {
        val row = com.kashke.app.data.Notification(id, id, com.kashke.app.data.NotificationType.LOAN_OFFER, "Offer", "Message", false, "2026-10-06")
        assertNull(ownedNotification(NotificationTarget(id,user),user,listOf(row)))
        assertNull(ownedNotification(NotificationTarget(id,id),user,listOf(row)))
        assertEquals(row, ownedNotification(NotificationTarget(id,id),id,listOf(row)))
    }
    @Test fun preservesDistinctTargetsAndRecipients() {
        assertNotEquals(notificationTargetFrom(id,user), notificationTargetFrom(user,id))
        assertNotEquals(notificationTargetFrom(id,user), notificationTargetFrom(user,user))
    }
}
