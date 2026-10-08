# Contact form and private email delivery

The Android Contact Us screen links to https://kash.ke, info@kash.ke, support@kash.ke and WhatsApp +254712203900. Its authenticated form collects a name, reply email, subject and message. The receiving inbox is configured exclusively in Edge Function secrets and is absent from Android code and responses.

## Install

1. Run the plain SQL in `supabase/CONTACT_SETUP.sql` in the Supabase SQL Editor. It combines migrations `202610060006_contact_requests.sql` and `202610060007_contact_email_dispatch.sql`, in that order. Both are rerunnable.
2. In Supabase → Edge Functions → Secrets, set `RESEND_API_KEY` and `CONTACT_MAIL_FROM` using a verified Resend sender. Never add these values to Android configuration or paste private keys into chat.
3. Reply that the SQL and sender are configured so live saving and delivery can be verified.

The protected `send-contact-mail` worker has been deployed. Its private destination and authentication secret have been configured on the server and in Vault. Missing sender credentials return HTTP 503 before claiming any queued submission. Messages saved while email delivery is unavailable remain pending.

## Behavior and security

- Forms are saved by an authenticated SQL RPC. The account identity comes from `auth.uid()`, with parameterized values; there is no SQL constructed from user input.
- App and database validation enforce lengths, an email format, and allowed characters. Names allow letters, spaces, apostrophes, dots and hyphens; subjects and messages allow letters, digits and common punctuation, with line breaks in messages. Markup, control characters and bidirectional controls are rejected.
- Submissions are limited to five per account per hour and separated by 30 seconds. An advisory lock serializes submissions per account. Client-generated UUIDs make uncertain network retries idempotent; duplicate IDs cannot be used by another account.
- Ordinary users have no direct table read/write privileges and cannot invoke delivery functions. The app can submit its own form only. Backend service credentials manage the queue. Vault secrets are inaccessible to ordinary users.
- Database inserts dispatch the email worker asynchronously. A five-minute cron schedule retries pending deliveries. Row locks and per-claim leases prevent concurrent workers from duplicating work.
- Emails use plain text, a fixed server-side recipient, a fixed subject with the submission reference, and a validated reply-to address. A provider idempotency key protects retries after uncertain outcomes. Failed attempts back off; after eight attempts the record is marked FAILED for operator review.
- Successful provider submission is recorded as SENT. This confirms acceptance by the email provider, not arrival in the destination inbox. SENT and FAILED submissions remain available for operator review; establish a retention/deletion policy appropriate to support needs.
- Privacy and terms screens no longer show review-before-launch or publication instructions. The privacy policy now describes support-form processing and private email forwarding. Both English and Swahili were updated.

## Validation completed

- Android APK built and 94 JVM tests passed.
- 56 SQL checks passed across fresh and hosted schema fixtures, covering validation, privacy, idempotency, rate limiting, delivery claims, stale leases, retry status, trigger dispatch and cron setup. Extension dispatch APIs were emulated locally; no real emails were sent by those tests.
- 16 worker checks passed with a mocked email provider: private recipient, reply-to, plain text, authentication, missing configuration, successful completion and retries.
- Deployed worker rejects requests without the server secret with HTTP 401.
- Live form persistence, the hosted trigger/cron path and actual provider delivery remain pending SQL installation and sender configuration.
- Subsequent live verification confirmed the contact SQL, insert trigger, active retry job and all sender secrets. Authenticated form saving, idempotent retries, private table access and markup rejection passed against hosted Supabase. Resend rejected the labelled delivery test with HTTP 403 / validation_error because the configured sender domain is unverified. The disposable account and unsent submission were removed. Domain verification is required before actual forwarding can be confirmed.
- Desktop `KashKe.apk` was replaced and its SHA-256 matched the final build. The final APK was installed on the connected phone; physical screen review remains behind the device's fingerprint unlock.

Run local backend checks from the project root:

```text
node tools/sql-check/verify-contact.mjs
node tools/sql-check/verify-contact-mail.mjs
```
