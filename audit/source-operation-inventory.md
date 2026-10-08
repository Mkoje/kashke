# Source database-operation inventory

Generated from project sources; fixtures, historical SQL and rollback definitions are evidence, not deployment recommendations. See DATABASE_CONTRACT_AUDIT.md for callers, parameters, DTOs, downstream features and findings.

## Production Kotlin operation sites

### app/src/main/java/com/kashke/app/data/repository/SupabaseDataRepository.kt

```text
app/src/main/java/com/kashke/app/data/repository/SupabaseDataRepository.kt:12 — import io.github.jan.supabase.postgrest.rpc
app/src/main/java/com/kashke/app/data/repository/SupabaseDataRepository.kt:169 — client.auth.awaitInitialization()
app/src/main/java/com/kashke/app/data/repository/SupabaseDataRepository.kt:172 — val sessionUserId = client.auth.currentSessionOrNull()?.user?.id
app/src/main/java/com/kashke/app/data/repository/SupabaseDataRepository.kt:213 — sessionUserId = client.auth.currentSessionOrNull()?.user?.id,
app/src/main/java/com/kashke/app/data/repository/SupabaseDataRepository.kt:238 — sessionUserId = client.auth.currentSessionOrNull()?.user?.id,
app/src/main/java/com/kashke/app/data/repository/SupabaseDataRepository.kt:280 — val page = client.from(table).select {
app/src/main/java/com/kashke/app/data/repository/SupabaseDataRepository.kt:298 — runLoggedStage("RPC refresh_account_state") { client.postgrest.rpc("refresh_account_state") }
app/src/main/java/com/kashke/app/data/repository/SupabaseDataRepository.kt:301 — client.from("profiles").select {
app/src/main/java/com/kashke/app/data/repository/SupabaseDataRepository.kt:310 — val walletRows = runLoggedStage("DATABASE SELECT wallets") { rows("wallets") }
app/src/main/java/com/kashke/app/data/repository/SupabaseDataRepository.kt:331 — runLoggedStage("DATABASE SELECT loan_requests") { rows("loan_requests") }
app/src/main/java/com/kashke/app/data/repository/SupabaseDataRepository.kt:335 — runLoggedStage("DATABASE SELECT loan_offers") { rows("loan_offers") }
app/src/main/java/com/kashke/app/data/repository/SupabaseDataRepository.kt:339 — runLoggedStage("DATABASE SELECT loans") { rows("loans") }
app/src/main/java/com/kashke/app/data/repository/SupabaseDataRepository.kt:343 — runLoggedStage("DATABASE SELECT connections") { rows("connections") }
app/src/main/java/com/kashke/app/data/repository/SupabaseDataRepository.kt:351 — runLoggedStage("DATABASE SELECT trust_events") { rows("trust_events") }
app/src/main/java/com/kashke/app/data/repository/SupabaseDataRepository.kt:355 — runLoggedStage("DATABASE SELECT transactions") { rows("transactions") }
app/src/main/java/com/kashke/app/data/repository/SupabaseDataRepository.kt:359 — runLoggedStage("DATABASE SELECT notifications") { rows("notifications") }
app/src/main/java/com/kashke/app/data/repository/SupabaseDataRepository.kt:390 — client.postgrest.rpc("public_profile_directory", params).decodeList()
app/src/main/java/com/kashke/app/data/repository/SupabaseDataRepository.kt:416 — val result = client.postgrest.rpc(
app/src/main/java/com/kashke/app/data/repository/SupabaseDataRepository.kt:436 — private suspend fun operation(name: String, params: JsonObject, actingUserId: String = idState.value, onSaved: () -> Unit = {}): Boolean {
app/src/main/java/com/kashke/app/data/repository/SupabaseDataRepository.kt:455 — runLoggedStage("RPC $name") { client.postgrest.rpc(name, params) }
app/src/main/java/com/kashke/app/data/repository/SupabaseDataRepository.kt:485 — private fun launchOperation(name: String, vararg values: Pair<String, Any?>, actingUserId: String = idState.value) {
app/src/main/java/com/kashke/app/data/repository/SupabaseDataRepository.kt:486 — scope.launch { operation(name, params(*values), actingUserId) }
app/src/main/java/com/kashke/app/data/repository/SupabaseDataRepository.kt:490 — operation("create_loan_request", params("p_amount" to amount, "p_purpose" to purpose, "p_description" to description, "p_period_days" to preferredPeriodDays), borrowerId)
app/src/main/java/com/kashke/app/data/repository/SupabaseDataRepository.kt:499 — ): Boolean = operation(
app/src/main/java/com/kashke/app/data/repository/SupabaseDataRepository.kt:512 — operation("edit_loan_request", params("p_request_id" to requestId, "p_amount" to amount, "p_purpose" to purpose, "p_description" to description, "p_period_days" to preferredPeriodDays), borrowerId)
app/src/main/java/com/kashke/app/data/repository/SupabaseDataRepository.kt:513 — suspend fun cancelLoanRequest(requestId: String, borrowerId: String): Boolean = operation("cancel_loan_request", params("p_request_id" to requestId), borrowerId)
app/src/main/java/com/kashke/app/data/repository/SupabaseDataRepository.kt:521 — ): Boolean = operation(
app/src/main/java/com/kashke/app/data/repository/SupabaseDataRepository.kt:533 — val rows = client.postgrest.rpc(
app/src/main/java/com/kashke/app/data/repository/SupabaseDataRepository.kt:546 — operation("request_loan_offer_terms", params("p_offer_id" to offerId, "p_interest_rate" to interestRate, "p_period_days" to repaymentPeriodDays), borrowerId)
app/src/main/java/com/kashke/app/data/repository/SupabaseDataRepository.kt:548 — operation("respond_loan_offer_terms", params("p_offer_id" to offerId, "p_accept_terms" to acceptRequestedTerms), lenderId)
app/src/main/java/com/kashke/app/data/repository/SupabaseDataRepository.kt:565 — val saved = operation("repay_loan_once", params("p_loan_id" to loanId, "p_amount" to amount, "p_operation_id" to operationId), borrowerId)
app/src/main/java/com/kashke/app/data/repository/SupabaseDataRepository.kt:573 — fun acceptLoanOffer(offer: LoanOffer, borrowerId: String) = launchOperation("accept_loan_offer_reviewed", "p_offer_id" to offer.id,
app/src/main/java/com/kashke/app/data/repository/SupabaseDataRepository.kt:575 — fun declineLoanOffer(offerId: String, borrowerId: String) = launchOperation("decline_loan_offer", "p_offer_id" to offerId, actingUserId = borrowerId)
app/src/main/java/com/kashke/app/data/repository/SupabaseDataRepository.kt:576 — fun sendConnectionRequest(requesterId: String, recipientId: String) = launchOperation("send_connection_request", "p_recipient_id" to recipientId, actingUserId = requesterId)
app/src/main/java/com/kashke/app/data/repository/SupabaseDataRepository.kt:577 — fun acceptConnectionRequest(connectionId: String) = launchOperation("respond_connection_request", "p_connection_id" to connectionId, "p_accept" to true)
app/src/main/java/com/kashke/app/data/repository/SupabaseDataRepository.kt:578 — fun rejectConnectionRequest(connectionId: String) = launchOperation("respond_connection_request", "p_connection_id" to connectionId, "p_accept" to false)
app/src/main/java/com/kashke/app/data/repository/SupabaseDataRepository.kt:579 — suspend fun grantGracePeriod(loanId: String, extraDays: Int): Boolean = operation("grant_grace_period", params("p_loan_id" to loanId, "p_extra_days" to extraDays))
app/src/main/java/com/kashke/app/data/repository/SupabaseDataRepository.kt:580 — suspend fun editLoanOffer(offerId: String, amount: Int, rate: Int, days: Int): Boolean = operation("edit_loan_offer", params("p_offer_id" to offerId, "p_amount" to amount, "p_interest_rate" to rate, "p_period_days" to days))
app/src/main/java/com/kashke/app/data/repository/SupabaseDataRepository.kt:581 — suspend fun cancelLoanOffer(offerId: String): Boolean = operation("cancel_loan_offer", params("p_offer_id" to offerId))
app/src/main/java/com/kashke/app/data/repository/SupabaseDataRepository.kt:588 — return operation("complete_member_profile", params("p_full_name" to name, "p_username" to username, "p_email" to email, "p_phone" to cleanPhone), actingUserId) {
app/src/main/java/com/kashke/app/data/repository/SupabaseDataRepository.kt:596 — fun markExternallyRecovered(loanId: String) = launchOperation("mark_recovered_externally", "p_loan_id" to loanId)
app/src/main/java/com/kashke/app/data/repository/SupabaseDataRepository.kt:603 — client.from("notifications").update({ set("read", true) }) { filter { eq("id", notificationId); eq("user_id", idState.value) } }
```

### app/src/main/java/com/kashke/app/data/repository/SupabaseRepository.kt

```text
app/src/main/java/com/kashke/app/data/repository/SupabaseRepository.kt:11 — import io.github.jan.supabase.postgrest.rpc
app/src/main/java/com/kashke/app/data/repository/SupabaseRepository.kt:35 — client.auth.awaitInitialization()
app/src/main/java/com/kashke/app/data/repository/SupabaseRepository.kt:36 — val user = client.auth.currentSessionOrNull()?.user
app/src/main/java/com/kashke/app/data/repository/SupabaseRepository.kt:47 — client.auth.signUpWith(Email) {
app/src/main/java/com/kashke/app/data/repository/SupabaseRepository.kt:62 — client.postgrest.rpc("is_username_available", buildJsonObject {
app/src/main/java/com/kashke/app/data/repository/SupabaseRepository.kt:72 — client.auth.signInWith(Email) {
app/src/main/java/com/kashke/app/data/repository/SupabaseRepository.kt:80 — client.auth.signInWith(Email) {
app/src/main/java/com/kashke/app/data/repository/SupabaseRepository.kt:90 — try { client.auth.signOut(SignOutScope.LOCAL) }
app/src/main/java/com/kashke/app/data/repository/SupabaseRepository.kt:93 — client.auth.clearSession()
app/src/main/java/com/kashke/app/data/repository/SupabaseRepository.kt:103 — runLoggedStage("PASSWORD RESET") { client.auth.resetPasswordForEmail(cleanEmail) }
app/src/main/java/com/kashke/app/data/repository/SupabaseRepository.kt:108 — client.postgrest.rpc("ensure_current_profile")
app/src/main/java/com/kashke/app/data/repository/SupabaseRepository.kt:119 — client.postgrest.rpc("complete_member_profile", buildJsonObject {
app/src/main/java/com/kashke/app/data/repository/SupabaseRepository.kt:132 — val row = client.from("profiles").select { filter { eq("id", identity.id) } }.decodeSingle<ProfileRecord>()
app/src/main/java/com/kashke/app/data/repository/SupabaseRepository.kt:143 — client.from("profiles").update({
```

## Serializable/database mapping files

- app/src/main/java/com/kashke/app/data/MemberProfile.kt
- app/src/main/java/com/kashke/app/data/Models.kt
- app/src/main/java/com/kashke/app/data/repository/DatabaseRecords.kt
- app/src/main/java/com/kashke/app/data/repository/SupabaseRepository.kt

## SQL function definition history (including report rollback blocks)

- supabase/AGREEMENT_SIGNUP_REPORT.md:31 — public.canonical_member_phone(p_phone text)
- supabase/AGREEMENT_SIGNUP_REPORT.md:43 — public.provision_confirmed_user(p_user_id uuid)
- supabase/AGREEMENT_SIGNUP_REPORT.md:65 — public.member_profile_complete(p_user_id uuid)
- supabase/AGREEMENT_SIGNUP_REPORT.md:75 — public.complete_member_profile(p_full_name text,p_username text,p_email text)
- supabase/AGREEMENT_SIGNUP_REPORT.md:89 — public.guard_member_requests()
- supabase/AGREEMENT_SIGNUP_REPORT.md:112 — public.guard_member_phone()
- supabase/AGREEMENT_SIGNUP_REPORT.md:123 — public.grant_grace_period(p_loan_id uuid,p_extra_days integer default 7)
- supabase/AGREEMENT_SIGNUP_REPORT.md:146 — public.edit_loan_offer(p_offer_id uuid,p_amount bigint,p_interest_rate numeric,p_period_days integer)
- supabase/AGREEMENT_SIGNUP_REPORT.md:171 — public.cancel_loan_offer(p_offer_id uuid)
- supabase/AGREEMENT_SIGNUP_REPORT.md:191 — public.maintain_account_data(p_user_id uuid default null)
- supabase/AGREEMENT_SIGNUP_REPORT.md:227 — public.repay_loan(p_loan_id uuid, p_amount bigint)
- supabase/AGREEMENT_SIGNUP_REPORT.md:275 — public.accept_loan_offer_reviewed(p_offer_id uuid,p_expected_amount bigint,p_expected_interest_rate numeric,p_expected_period_days integer)
- supabase/dashboard_setup.sql:31 — public.create_loan_offer(p_request_id uuid, p_amount bigint, p_interest_rate numeric, p_period_days integer, p_approve_above_limit boolean default false)
- supabase/email_auth_setup.sql:7 — public.canonical_member_phone(p_phone text)
- supabase/email_auth_setup.sql:21 — public.member_profile_complete(p_user_id uuid)
- supabase/email_auth_setup.sql:32 — public.guard_member_phone()
- supabase/email_auth_setup.sql:52 — public.complete_member_profile(p_full_name text,p_username text,p_email text,p_phone text)
- supabase/migrations/202609300001_schema.sql:150 — public.handle_new_auth_user()
- supabase/migrations/202609300002_financial_rpcs.sql:1 — public.refresh_profile_limit(p_user_id uuid)
- supabase/migrations/202609300002_financial_rpcs.sql:22 — public.record_trust_event(p_user_id uuid, p_event_type text, p_points integer, p_description text, p_loan_id uuid default null)
- supabase/migrations/202609300002_financial_rpcs.sql:38 — public.send_connection_request(p_recipient_id uuid)
- supabase/migrations/202609300002_financial_rpcs.sql:50 — public.respond_connection_request(p_connection_id uuid, p_accept boolean)
- supabase/migrations/202609300002_financial_rpcs.sql:68 — public.create_loan_request(p_amount bigint, p_purpose text, p_description text, p_period_days integer, p_direct_request_to uuid default null, p_above_limit_requested boolean default false)
- supabase/migrations/202609300002_financial_rpcs.sql:107 — public.edit_loan_request(p_request_id uuid, p_amount bigint, p_purpose text, p_description text, p_period_days integer)
- supabase/migrations/202609300002_financial_rpcs.sql:121 — public.cancel_loan_request(p_request_id uuid)
- supabase/migrations/202609300002_financial_rpcs.sql:130 — public.get_direct_request_borrower_summary(p_request_id uuid)
- supabase/migrations/202609300002_financial_rpcs.sql:171 — public.create_loan_offer(p_request_id uuid, p_amount bigint, p_interest_rate numeric, p_period_days integer, p_approve_above_limit boolean default false)
- supabase/migrations/202609300002_financial_rpcs.sql:217 — public.request_loan_offer_terms(p_offer_id uuid, p_interest_rate numeric, p_period_days integer)
- supabase/migrations/202609300002_financial_rpcs.sql:234 — public.respond_loan_offer_terms(p_offer_id uuid, p_accept_terms boolean)
- supabase/migrations/202609300002_financial_rpcs.sql:254 — public.accept_loan_offer(p_offer_id uuid)
- supabase/migrations/202609300002_financial_rpcs.sql:318 — public.decline_loan_offer(p_offer_id uuid)
- supabase/migrations/202609300002_financial_rpcs.sql:331 — public.repay_loan(p_loan_id uuid, p_amount bigint)
- supabase/migrations/202609300002_financial_rpcs.sql:372 — public.mark_loan_overdue(p_loan_id uuid)
- supabase/migrations/202609300002_financial_rpcs.sql:388 — public.grant_grace_period(p_loan_id uuid, p_extra_days integer default 7)
- supabase/migrations/202609300002_financial_rpcs.sql:405 — public.mark_recovered_externally(p_loan_id uuid)
- supabase/migrations/202610010001_confirmed_profiles.sql:2 — public.provision_confirmed_user(p_user_id uuid)
- supabase/migrations/202610010001_confirmed_profiles.sql:32 — public.handle_new_auth_user()
- supabase/migrations/202610010001_confirmed_profiles.sql:52 — public.ensure_current_profile()
- supabase/migrations/202610010002_immediate_signup.sql:3 — public.provision_confirmed_user(p_user_id uuid)
- supabase/migrations/202610010002_immediate_signup.sql:32 — public.handle_new_auth_user()
- supabase/migrations/202610010002_immediate_signup.sql:45 — public.is_username_available(p_username text)
- supabase/migrations/202610010003_complete_integration.sql:7 — public.derive_offer_borrower()
- supabase/migrations/202610010003_complete_integration.sql:37 — public.touch_updated_at()
- supabase/migrations/202610010003_complete_integration.sql:49 — public.refresh_profile_statistics(p_user_id uuid)
- supabase/migrations/202610010003_complete_integration.sql:67 — public.update_loan_statistics()
- supabase/migrations/202610010003_complete_integration.sql:82 — public.provision_confirmed_user(p_user_id uuid)
- supabase/migrations/202610010003_complete_integration.sql:106 — public.ensure_current_profile()
- supabase/migrations/202610010003_complete_integration.sql:118 — public.release_request_offers(p_request_id uuid)
- supabase/migrations/202610010003_complete_integration.sql:130 — public.cancel_loan_request(p_request_id uuid)
- supabase/migrations/202610010003_complete_integration.sql:142 — public.mark_loan_overdue(p_loan_id uuid)
- supabase/migrations/202610010003_complete_integration.sql:161 — public.maintain_account_data(p_user_id uuid default null)
- supabase/migrations/202610010003_complete_integration.sql:184 — public.refresh_account_state()
- supabase/migrations/202610010003_complete_integration.sql:236 — public.send_connection_request(p_recipient_id uuid)
- supabase/migrations/202610010003_complete_integration.sql:249 — public.respond_connection_request(p_connection_id uuid, p_accept boolean)
- supabase/migrations/202610010003_complete_integration.sql:268 — public.create_loan_request(p_amount bigint, p_purpose text, p_description text, p_period_days integer, p_direct_request_to uuid default null, p_above_limit_requested boolean default false)
- supabase/migrations/202610010003_complete_integration.sql:310 — public.edit_loan_request(p_request_id uuid, p_amount bigint, p_purpose text, p_description text, p_period_days integer)
- supabase/migrations/202610010003_complete_integration.sql:329 — public.get_direct_request_borrower_summary(p_request_id uuid)
- supabase/migrations/202610010003_complete_integration.sql:371 — public.create_loan_offer(p_request_id uuid, p_amount bigint, p_interest_rate numeric, p_period_days integer, p_approve_above_limit boolean default false)
- supabase/migrations/202610010003_complete_integration.sql:423 — public.request_loan_offer_terms(p_offer_id uuid, p_interest_rate numeric, p_period_days integer)
- supabase/migrations/202610010003_complete_integration.sql:442 — public.respond_loan_offer_terms(p_offer_id uuid, p_accept_terms boolean)
- supabase/migrations/202610010003_complete_integration.sql:464 — public.accept_loan_offer(p_offer_id uuid)
- supabase/migrations/202610010003_complete_integration.sql:535 — public.decline_loan_offer(p_offer_id uuid)
- supabase/migrations/202610010003_complete_integration.sql:550 — public.repay_loan(p_loan_id uuid, p_amount bigint)
- supabase/migrations/202610010003_complete_integration.sql:595 — public.grant_grace_period(p_loan_id uuid, p_extra_days integer default 7)
- supabase/migrations/202610010003_complete_integration.sql:613 — public.mark_recovered_externally(p_loan_id uuid)
- supabase/migrations/202610010004_financial_guards.sql:31 — public.repay_loan_once(p_loan_id uuid,p_amount bigint,p_operation_id uuid)
- supabase/migrations/202610020001_private_profile_directory.sql:1 — public.normalize_ke_phone(p_phone text)
- supabase/migrations/202610020001_private_profile_directory.sql:29 — public.public_profile_directory(p_query text default '', p_phone_numbers text[] default array[]::text[], p_profile_ids uuid[] default array[]::uuid[])
- supabase/migrations/202610020002_public_profile_directory_fix.sql:1 — public.normalize_ke_phone(p_phone text)
- supabase/migrations/202610020002_public_profile_directory_fix.sql:23 — public.public_profile_directory(p_query text default '', p_phone_numbers text[] default array[]::text[], p_profile_ids uuid[] default array[]::uuid[])
- supabase/migrations/202610020003_public_profile_directory_final.sql:1 — public.normalize_ke_phone(p_phone text)
- supabase/migrations/202610020003_public_profile_directory_final.sql:23 — public.public_profile_directory(p_query text default '', p_phone_numbers text[] default array[]::text[], p_profile_ids uuid[] default array[]::uuid[])
- supabase/migrations/202610020004_public_profile_directory_safe_projection.sql:3 — public.public_profile_directory(p_query text default '', p_phone_numbers text[] default array[]::text[], p_profile_ids uuid[] default array[]::uuid[])
- supabase/migrations/202610020005_public_profile_directory_contract_fix.sql:6 — public.normalize_ke_phone(p_phone text)
- supabase/migrations/202610020005_public_profile_directory_contract_fix.sql:36 — public.public_profile_directory(p_query text default '', p_phone_numbers text[] default array[]::text[], p_profile_ids uuid[] default array[]::uuid[])
- supabase/migrations/202610030001_direct_request_limits.sql:19 — public.create_loan_request(p_amount bigint, p_purpose text, p_description text, p_period_days integer, p_direct_request_to uuid default null, p_above_limit_requested boolean default false)
- supabase/migrations/202610030001_direct_request_limits.sql:80 — public.create_loan_offer(p_request_id uuid, p_amount bigint, p_interest_rate numeric, p_period_days integer, p_approve_above_limit boolean default false)
- supabase/migrations/202610040001_close_competing_offers.sql:2 — public.accept_loan_offer(p_offer_id uuid)
- supabase/migrations/202610040002_trust_reliability.sql:36 — public.initialize_kash_trust_state()
- supabase/migrations/202610040002_trust_reliability.sql:49 — public.preserve_original_deadline()
- supabase/migrations/202610040002_trust_reliability.sql:63 — public.loan_reliability(p_user_id uuid)
- supabase/migrations/202610040002_trust_reliability.sql:73 — public.refresh_profile_limit(p_user_id uuid)
- supabase/migrations/202610040002_trust_reliability.sql:83 — public.record_trust_event(p_user_id uuid,p_event_type text,p_points integer,p_description text,p_loan_id uuid default null)
- supabase/migrations/202610040002_trust_reliability.sql:162 — public.apply_daily_trust_penalties(p_user_id uuid)
- supabase/migrations/202610040002_trust_reliability.sql:200 — public.refresh_profile_statistics(p_user_id uuid)
- supabase/migrations/202610040002_trust_reliability.sql:217 — public.maintain_account_data(p_user_id uuid default null)
- supabase/migrations/202610040002_trust_reliability.sql:243 — public.mark_loan_overdue(p_loan_id uuid)
- supabase/migrations/202610040002_trust_reliability.sql:257 — public.repay_loan(p_loan_id uuid, p_amount bigint)
- supabase/migrations/202610040002_trust_reliability.sql:302 — public.grant_grace_period(p_loan_id uuid, p_extra_days integer default 7)
- supabase/migrations/202610040002_trust_reliability.sql:320 — public.mark_recovered_externally(p_loan_id uuid)
- supabase/migrations/202610040002_trust_reliability.sql:339 — public.send_connection_request(p_recipient_id uuid)
- supabase/migrations/202610040002_trust_reliability.sql:351 — public.respond_connection_request(p_connection_id uuid, p_accept boolean)
- supabase/migrations/202610040002_trust_reliability.sql:369 — public.create_loan_offer(p_request_id uuid, p_amount bigint, p_interest_rate numeric, p_period_days integer, p_approve_above_limit boolean default false)
- supabase/migrations/202610040002_trust_reliability.sql:440 — public.request_loan_offer_terms(p_offer_id uuid, p_interest_rate numeric, p_period_days integer)
- supabase/migrations/202610040002_trust_reliability.sql:458 — public.respond_loan_offer_terms(p_offer_id uuid, p_accept_terms boolean)
- supabase/migrations/202610040002_trust_reliability.sql:479 — public.decline_loan_offer(p_offer_id uuid)
- supabase/migrations/202610040002_trust_reliability.sql:500 — public.public_profile_directory(p_query text default '', p_phone_numbers text[] default array[]::text[], p_profile_ids uuid[] default array[]::uuid[])
- supabase/migrations/202610040002_trust_reliability.sql:592 — public.get_direct_request_borrower_summary(p_request_id uuid)
- supabase/migrations/202610040002_trust_reliability.sql:614 — public.name_notification_participants()
- supabase/migrations/202610040003_member_profile_details.sql:11 — public.member_profile_details(p_profile_id uuid)
- supabase/migrations/202610040005_support_interest_complete_fix.sql:29 — public.create_loan_offer(p_request_id uuid, p_amount bigint, p_interest_rate numeric, p_period_days integer, p_approve_above_limit boolean default false)
- supabase/migrations/202610040006_email_signup_profile_phone.sql:5 — public.canonical_member_phone(p_phone text)
- supabase/migrations/202610040006_email_signup_profile_phone.sql:19 — public.member_profile_complete(p_user_id uuid)
- supabase/migrations/202610040006_email_signup_profile_phone.sql:30 — public.guard_member_phone()
- supabase/migrations/202610040006_email_signup_profile_phone.sql:50 — public.complete_member_profile(p_full_name text,p_username text,p_email text,p_phone text)
- supabase/rollback/pause_trust_daily_penalties.sql:6 — public.apply_daily_trust_penalties(p_user_id uuid)

## SQL mutation sites (historical, operational and fixture files)

- supabase/AGREEMENT_SIGNUP_REPORT.md:14 — INSERT INTO agreement_release_backup.functions
- supabase/AGREEMENT_SIGNUP_REPORT.md:23 — UPDATE public.loans
- supabase/AGREEMENT_SIGNUP_REPORT.md:28 — UPDATE public.profiles
- supabase/AGREEMENT_SIGNUP_REPORT.md:55 — INSERT INTO public.profiles
- supabase/AGREEMENT_SIGNUP_REPORT.md:59 — INSERT INTO public.wallets
- supabase/AGREEMENT_SIGNUP_REPORT.md:60 — INSERT INTO public.lender_preferences
- supabase/AGREEMENT_SIGNUP_REPORT.md:82 — UPDATE public.profiles
- supabase/AGREEMENT_SIGNUP_REPORT.md:134 — UPDATE public.loans
- supabase/AGREEMENT_SIGNUP_REPORT.md:135 — UPDATE public.profiles
- supabase/AGREEMENT_SIGNUP_REPORT.md:136 — INSERT INTO public.notifications
- supabase/AGREEMENT_SIGNUP_REPORT.md:160 — UPDATE public.wallets
- supabase/AGREEMENT_SIGNUP_REPORT.md:161 — UPDATE public.loan_offers
- supabase/AGREEMENT_SIGNUP_REPORT.md:164 — INSERT INTO public.notifications
- supabase/AGREEMENT_SIGNUP_REPORT.md:180 — UPDATE public.wallets
- supabase/AGREEMENT_SIGNUP_REPORT.md:182 — DELETE FROM public.notifications
- supabase/AGREEMENT_SIGNUP_REPORT.md:183 — DELETE FROM public.loan_offers
- supabase/AGREEMENT_SIGNUP_REPORT.md:184 — INSERT INTO public.notifications
- supabase/AGREEMENT_SIGNUP_REPORT.md:199 — UPDATE public.loans
- supabase/AGREEMENT_SIGNUP_REPORT.md:200 — UPDATE public.profiles
- supabase/AGREEMENT_SIGNUP_REPORT.md:201 — INSERT INTO public.notifications
- supabase/AGREEMENT_SIGNUP_REPORT.md:214 — UPDATE public.loan_requests
- supabase/AGREEMENT_SIGNUP_REPORT.md:220 — UPDATE public.loans
- supabase/AGREEMENT_SIGNUP_REPORT.md:221 — INSERT INTO public.notifications
- supabase/AGREEMENT_SIGNUP_REPORT.md:250 — UPDATE public.wallets
- supabase/AGREEMENT_SIGNUP_REPORT.md:251 — UPDATE public.wallets
- supabase/AGREEMENT_SIGNUP_REPORT.md:252 — UPDATE public.loans
- supabase/AGREEMENT_SIGNUP_REPORT.md:255 — INSERT INTO public.loan_payments
- supabase/AGREEMENT_SIGNUP_REPORT.md:258 — INSERT INTO public.transactions
- supabase/AGREEMENT_SIGNUP_REPORT.md:260 — UPDATE public.profiles
- supabase/AGREEMENT_SIGNUP_REPORT.md:261 — INSERT INTO public.notifications
- supabase/AGREEMENT_SIGNUP_REPORT.md:265 — UPDATE public.profiles
- supabase/AGREEMENT_SIGNUP_REPORT.md:305 — UPDATE public.notifications
- supabase/AGREEMENT_SIGNUP_REPORT.md:306 — UPDATE public.notifications
- supabase/dashboard_setup.sql:88 — UPDATE public.wallets
- supabase/dashboard_setup.sql:90 — INSERT INTO public.loan_offers
- supabase/dashboard_setup.sql:95 — INSERT INTO public.notifications
- supabase/email_auth_setup.sql:70 — UPDATE public.profiles
- supabase/migrations/202609300001_schema.sql:153 — INSERT INTO public.profiles
- supabase/migrations/202609300001_schema.sql:161 — INSERT INTO public.wallets
- supabase/migrations/202609300001_schema.sql:162 — INSERT INTO public.lender_preferences
- supabase/migrations/202609300002_financial_rpcs.sql:17 — UPDATE public.profiles
- supabase/migrations/202609300002_financial_rpcs.sql:28 — INSERT INTO public.trust_events
- supabase/migrations/202609300002_financial_rpcs.sql:30 — UPDATE public.profiles
- supabase/migrations/202609300002_financial_rpcs.sql:32 — INSERT INTO public.notifications
- supabase/migrations/202609300002_financial_rpcs.sql:43 — INSERT INTO public.connections
- supabase/migrations/202609300002_financial_rpcs.sql:44 — INSERT INTO public.notifications
- supabase/migrations/202609300002_financial_rpcs.sql:57 — UPDATE public.connections
- supabase/migrations/202609300002_financial_rpcs.sql:59 — INSERT INTO public.notifications
- supabase/migrations/202609300002_financial_rpcs.sql:84 — INSERT INTO public.loan_requests
- supabase/migrations/202609300002_financial_rpcs.sql:90 — INSERT INTO public.notifications
- supabase/migrations/202609300002_financial_rpcs.sql:98 — INSERT INTO public.notifications
- supabase/migrations/202609300002_financial_rpcs.sql:113 — UPDATE public.loan_requests
- supabase/migrations/202609300002_financial_rpcs.sql:124 — UPDATE public.loan_requests
- supabase/migrations/202609300002_financial_rpcs.sql:207 — UPDATE public.wallets
- supabase/migrations/202609300002_financial_rpcs.sql:208 — INSERT INTO public.loan_offers
- supabase/migrations/202609300002_financial_rpcs.sql:210 — INSERT INTO public.notifications
- supabase/migrations/202609300002_financial_rpcs.sql:226 — UPDATE public.loan_offers
- supabase/migrations/202609300002_financial_rpcs.sql:228 — INSERT INTO public.notifications
- supabase/migrations/202609300002_financial_rpcs.sql:245 — UPDATE public.loan_offers
- supabase/migrations/202609300002_financial_rpcs.sql:248 — INSERT INTO public.notifications
- supabase/migrations/202609300002_financial_rpcs.sql:283 — INSERT INTO public.loans
- supabase/migrations/202609300002_financial_rpcs.sql:288 — UPDATE public.wallets
- supabase/migrations/202609300002_financial_rpcs.sql:290 — UPDATE public.wallets
- supabase/migrations/202609300002_financial_rpcs.sql:291 — UPDATE public.loan_offers
- supabase/migrations/202609300002_financial_rpcs.sql:292 — UPDATE public.loan_requests
- supabase/migrations/202609300002_financial_rpcs.sql:295 — UPDATE public.wallets
- supabase/migrations/202609300002_financial_rpcs.sql:303 — UPDATE public.loan_offers
- supabase/migrations/202609300002_financial_rpcs.sql:306 — INSERT INTO public.transactions
- supabase/migrations/202609300002_financial_rpcs.sql:308 — INSERT INTO public.notifications
- supabase/migrations/202609300002_financial_rpcs.sql:311 — UPDATE public.profiles
- supabase/migrations/202609300002_financial_rpcs.sql:324 — UPDATE public.loan_offers
- supabase/migrations/202609300002_financial_rpcs.sql:325 — UPDATE public.wallets
- supabase/migrations/202609300002_financial_rpcs.sql:326 — INSERT INTO public.notifications
- supabase/migrations/202609300002_financial_rpcs.sql:350 — UPDATE public.wallets
- supabase/migrations/202609300002_financial_rpcs.sql:351 — UPDATE public.wallets
- supabase/migrations/202609300002_financial_rpcs.sql:352 — UPDATE public.loans
- supabase/migrations/202609300002_financial_rpcs.sql:355 — INSERT INTO public.loan_payments
- supabase/migrations/202609300002_financial_rpcs.sql:358 — INSERT INTO public.transactions
- supabase/migrations/202609300002_financial_rpcs.sql:360 — UPDATE public.profiles
- supabase/migrations/202609300002_financial_rpcs.sql:361 — INSERT INTO public.notifications
- supabase/migrations/202609300002_financial_rpcs.sql:378 — UPDATE public.loans
- supabase/migrations/202609300002_financial_rpcs.sql:379 — UPDATE public.profiles
- supabase/migrations/202609300002_financial_rpcs.sql:380 — INSERT INTO public.notifications
- supabase/migrations/202609300002_financial_rpcs.sql:394 — UPDATE public.loans
- supabase/migrations/202609300002_financial_rpcs.sql:395 — UPDATE public.profiles
- supabase/migrations/202609300002_financial_rpcs.sql:399 — INSERT INTO public.notifications
- supabase/migrations/202609300002_financial_rpcs.sql:411 — UPDATE public.loans
- supabase/migrations/202609300002_financial_rpcs.sql:412 — UPDATE public.profiles
- supabase/migrations/202609300002_financial_rpcs.sql:416 — INSERT INTO public.notifications
- supabase/migrations/202610010001_confirmed_profiles.sql:21 — INSERT INTO public.profiles
- supabase/migrations/202610010001_confirmed_profiles.sql:26 — INSERT INTO public.wallets
- supabase/migrations/202610010001_confirmed_profiles.sql:27 — INSERT INTO public.lender_preferences
- supabase/migrations/202610010002_immediate_signup.sql:21 — INSERT INTO public.profiles
- supabase/migrations/202610010002_immediate_signup.sql:26 — INSERT INTO public.wallets
- supabase/migrations/202610010002_immediate_signup.sql:27 — INSERT INTO public.lender_preferences
- supabase/migrations/202610010003_complete_integration.sql:3 — UPDATE public.loan_offers
- supabase/migrations/202610010003_complete_integration.sql:52 — UPDATE public.profiles
- supabase/migrations/202610010003_complete_integration.sql:97 — INSERT INTO public.profiles
- supabase/migrations/202610010003_complete_integration.sql:101 — INSERT INTO public.wallets
- supabase/migrations/202610010003_complete_integration.sql:102 — INSERT INTO public.lender_preferences
- supabase/migrations/202610010003_complete_integration.sql:124 — UPDATE public.wallets
- supabase/migrations/202610010003_complete_integration.sql:125 — UPDATE public.loan_offers
- supabase/migrations/202610010003_complete_integration.sql:138 — UPDATE public.loan_requests
- supabase/migrations/202610010003_complete_integration.sql:148 — UPDATE public.loans
- supabase/migrations/202610010003_complete_integration.sql:149 — UPDATE public.profiles
- supabase/migrations/202610010003_complete_integration.sql:150 — INSERT INTO public.notifications
- supabase/migrations/202610010003_complete_integration.sql:169 — UPDATE public.loan_requests
- supabase/migrations/202610010003_complete_integration.sql:175 — UPDATE public.loans
- supabase/migrations/202610010003_complete_integration.sql:176 — INSERT INTO public.notifications
- supabase/migrations/202610010003_complete_integration.sql:242 — INSERT INTO public.connections
- supabase/migrations/202610010003_complete_integration.sql:243 — INSERT INTO public.notifications
- supabase/migrations/202610010003_complete_integration.sql:257 — UPDATE public.connections
- supabase/migrations/202610010003_complete_integration.sql:259 — INSERT INTO public.notifications
- supabase/migrations/202610010003_complete_integration.sql:287 — INSERT INTO public.loan_requests
- supabase/migrations/202610010003_complete_integration.sql:293 — INSERT INTO public.notifications
- supabase/migrations/202610010003_complete_integration.sql:301 — INSERT INTO public.notifications
- supabase/migrations/202610010003_complete_integration.sql:321 — UPDATE public.loan_requests
- supabase/migrations/202610010003_complete_integration.sql:413 — UPDATE public.wallets
- supabase/migrations/202610010003_complete_integration.sql:414 — INSERT INTO public.loan_offers
- supabase/migrations/202610010003_complete_integration.sql:416 — INSERT INTO public.notifications
- supabase/migrations/202610010003_complete_integration.sql:434 — UPDATE public.loan_offers
- supabase/migrations/202610010003_complete_integration.sql:436 — INSERT INTO public.notifications
- supabase/migrations/202610010003_complete_integration.sql:455 — UPDATE public.loan_offers
- supabase/migrations/202610010003_complete_integration.sql:458 — INSERT INTO public.notifications
- supabase/migrations/202610010003_complete_integration.sql:500 — INSERT INTO public.loans
- supabase/migrations/202610010003_complete_integration.sql:505 — UPDATE public.wallets
- supabase/migrations/202610010003_complete_integration.sql:507 — UPDATE public.wallets
- supabase/migrations/202610010003_complete_integration.sql:508 — UPDATE public.loan_offers
- supabase/migrations/202610010003_complete_integration.sql:509 — UPDATE public.loan_requests
- supabase/migrations/202610010003_complete_integration.sql:512 — UPDATE public.wallets
- supabase/migrations/202610010003_complete_integration.sql:520 — UPDATE public.loan_offers
- supabase/migrations/202610010003_complete_integration.sql:523 — INSERT INTO public.transactions
- supabase/migrations/202610010003_complete_integration.sql:525 — INSERT INTO public.notifications
- supabase/migrations/202610010003_complete_integration.sql:528 — UPDATE public.profiles
- supabase/migrations/202610010003_complete_integration.sql:543 — UPDATE public.loan_offers
- supabase/migrations/202610010003_complete_integration.sql:544 — UPDATE public.wallets
- supabase/migrations/202610010003_complete_integration.sql:545 — INSERT INTO public.notifications
- supabase/migrations/202610010003_complete_integration.sql:572 — UPDATE public.wallets
- supabase/migrations/202610010003_complete_integration.sql:573 — UPDATE public.wallets
- supabase/migrations/202610010003_complete_integration.sql:574 — UPDATE public.loans
- supabase/migrations/202610010003_complete_integration.sql:577 — INSERT INTO public.loan_payments
- supabase/migrations/202610010003_complete_integration.sql:580 — INSERT INTO public.transactions
- supabase/migrations/202610010003_complete_integration.sql:582 — UPDATE public.profiles
- supabase/migrations/202610010003_complete_integration.sql:583 — INSERT INTO public.notifications
- supabase/migrations/202610010003_complete_integration.sql:587 — UPDATE public.profiles
- supabase/migrations/202610010003_complete_integration.sql:602 — UPDATE public.loans
- supabase/migrations/202610010003_complete_integration.sql:603 — UPDATE public.profiles
- supabase/migrations/202610010003_complete_integration.sql:607 — INSERT INTO public.notifications
- supabase/migrations/202610010003_complete_integration.sql:620 — UPDATE public.loans
- supabase/migrations/202610010003_complete_integration.sql:621 — UPDATE public.profiles
- supabase/migrations/202610010003_complete_integration.sql:625 — INSERT INTO public.notifications
- supabase/migrations/202610010004_financial_guards.sql:51 — UPDATE public.loan_payments
- supabase/migrations/202610030001_direct_request_limits.sql:51 — INSERT INTO public.loan_requests
- supabase/migrations/202610030001_direct_request_limits.sql:57 — INSERT INTO public.notifications
- supabase/migrations/202610030001_direct_request_limits.sql:71 — INSERT INTO public.notifications
- supabase/migrations/202610030001_direct_request_limits.sql:136 — UPDATE public.wallets
- supabase/migrations/202610030001_direct_request_limits.sql:138 — INSERT INTO public.loan_offers
- supabase/migrations/202610030001_direct_request_limits.sql:143 — INSERT INTO public.notifications
- supabase/migrations/202610040001_close_competing_offers.sql:41 — INSERT INTO public.loans
- supabase/migrations/202610040001_close_competing_offers.sql:46 — UPDATE public.wallets
- supabase/migrations/202610040001_close_competing_offers.sql:48 — UPDATE public.wallets
- supabase/migrations/202610040001_close_competing_offers.sql:49 — UPDATE public.loan_offers
- supabase/migrations/202610040001_close_competing_offers.sql:50 — UPDATE public.loan_requests
- supabase/migrations/202610040001_close_competing_offers.sql:54 — UPDATE public.wallets
- supabase/migrations/202610040001_close_competing_offers.sql:63 — UPDATE public.loan_offers
- supabase/migrations/202610040001_close_competing_offers.sql:69 — INSERT INTO public.notifications
- supabase/migrations/202610040001_close_competing_offers.sql:74 — INSERT INTO public.transactions
- supabase/migrations/202610040001_close_competing_offers.sql:76 — INSERT INTO public.notifications
- supabase/migrations/202610040001_close_competing_offers.sql:79 — UPDATE public.profiles
- supabase/migrations/202610040002_trust_reliability.sql:7 — UPDATE public.loans
- supabase/migrations/202610040002_trust_reliability.sql:26 — INSERT INTO public.kash_trust_state
- supabase/migrations/202610040002_trust_reliability.sql:39 — INSERT INTO public.kash_trust_state
- supabase/migrations/202610040002_trust_reliability.sql:76 — UPDATE public.profiles
- supabase/migrations/202610040002_trust_reliability.sql:95 — INSERT INTO public.kash_trust_state
- supabase/migrations/202610040002_trust_reliability.sql:108 — INSERT INTO public.trust_events
- supabase/migrations/202610040002_trust_reliability.sql:124 — INSERT INTO public.notifications
- supabase/migrations/202610040002_trust_reliability.sql:138 — INSERT INTO public.notifications
- supabase/migrations/202610040002_trust_reliability.sql:145 — UPDATE public.profiles
- supabase/migrations/202610040002_trust_reliability.sql:147 — UPDATE public.kash_trust_state
- supabase/migrations/202610040002_trust_reliability.sql:151 — INSERT INTO public.notifications
- supabase/migrations/202610040002_trust_reliability.sql:167 — INSERT INTO public.kash_trust_state
- supabase/migrations/202610040002_trust_reliability.sql:187 — INSERT INTO public.kash_trust_daily_penalties
- supabase/migrations/202610040002_trust_reliability.sql:193 — UPDATE public.kash_trust_daily_penalties
- supabase/migrations/202610040002_trust_reliability.sql:203 — UPDATE public.profiles
- supabase/migrations/202610040002_trust_reliability.sql:230 — UPDATE public.loan_requests
- supabase/migrations/202610040002_trust_reliability.sql:236 — UPDATE public.loans
- supabase/migrations/202610040002_trust_reliability.sql:237 — INSERT INTO public.notifications
- supabase/migrations/202610040002_trust_reliability.sql:249 — UPDATE public.loans
- supabase/migrations/202610040002_trust_reliability.sql:250 — UPDATE public.profiles
- supabase/migrations/202610040002_trust_reliability.sql:251 — INSERT INTO public.notifications
- supabase/migrations/202610040002_trust_reliability.sql:280 — UPDATE public.wallets
- supabase/migrations/202610040002_trust_reliability.sql:281 — UPDATE public.wallets
- supabase/migrations/202610040002_trust_reliability.sql:282 — UPDATE public.loans
- supabase/migrations/202610040002_trust_reliability.sql:285 — INSERT INTO public.loan_payments
- supabase/migrations/202610040002_trust_reliability.sql:288 — INSERT INTO public.transactions
- supabase/migrations/202610040002_trust_reliability.sql:290 — UPDATE public.profiles
- supabase/migrations/202610040002_trust_reliability.sql:291 — INSERT INTO public.notifications
- supabase/migrations/202610040002_trust_reliability.sql:295 — UPDATE public.profiles
- supabase/migrations/202610040002_trust_reliability.sql:310 — UPDATE public.loans
- supabase/migrations/202610040002_trust_reliability.sql:311 — UPDATE public.profiles
- supabase/migrations/202610040002_trust_reliability.sql:315 — INSERT INTO public.notifications
- supabase/migrations/202610040002_trust_reliability.sql:328 — UPDATE public.loans
- supabase/migrations/202610040002_trust_reliability.sql:329 — UPDATE public.profiles
- supabase/migrations/202610040002_trust_reliability.sql:333 — INSERT INTO public.notifications
- supabase/migrations/202610040002_trust_reliability.sql:345 — INSERT INTO public.connections
- supabase/migrations/202610040002_trust_reliability.sql:346 — INSERT INTO public.notifications
- supabase/migrations/202610040002_trust_reliability.sql:359 — UPDATE public.connections
- supabase/migrations/202610040002_trust_reliability.sql:361 — INSERT INTO public.notifications
- supabase/migrations/202610040002_trust_reliability.sql:425 — UPDATE public.wallets
- supabase/migrations/202610040002_trust_reliability.sql:427 — INSERT INTO public.loan_offers
- supabase/migrations/202610040002_trust_reliability.sql:432 — INSERT INTO public.notifications
- supabase/migrations/202610040002_trust_reliability.sql:451 — UPDATE public.loan_offers
- supabase/migrations/202610040002_trust_reliability.sql:453 — INSERT INTO public.notifications
- supabase/migrations/202610040002_trust_reliability.sql:471 — UPDATE public.loan_offers
- supabase/migrations/202610040002_trust_reliability.sql:474 — INSERT INTO public.notifications
- supabase/migrations/202610040002_trust_reliability.sql:487 — UPDATE public.loan_offers
- supabase/migrations/202610040002_trust_reliability.sql:488 — UPDATE public.wallets
- supabase/migrations/202610040002_trust_reliability.sql:489 — INSERT INTO public.notifications
- supabase/migrations/202610040002_trust_reliability.sql:645 — UPDATE public.notifications
- supabase/migrations/202610040005_support_interest_complete_fix.sql:86 — UPDATE public.wallets
- supabase/migrations/202610040005_support_interest_complete_fix.sql:88 — INSERT INTO public.loan_offers
- supabase/migrations/202610040005_support_interest_complete_fix.sql:93 — INSERT INTO public.notifications
- supabase/migrations/202610040006_email_signup_profile_phone.sql:68 — UPDATE public.profiles
- supabase/seed.sql:3 — INSERT INTO auth.users
- supabase/seed.sql:15 — UPDATE public.profiles
- supabase/seed.sql:34 — INSERT INTO public.connections
- supabase/seed.sql:42 — INSERT INTO public.loan_requests
- supabase/seed.sql:56 — INSERT INTO public.loan_offers
- supabase/seed.sql:64 — INSERT INTO public.loans
- supabase/seed.sql:73 — UPDATE public.profiles
- supabase/seed.sql:75 — INSERT INTO public.wallets
- supabase/seed.sql:85 — INSERT INTO public.trust_events
- supabase/seed.sql:92 — INSERT INTO public.transactions
- supabase/seed.sql:99 — INSERT INTO public.notifications
- supabase/tests/close_competing_offers.sql:16 — INSERT INTO auth.users
- supabase/tests/close_competing_offers.sql:19 — UPDATE public.wallets
- supabase/tests/close_competing_offers.sql:20 — INSERT INTO public.connections
- supabase/tests/immediate_signup.sql:13 — INSERT INTO auth.users
- supabase/tests/immediate_signup.sql:26 — INSERT INTO auth.users
- supabase/tests/member_profile_details.sql:7 — INSERT INTO auth.users
- supabase/tests/member_profile_details.sql:11 — UPDATE public.profiles
- supabase/tests/member_profile_details.sql:12 — INSERT INTO public.loan_requests
- supabase/tests/member_profile_details.sql:14 — INSERT INTO public.loans
- supabase/tests/member_profile_details.sql:19 — INSERT INTO public.transactions
- supabase/tests/member_profile_details.sql:41 — UPDATE public.loans
- supabase/tests/member_profile_details.sql:45 — INSERT INTO public.loans
- supabase/tests/support_interest.sql:8 — INSERT INTO auth.users
- supabase/tests/support_interest.sql:11 — INSERT INTO public.connections
- supabase/tests/support_interest.sql:12 — UPDATE public.wallets
- supabase/tests/support_interest.sql:13 — INSERT INTO public.lender_preferences
- supabase/tests/support_interest.sql:15 — UPDATE public.lender_preferences
- supabase/tests/support_interest.sql:17 — UPDATE public.profiles
- supabase/tests/support_interest.sql:48 — UPDATE public.profiles
- supabase/tests/support_interest.sql:58 — UPDATE public.profiles
- supabase/tests/trust_reliability.sql:8 — INSERT INTO auth.users
- supabase/tests/trust_reliability.sql:15 — INSERT INTO public.loan_requests
- supabase/tests/trust_reliability.sql:17 — INSERT INTO public.loans
- supabase/tests/trust_reliability.sql:21 — UPDATE public.loans
- supabase/tests/trust_reliability.sql:24 — UPDATE public.kash_trust_state
- supabase/tests/trust_reliability.sql:41 — UPDATE public.wallets
- supabase/tests/trust_reliability.sql:59 — INSERT INTO public.loans
- supabase/tests/trust_reliability.sql:70 — INSERT INTO public.loans
- supabase/tests/trust_reliability.sql:77 — UPDATE public.profiles
- supabase/tests/trust_reliability.sql:78 — UPDATE public.kash_trust_state
- supabase/tests/trust_reliability.sql:83 — UPDATE public.profiles
- supabase/tests/trust_reliability.sql:84 — UPDATE public.kash_trust_state
- supabase/tests/trust_reliability.sql:88 — UPDATE public.profiles
- supabase/tests/trust_reliability.sql:89 — UPDATE public.kash_trust_state
- supabase/tests/trust_reliability.sql:94 — UPDATE public.profiles
- supabase/tests/trust_reliability.sql:95 — UPDATE public.kash_trust_state

## Reconstructed final required fields without column defaults

This is the agreement deployment followed by the final email migration. BEFORE INSERT triggers can provide a required value; see the report. Hosted extra columns are unknown.

- connections: requester_id (uuid), recipient_id (uuid)
- kash_trust_daily_penalties: user_id (uuid), penalty_date (date), related_loan_id (uuid), limit_before (integer), limit_after (integer)
- kash_trust_state: user_id (uuid)
- lender_preferences: user_id (uuid)
- loan_offers: loan_request_id (uuid), lender_id (uuid), amount (bigint), interest_rate (numeric), repayment_period_days (integer), total_repayment (bigint), borrower_id (uuid)
- loan_payments: loan_id (uuid), payer_id (uuid), amount (bigint), payment_type (text)
- loan_requests: borrower_id (uuid), requested_amount (bigint), remaining_amount (bigint), purpose (text), description (text)
- loans: loan_request_id (uuid), borrower_id (uuid), lender_id (uuid), principal (bigint), interest_rate (numeric), interest_amount (bigint), total_repayment (bigint), due_date (timestamp with time zone), original_due_date (timestamp with time zone)
- notifications: user_id (uuid), type (text), title (text), message (text)
- profiles: id (uuid), full_name (text), username (text)
- transactions: type (text), sender_id (uuid), recipient_id (uuid), amount (bigint), description (text)
- trust_events: user_id (uuid), event_type (text), points (integer), description (text)
- wallets: user_id (uuid)

## Final function signatures and return contracts

- accept_loan_offer(p_offer_id uuid) → uuid; SECURITY DEFINER=true; ACL={postgres=X/postgres,authenticated=X/postgres}
- accept_loan_offer_reviewed(p_offer_id uuid, p_expected_amount bigint, p_expected_interest_rate numeric, p_expected_period_days integer) → uuid; SECURITY DEFINER=true; ACL={postgres=X/postgres,authenticated=X/postgres}
- apply_daily_trust_penalties(p_user_id uuid) → void; SECURITY DEFINER=true; ACL={postgres=X/postgres}
- cancel_loan_offer(p_offer_id uuid) → void; SECURITY DEFINER=true; ACL={postgres=X/postgres,authenticated=X/postgres}
- cancel_loan_request(p_request_id uuid) → void; SECURITY DEFINER=true; ACL={postgres=X/postgres,authenticated=X/postgres}
- canonical_member_phone(p_phone text) → text; SECURITY DEFINER=false; ACL={postgres=X/postgres}
- complete_member_profile(p_full_name text, p_username text, p_email text) → void; SECURITY DEFINER=true; ACL={postgres=X/postgres,authenticated=X/postgres}
- complete_member_profile(p_full_name text, p_username text, p_email text, p_phone text) → void; SECURITY DEFINER=true; ACL={postgres=X/postgres,authenticated=X/postgres}
- create_loan_offer(p_request_id uuid, p_amount bigint, p_interest_rate numeric, p_period_days integer, p_approve_above_limit boolean DEFAULT false) → uuid; SECURITY DEFINER=true; ACL={postgres=X/postgres,authenticated=X/postgres}
- create_loan_request(p_amount bigint, p_purpose text, p_description text, p_period_days integer, p_direct_request_to uuid DEFAULT NULL::uuid, p_above_limit_requested boolean DEFAULT false) → uuid; SECURITY DEFINER=true; ACL={postgres=X/postgres,authenticated=X/postgres}
- decline_loan_offer(p_offer_id uuid) → void; SECURITY DEFINER=true; ACL={postgres=X/postgres,authenticated=X/postgres}
- derive_offer_borrower() → trigger; SECURITY DEFINER=true; ACL={postgres=X/postgres}
- edit_loan_offer(p_offer_id uuid, p_amount bigint, p_interest_rate numeric, p_period_days integer) → void; SECURITY DEFINER=true; ACL={postgres=X/postgres,authenticated=X/postgres}
- edit_loan_request(p_request_id uuid, p_amount bigint, p_purpose text, p_description text, p_period_days integer) → void; SECURITY DEFINER=true; ACL={postgres=X/postgres,authenticated=X/postgres}
- ensure_current_profile() → void; SECURITY DEFINER=true; ACL={postgres=X/postgres,authenticated=X/postgres}
- get_direct_request_borrower_summary(p_request_id uuid) → TABLE(borrower_id uuid, full_name text, available_limit integer); SECURITY DEFINER=true; ACL={postgres=X/postgres,authenticated=X/postgres}
- grant_grace_period(p_loan_id uuid, p_extra_days integer DEFAULT 7) → void; SECURITY DEFINER=true; ACL={postgres=X/postgres,authenticated=X/postgres}
- guard_member_phone() → trigger; SECURITY DEFINER=true; ACL={postgres=X/postgres}
- guard_member_requests() → trigger; SECURITY DEFINER=true; ACL={postgres=X/postgres}
- handle_new_auth_user() → trigger; SECURITY DEFINER=true; ACL={postgres=X/postgres}
- initialize_kash_trust_state() → trigger; SECURITY DEFINER=true; ACL={postgres=X/postgres}
- is_username_available(p_username text) → boolean; SECURITY DEFINER=true; ACL={postgres=X/postgres,anon=X/postgres,authenticated=X/postgres}
- loan_reliability(p_user_id uuid) → TABLE(score integer, sample_size integer); SECURITY DEFINER=true; ACL={postgres=X/postgres}
- maintain_account_data(p_user_id uuid DEFAULT NULL::uuid) → void; SECURITY DEFINER=true; ACL={postgres=X/postgres,service_role=X/postgres}
- mark_loan_overdue(p_loan_id uuid) → void; SECURITY DEFINER=true; ACL={postgres=X/postgres,service_role=X/postgres}
- mark_recovered_externally(p_loan_id uuid) → void; SECURITY DEFINER=true; ACL={postgres=X/postgres,authenticated=X/postgres}
- member_profile_complete(p_user_id uuid) → boolean; SECURITY DEFINER=true; ACL={postgres=X/postgres}
- member_profile_details(p_profile_id uuid) → TABLE(id uuid, full_name text, username text, phone text, bio text, location text, member_since text, reliability_score integer, reliability_sample_size integer, current_debt_total bigint, current_debt_count integer, borrowing_limit bigint, available_limit bigint, previous_transaction_count integer, borrowing_count integer, completed_borrowing_count integer, minimum_borrowed bigint, maximum_borrowed bigint, average_borrowed bigint); SECURITY DEFINER=true; ACL={postgres=X/postgres,authenticated=X/postgres}
- name_notification_participants() → trigger; SECURITY DEFINER=true; ACL={postgres=X/postgres}
- normalize_ke_phone(p_phone text) → text; SECURITY DEFINER=false; ACL={postgres=X/postgres}
- preserve_original_deadline() → trigger; SECURITY DEFINER=false; ACL={postgres=X/postgres}
- provision_confirmed_user(p_user_id uuid) → void; SECURITY DEFINER=true; ACL={postgres=X/postgres}
- public_profile_directory(p_query text DEFAULT ''::text, p_phone_numbers text[] DEFAULT ARRAY[]::text[], p_profile_ids uuid[] DEFAULT ARRAY[]::uuid[]) → TABLE(id uuid, full_name text, username text, phone text, bio text, location text, profile_image text, reliability_score integer, reliability_sample_size integer, account_status text, created_at timestamp with time zone, matched_phone text); SECURITY DEFINER=true; ACL={postgres=X/postgres,authenticated=X/postgres}
- record_trust_event(p_user_id uuid, p_event_type text, p_points integer, p_description text, p_loan_id uuid DEFAULT NULL::uuid) → uuid; SECURITY DEFINER=true; ACL={postgres=X/postgres}
- refresh_account_state() → void; SECURITY DEFINER=true; ACL={postgres=X/postgres,authenticated=X/postgres}
- refresh_profile_limit(p_user_id uuid) → void; SECURITY DEFINER=true; ACL={postgres=X/postgres}
- refresh_profile_statistics(p_user_id uuid) → void; SECURITY DEFINER=true; ACL={postgres=X/postgres}
- release_request_offers(p_request_id uuid) → void; SECURITY DEFINER=true; ACL={postgres=X/postgres}
- repay_loan(p_loan_id uuid, p_amount bigint) → void; SECURITY DEFINER=true; ACL={postgres=X/postgres}
- repay_loan_once(p_loan_id uuid, p_amount bigint, p_operation_id uuid) → void; SECURITY DEFINER=true; ACL={postgres=X/postgres,authenticated=X/postgres}
- request_loan_offer_terms(p_offer_id uuid, p_interest_rate numeric, p_period_days integer) → void; SECURITY DEFINER=true; ACL={postgres=X/postgres,authenticated=X/postgres}
- respond_connection_request(p_connection_id uuid, p_accept boolean) → void; SECURITY DEFINER=true; ACL={postgres=X/postgres,authenticated=X/postgres}
- respond_loan_offer_terms(p_offer_id uuid, p_accept_terms boolean) → void; SECURITY DEFINER=true; ACL={postgres=X/postgres,authenticated=X/postgres}
- send_connection_request(p_recipient_id uuid) → uuid; SECURITY DEFINER=true; ACL={postgres=X/postgres,authenticated=X/postgres}
- touch_updated_at() → trigger; SECURITY DEFINER=false; ACL={postgres=X/postgres}
- update_loan_statistics() → trigger; SECURITY DEFINER=true; ACL={postgres=X/postgres}
