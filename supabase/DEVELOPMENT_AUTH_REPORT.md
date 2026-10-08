# Development authentication — 4 October 2026

Superseded by real Email/password signup and required profile phone entry. Internal-email mapping remains only for compatibility with accounts created by this earlier development build. See [EMAIL_AUTH_REPORT.md](EMAIL_AUTH_REPORT.md).

The phone/password UI now uses Supabase Email/password internally. Both registration and phone login call `internalAuthEmail`, which reuses `registrationPhone` and its existing Kenyan normalizer. `0715501556`, `+254715501556`, `254715501556`, and `715501556` all produce `254715501556@auth.kashke.local`. Kenyan 01 numbers use the same rules.

Registration retains the existing phone metadata (`+254715501556` in the existing E.164 format). Existing profile provisioning reads that metadata into `profiles.phone`; wallet provisioning and all SQL/RPCs remain unchanged. The authenticated UUID remains the application identity. Internal emails are filtered from the application identity and profile email fallback, and redacted from diagnostics. Legacy real-email login remains available through the existing repository branch. Password reset cannot send email to internal identifiers.

Before testing, open the project matching the app's SUPABASE_URL. Enable Email authentication, enable Allow new users to sign up, and disable Confirm email. Phone authentication and an SMS provider are unnecessary. These hosted settings were not changed from this workspace. With confirmation enabled, Supabase may attempt to send a signup email and withhold the session; configure the dashboard before registering. The client does not fabricate verification or bypass server settings.

Sources: [Supabase Auth configuration](https://supabase.com/docs/guides/auth/general-configuration), [signup session behavior](https://supabase.com/docs/reference/swift/auth-signup).

Existing Auth users are untouched. Accounts created through Phone Auth or a different email identifier cannot automatically use the new phone login mapping. Use new development accounts; an existing profile phone constraint may reject reuse of a phone already belonging to another account. No migration or deletion is performed.

Changed files:

- app/src/main/java/com/kashke/app/data/repository/DevelopmentAuth.kt
- app/src/main/java/com/kashke/app/data/repository/SupabaseRepository.kt
- app/src/main/java/com/kashke/app/data/repository/SupabaseDataRepository.kt (only profile email fallback filtering)
- app/src/main/java/com/kashke/app/data/repository/SafeErrors.kt
- app/src/main/java/com/kashke/app/KashKeApp.kt (only missing-session guidance)
- app/src/test/java/com/kashke/app/data/repository/DevelopmentAuthTest.kt
- app/src/test/java/com/kashke/app/data/repository/SafeErrorsTest.kt
- tools/verify-development-auth.gradle
- supabase/DEVELOPMENT_AUTH_REPORT.md

Validation command: `gradlew.bat :app:testDebugUnitTest :app:assembleDebug --offline -I tools/verify-development-auth.gradle`. Output uses `app/build-development-auth` to avoid the existing build lock. Tests cover equivalent 07/01 inputs, shared signup/login helper call sites, metadata retention, hidden internal identity, safe credential and duplicate errors, and diagnostic redaction. Hosted signup/profile/wallet success still requires a device check after dashboard configuration.

Result: BUILD SUCCESSFUL. The full debug unit suite passed and the debug APK was assembled at `app/build-development-auth/outputs/apk/debug/app-debug.apk`. The signup/login wiring check is a source contract test; it does not contact hosted Supabase.
