# KashKe push notification setup

The Android app posts high-importance system notifications for saved Supabase notifications. Tapping opens KashKe and keeps its normal fingerprint/password protection. Settings → Test notification checks the Android display and launch behavior independently of Firebase.

**Closed-app delivery is not active until the following setup is completed.** The existing repository polling is only a fallback while the app process is running.

1. Create a Firebase project at https://console.firebase.google.com/. Register an Android app with package name `com.kashke.app`. Download `google-services.json` into `app/google-services.json`. Rebuild and reinstall. The Gradle Google Services plugin is applied only when this file exists. Firebase Analytics is not required.
2. Apply `migrations/202610050005_push_devices.sql` to the existing Supabase project. Tokens are written through an authenticated RPC and are not readable by app users. Reopen the configured app to register the phone.
3. In Firebase → Project settings → Service accounts, generate a service-account key for server-side FCM delivery. Enable Firebase Cloud Messaging API v1 if disabled. Keep this private key out of the app, source tree, and chat.
4. In Supabase → Edge Functions → Secrets, set `FIREBASE_SERVICE_ACCOUNT` to the entire service-account JSON and `PUSH_WEBHOOK_SECRET` to a strong random secret. `SUPABASE_URL` and `SUPABASE_SERVICE_ROLE_KEY` are provided by Supabase on its hosted Edge Functions.
5. Deploy `functions/send-push/index.ts` as `send-push`, with JWT verification disabled. The function authenticates its webhook using `x-push-secret`; it rejects requests without the configured secret.
6. In Supabase → Database Webhooks, create an INSERT webhook on `public.notifications`. Set the HTTP POST URL to `https://<project-ref>.supabase.co/functions/v1/send-push`. Add header `x-push-secret` with the same secret from step 4 and `Content-Type: application/json`.

## Verify delivery

- Enable notifications when Android asks. Check the Account activity channel's “Pop on screen” setting; Do Not Disturb and phone settings can suppress banners.
- Tap Settings → Test notification. Confirm an Android notification appears and tapping it opens KashKe.
- With Firebase configured, sign in, then send a real notification-producing event to that account from a second account. Background KashKe using the Home button. Confirm the alert appears and tapping opens the app.
- Repeat after swiping KashKe out of recents. Android's explicit Settings → Force stop suppresses FCM until the app is reopened.
- Confirm logged-out accounts do not display alerts and two accounts do not receive each other's alerts. Firebase and repository polling use the same persisted delivery receipts to suppress duplicates.

The function loads the notification row and recipient from Supabase, sends data-only high-priority FCM messages, and removes unregistered device tokens. It does not change the existing in-app notification records. Failed webhook deliveries are reported as non-success HTTP responses; inspect/retry them in the webhook and Edge Function logs.

References: https://firebase.google.com/docs/cloud-messaging/android/get-started and https://supabase.com/docs/guides/database/webhooks.
