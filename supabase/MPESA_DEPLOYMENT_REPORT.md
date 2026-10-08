# M-Pesa deposit deployment — 6 October 2026

Project: `kmmlybcnluhkfvnelljy`.

- Applied `202610060001_mpesa_deposits.sql` in a transaction to the hosted database.
- Uploaded the six `MPESA_` settings using the supplied deployment token.
- Deployed `mpesa-deposit` and `mpesa-callback`, including their shared module.
- Verified deposit row-level security is enabled.
- Safaricom production OAuth authentication passed.
- Installed the verified APK from `app/build-development-auth/outputs/apk/debug/app-debug.apk` on the connected phone. The older APK in `app/build/` did not contain the deposit flow.
- Submitted one KSh 1 deposit to the user-confirmed test phone number.
- Safaricom returned checkout identifiers, the user approved the prompt, and the deposit completed.
- Receipt: `UJ65Z9QETL`.
- Confirmed wallet balance KSh 10,106, exactly one deposit ledger entry, and exactly one deposit notification.
- Replayed the completed callback: HTTP 200, with no additional wallet credit, ledger entry, or notification.
- Verified the deployed deposit endpoint rejects requests without an authenticated session with HTTP 401.

The wallet held KSh 10,105 before the test and KSh 10,106 afterwards.
This test deposit is completed; it no longer blocks another deposit.

Secrets remain in the ignored `.env`; the token scratch file `supabase/tk.txt`
is also ignored. No M-Pesa PIN is read or stored by the integration.
