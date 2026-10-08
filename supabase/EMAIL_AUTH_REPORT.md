# Email signup and required profile phone — 4 October 2026

New users fill in full name, username, Kenyan phone number, email and password at signup. The app validates these details before creating the Auth account and checks username availability. Supabase Email/password Auth uses the real email address. All profile details are included in Auth metadata so they survive email confirmation. During account initialization, the app provisions the profile and saves the required details through `complete_member_profile` before loading the dashboard. It verifies the saved profile is complete; a failed save stays on account setup with Retry rather than presenting an apparently successful signup.

Existing incomplete accounts can still edit their profile. New signups complete it from the signup form and proceed to Home after account setup. The existing phone normalizer supports 07/01, +254, 254, and nine-digit mobile formats. Phones are stored in profiles.phone as +254…; the contact email remains private in profiles.profile_email. Phone entry does not verify ownership or become the email account's login credential.

The profile-only SQL update adds a four-argument complete_member_profile overload and updates the existing phone guard and member_profile_complete predicate. It permits the authenticated owner to fill an empty phone once, validates/normalizes it, retains canonical uniqueness, and saves the required fields atomically. Existing nonempty phones cannot be replaced through this form. The prior three-argument API is retained for older clients. Loans, wallet balances, offers, limits, Trust/reliability, Discover and connection implementations are unchanged. Existing profile/wallet provisioning is preserved.

## Deployment

1. Run [email_auth_setup.sql](email_auth_setup.sql) in the existing project's SQL Editor. This is a transaction containing only the new profile update; do not replay the old agreement report or loan-interest SQL for this change. Duplicate existing canonical phone numbers cause the transaction to fail rather than modifying accounts.
2. Enable Email Auth and Allow new users to sign up. Phone Auth/SMS configuration is unnecessary.
3. For immediate development signup without email delivery, disable Confirm email. If you keep Confirm email enabled, users must confirm their real mailbox and then sign in; the app shows that instruction when signup yields no session. No fake verification is implemented. [Supabase Auth settings](https://supabase.com/docs/guides/auth/general-configuration)
4. Install the new APK and verify email signup/signin, profile completion with phone, and subsequent account loading on the hosted project. Hosted settings and SQL have not been applied from this workspace.

Existing email accounts without a valid Kenyan phone are now incomplete until they provide one. Existing phones and Auth accounts are preserved. Accounts previously created through Phone Auth are not automatically converted to Email Auth.

## Files changed

- app/src/main/java/com/kashke/app/data/repository/AuthRepository.kt
- app/src/main/java/com/kashke/app/data/repository/SupabaseRepository.kt
- app/src/main/java/com/kashke/app/data/repository/DevelopmentAuth.kt
- app/src/main/java/com/kashke/app/data/repository/SupabaseDataRepository.kt
- app/src/main/java/com/kashke/app/data/repository/SafeErrors.kt
- app/src/main/java/com/kashke/app/data/AgreementPolicy.kt
- app/src/main/java/com/kashke/app/KashKeApp.kt
- app/src/test/java/com/kashke/app/data/repository/DevelopmentAuthTest.kt
- app/src/test/java/com/kashke/app/data/repository/SafeErrorsTest.kt
- app/src/test/java/com/kashke/app/AgreementPolicyTest.kt
- supabase/migrations/202610040006_email_signup_profile_phone.sql
- supabase/email_auth_setup.sql
- tools/sql-check/verify-email-profile.mjs
- tools/sql-check/verify-agreements.mjs (keeps the historical upgrade fixture before the new migration)
- supabase/SIGNUP_LOGCAT_REPORT.md and supabase/DEVELOPMENT_AUTH_REPORT.md (historical notices)
- supabase/EMAIL_AUTH_REPORT.md

## Validation

`node tools/sql-check/verify-email-profile.mjs`: 17 checks passed, covering rerunnable deployment, required/invalid phones, atomic profile completion, duplicate phones, protection of existing phones, 01 normalization, anonymous denial, unchanged Auth email, and wallet provisioning. PGlite tests do not emulate hosted Auth.

Android verification uses `gradlew.bat :app:testDebugUnitTest :app:assembleDebug --offline -I tools/verify-development-auth.gradle`, with output in app/build-development-auth.

Result: BUILD SUCCESSFUL; all 65 Android unit tests passed with zero failures/errors and the debug APK was assembled. `node tools/sql-check/verify-agreements.mjs` also passed all 74 existing agreement/signup checks. APK: `app/build-development-auth/outputs/apk/debug/app-debug.apk`.

Normal-build correction: the standard `app/build` directory fails at `:app:processDebugResources` because Windows prevents deletion of its generated `R.jar`. The root build configuration now uses `build-development-auth` for ordinary Gradle/Android Studio builds; `.gitignore` excludes that output and `tools/verify-build.gradle` uses the same directory. `gradlew.bat :app:assembleDebug :app:testDebugUnitTest --offline` succeeded without an init script (test results reused as up to date). Sync the Gradle project in Android Studio before building again. The locked files were left intact.
