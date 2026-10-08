# Input and financial security — 6 October 2026

Deployed to project `kmmlybcnluhkfvnelljy`. Exported APK: `output/apk/kashke.apk`.

## Input rules

- Phone fields accept exactly ten digits in `07…` or `01…` format, or nine subscriber digits after `+254`/`254`. The phone input rejects spaces, brackets, punctuation, foreign prefixes and excess digits. Read-only contact imports still normalize device formatting.
- All editable amounts and day counts use ASCII digits with field-specific lengths, followed by range checks. Invalid pasted amounts such as `1.5`, `-1`, `1e3` and `1,000` are rejected rather than converted into different amounts.
- Full names accept letters and spaces, 2–80 characters. Usernames retain their existing letters/digits/underscore rule and 3–24-character limit.
- Email fields follow the requested letters/digits/dots/@ policy, with one @, valid dot placement, domain structure and length checks. This deliberately excludes some valid standard email addresses containing `+`, `_`, or `-`.
- Request purpose/description retain their intended letters, digits, spaces, commas and full stops, with 20/150-character limits. Search has a bounded set of characters suited to names, usernames and phone numbers.
- Signup passwords require 8–12 printable characters, with uppercase, lowercase, a digit and a symbol; spaces are rejected. Existing passwords remain usable for sign-in and local unlock.

The app and registration repository enforce the 12-character maximum. Supabase Auth has no native maximum-password-length option; direct Auth clients can submit longer passwords. The server enforces minimum length 8 and all four character categories. Longer passwords are not a financial authorization bypass. A short maximum is a usability policy, not a security improvement.

## Backend controls

- Applied `202610060002_security_hardening.sql` in a transaction and redeployed both M-Pesa functions.
- Removed client write access to financial tables and excessive TRUNCATE grants on lender preferences; preserved required read access and owner RLS.
- Confirmed zero public tables without RLS. App users cannot directly insert/update wallets, insert deposit transactions, call settlement, or read callback secrets.
- Profile and support-request validation runs in PostgreSQL, including direct API/RPC requests. Historic records are not rewritten.
- Pending deposits cannot change owner, amount, phone, secret, or creation time. Provider identifiers and settled status/receipt are immutable.
- STK limits: 5 requests per phone in 10 minutes, 10 per account per hour, and 30 per phone per day. A database advisory lock prevents simultaneous accounts evading the phone limit. These apply to charge initiation, not callbacks or repayments.
- Initiation/callback body limits are 2/8 KiB. Status verification is throttled to one provider query per deposit per 10 seconds.
- Settlement still requires a private callback token, stored request identifiers, matched amount/phone/receipt, and independent Safaricom verification. The wallet, ledger and notification commit atomically; retries cannot duplicate credit.
- Enabled password-change reauthentication, refresh-token rotation and password-change notification emails in Supabase. Delivery still depends on functioning email/SMTP configuration.
- Disabled cleartext HTTP in Android; session encryption and the existing fingerprint/password lock remain in place. No server key is embedded in the APK.

Character restrictions are not the SQL-injection defense. Database queries remain parameterized, financial permissions are restricted, and the server checks authorization independently of client values.

## Verification

- All 85 Android unit tests passed; debug APK built, exported, and SHA-256 checked against the verified build.
- 20 SQL security checks passed against each of the fresh and captured hosted schemas: money/Trust tampering, row visibility, unauthorized settlement, null/incorrect tokens, immutable deposits, invalid profiles and cross-account phone limits.
- Function tests passed for malformed phone numbers, excess body sizes and rate limiting; rejected requests never sent an STK prompt.
- Installed the exported build on the connected phone. Device checks confirmed local/international phone length limits and rejection of numeric full stops. The test form was closed without submitting a payment.
- Live Supabase rejected a weak signup password with HTTP 422 (`weak_password`) and a forged callback with HTTP 401.
- Live privilege inspection confirmed all tested financial mutation/settlement/secret-access flags are false. Deployment left deposit counts and completed totals unchanged. No new payment was requested during this hardening work.

## Owner actions before public money use

1. Enable MFA on the Supabase owner account and any connected GitHub account; limit administrative membership. A stolen administrator/service key can bypass app-user RLS, so these controls are essential.
2. Rotate any credentials exposed in chat, logs or version control, including the previously pasted STK passkey, and use a secret manager for production deployments. Update both KashKe and KahayaDrive when rotating their shared M-Pesa credentials. Ignored files are not encrypted storage.
3. Configure verified email/SMS onboarding, recovery delivery and app-account MFA before launch. Current email/SMS auto-confirm is still enabled for the existing development signup flow. A local fingerprint lock does not verify phone ownership or add a second factor to a stolen API session.
4. Configure CAPTCHA with an appropriate provider and enable leaked-password protection if available on your plan. These are not enabled by this change; they require provider/plan configuration.
5. Establish tested backups, transaction reconciliation against Safaricom statements, monitoring and alerts for failed confirmations, abnormal activity and permission changes. Restrict direct database connections to trusted networks where practical.
6. Distribute a signed production release, not this debugging APK, and arrange an independent security assessment before handling public funds at scale.

No input filter or database policy can make an application impossible to hack. This release protects the tested app-user and forged-payment paths; compromised administrative credentials require incident response and credential revocation.

References: [Supabase password security](https://supabase.com/docs/guides/auth/password-security), [API security](https://supabase.com/docs/guides/api/securing-your-api), [production checklist](https://supabase.com/docs/guides/deployment/going-into-prod), [MFA](https://supabase.com/docs/guides/auth/auth-mfa).
