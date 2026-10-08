# Signup Logcat diagnosis — 4 October 2026

Historical diagnosis: signup now uses real Email/password Auth, followed by required phone entry in Profile. The Phone provider instructions below describe the earlier build. See [EMAIL_AUTH_REPORT.md](EMAIL_AUTH_REPORT.md) for current setup.

At 16:21:21.100, `KashKeRuntime` records `stage=AUTH SIGNUP`, with an `AuthRestException` containing `Phone signups are disabled`.

The blocking failure is Supabase Auth configuration. `SupabaseRepository.register()` correctly calls `signUpWith(Phone)` with a normalized Kenyan phone number and a password. Supabase rejects phone signup before user insertion and profile provisioning. This attempt supplies no evidence of a SQL constraint, trigger, RPC, or RLS failure; those paths still need verification after Auth is enabled.

## Required Supabase change

1. Open the Supabase project used by the installed app's `SUPABASE_URL`.
2. Go to Authentication → Sign In / Providers (Auth Providers) → Phone, enable phone authentication, and save.
3. Check that the general **Allow new users to sign up** setting is enabled.
4. This app's current signup flow expects an immediate authenticated session and has no SMS verification screen. To test that existing flow, **Confirm phone** must be off. This means phone ownership is not verified. If verified phones are required, configure an SMS provider and implement SMS code entry and verification before enabling phone confirmation.
5. Retry signup. Confirm that Auth creates the user, the app proceeds to profile completion, and profile/wallet provisioning succeeds. If another error appears, capture its `KashKeRuntime` stage and underlying exception.

Phone provider settings belong to the Auth service, not application SQL. There is no SQL query to run to fix this error. The selected interest-rate SQL changes loan offers and cannot enable phone signup. Do not replay schema migrations to address this log.

## App fix

`SafeErrors.kt` previously recognized the error code `phone_provider_disabled` and the wording `Phone provider is disabled`, but missed the actual exception message in this log. It now recognizes `Phone signups are disabled`, including wrapped exceptions, and displays `Phone signup is not enabled on the server yet.` A regression test covers all three variants.

## Other log entries

- Skipped frames and the 1504 ms startup frame show UI startup jank. The input-routing warnings alone do not establish an actual ANR; there is no ANR trace in this attachment. They do not explain the explicit Auth rejection.
- SLF4J's missing logger binding affects logging, not signup.
- GPU/EGL warnings, vendor `file does not exist` messages, keyboard warnings, and the boot-loader `NoClassDefFoundError` warning contain no fatal app crash in this capture.
- `No session found` at startup is expected before login/signup.

## Sources

- [Supabase phone/password authentication and confirmation](https://supabase.com/docs/guides/auth/passwords#with-phone)
- [Supabase general Auth configuration](https://supabase.com/docs/guides/auth/general-configuration)
- [Supabase Auth signup implementation](https://github.com/supabase/auth/blob/master/internal/api/signup.go)

Hosted settings were not changed from this workspace. The log establishes the provider rejection; a successful hosted signup must be checked after the dashboard change.

Validation: `:app:testDebugUnitTest --tests com.kashke.app.data.repository.SafeErrorsTest --offline` passed using a separate `build-signup-verification` directory. The normal build directory had a locked generated `R.jar`; the separate build compiled the app and ran the error-handler tests successfully.
