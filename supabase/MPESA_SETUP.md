# M-Pesa wallet deposits

The Android Wallet > Add Money flow calls an authenticated Supabase Edge Function.
This integration uses M-Pesa Express Buy Goods, with BusinessShortCode 5511828
and receiving PartyB till 4163287. Confirm these are the production identifiers
associated with your approved Daraja app and STK passkey.

## Required configuration

Copy `.env.example` to `.env` and fill in the **production** Daraja consumer key,
consumer secret, and Lipa na M-Pesa Online/STK passkey. These must belong to the
approved app/shortcode. They stay on the server and are never embedded in the APK.
Supabase supplies `SUPABASE_URL` and `SUPABASE_SERVICE_ROLE_KEY` to hosted functions.
Do not use Firebase credentials for M-Pesa.

Apply `migrations/202610060001_mpesa_deposits.sql` after existing migrations.
Then, using your linked Supabase CLI project:

```powershell
supabase secrets set --env-file supabase/.env
supabase functions deploy mpesa-deposit --no-verify-jwt
supabase functions deploy mpesa-callback --no-verify-jwt
```

JWT verification at the gateway is disabled because the deposit function checks
the bearer token itself with Supabase Auth. The callback accepts only a per-request
secret URL and independently queries Daraja before settling a payment. Safaricom
does not send a Supabase JWT. The function generates its public HTTPS callback URL:
`https://<project-ref>.supabase.co/functions/v1/mpesa-callback?id=...&token=...`.
Treat callback URLs, query strings, and stored payloads as confidential.

## Live smoke test

Rebuild/install the app, sign in, open Wallet > Add Money, enter KSh 1 and
`0712203900`, and tap Send M-Pesa prompt. The member enters their PIN on the phone.
Confirm the actual merchant name before approving. Expect one deposit transaction,
one notification, the M-Pesa receipt, and a wallet increase of KSh 1.
Also test cancelling the prompt, an incorrect PIN, and checking status after
closing/reopening the app. Never enter the M-Pesa PIN into KashKe or chat.

Local tests never initiate charges. Live deployment and test results are recorded
in `MPESA_DEPLOYMENT_REPORT.md`.

## Recovery and limits

Amounts are whole KES, from 1 to 150,000. One pending deposit per member prevents
duplicate concurrent prompts. Callback receipts are unique; settlement and wallet/
ledger/notification writes run in one transaction with a row lock.

The app polls for about two minutes and provides Check deposit status afterwards.
Callbacks are saved before querying Daraja; status checks retry verification after
temporary provider failures. A positive STK query alone does **not** credit a wallet:
the matched receipt, amount and phone from the confidential callback are required.

If a success callback never arrives, or the initiation response is lost before its
checkout ID can be saved and no callback arrives, leave the deposit pending. An
operator must reconcile the actual Safaricom transaction/statement before changing
anything. Do not clear pending rows simply to resend a prompt or invent receipts.
Automated statement reconciliation, withdrawals, refunds and C2B manual till
payments are outside this deposit implementation.

Run database/security checks from the repository root:

```powershell
node tools/sql-check/verify-mpesa.mjs
node tools/sql-check/verify-mpesa.mjs hosted
node tools/sql-check/verify-mpesa-functions.mjs
```

API reference: [Safaricom Daraja](https://developer.safaricom.co.ke/apis) and
[Safaricom SDK](https://github.com/safaricom/mpesa-java-sdk/blob/master/src/main/java/Mpesa.java).
