# Settings changes — 6 October 2026

Implemented:

- Member profiles offer mute/unmute support requests and disconnect with confirmation.
- Settings lists muted members and lets the account unmute them.
- Muted borrowers are excluded from the support feed and request action counts. A database trigger suppresses new community/direct request notifications. Existing request notifications are removed when the member is muted; agreement and repayment alerts remain.
- Disconnect changes the accepted connection to REMOVED. Existing agreements remain intact. The existing connection-request RPC allows reconnecting, and the profile now exposes that action.
- English is the default. The saved Kiswahili selection updates interface labels and the policy, terms and FAQ. Member-authored content is left as entered. Translations are centralized in AppLanguage.kt; legal text lives in SettingsDialogs.kt and SwahiliLegal.kt.
- Change password checks the current credentials with Supabase Auth before updating the password. No reset email or verification-code request is sent by the app.
- Approximate foreground location permission is requested on entry. App access is blocked when permission is missing; Android settings provides a recovery path after denial. Permission is checked again on resume. Device coordinates are not collected by this change.
- Biometrics default to enabled. Disabling them leaves password unlock available.
- Notification settings now request permission on the first attempt and refresh their status on resume.
- Static FAQ, About, privacy policy and terms screens replace their placeholders. Contact links and the database contact form were subsequently added; see CONTACT_SETUP.md for installation and private email-delivery setup.

## Validation

- Android debug APK built and all 91 JVM unit tests passed, including email/phone password changes, invalid-current-password rejection and language switching.
- 28 SQL assertions passed across fresh and hosted schema fixtures. Tests cover owner-only mute access, self-mute rejection, suppression of direct/community notifications, unmuting, disconnect authorization, direct requests after disconnect, reconnection and repeat installation.
- Run database checks with `node tools/sql-check/verify-settings.mjs` from the project root.
- Build with `gradlew.bat -I tools/verify-offer-ui.gradle :app:assembleDebug :app:testDebugUnitTest --offline` using the existing local Gradle cache.

## Live verification — migration installed

- The installed table, mute RPC, disconnect RPC and notification trigger were verified on hosted Supabase.
- Ten live database checks passed inside a transaction that was rolled back. These covered saved mutes, self-mute rejection, suppression of direct and community alerts, unmuting, removal of existing request alerts, disconnect authorization, disconnected direct-request rejection and reconnection/acceptance.
- Ten live Auth checks passed across disposable email and phone accounts: original credentials worked, wrong credentials failed, password update succeeded without an OTP request, old passwords stopped working and new passwords worked. Both temporary accounts were deleted.
- Authenticated REST reads of `support_request_mutes` passed using `order=muted_user_id.asc`.
- Device testing exposed a paging bug: the generic data reader tried to sort the mute table by a nonexistent `id`. Fixed its ordering to use `muted_user_id`, rebuilt and installed the corrected APK. All 92 unit tests passed. No further migration is required.
- On the connected phone, session setup and settings load successfully. English/Swahili switching immediately translates settings and navigation. Swahili persisted through a process restart.
- Biometrics were enabled initially; the toggle was turned off successfully and restored to enabled.
- A denied-location simulation displayed the required location gate and blocked app entry. The original location permission was restored and the temporary denial flag cleared.
- Privacy policy, terms and FAQ dialogs opened successfully with their content on the device. English was restored after the language tests, biometrics restored to enabled and location permission restored to granted.
- Physical biometric sensor behavior was not independently automated. Actual remote push delivery was not independently observed; database suppression was verified.

## Installation and live verification procedure

The user installed `supabase/migrations/202610060005_settings_controls.sql` after the existing Android contract reconciliation migration, and its hosted objects have been verified. No additional migration is needed for the app query correction.

After installation, test with two connected accounts:

1. Mute the other account from its profile. Create a community and direct request from that account; check the feed, action count, inbox and new push alerts. Unmute and confirm new requests appear.
2. Disconnect. Confirm both accounts no longer list an accepted connection, direct support requests are prevented, existing repayments still work, and a new connection request can be sent and accepted.
3. Change the password with an incorrect current password (must fail), then the correct current password. Confirm the new password signs in and the old one fails.
4. Select Kiswahili and restart the app. Check the saved language, settings, member profile, FAQ and legal screens; switch back to English.
5. Disable biometrics, background the app for at least one minute, and confirm password unlock. Enable biometrics and repeat.
6. Deny location permission and confirm the app blocks entry. Grant it from Android settings and confirm entry works. Revoke permission and return to verify the gate appears again.

Hosted authentication, push delivery, device permissions and biometric hardware require these live checks after the user installs the SQL.
