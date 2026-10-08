# Notification navigation verification

Notification intents carry a notification UUID and recipient UUID, with a unique data URI and immutable PendingIntent. MainActivity handles both initial and subsequent intents and saves an unconsumed destination across recreation. Navigation waits for the existing app unlock and authenticated repository refresh. The destination must match a record owned by the current account; intents do not authorize financial operations.

Notifications opens the selected record, highlights it, and follows its position when marking it read changes the unread-first ordering. Missing records or refresh failures show a Retry notice at the top. Back returns to the dashboard. Explicit logout clears the destination; another account cannot consume it.

The Settings test row and production test helper are removed. The notification symbol is a transparent white brand vector. The larger colored symbol has a white circular background for dark-mode contrast; the adaptive launcher foreground uses a vector directly.

## Reproducing verification

Build with `gradle -I tools/verify-notifications.gradle :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --offline`. The separate output directory avoids Windows resource JAR locks. Install the app APK and androidTest APK from `app/build-notifications/outputs/apk/`.

The separate test APK registers `com.kashke.app.NotificationHarness`. Run `adb shell am instrument -w -e user ACCOUNT_UUID -e id1 NOTIFICATION_UUID_1 -e id2 NOTIFICATION_UUID_2 com.kashke.app.test/com.kashke.app.NotificationHarness` using existing notifications owned by the current account. It posts temporary alerts without modifying delivery receipts or server records. Normal taps may mark those existing records read. Do not tap offer Accept/Reject during routing verification.

The harness is absent from the app APK. Remove its package with `adb uninstall com.kashke.app.test` after verification. Only newly posted alerts contain the new destination; older PendingIntents cannot be updated retroactively.

## Checked on the connected phone

- Cold launch preserved the KSh 6,000 notification through owner unlock; warm navigation opened the separate KSh 2,000 notification.
- A real notification-bar tap opened the corresponding existing record, including its offer actions.
- Already-read notification navigation survived landscape/portrait recreation.
- Back returned to the dashboard; wrong-account intents stayed on the dashboard.
- Missing notification displayed its unavailable message and Retry control.
- Settings showed Notification preferences and omitted Test notification.
- Unit tests cover malformed/missing UUIDs, distinct destinations, and foreign-record ownership rejection.

Financial records were not changed by these tests. The exported APK is a debug build, as in the previous exports.

Final verification: 89 unit tests passed with zero failures. The connected phone displayed the brand in both the small app badge and the larger notification icon. The exported debug APK at `output/apk/kashke.apk` matches the verified `app/build-notifications/outputs/apk/debug/app-debug.apk`; SHA-256: `4a3faa36e1a3d597748c2b4c1ea49ecff95c978461f2c778d097f476797d9258`.
