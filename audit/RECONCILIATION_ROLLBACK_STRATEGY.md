# Reconciliation rollback strategy

The proposed migration is transactional and makes no financial data changes during deployment. An assertion, lock timeout, dependency restriction, duplicate mobile or schema mismatch before COMMIT rolls back the whole proposal. If SQL Editor leaves a failed transaction open, issue ROLLBACK in that session before doing further work. Do not remove assertions to force deployment.

Before approved deployment:

1. Export the deployment-time schema definitions, function ACLs, table/column grants, policies, indexes and trigger definitions. Keep the supplied `hosted-contract-snapshot.json` and the reviewed migration SHA-256. The supplied snapshot is evidence from its capture time, not a complete backup of all objects.
2. Take the project's normal backup/PITR checkpoint. Note whether pending offers match wallet reservations and whether legacy repayment receipt shape satisfies preflight. Do not change live financial rows during review.
3. Validate the proposal on staging with representative legacy UUID payment retries, active reservations and actual PostgREST requests. Confirm unrelated public definers do not trip the fail-closed scope assertion.

After deployment, before any new financial action, a controlled rollback can restore **individual reviewed function definitions and access settings** from the deployment-time export in a new owner transaction. Do not automatically resurrect the integer terms overload or public access to maintenance/Trust helpers. Additive fields/indexes can remain; dropping them is unnecessary and can break the current app. Restoring older APIs requires coordinating the APK/API contract and will remove support for current actions; keep affected actions unavailable until the safe patch is ready.

After any new financial action, use a reviewed forward repair rather than a blanket schema rollback:

- Keep lender_contribution, original deadlines, loan/payment/transaction/notification rows, reservation transitions, Trust events, daily penalty receipts and all new operation UUID receipts.
- Keep the new receipt wrapper, or another reviewed wrapper that consults **both** the old repayment_operations and new loan_payments receipts. Restoring the old text wrapper alone cannot see new receipts and risks charging an ambiguous retry again.
- Keep borrower-explicit offer creation and contribution-explicit acceptance. Do not restore canonical historical writers that omit real hosted required fields.
- Preserve revised grace history and persisted grant fields. Do not allow a second extension by restoring an API that ignores those fields.
- Keep private-data and money-write privileges narrow. Reverting security tightening can expose arbitrary-user mutation again.
- Stop new financial actions through the application's operational controls while assessing an accounting problem; leave reads available where practical. Reconcile discrepancies with a separately reviewed financial repair, never by resetting wallets or overwriting live rows.

No executable blanket rollback migration is supplied because it would reintroduce the proven mismatches and cannot safely undo financial actions after COMMIT. The captured old function definitions support targeted review; they are not instructions to replay the old application generation.
