# KashKe database-contract audit — 5 October 2026

Follow-up: the supplied real hosted snapshot is analyzed in [HOSTED_CONTRACT_RECONCILIATION.md](HOSTED_CONTRACT_RECONCILIATION.md). This original report remains a record of the pre-hosted-evidence audit; its category C findings are resolved/reclassified in that follow-up. A proposed reconciliation migration has been prepared separately and has not been deployed.

Diagnosis only. No Android source, business logic, migration, RLS policy or hosted data was changed. Added this report, a metadata-only diagnostic statement, a disposable local reproduction script and its evidence files. Hosted Supabase was not accessed. Findings below distinguish repository defects from possible hosted drift.

Evidence labels: **A** = proven repository defect/behavior; **B** = high-confidence local contract mismatch; **C** = hosted verification required. A reproduced behavior is not automatically the cause of the reported incident.

## 1. Root cause of the current terms failure

**The exact incident is not yet determinable.** There is no failing-attempt exception, offer UUID, installed function definition or live row state in the supplied request. It would be incorrect to claim a missing `borrower_id` caused it.

The exact path is:

1. Borrowing screen renders offers with `offer.borrowerId == user.id && status == OFFERED` (`KashKeApp.kt:2900`). “Request different terms” saves that `LoanOffer` in `offerForTerms`.
2. `RequestTermsDialog` (`:3769`) sends integer interest and days through its callback (`:3129`). The dialog retains a snapshot keyed by offer ID; it does not revalidate current offer status or updated terms.
3. Callback calls `KashKeViewModel.submitOfferTermsRequest` (`:687`). Another wrapper, `requestOfferTerms` (`:675`), calls the same repository method.
4. `SupabaseDataRepository.requestLoanOfferTerms` (`:545`) calls `operation("request_loan_offer_terms", ...)`.
5. JSON contains exactly `p_offer_id` = selected **offer UUID**, `p_interest_rate` = Kotlin Int, `p_period_days` = Kotlin Int. Borrower ID is an acting-user guard, not an RPC argument.
6. `operation` (`:439`) checks busy state, generation/session identity and local profile completion before issuing the RPC. Any of these can return false without a database exception.
7. Latest migration definition is `202610040002_trust_reliability.sql:440`, signature `(uuid,numeric,integer) RETURNS void`. It locks the parent request, then selects the existing offer with `borrower_id = auth.uid()` and `status = 'OFFERED'`.
8. It updates that row to `TERMS_REQUESTED`, saves requested terms and inserts a notification for the **existing offer lender**. There is **no loan-offer INSERT and no loan INSERT** in this flow.
9. In the separate agreement deployment, the status update also runs `require_member_profile → guard_member_requests → member_profile_complete`; notification INSERT runs `name_notification_participants`. Their hosted installation must be checked.
10. Any false result reaches `KashKeApp.kt:3807` and becomes “Your terms request could not be sent.” The more informative repository error is discarded by this dialog.

Identity provenance:

| Identity/entity | Source and use |
|---|---|
| Authenticated borrower | GoTrue session user UUID → profile UUID → repository `idState`; repository checks current Auth UUID equals acting UUID |
| `borrower_id` | Existing offer field, derived locally from parent `loan_requests.borrower_id` by `derive_offer_borrower`; checked against `auth.uid()` in terms RPC |
| `lender_id` | Existing offer field set from lender `auth.uid()` at offer creation; notification goes to this UUID |
| Request UUID | Existing offer `loan_request_id`; SQL derives it from `p_offer_id` to lock the request |
| Offer UUID | `offer.id` from RLS-filtered offer records; the only entity UUID sent by the terms caller |
| Loan UUID | None: terms negotiation does not create or reference a funded loan |

**A — Reproduced state restriction:** first request on an eligible offer succeeds; another request on the **same** offer while pending fails `P0001: Offer is no longer available`. The pending request is not updated. Lender accept/reject returns it to `OFFERED`; a further borrower request then succeeds. This is a state-machine rule, not a uniqueness violation. Whether pending terms should be revisable is a product decision.

**A — Reproduced independence:** a terms request on another eligible offer succeeds even while the first offer is pending. The local schema has no “one pending terms request per borrower/lender” unique index. Sending terms to one account does not, by itself, block another eligible offer.

**A — Other status transitions:** acceptance of any offer closes all competing `OFFERED`/`TERMS_REQUESTED` offers on the same request, even on partial funding (`202610040001_close_competing_offers.sql`). Editing/cancelling the parent releases offers; expiry maintenance expires them. A stale dialog can therefore reference an offer that SQL correctly rejects. A cancelled offer can have been deleted entirely.

**C — Plausible hosted causes, not a ranking or diagnosis:** stale status/offer snapshot, profile trigger, old requested-interest constraint (e.g. negotiated rate above legacy 5%), overload/signature/grant drift, network failure, or an installed trigger/constraint absent locally. A missing/wrong existing borrower UUID would prevent selection but is unproven. Capture `KashKeRuntime` for the failing `RPC request_loan_offer_terms` plus the consolidated metadata result to identify which branch actually failed.

## 2. Critical contract mismatches

**B1 — Current app RPCs missing from canonical migration-only installation.** `edit_loan_offer(uuid,bigint,numeric,integer)`, `cancel_loan_offer(uuid)` and `accept_loan_offer_reviewed(uuid,bigint,numeric,integer)` exist only in the deployment block of `supabase/AGREEMENT_SIGNUP_REPORT.md`. No migration creates them; the current dashboard script does not create them. PGlite applying all 19 migrations confirmed all three are absent. The app calls these for editing, cancelling and accepting offers. Hosted impact is C until installed definitions are returned.

**B2 — Grace/default semantics depend on an unversioned release.** Android `canGrantGrace` requires one extension, within ±3 days of the original deadline. Migration-only SQL `grant_grace_period` uses different eligibility and lacks persisted one-time grant columns (`grace_granted_at`, `grace_days`), supplied by the agreement report. Migration-only maintenance does not implement that report's automatic default transition, and migration-only repayment excludes DEFAULTED. DTO defaults can hide the missing grace columns. There are two internally different supported database reconstructions.

**A1 — Replaying old agreement deployment removes the new profile API.** Its deployment explicitly drops `complete_member_profile(text,text,text,text)` and installs the older three-parameter function. The current app always sends `p_phone`. Local reproduction confirmed the four-argument API disappears when this block is applied after the newest migration. The email migration restores it. This provides a concrete mechanism for “later change breaks previously working functionality.” Do not replay the report as a repair without reviewing order and final definitions.

**C1 — Hosted `loans.lender_contribution` remains unexplained by local schema.** No local migration adds this column. Latest `accept_loan_offer` INSERT and report/test loan INSERTs omit it; Kotlin supplies `lenderContribution = principal` only when decoding. That default cannot satisfy a hosted NOT NULL INSERT constraint. If the hosted column still requires explicit input and no trigger/default supplies it, acceptance and test creation fail. The reported historical hosted requirement is not represented in canonical history.

## 3. Latent contract mismatches

**A2 — Above-limit flag survives an edit that removes the excess.** `edit_loan_request` updates amount but never recalculates `above_limit_requested`; creation enforces that flag matches amount versus available limit. Reproduction: available limit 100, direct request 500 with flag true, edit to 50 → amount 50 and flag **still true**. Offer creation then requires explicit above-limit approval and uses the 15% ceiling despite the edited amount being within capacity. Define how editing should preserve/recalculate disclosure before repair.

**A3 — Expiry is not enforced in terms RPCs.** Request and response functions lock the parent but never check its status or `expires_at`. Both succeeded locally with an expired parent whose offer had not yet been expired by maintenance. Behavior depends on cron/refresh timing; acceptance separately rejects an expired request. This permits apparently successful negotiation on unusable support.

**A4 — Inconsistent repayment-period ceilings across actions.** Creation and lender editing cap days at parent `preferred_period_days`. Borrower terms request/UI allow 7–365, and lender response accepts those requested days without the parent cap. Reproduction: request prefers 30; negotiation to 60 succeeds. This may be intentional bargaining, but the policy is not consistently expressed and should be decided explicitly.

**A5 — Aggregate money can exceed Android Int although individual rows are capped.** Profile `total_borrowed`, `total_repaid`, `total_supported`, `total_earned_from_interest` are bigint SUMs with no aggregate Int cap, while `User` uses Int. `wallet_android_amount` and offer/loan checks cap individual balances/totals, not lifetime statistics. Large history can make profile decoding fail. Kotlin wallet `sumOf(Int)` and outstanding-lending sums can also overflow. `MemberProfile` uses Long correctly for monetary aggregates.

**A6 — No dialog submission lock for terms.** Unlike offer creation and agreement action dialogs, `RequestTermsDialog` does not disable repeated clicks or set a submitting flag. Repository busy guard rejects overlap; after the first succeeds, a late repeated attempt can hit TERMS_REQUESTED. This is a proven UI race opportunity, not proof that the user double-clicked.

**A7 — Connection retry cannot insert the same pair.** Unique unordered-pair index includes REJECTED/REMOVED. `send_connection_request` always INSERTs; it neither updates an existing rejected pair nor distinguishes its unique violation. Retrying such a pair fails locally by design. `toAgreementMessage` incorrectly maps any “duplicate” error to a username conflict.

**A8 — Operational restore instructions can replay unrelated contracts.** `rollback/pause_trust_daily_penalties.sql` tells operators to restore by reapplying the entire trust migration. That migration replaces grace, maintenance, repayment and directory functions, among others; later agreement semantics would be overwritten. Review a targeted restoration plan rather than executing this instruction during the audit.

**A9 — Repayment affordability checks reserved money as spendable.** Final report and migration repayment bodies test `wallet.balance >= payment`, not `balance - locked_balance >= payment`. The wallet check correctly prevents reducing balance below locked funds, so repayment can pass business validation and then fail `23514: wallets_check`. Reproduction: borrower wallet 10050, open support offer reserving 10000, loan repayment 54 → rejected because available balance is 50. This is a contract/validation mismatch; do not weaken the wallet constraint or release unrelated reservations. Add a precise available-balance check/message once the repair is authorized.

## 4. RPC signature audit and inventory

All current named caller parameters match the corresponding local final definition **when the agreement deployment and final email upgrade are installed in order**. Kotlin Int values are valid inputs for bigint/numeric; JSON UUID strings map to uuid; JSON string arrays map to text[]/uuid[]. None of the standard mutation return UUIDs/void are decoded by `operation`; success is HTTP/RPC completion followed by optional refresh.

`SupabaseDataRepository` has one shared mutation path; the following table includes dynamic RPC calls, not just literal `rpc(...)` searches. Parameter names below are exact, with Kotlin source types mapped to SQL types. Acting user IDs enforce session equality locally and are deliberately not additional RPC parameters.

| Kotlin caller / feature | RPC and sent parameters | SQL result / response DTO | Final local source |
|---|---|---|---|
| Auth `isUsernameAvailable` | `is_username_available(p_username: String→text)` | boolean→Boolean | `202610010002_immediate_signup.sql` |
| Auth `ensureCurrentProfile` | `ensure_current_profile()` | void, ignored | integration migration; provisioning additionally replaced by agreement report |
| Auth initialization; data `saveProfile` | `complete_member_profile(p_full_name,p_username,p_email,p_phone: String→text)` | void, ignored | `202610040006_email_signup_profile_phone.sql` |
| `refresh` / every load and post-mutation refresh | `refresh_account_state()` | void, ignored | integration migration, invokes installed maintenance |
| `searchPublicProfiles`, `matchContactProfiles`, `getPublicProfile(s)` | `public_profile_directory(p_query: String→text,p_phone_numbers: string array→text[],p_profile_ids: UUID string array→uuid[])` | table→JsonObject→User/ContactProfileMatch | trust migration |
| `getMemberProfileDetails` | `member_profile_details(p_profile_id: String→uuid)` | table→MemberProfile, zero/one row expected | member-profile migration |
| `createLoanRequest` | `create_loan_request(p_amount: Int→bigint,p_purpose,p_description: String→text,p_period_days: Int→integer)` | uuid, ignored; optional SQL args default | direct-request migration |
| `createDirectLoanRequest` | same plus `p_direct_request_to: String→uuid,p_above_limit_requested: Boolean→boolean` | uuid, ignored | direct-request migration |
| `editLoanRequest` | `edit_loan_request(p_request_id: String→uuid,p_amount: Int→bigint,p_purpose,p_description: String→text,p_period_days: Int→integer)` | void | integration migration |
| `cancelLoanRequest` | `cancel_loan_request(p_request_id: String→uuid)` | void | integration migration |
| `makeLoanOffer` | `create_loan_offer(p_request_id: String→uuid,p_amount: Int→bigint,p_interest_rate: Int→numeric,p_period_days: Int→integer,p_approve_above_limit: Boolean→boolean)` | uuid | complete-interest fix / dashboard |
| `getDirectRequestBorrowerSummary` | `get_direct_request_borrower_summary(p_request_id: String→uuid)` | table→JsonObject→DirectRequestBorrowerSummary | trust migration |
| `requestLoanOfferTerms` | `request_loan_offer_terms(p_offer_id: String→uuid,p_interest_rate: Int→numeric,p_period_days: Int→integer)` | void | trust migration |
| `respondToLoanOfferTerms` | `respond_loan_offer_terms(p_offer_id: String→uuid,p_accept_terms: Boolean→boolean)` | void | trust migration |
| `repayLoan` | `repay_loan_once(p_loan_id,p_operation_id: String→uuid,p_amount: Int→bigint)` | void; persistent receipt prevents duplicated payment | financial-guards migration, calls installed repay function |
| `acceptLoanOffer` | `accept_loan_offer_reviewed(p_offer_id: String→uuid,p_expected_amount: Int→bigint,p_expected_interest_rate: Int→numeric,p_expected_period_days: Int→integer)` | uuid | **agreement report only** |
| `declineLoanOffer` | `decline_loan_offer(p_offer_id: String→uuid)` | void | trust migration |
| `sendConnectionRequest` | `send_connection_request(p_recipient_id: String→uuid)` | uuid | trust migration |
| accept/reject connection | `respond_connection_request(p_connection_id: String→uuid,p_accept: Boolean→boolean)` | void | trust migration |
| `grantGracePeriod` | `grant_grace_period(p_loan_id: String→uuid,p_extra_days: Int→integer)` | void | agreement report for app's one-time policy; different migration-only body |
| `editLoanOffer` | `edit_loan_offer(p_offer_id: String→uuid,p_amount: Int→bigint,p_interest_rate: Int→numeric,p_period_days: Int→integer)` | void | **agreement report only** |
| `cancelLoanOffer` | `cancel_loan_offer(p_offer_id: String→uuid)` | void | **agreement report only** |
| `markExternallyRecovered` | `mark_recovered_externally(p_loan_id: String→uuid)` | void | trust migration |

Overloads/history: repeated `CREATE OR REPLACE` with the same SQL type signature is a replacement, not an additional overload. Directory migrations explicitly remove known uuid[], text[], jsonb and shorter signatures before defining the final `(text,text[],uuid[])`. The agreement report removes an obsolete four-text profile signature, installs a three-text compatibility API, and the final email migration adds the new four-text API. These **intentional** three/four argument overloads use different parameter key sets and are not inherently ambiguous. Unknown hosted integer/numeric/shorter overloads can still exist; the diagnostic lists all installed signatures and grants. Migration replacements preserve existing function ACLs, so hosted ACL differences also survive unless explicitly repaired.

Other direct database operations:

| Caller | Database operation and input | Decoding / downstream |
|---|---|---|
| Auth `getCurrentProfile` | profiles SELECT filtered by authenticated identity `id` | ProfileRecord→User, startup/completion gate |
| Auth `updateCurrentProfile` | profiles UPDATE own `id`; full_name, username, bio, location, profile_image | no returned DTO; method's phone parameter is intentionally unused |
| Data `refresh` authenticated profile hydration | profiles SELECT own Auth/profile UUID | JsonObject→User, owner-only Trust/limits/aggregates |
| Data `rows("wallets")` | RLS SELECT pages of 500, order user_id | JsonObject balance/locked_balance→Int, wallet and offer affordability |
| Data `rows("loan_requests")` | RLS SELECT pages, order id | LoanRequest, feeds/direct requests/offer parent mapping |
| Data `rows("loan_offers")` | same | LoanOffer, incoming/supporting/negotiation |
| Data `rows("loans")` | same | LoanAgreement, repayment/grace/history |
| Data `rows("connections")` | same | Connection, Discover connection state |
| Data `rows("trust_events")` | same | TrustEvent, private Trust history |
| Data `rows("transactions")` | same | Transaction, wallet/history metrics |
| Data `rows("notifications")` | same | Notification enum, alerts |
| Data `markNotificationRead` | notifications UPDATE `read=true`, filter notification UUID AND current `user_id` | optimistically publishes read=true after response; affected-row count not verified |

No Android direct INSERT/UPSERT/DELETE, direct SQL/JDBC, Edge Function invocation or other Supabase data client was found in production sources. ViewModel delegates database operations to repositories. Installed Realtime is not used for a data subscription; refresh is polling. Auth sign-up/sign-in/reset/sign-out/session restoration are GoTrue operations, with sign-up profile metadata; provisioning is SQL trigger/RPC driven. EncryptedSessionManager persists Auth sessions locally, not Postgres rows. Contacts normalize phone strings and pass them only to the directory phone parameter. Test fakes/JSON fixtures are not production writes.

SQL coverage: examined all 19 migrations, dashboard/email setup, agreement deployment **and rollback** SQL in Markdown, seed, cron/penalty rollback and SQL tests/tools. `local-contract-catalog.json` preserves every installed public function body, table column/default, constraint, policy and trigger in the reconstructed release. `source-operation-inventory.md` records database-call occurrences and all function-definition history, including server helpers, SQL INSERT/UPDATE/DELETE and fixtures. `dashboard_setup.sql` is only the interest fix; `email_auth_setup.sql` is only the final profile upgrade. The `--full` generator concatenates migrations and therefore also omits report-only APIs.

## 5. Required-field and ID audit

| Record/write | Required fields supplied or derived | Assessment |
|---|---|---|
| Profile provisioning | id from Auth UUID, full_name, username, phone; wallet/preferences user_id same UUID; later defaults provide metrics | Correct ID source locally; installed provisioning/profile release must agree |
| Connections | requester_id=auth.uid(), recipient_id=p_recipient_id; PK/status/timestamps default | Correct roles; duplicate-pair retry issue above |
| Loan request creation | borrower_id=auth.uid(), requested/remaining amounts, purpose, description; funded/status/expiry default | Required local fields supplied; direct UUID is recipient, not borrower |
| Offer creation | parent UUID, lender=auth.uid(), amount/rate/days/total; borrower omitted in newest INSERT | Locally satisfied by BEFORE INSERT `derive_offer_borrower`; **not a proven missing-field bug locally**. Hosted missing/disabled trigger would expose NOT NULL failure |
| Terms update | existing offer status and requested rate/days; notification user_id=v_offer.lender_id | Does not need a new borrower/lender/request INSERT payload |
| Offer acceptance | parent UUID, parent borrower UUID, offer lender UUID, principal/rate/interest/total/due/override | Required local fields supplied; original_due_date filled by BEFORE INSERT trigger. Hosted lender_contribution unresolved |
| Repayment | loan UUID, payer=auth.uid(), amount/payment_type; receipt later attaches operation UUID/requested amount | Matches local MIXED/PRINCIPAL/INTEREST check and unique receipt; no caller phone/email as FK |
| Transactions | lender→borrower for funding; borrower→lender for repayment; amount/type/description/loan | Correct roles; interest defaults to 0 for funding, supplied on repayment |
| Notifications | user UUID, type/title/message/entity UUID; read/created/id default | Recipient roles consistent in inspected final bodies; cancellation intentionally has null related entity |
| Trust events/state/penalties | user UUID/loan UUID, event fields, dates/limits; state cycle defaults | Server-only helpers, no app-authored scores or limits |

SQL test/seed inserts satisfy local required fields or local triggers, but cannot prove compatibility with an extra hosted NOT NULL column. Missing offer borrower in latest source relies on the integration trigger, unlike an explicit `borrower_id=v_request.borrower_id` implementation described in the historical incident. Both can work; independent partial deployment breaks the implicit version. Verify triggers before choosing an explicit-field repair.

## 6. DTO and serialization audit

`DatabaseRecords` ignores unknown keys but does not ignore unknown enum values, missing required fields, explicit nulls for non-null types, fractions or numeric overflow. Request/connection/notification DTOs decode table rows directly. Event mapping renames `event_type→type`; transaction mapping defaults only an absent `interest`; offer mapping enforces whole rates and derives direct-request context; loan mapping enforces whole rates and derives original-request amount from available parent rows. `number()` rejects fractional/overflow values deliberately.

Local UUIDs are modeled as strings, correctly passed to uuid RPC arguments. Timestamptz fields are strings; nullable accepted/responded/completed/grace timestamps are modeled as nullable. Reliability score is nullable and sample count is integer, matching local directory and MemberProfile output. MemberProfile has all returned SQL names/types and nullable min/max/average values. Latest direct-summary output matches borrower UUID/name/integer available limit; obsolete private trust/aggregate return columns are no longer required by the caller.

Risks: lifetime bigint→Int aggregates (A5); legacy fractional numeric interest rows fail the whole-rate mapping even though SQL NOT VALID checks preserve old rows; additional hosted status/notification types fail enum decoding and can abort the shared refresh. `LoanOffer.borrowerId` is required and exposes a schema missing that column. `LoanAgreement.lenderContribution=principal`, `originalDueDate=dueDate`, null grace defaults and User's default reliability sample mask missing release columns; they do not supply INSERT defaults. `ProfileRecord` gives borrowing/available limits a 2000 fallback whereas generic user mapping uses 0; missing columns could produce inconsistent displayed capacity. These fallbacks are compatibility behavior, not permission to delete them.

## 7. Status and constraint audit

Local enum/check pairs agree:

- Connection: PENDING, ACCEPTED, REJECTED, REMOVED.
- Requests: REQUESTED, PARTIALLY_FUNDED, FULLY_FUNDED, EXPIRED, CANCELLED.
- Loans: ACTIVE, DUE_SOON, OVERDUE, GRACE_PERIOD, REPAID, RECOVERED_EXTERNALLY, DEFAULTED.
- Offers: OFFERED, TERMS_REQUESTED, ACCEPTED, DECLINED, EXPIRED. Cancellation deletes offers and emits LOAN_OFFER_CANCELLED; it does not add a CANCELLED offer status.
- Payments: PRINCIPAL, INTEREST, MIXED. Transaction/event/account statuses are strings, not these enums.

NotificationType includes final SQL-emitted types, including LOAN_DEFAULTED, LOAN_OFFER_CANCELLED, LOAN_PAYMENT, RECOVERED_EXTERNALLY and trust/limit changes. No missing final local notification enum was identified. Some enum values are unused by final SQL; this alone is not drift. Hosted extra values still require checking via the failed decoding/error evidence, not wholesale enumeration changes.

Final local storage allows offer/requested/loan rates 0–15; business RPCs allow whole rates 0–7 normally, 0–15 for explicitly approved designated direct above-limit offers. Lender defaults cap at 7. Financial-guard NOT VALID checks enforce whole rates and Int row totals for new/changed rows. Named unknown hosted checks can remain after drops of known legacy names. A trigger/constraint on notification INSERT can roll back a successful-looking terms UPDATE because they execute in one transaction.

Creation requires positive amount, nonempty purpose/description, 7–365 days, live available capacity/disclosure and accepted direct connection; support creation requires a live request, non-self lender, correct direct recipient or connection, remaining amount, available wallet reservation and lender preference maximum. UI cache validation does not replace these server checks. Above-limit edit drift, inconsistent negotiated period ceilings and pending state restrictions are described above. Amount-plus-interest may exceed the Int total check even for an Int-valid principal; current UI does not universally prevalidate that upper boundary.

Local fixture issue: later demo seed phones such as `+2547000000003` have excess digits. Final member-profile completion rejects these as Kenyan mobiles. Seed metadata lacks profile_email, which migration-only provisioning does not backfill after inserting seed users; report-based provisioning derives it from Auth email. Local fixture success is deployment-order dependent and should not be treated as hosted evidence.

## 8. RLS and authorization risks

Final local RLS: own profiles/wallet/notifications/private trust events; connection participants; offer/loan participants; request borrower/designated recipient or accepted connections for community requests; transaction participants; payment loan participants; own lender preferences. Anonymous table access is revoked in local policy/grant history. App SELECTs depend on this RLS filtering. No RLS relaxation is proposed.

Public directory and member details deliberately use SECURITY DEFINER to return approved public phone/aggregate fields while excluding private Trust, wallet and email. This matches current app architecture. Direct-request summary enforces designated recipient. Mutation RPCs enforce authenticated actor and borrower/lender/recipient roles explicitly rather than relying on RLS. Internal arbitrary-user helpers (provision, statistics/limit updates, trust rewards/penalties, offer release) are revoked from public/anon/authenticated; maintenance/overdue privileged entry points are server scoped. Current helpers are safe only if hosted grants remain restricted; the consolidated query includes effective function privileges, including PUBLIC inheritance.

Known limits: terms functions lock a parent based on arbitrary offer ID before applying participant filtering; this can let an authenticated outsider contend on an unrelated request lock, though the mutation subsequently fails authorization. It does not demonstrate data disclosure or successful unauthorized mutation. Request edit lacks a borrowing_frozen/expiry recheck present in creation; acceptance retains its own freeze/expiry checks. `mark_recovered_externally` allows OVERDUE/GRACE_PERIOD but excludes DEFAULTED although the agreement release newly transitions debt to DEFAULTED; confirm whether external settlement should remain available then. Reviewed acceptance protects the displayed terms; the older `accept_loan_offer(uuid)` remains executable for compatibility, so exact-reviewed-terms guarantees apply to the current app path rather than every legacy client.

Direct UPDATE notification/profile operations do not verify affected-row count; a successful HTTP response with zero RLS-visible rows can be treated as success. This is an assumption to harden selectively, not an argument to broaden access.

Auth UUID remains authoritative throughout production caller paths. Registration real email and development internal email are credentials/contact metadata, not foreign keys. `profiles.id REFERENCES auth.users(id)`, profile creation uses Auth UUID, and member/directory lookup distinguishes UUID arrays from contact phone arrays. `profiles.phone` stores the phone. The direct profile update method does not change it; the four-field completion RPC permits filling an empty phone once and rejects replacement of existing numbers. Legacy username/email-derived display fallbacks should not be confused with UUID identity. No proven phone/email/username-as-database-UUID defect was found.

## 9. Generic errors and diagnostic visibility

The current RPC path already wraps calls in `runLoggedStage("RPC $name")`, which logs sanitized exception message and debug stack to KashKeRuntime before returning false. No Android diagnostic change was necessary for this first pass. In release builds, only the exception message (400-character sanitized limit) and class/stage are logged, not structured PostgreSQL code, actor/offer UUID or guard reason. Whether SQLSTATE appears depends on SDK exception formatting. A root code may be truncated or hidden in a cause.

Visibility gaps: busy/session/profile early returns have no per-operation failure log; terms dialog replaces repository guidance; mark-notification-read catches without logging; member-profile and direct-summary RPC/decode methods have no repository stage wrapper; their UI catches may only set generic errors; agreement dialog's unexpected exception catch is generic. Post-save refresh errors may occur after a mutation succeeded; repository correctly preserves success there. Do not treat that refresh failure as an unsaved mutation and retry financial writes blindly.

`safeRpcDiagnosticContext` summarizes primitives using `value.content::class`, which labels all primitive values String; it reports length, not actual JSON numeric/boolean type. The helper is currently used for directory diagnostics, not the shared mutation path. Sanitizer redacts bearer/JWT tokens, named authorization/apikey/access/refresh/password values and sb publishable/secret keys. It is pattern based; arbitrary remote exception text or older key formats are not a proof of universal credential safety. Do not log full payloads, raw headers or Auth metadata. A proposed improvement should log operation, stage, exception/cause class, structured SQLSTATE/PostgREST code and safe entity UUIDs with allowlisted fields; clean UI messages should remain.

## 10. Safe fix plan (no fixes applied)

1. Run the consolidated read-only hosted statement and reproduce the terms failure once while capturing the KashKeRuntime RPC failure. Note whether the selected offer is the same pending offer or a separate eligible one. Existing logging makes this concrete without changing financial logic.
2. Compare installed function bodies, triggers, NOT NULL/defaults, requested-interest checks and effective grants with the catalog. Resolve hosted `lender_contribution` and offer-borrower trigger assumptions before any SQL repair.
3. Add targeted sanitized structured diagnostics for guard rejection and RPC/decode failures; let the terms dialog use clean repository guidance and prevent overlapping submissions. Preserve business state rules until revised explicitly.
4. Consolidate report-only final release into reviewed forward-only versioned history, keeping compatibility signatures and all trust/reliability/ledger behavior. Verify both hosted upgrade and fresh install end states; do not run older releases wholesale.
5. Repair only confirmed missing RPC/required-field contracts, supplying derived participant/contribution values where required by the actual schema. Validate acceptance, competing-offer refunds and repayment receipts together.
6. Fix above-limit edit disclosure semantics with product-approved rules, then separately settle negotiation-period/expiry behavior. Test changed transitions, not unrelated rewrites.
7. Align repayment affordability with existing wallet reservations without changing accounting or releasing unrelated offers. Move uncapped aggregate monetary decoding/calculation to Long with explicit boundary behavior. Keep strict whole-rate and unknown-schema error detection; do not silently round money.
8. Correct connection duplicate error mapping/retry policy, defaulted external recovery eligibility and fixture/operational instructions where agreed.

## 11. Hosted Supabase diagnostic SQL

Paste the **single statement** in [hosted-database-diagnostic.sql](hosted-database-diagnostic.sql) into Supabase SQL Editor. It collects relevant table columns/nullability/defaults, constraints/FKs/indexes, enabled triggers and their function definitions, RLS/policies/table and column grants, called/helper RPC signatures/results/bodies/effective privileges, overloads and schema/default grants. It reads metadata only and does not execute the RPCs or maintenance. An admin SQL Editor session does not simulate end-user RLS; the results support definition comparison, not a claim that a specific mobile request was authorized.

The failed request's error is still needed to distinguish constraint/auth/state failures. Schema metadata alone cannot prove the history of an individual attempt. Do not provide passwords, tokens or keys with the result.

## 12. Files that would need modification after diagnosis

| File(s) | Potential targeted modification |
|---|---|
| `app/src/main/java/com/kashke/app/KashKeApp.kt` | terms submission guard and clean repository error presentation; selected-offer refresh handling |
| `.../data/repository/SupabaseDataRepository.kt` | allowlisted operation/guard diagnostics; RPC/decode stages; affected-row verification; aggregate Long calculations |
| `.../data/repository/SafeErrors.kt` | structured codes/cause handling; correct duplicate-context mapping and diagnostic type summary |
| `.../data/Models.kt`, `.../data/repository/DatabaseRecords.kt`, `.../data/repository/SupabaseRepository.kt` | proven bigint/DTO gaps and deliberate compatibility handling; preserve Auth UUID |
| `supabase/migrations/` (future reviewed migration only) | confirmed hosted contract repair and final report-only release reconciliation; no migration created in this audit |
| `supabase/README.md`, `AGREEMENT_SIGNUP_REPORT.md`, `EMAIL_AUTH_REPORT.md`, `tools/build-dashboard-sql.cjs`, operational restore instructions | one unambiguous final installation order and fresh-install coverage |
| `supabase/seed.sql`, SQL verification tools/tests | valid final-contract fixtures, both installation paths and identified state/required-field regressions |

Validation performed: existing suites passed **53 database**, **74 agreement/signup**, **17 email/profile** checks (144 total). Added local audit reproduced the missing migration-only RPCs, old report removal of the new profile signature, pending second-request rejection, independent other-offer success, renegotiation after response, expired-parent negotiation, 30→60-day negotiation, stale above-limit flag and repayment reservation check failure. The diagnostic statement also executed in the reconstructed PostgreSQL/PGlite environment. Evidence is in [local-reproduction.json](local-reproduction.json) and [local-contract-catalog.json](local-contract-catalog.json); rerun with `node tools/sql-check/audit-contract.mjs`. PGlite uses an Auth/roles shim and does not emulate hosted GoTrue/PostgREST/schema cache/network/cron. No Android build was needed because no Android source changed.

Systemic conclusion: **repository deployment paths demonstrably produce different database contracts**, and historical hosted required fields are missing from local schema history. Trigger-provided fields and DTO compatibility defaults can conceal incomplete releases, while generic error presentation makes unrelated failures look alike. This supports a systemic contract-drift investigation; it does not establish that every broken screen shares one SQL error or that the specific current terms attempt was caused by schema drift.
