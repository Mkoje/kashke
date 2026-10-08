-- ONE read-only statement. Paste into Supabase SQL Editor as its normal admin.
-- Metadata only: no user rows, Auth metadata, tokens, keys or passwords.
WITH
table_names(name) AS (VALUES
 ('profiles'),('wallets'),('connections'),('loan_requests'),('loan_offers'),('loans'),
 ('loan_payments'),('transactions'),('notifications'),('trust_events'),
 ('lender_preferences'),('kash_trust_state'),('kash_trust_daily_penalties')),
function_names(name) AS (VALUES
 ('is_username_available'),('ensure_current_profile'),('provision_confirmed_user'),('handle_new_auth_user'),
 ('complete_member_profile'),('canonical_member_phone'),('member_profile_complete'),('guard_member_phone'),
 ('guard_member_requests'),('refresh_account_state'),('maintain_account_data'),('release_request_offers'),
 ('send_connection_request'),('respond_connection_request'),('create_loan_request'),('edit_loan_request'),
 ('cancel_loan_request'),('create_loan_offer'),('derive_offer_borrower'),('request_loan_offer_terms'),
 ('respond_loan_offer_terms'),('edit_loan_offer'),('cancel_loan_offer'),('accept_loan_offer_reviewed'),
 ('accept_loan_offer'),('decline_loan_offer'),('repay_loan_once'),('repay_loan'),('grant_grace_period'),
 ('mark_recovered_externally'),('mark_loan_overdue'),('preserve_original_deadline'),('name_notification_participants'),
 ('public_profile_directory'),('normalize_ke_phone'),('member_profile_details'),('get_direct_request_borrower_summary'),
 ('refresh_profile_statistics'),('refresh_profile_limit'),('update_loan_statistics'),('touch_updated_at'),
 ('record_trust_event'),('loan_reliability'),('apply_daily_trust_penalties'),('initialize_kash_trust_state')),
rels AS (SELECT c.* FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
 WHERE n.nspname='public' AND c.relname IN (SELECT name FROM table_names)),
funcs AS (SELECT p.* FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
 WHERE n.nspname='public' AND p.proname IN (SELECT name FROM function_names) AND p.prokind='f'),
trigger_funcs AS (SELECT DISTINCT t.tgfoid FROM pg_trigger t JOIN rels r ON r.oid=t.tgrelid
 WHERE NOT t.tgisinternal),
all_funcs AS (SELECT * FROM funcs UNION SELECT p.* FROM pg_proc p
 WHERE p.oid IN (SELECT tgfoid FROM trigger_funcs))
SELECT jsonb_build_object(
 'captured_at',current_timestamp,
 'missing_tables',(SELECT coalesce(jsonb_agg(name ORDER BY name),'[]'::jsonb) FROM table_names
   WHERE name NOT IN (SELECT relname FROM rels)),
 'missing_functions',(SELECT coalesce(jsonb_agg(name ORDER BY name),'[]'::jsonb) FROM function_names
   WHERE name NOT IN (SELECT proname FROM funcs)),
 'tables',(SELECT coalesce(jsonb_agg(jsonb_build_object('table',relname,'rls',relrowsecurity,
   'force_rls',relforcerowsecurity,'owner',pg_get_userbyid(relowner),'acl',relacl::text) ORDER BY relname),'[]'::jsonb) FROM rels),
 'columns',(SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.table_name,x.ordinal_position),'[]'::jsonb)
   FROM (SELECT table_name,ordinal_position,column_name,data_type,udt_name,is_nullable,column_default,
     numeric_precision,numeric_scale,is_identity,is_generated FROM information_schema.columns
     WHERE table_schema='public' AND table_name IN (SELECT name FROM table_names)) x),
 'constraints',(SELECT coalesce(jsonb_agg(jsonb_build_object('table',r.relname,'name',c.conname,
   'type',c.contype,'validated',c.convalidated,'definition',pg_get_constraintdef(c.oid))
   ORDER BY r.relname,c.conname),'[]'::jsonb) FROM pg_constraint c JOIN rels r ON r.oid=c.conrelid),
 'indexes',(SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.tablename,x.indexname),'[]'::jsonb)
   FROM pg_indexes x WHERE schemaname='public' AND tablename IN (SELECT name FROM table_names)),
 'triggers',(SELECT coalesce(jsonb_agg(jsonb_build_object('table',r.relname,'name',t.tgname,
   'enabled',t.tgenabled,'definition',pg_get_triggerdef(t.oid)) ORDER BY r.relname,t.tgname),'[]'::jsonb)
   FROM pg_trigger t JOIN rels r ON r.oid=t.tgrelid WHERE NOT t.tgisinternal),
 'policies',(SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.tablename,x.policyname),'[]'::jsonb)
   FROM pg_policies x WHERE schemaname='public' AND tablename IN (SELECT name FROM table_names)),
 'auth_profile_triggers',(SELECT coalesce(jsonb_agg(jsonb_build_object('name',t.tgname,
   'enabled',t.tgenabled,'definition',pg_get_triggerdef(t.oid)) ORDER BY t.tgname),'[]'::jsonb)
   FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid JOIN pg_namespace n ON n.oid=c.relnamespace
   WHERE n.nspname='auth' AND c.relname='users' AND NOT t.tgisinternal
     AND (t.tgname IN ('on_auth_user_created','on_auth_user_email_confirmed')
       OR t.tgfoid IN (SELECT oid FROM funcs WHERE proname='handle_new_auth_user'))),
 'table_grants',(SELECT coalesce(jsonb_agg(to_jsonb(x)),'[]'::jsonb) FROM information_schema.table_privileges x
   WHERE table_schema='public' AND table_name IN (SELECT name FROM table_names)),
 'column_grants',(SELECT coalesce(jsonb_agg(to_jsonb(x)),'[]'::jsonb) FROM information_schema.column_privileges x
   WHERE table_schema='public' AND table_name IN (SELECT name FROM table_names)),
 'functions',(SELECT coalesce(jsonb_agg(jsonb_build_object('schema',n.nspname,'name',p.proname,
   'signature',p.oid::regprocedure::text,'arguments',pg_get_function_arguments(p.oid),
   'result',pg_get_function_result(p.oid),'security_definer',p.prosecdef,
   'owner',pg_get_userbyid(p.proowner),'settings',p.proconfig,'acl',p.proacl::text,
   'authenticated_execute',has_function_privilege('authenticated',p.oid,'EXECUTE'),
   'anon_execute',has_function_privilege('anon',p.oid,'EXECUTE'),
   'definition',pg_get_functiondef(p.oid)) ORDER BY n.nspname,p.proname,p.oid),'[]'::jsonb)
   FROM all_funcs p JOIN pg_namespace n ON n.oid=p.pronamespace),
 'overloads',(SELECT coalesce(jsonb_agg(to_jsonb(x)),'[]'::jsonb) FROM
   (SELECT proname,count(*) signatures,jsonb_agg(oid::regprocedure::text) installed
    FROM funcs GROUP BY proname HAVING count(*)>1) x),
 'public_schema',(SELECT jsonb_build_object('owner',pg_get_userbyid(nspowner),'acl',nspacl::text)
   FROM pg_namespace WHERE nspname='public'),
 'default_grants',(SELECT coalesce(jsonb_agg(jsonb_build_object('owner',pg_get_userbyid(d.defaclrole),
   'schema',n.nspname,'object_type',d.defaclobjtype,'acl',d.defaclacl::text)),'[]'::jsonb)
   FROM pg_default_acl d LEFT JOIN pg_namespace n ON n.oid=d.defaclnamespace
   WHERE d.defaclnamespace=0 OR n.nspname='public')
) AS database_contract_audit;
