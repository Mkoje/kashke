# Supabase backend

The percentage calculation fix is `migrations/202610060003_flat_support_interest.sql`. Apply it after the preceding migrations on the hosted database. Interest is charged once as `round(principal * rate / 100)`, independent of the repayment period: KSh 6,000 at 7% incurs KSh 420 interest and KSh 6,420 total repayment. This migration corrects creation, editing, negotiation, and acceptance, refreshes pending offer totals, and preserves accepted loans and historical transactions. Do not replay the older interest dashboard SQL afterward. Verify with `node tools/sql-check/verify-flat-interest.mjs` (fresh and hosted-schema fixtures).

The agreement/grace, offer editing/cancellation, and phone signup release is documented in [AGREEMENT_SIGNUP_REPORT.md](AGREEMENT_SIGNUP_REPORT.md). Apply its deployment SQL **after** the existing schema history; it intentionally has no migration file. The report also contains rollback SQL and hosted Auth setup instructions. Do not replay older dashboard SQL after this release.

The app uses the existing hosted Supabase database. Keep `migrations/` as the canonical schema history for future changes and fresh installations; do not replay historical migrations on the working hosted database.

`dashboard_setup.sql` contains the latest incremental migration and is also used by the SQL verification suite. `tools/build-dashboard-sql.cjs` regenerates it. Apply an update only when that migration has not already been deployed.

The current dashboard update removes legacy 5% checks on offers, negotiated rates, accepted loans and lender preferences, and replaces the old `create_loan_offer` RPC. Normal requests allow whole rates from 0?7%; explicitly approved direct above-limit requests allow 0?15%. Run the complete `dashboard_setup.sql` in the hosted SQL Editor; it is safe to rerun and does not replay profile, trust or agreement migrations. The app already sends 6 and 7 unchanged. Local verification reproduces both a stale 5% RPC and legacy constraints, then checks creation, negotiation and acceptance.

`schedule_maintenance.sql` configures the recurring account maintenance job. `rollback/pause_trust_daily_penalties.sql` provides the targeted penalty pause operation. Keep these for maintenance. `config.toml`, `seed.sql`, and `tests/` support local database development and verification. The seed is a local fixture.

The Android build reads `SUPABASE_URL` and `SUPABASE_ANON_KEY` from Gradle properties, environment variables, or `local.properties`. Use a public publishable or anon key. RLS and server authorization protect account data.

Run database verification from the project root:

```powershell
npm.cmd ci --prefix tools/sql-check --ignore-scripts --no-audit --no-fund
node tools/sql-check/verify.mjs
node tools/sql-check/verify-agreements.mjs
```

Run Android verification:

```powershell
.\gradlew.bat -I tools/verify-build.gradle :app:assembleDebug :app:testDebugUnitTest
```

Android Studio, normal builds, and verification builds all write to `app/build/`. The debug APK is `app/build/outputs/apk/debug/app-debug.apk`.
