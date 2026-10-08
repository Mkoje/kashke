-- PROPOSED, NOT APPLIED TO HOSTED SUPABASE.
-- Standalone forward-only reconciliation of the 2026-10-05 Android contract.
-- No data repair/maintenance runs during deployment. Runtime actions below
-- retain financial ledgers, and cancel archives an offer rather than deleting it.
BEGIN;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

DO $preflight$
DECLARE t text; bad text; col record;
BEGIN
  IF current_user NOT IN ('postgres','supabase_admin') THEN
    RAISE EXCEPTION 'Run as the trusted database owner; no client/service token deployment';
  END IF;
  -- The supplied diagnostic covered named RPCs, not every unrelated public
  -- function. Refuse to leave an unseen client-callable definer unreviewed.
  SELECT p.oid::regprocedure::text INTO bad FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
  WHERE n.nspname='public' AND p.prosecdef AND p.prokind='f'
    AND (has_function_privilege('anon',p.oid,'EXECUTE') OR has_function_privilege('authenticated',p.oid,'EXECUTE'))
    AND NOT EXISTS(SELECT 1 FROM pg_depend d WHERE d.objid=p.oid AND d.classid='pg_proc'::regclass AND d.deptype='e')
    AND p.proname NOT IN ('is_username_available','ensure_current_profile','complete_member_profile','refresh_account_state',
      'public_profile_directory','member_profile_details','get_direct_request_borrower_summary','send_connection_request',
      'respond_connection_request','create_loan_request','edit_loan_request','cancel_loan_request','create_loan_offer',
      'request_loan_offer_terms','respond_loan_offer_terms','edit_loan_offer','cancel_loan_offer','accept_loan_offer_reviewed',
      'accept_loan_offer','decline_loan_offer','repay_loan_once','grant_grace_period','mark_recovered_externally','repay_loan',
      'maintain_account_data','mark_loan_overdue','record_trust_event','apply_daily_trust_penalties','refresh_profile_limit',
      'refresh_profile_statistics','loan_reliability','member_profile_complete','release_request_offers','canonical_member_phone',
      'initialize_kash_trust_state','name_notification_participants','preserve_original_deadline','finalize_repaid_loan',
      'normalize_ke_phone','derive_offer_borrower','guard_member_requests','guard_member_phone','handle_new_auth_user',
      'provision_confirmed_user','touch_updated_at','update_loan_statistics') LIMIT 1;
  IF bad IS NOT NULL THEN RAISE EXCEPTION 'Unreviewed client-callable SECURITY DEFINER %; review before deployment',bad; END IF;
  FOREACH t IN ARRAY ARRAY['profiles','wallets','connections','loan_requests','loan_offers','loans',
    'loan_payments','transactions','notifications','lender_preferences','trust_events',
    'kash_trust_state','kash_trust_daily_penalties'] LOOP
    IF to_regclass('public.'||t) IS NULL THEN RAISE EXCEPTION 'Missing prerequisite table %',t; END IF;
    IF NOT (SELECT relrowsecurity FROM pg_class WHERE oid=to_regclass('public.'||t)) THEN
      RAISE EXCEPTION 'Prerequisite RLS is disabled on %',t;
    END IF;
  END LOOP;
  FOREACH t IN ARRAY ARRAY['auth.uid()','public.record_trust_event(uuid,text,integer,text,uuid)',
    'public.apply_daily_trust_penalties(uuid)','public.loan_reliability(uuid)',
    'public.refresh_profile_limit(uuid)','public.refresh_profile_statistics(uuid)',
    'public.public_profile_directory(text,text[],uuid[])','public.member_profile_details(uuid)',
    'public.get_direct_request_borrower_summary(uuid)','public.is_username_available(text)',
    'public.preserve_original_deadline()','public.initialize_kash_trust_state()',
    'public.name_notification_participants()'] LOOP
    IF to_regprocedure(t) IS NULL THEN RAISE EXCEPTION 'Missing prerequisite function %',t; END IF;
  END LOOP;
  -- These policy calculations/trigger bodies are preserved, not reinstalled.
  IF NOT EXISTS(SELECT 1 FROM pg_trigger WHERE tgrelid='public.loans'::regclass
    AND tgfoid='public.preserve_original_deadline()'::regprocedure AND tgenabled IN ('O','A')) THEN
    RAISE EXCEPTION 'Enabled original-deadline trigger is required';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM pg_trigger WHERE tgrelid='public.profiles'::regclass
    AND tgfoid='public.initialize_kash_trust_state()'::regprocedure AND tgenabled IN ('O','A')) THEN
    RAISE EXCEPTION 'Enabled trust initialization trigger is required';
  END IF;
  FOR col IN SELECT * FROM (VALUES
    ('loan_offers','borrower_id','uuid'),('loans','original_due_date','timestamptz'),
    ('loan_payments','operation_id','uuid'),('loan_payments','requested_amount','int8')) v(t,c,typ) LOOP
    IF NOT EXISTS(SELECT 1 FROM pg_attribute WHERE attrelid=to_regclass('public.'||col.t)
      AND attname=col.c AND NOT attisdropped AND atttypid=to_regtype(col.typ)) THEN
      RAISE EXCEPTION 'Unexpected/missing prerequisite column %.%',col.t,col.c;
    END IF;
  END LOOP;
  IF EXISTS(SELECT 1 FROM public.wallets WHERE balance<0 OR locked_balance<0 OR locked_balance>balance) THEN
    RAISE EXCEPTION 'Existing wallet invariants fail; reconcile separately without resetting balances';
  END IF;
  IF EXISTS(SELECT 1 FROM public.loan_offers o JOIN public.loan_requests r ON r.id=o.loan_request_id
    WHERE o.borrower_id IS DISTINCT FROM r.borrower_id OR o.lender_id=o.borrower_id) THEN
    RAISE EXCEPTION 'Existing offer participant mismatch; no automatic financial repair';
  END IF;
  IF EXISTS(SELECT 1 FROM public.loan_requests WHERE requested_amount<>funded_amount+remaining_amount
    OR funded_amount<0 OR remaining_amount<0) THEN
    RAISE EXCEPTION 'Existing request accounting mismatch; no automatic repair';
  END IF;
  IF EXISTS(SELECT 1 FROM public.loans WHERE total_repayment<>principal+interest_amount
    OR amount_paid<0 OR amount_paid>total_repayment OR principal_paid<0 OR principal_paid>principal
    OR borrower_id=lender_id OR original_due_date IS NULL) THEN
    RAISE EXCEPTION 'Existing loan accounting mismatch; no automatic repair';
  END IF;
  IF EXISTS(SELECT 1 FROM public.wallets w WHERE w.locked_balance <> coalesce(
    (SELECT sum(o.amount) FROM public.loan_offers o WHERE o.lender_id=w.user_id
      AND o.status IN ('OFFERED','TERMS_REQUESTED')),0))
    OR EXISTS(SELECT 1 FROM public.loan_offers o WHERE o.status IN ('OFFERED','TERMS_REQUESTED')
      AND NOT EXISTS(SELECT 1 FROM public.wallets w WHERE w.user_id=o.lender_id)) THEN
    RAISE EXCEPTION 'Offer reservations and wallets disagree; deployment will not repair/release funds';
  END IF;
  IF EXISTS(SELECT 1 FROM public.loan_payments WHERE operation_id IS NOT NULL
    GROUP BY operation_id HAVING count(*)>1) THEN RAISE EXCEPTION 'Duplicate payment receipt UUIDs'; END IF;
  -- Legacy receipts are outside the supplied metadata scope. Validate before
  -- removing the old API; keep all historical receipts and consult them on retry.
  IF to_regclass('public.repayment_operations') IS NOT NULL THEN
    FOR col IN SELECT * FROM (VALUES ('operation_id','text'),('loan_id','uuid'),
      ('borrower_id','uuid'),('amount','integer')) v(c,typ) LOOP
      IF NOT EXISTS(SELECT 1 FROM pg_attribute WHERE attrelid='public.repayment_operations'::regclass
        AND attname=col.c AND NOT attisdropped AND atttypid=to_regtype(col.typ)) THEN
        RAISE EXCEPTION 'Legacy repayment receipt schema %.% needs review', 'repayment_operations',col.c;
      END IF;
    END LOOP;
    EXECUTE 'select operation_id from public.repayment_operations group by operation_id having count(*)>1 limit 1' INTO bad;
    IF bad IS NOT NULL THEN RAISE EXCEPTION 'Duplicate legacy receipt IDs need review'; END IF;
  ELSIF to_regprocedure('public.repay_loan_once(uuid,integer,text)') IS NOT NULL THEN
    RAISE EXCEPTION 'Legacy receipt API exists but receipt table is absent; cannot preserve retries safely';
  END IF;
  -- Extra columns without defaults are not silently ignored by new INSERTs.
  SELECT format('%I.%I',table_name,column_name) INTO bad FROM information_schema.columns
  WHERE table_schema='public' AND is_nullable='NO' AND column_default IS NULL
    AND is_identity='NO' AND is_generated='NEVER' AND table_name IN
      ('profiles','wallets','connections','loan_requests','loan_offers','loans','loan_payments','transactions','notifications','lender_preferences')
    AND (table_name,column_name) NOT IN (VALUES
      ('profiles','id'),('profiles','full_name'),('profiles','username'),
      ('wallets','user_id'),('connections','requester_id'),('connections','recipient_id'),
      ('loan_requests','borrower_id'),('loan_requests','requested_amount'),('loan_requests','remaining_amount'),
      ('loan_requests','purpose'),('loan_requests','description'),
      ('loan_offers','loan_request_id'),('loan_offers','lender_id'),('loan_offers','borrower_id'),('loan_offers','amount'),
      ('loan_offers','interest_rate'),('loan_offers','repayment_period_days'),('loan_offers','total_repayment'),
      ('loans','borrower_id'),('loans','lender_id'),('loans','loan_request_id'),('loans','principal'),
      ('loans','interest_rate'),('loans','interest_amount'),('loans','total_repayment'),('loans','lender_contribution'),
      ('loans','due_date'),('loans','original_due_date'),('loan_payments','loan_id'),('loan_payments','payer_id'),
      ('loan_payments','amount'),('loan_payments','payment_type'),('transactions','type'),('transactions','sender_id'),
      ('transactions','recipient_id'),('transactions','amount'),('transactions','status'),('transactions','description'),
      ('notifications','user_id'),('notifications','type'),('notifications','title'),('notifications','message'),
      ('lender_preferences','user_id')) LIMIT 1;
  IF bad IS NOT NULL THEN RAISE EXCEPTION 'Unreviewed required INSERT column %',bad; END IF;
END;
$preflight$;

-- Only empty fresh installations may gain this forgotten historical column.
-- Existing hosted loans already have it; no backfill/default or row update.
DO $$ BEGIN
  IF NOT EXISTS(SELECT 1 FROM pg_attribute WHERE attrelid='public.loans'::regclass
    AND attname='lender_contribution' AND NOT attisdropped) THEN
    IF EXISTS(SELECT 1 FROM public.loans) THEN RAISE EXCEPTION 'Cannot add contribution to existing loans without reviewed data repair'; END IF;
    ALTER TABLE public.loans ADD COLUMN lender_contribution integer NOT NULL;
  END IF;
END $$;
DO $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM pg_attribute WHERE attrelid='public.loans'::regclass AND attname='lender_contribution'
  AND NOT attisdropped AND atttypid='integer'::regtype AND attnotnull) THEN
  RAISE EXCEPTION 'Required lender_contribution must remain integer NOT NULL'; END IF;
END $$;
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS profile_email text NOT NULL DEFAULT '';
ALTER TABLE public.loans ADD COLUMN IF NOT EXISTS grace_granted_at timestamptz;
ALTER TABLE public.loans ADD COLUMN IF NOT EXISTS grace_days integer CHECK(grace_days BETWEEN 1 AND 30);
CREATE UNIQUE INDEX IF NOT EXISTS loan_payments_operation_idx ON public.loan_payments(operation_id) WHERE operation_id IS NOT NULL;

-- Exact obsolete signatures; RESTRICT is intentional (never CASCADE).
DROP FUNCTION IF EXISTS public.request_loan_offer_terms(uuid,integer,integer);
DROP FUNCTION IF EXISTS public.create_loan_request(integer,text,text,integer);
DROP FUNCTION IF EXISTS public.edit_loan_request(uuid,integer,text,text,integer);
DROP FUNCTION IF EXISTS public.repay_loan_once(uuid,integer,text);
-- Same type signature had different names/order/return type across installations.
DROP FUNCTION IF EXISTS public.complete_member_profile(text,text,text);

CREATE OR REPLACE FUNCTION public.canonical_member_phone(p_phone text)
RETURNS text LANGUAGE sql IMMUTABLE SET search_path='' AS $$
SELECT CASE WHEN d ~ '^254[17][0-9]{8}$' THEN '+'||d
 WHEN d ~ '^0[17][0-9]{8}$' THEN '+254'||substring(d FROM 2)
 WHEN d ~ '^[17][0-9]{8}$' THEN '+254'||d ELSE NULL END
FROM (SELECT regexp_replace(coalesce(p_phone,''),'[^0-9]','','g') d) s;
$$;
-- Creating an index scans keys, but does not alter any profile. Duplicate
-- canonical mobiles abort the transaction rather than merge/change users.
CREATE UNIQUE INDEX IF NOT EXISTS profiles_unique_mobile ON public.profiles(public.canonical_member_phone(phone))
 WHERE public.canonical_member_phone(phone) IS NOT NULL;

CREATE OR REPLACE FUNCTION public.member_profile_complete(p_user_id uuid)
RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
SELECT coalesce((SELECT length(trim(full_name))>0 AND username ~ '^[a-z0-9_]{3,24}$'
 AND username !~ '^user_[a-f0-9]{19}$' AND trim(profile_email) ~ '^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$'
 AND public.canonical_member_phone(phone) IS NOT NULL FROM public.profiles WHERE id=p_user_id),false);
$$;

CREATE OR REPLACE FUNCTION public.ensure_current_profile()
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE u auth.users%rowtype; un text; em text;
BEGIN
 IF auth.uid() IS NULL THEN RAISE EXCEPTION 'Sign in first' USING ERRCODE='28000'; END IF;
 SELECT * INTO STRICT u FROM auth.users WHERE id=auth.uid();
 un:=lower(trim(coalesce(u.raw_user_meta_data->>'username','')));
 IF un !~ '^[a-z0-9_]{3,24}$' THEN un:='user_'||left(replace(u.id::text,'-',''),19); END IF;
 em:=coalesce(nullif(u.raw_user_meta_data->>'profile_email',''),u.email,'');
 IF em ~* '@auth\.kashke\.local$' THEN em:=''; END IF;
 INSERT INTO public.profiles(id,full_name,username,phone,profile_email)
 VALUES(u.id,coalesce(nullif(trim(u.raw_user_meta_data->>'full_name'),''),'Member'),un,
   coalesce(public.canonical_member_phone(u.raw_user_meta_data->>'phone'),''),lower(trim(em)))
 ON CONFLICT(id) DO NOTHING;
 -- Only the signed-in owner's empty contact email is hydrated; never money/Trust.
 UPDATE public.profiles SET profile_email=lower(trim(em)) WHERE id=u.id AND profile_email='' AND em<>'';
 INSERT INTO public.wallets(user_id) VALUES(u.id) ON CONFLICT(user_id) DO NOTHING;
 INSERT INTO public.lender_preferences(user_id) VALUES(u.id) ON CONFLICT(user_id) DO NOTHING;
END;
$$;
-- OFFER CREATION: the borrower is explicit; no derivation trigger dependency.
CREATE OR REPLACE FUNCTION public.create_loan_offer(p_request_id uuid,p_amount bigint,p_interest_rate numeric,p_period_days integer,
 p_approve_above_limit boolean DEFAULT false)
RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE r public.loan_requests%rowtype; w public.wallets%rowtype; oid uuid; total bigint; ceiling integer;
BEGIN
 IF auth.uid() IS NULL THEN RAISE EXCEPTION 'Sign in first' USING ERRCODE='28000'; END IF;
 SELECT * INTO r FROM public.loan_requests WHERE id=p_request_id FOR UPDATE;
 IF NOT FOUND OR r.status NOT IN ('REQUESTED','PARTIALLY_FUNDED') OR r.expires_at<=now() THEN RAISE EXCEPTION 'Request is no longer available'; END IF;
 IF r.direct_request_to IS NOT NULL AND r.direct_request_to<>auth.uid() THEN RAISE EXCEPTION 'This request is private'; END IF;
 IF r.direct_request_to IS NULL AND NOT EXISTS(SELECT 1 FROM public.connections WHERE status='ACCEPTED'
  AND ((requester_id=auth.uid() AND recipient_id=r.borrower_id) OR (recipient_id=auth.uid() AND requester_id=r.borrower_id))) THEN
   RAISE EXCEPTION 'Connect with this borrower first'; END IF;
 IF r.borrower_id=auth.uid() OR p_amount IS NULL OR p_amount NOT BETWEEN 1 AND r.remaining_amount THEN RAISE EXCEPTION 'Invalid offer amount'; END IF;
 IF p_approve_above_limit IS NULL OR (r.above_limit_requested AND (r.direct_request_to IS DISTINCT FROM auth.uid() OR NOT p_approve_above_limit))
  OR (p_approve_above_limit AND (NOT r.above_limit_requested OR r.direct_request_to IS DISTINCT FROM auth.uid())) THEN
   RAISE EXCEPTION 'Only the designated lender may explicitly approve an above-limit request'; END IF;
 ceiling:=CASE WHEN r.above_limit_requested THEN 15 ELSE 7 END;
 IF p_interest_rate IS NULL OR p_interest_rate<>trunc(p_interest_rate) OR p_interest_rate NOT BETWEEN 0 AND ceiling
  OR p_period_days IS NULL OR p_period_days NOT BETWEEN 7 AND r.preferred_period_days THEN RAISE EXCEPTION 'Invalid offer terms'; END IF;
 total:=p_amount+round(p_amount*p_interest_rate*p_period_days/36500.0)::bigint;
 IF total>2147483647 THEN RAISE EXCEPTION 'Offer total exceeds supported amount'; END IF;
 PERFORM 1 FROM public.profiles WHERE id IN (r.borrower_id,auth.uid()) ORDER BY id FOR UPDATE;
 SELECT * INTO w FROM public.wallets WHERE user_id=auth.uid() FOR UPDATE;
 IF NOT FOUND OR w.balance-w.locked_balance<p_amount THEN RAISE EXCEPTION 'Insufficient available wallet balance'; END IF;
 IF EXISTS(SELECT 1 FROM public.lender_preferences WHERE user_id=auth.uid() AND maximum_lending_amount>0 AND maximum_lending_amount<p_amount) THEN
   RAISE EXCEPTION 'Offer exceeds your maximum lending amount'; END IF;
 UPDATE public.wallets SET locked_balance=locked_balance+p_amount,updated_at=now() WHERE user_id=auth.uid();
 INSERT INTO public.loan_offers(loan_request_id,lender_id,borrower_id,amount,interest_rate,repayment_period_days,total_repayment,above_limit_override)
 VALUES(r.id,auth.uid(),r.borrower_id,p_amount,p_interest_rate,p_period_days,total,p_approve_above_limit) RETURNING id INTO oid;
 INSERT INTO public.notifications(user_id,type,title,message,related_entity_id)
 VALUES(r.borrower_id,'LOAN_OFFER','New support offer',format('A supporter offered KSh %s at %s%% for %s days.',p_amount,p_interest_rate,p_period_days),oid);
 RETURN oid;
END;
$$;

CREATE OR REPLACE FUNCTION public.request_loan_offer_terms(p_offer_id uuid,p_interest_rate numeric,p_period_days integer)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE o public.loan_offers%rowtype; r public.loan_requests%rowtype;
BEGIN
 IF auth.uid() IS NULL THEN RAISE EXCEPTION 'Sign in first' USING ERRCODE='28000'; END IF;
 SELECT * INTO r FROM public.loan_requests WHERE id=(SELECT loan_request_id FROM public.loan_offers
  WHERE id=p_offer_id AND borrower_id=auth.uid()) FOR UPDATE;
 IF NOT FOUND OR r.status NOT IN ('REQUESTED','PARTIALLY_FUNDED') OR r.expires_at<=now() THEN RAISE EXCEPTION 'Offer is no longer available'; END IF;
 SELECT * INTO o FROM public.loan_offers WHERE id=p_offer_id AND borrower_id=auth.uid() AND status='OFFERED' FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Offer is no longer available'; END IF;
 IF NOT public.member_profile_complete(auth.uid()) THEN RAISE EXCEPTION 'Complete your profile before making requests'; END IF;
 IF p_interest_rate IS NULL OR p_interest_rate<>trunc(p_interest_rate) OR p_interest_rate NOT BETWEEN 0 AND o.interest_rate
  OR p_period_days IS NULL OR p_period_days NOT BETWEEN 7 AND 365 THEN RAISE EXCEPTION 'Requested terms must use equal-or-lower interest and a 7-365 day period'; END IF;
 -- Pending terms cannot be overwritten; the lender must respond first.
 UPDATE public.loan_offers SET status='TERMS_REQUESTED',requested_interest_rate=p_interest_rate,
  requested_repayment_period_days=p_period_days WHERE id=o.id;
 INSERT INTO public.notifications(user_id,type,title,message,related_entity_id)
 VALUES(o.lender_id,'LOAN_OFFER_TERMS_REQUESTED','Terms requested',format('%s requested %s%% for %s days.',
  (SELECT full_name FROM public.profiles WHERE id=auth.uid()),p_interest_rate,p_period_days),o.id);
END;
$$;

CREATE OR REPLACE FUNCTION public.respond_loan_offer_terms(p_offer_id uuid,p_accept_terms boolean)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE o public.loan_offers%rowtype; r public.loan_requests%rowtype; rate numeric; days integer; total bigint;
BEGIN
 IF auth.uid() IS NULL THEN RAISE EXCEPTION 'Sign in first' USING ERRCODE='28000'; END IF;
 SELECT * INTO r FROM public.loan_requests WHERE id=(SELECT loan_request_id FROM public.loan_offers
  WHERE id=p_offer_id AND lender_id=auth.uid()) FOR UPDATE;
 IF NOT FOUND OR r.status NOT IN ('REQUESTED','PARTIALLY_FUNDED') OR r.expires_at<=now() THEN RAISE EXCEPTION 'Offer is no longer available'; END IF;
 SELECT * INTO o FROM public.loan_offers WHERE id=p_offer_id AND lender_id=auth.uid() AND status='TERMS_REQUESTED' FOR UPDATE;
 IF NOT FOUND OR p_accept_terms IS NULL THEN RAISE EXCEPTION 'Terms request is no longer available'; END IF;
 rate:=CASE WHEN p_accept_terms THEN o.requested_interest_rate ELSE o.interest_rate END;
 days:=CASE WHEN p_accept_terms THEN o.requested_repayment_period_days ELSE o.repayment_period_days END;
 IF rate IS NULL OR rate<>trunc(rate) OR rate NOT BETWEEN 0 AND o.interest_rate
  OR rate>(CASE WHEN r.above_limit_requested AND o.above_limit_override AND r.direct_request_to=o.lender_id THEN 15 ELSE 7 END)
  OR days IS NULL OR days NOT BETWEEN 7 AND 365 THEN RAISE EXCEPTION 'Invalid offer terms'; END IF;
 total:=o.amount+round(o.amount::numeric*rate*days/36500.0)::bigint;
 IF total>2147483647 THEN RAISE EXCEPTION 'Offer total exceeds supported amount'; END IF;
 UPDATE public.loan_offers SET status='OFFERED',interest_rate=rate,repayment_period_days=days,total_repayment=total,
  requested_interest_rate=NULL,requested_repayment_period_days=NULL WHERE id=o.id;
 INSERT INTO public.notifications(user_id,type,title,message,related_entity_id)
 VALUES(o.borrower_id,'LOAN_OFFER_UPDATED','Offer terms updated',format('The supporter responded with %s%% interest for %s days.',rate,days),o.id);
END;
$$;

CREATE OR REPLACE FUNCTION public.edit_loan_offer(p_offer_id uuid,p_amount bigint,p_interest_rate numeric,p_period_days integer)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE o public.loan_offers%rowtype; r public.loan_requests%rowtype; w public.wallets%rowtype; total bigint;
BEGIN
 IF auth.uid() IS NULL THEN RAISE EXCEPTION 'Sign in first' USING ERRCODE='28000'; END IF;
 SELECT * INTO r FROM public.loan_requests WHERE id=(SELECT loan_request_id FROM public.loan_offers
  WHERE id=p_offer_id AND lender_id=auth.uid()) FOR UPDATE;
 SELECT * INTO o FROM public.loan_offers WHERE id=p_offer_id AND lender_id=auth.uid() AND status='OFFERED' FOR UPDATE;
 IF NOT FOUND OR r.id IS NULL OR r.status NOT IN ('REQUESTED','PARTIALLY_FUNDED') OR r.expires_at<=now() THEN RAISE EXCEPTION 'Offer unavailable'; END IF;
 IF p_amount IS NULL OR p_amount NOT BETWEEN 1 AND r.remaining_amount OR p_interest_rate IS NULL OR p_interest_rate<>trunc(p_interest_rate)
  OR p_interest_rate NOT BETWEEN 0 AND (CASE WHEN r.above_limit_requested AND o.above_limit_override AND r.direct_request_to=o.lender_id THEN 15 ELSE 7 END)
  OR p_period_days IS NULL OR p_period_days NOT BETWEEN 7 AND r.preferred_period_days THEN RAISE EXCEPTION 'Invalid offer terms'; END IF;
 total:=p_amount+round(p_amount*p_interest_rate*p_period_days/36500.0)::bigint;
 IF total>2147483647 THEN RAISE EXCEPTION 'Offer total exceeds supported amount'; END IF;
 IF EXISTS(SELECT 1 FROM public.lender_preferences WHERE user_id=auth.uid() AND maximum_lending_amount>0 AND maximum_lending_amount<p_amount) THEN
  RAISE EXCEPTION 'Maximum lending amount exceeded'; END IF;
 SELECT * INTO w FROM public.wallets WHERE user_id=auth.uid() FOR UPDATE;
 IF NOT FOUND OR w.locked_balance<o.amount OR w.balance-w.locked_balance+o.amount<p_amount THEN RAISE EXCEPTION 'Insufficient available wallet balance'; END IF;
 UPDATE public.wallets SET locked_balance=locked_balance-o.amount+p_amount,updated_at=now() WHERE user_id=auth.uid();
 UPDATE public.loan_offers SET amount=p_amount,interest_rate=p_interest_rate,repayment_period_days=p_period_days,total_repayment=total,
  requested_interest_rate=NULL,requested_repayment_period_days=NULL WHERE id=o.id;
 INSERT INTO public.notifications(user_id,type,title,message,related_entity_id)
 VALUES(r.borrower_id,'LOAN_OFFER_UPDATED','Offer updated',format('Your lender updated the offer to KSh %s at %s%% for %s days. Review before accepting.',p_amount,p_interest_rate,p_period_days),o.id);
END;
$$;

CREATE OR REPLACE FUNCTION public.cancel_loan_offer(p_offer_id uuid)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE o public.loan_offers%rowtype;
BEGIN
 IF auth.uid() IS NULL THEN RAISE EXCEPTION 'Sign in first' USING ERRCODE='28000'; END IF;
 PERFORM 1 FROM public.loan_requests WHERE id=(SELECT loan_request_id FROM public.loan_offers
  WHERE id=p_offer_id AND lender_id=auth.uid()) FOR UPDATE;
 SELECT * INTO o FROM public.loan_offers WHERE id=p_offer_id AND lender_id=auth.uid() AND status IN ('OFFERED','TERMS_REQUESTED') FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Offer unavailable'; END IF;
 UPDATE public.wallets SET locked_balance=locked_balance-o.amount,updated_at=now() WHERE user_id=o.lender_id AND locked_balance>=o.amount;
 IF NOT FOUND THEN RAISE EXCEPTION 'Reserved funds unavailable'; END IF;
 UPDATE public.loan_offers SET status='EXPIRED',requested_interest_rate=NULL,requested_repayment_period_days=NULL WHERE id=o.id;
 INSERT INTO public.notifications(user_id,type,title,message,related_entity_id)
 VALUES(o.borrower_id,'LOAN_OFFER_CANCELLED','Offer cancelled',format('Your lender cancelled the KSh %s offer. It is no longer available.',o.amount),o.id);
END;
$$;

CREATE OR REPLACE FUNCTION public.decline_loan_offer(p_offer_id uuid)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE o public.loan_offers%rowtype;
BEGIN
 IF auth.uid() IS NULL THEN RAISE EXCEPTION 'Sign in first' USING ERRCODE='28000'; END IF;
 PERFORM 1 FROM public.loan_requests WHERE id=(SELECT loan_request_id FROM public.loan_offers
  WHERE id=p_offer_id AND borrower_id=auth.uid()) FOR UPDATE;
 SELECT * INTO o FROM public.loan_offers WHERE id=p_offer_id AND borrower_id=auth.uid() AND status IN ('OFFERED','TERMS_REQUESTED') FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Offer is no longer available'; END IF;
 UPDATE public.wallets SET locked_balance=locked_balance-o.amount,updated_at=now() WHERE user_id=o.lender_id AND locked_balance>=o.amount;
 IF NOT FOUND THEN RAISE EXCEPTION 'Reserved funds unavailable'; END IF;
 UPDATE public.loan_offers SET status='DECLINED',requested_interest_rate=NULL,requested_repayment_period_days=NULL WHERE id=o.id;
 INSERT INTO public.notifications(user_id,type,title,message,related_entity_id)
 VALUES(o.lender_id,'LOAN_OFFER_DECLINED','Offer declined','The borrower declined your support offer.',o.id);
END;
$$;

CREATE OR REPLACE FUNCTION public.complete_member_profile(p_full_name text,p_username text,p_email text,p_phone text)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE ph text; oldph text;
BEGIN
 IF auth.uid() IS NULL THEN RAISE EXCEPTION 'Sign in first' USING ERRCODE='28000'; END IF;
 IF p_full_name IS NULL OR length(trim(p_full_name))=0 OR p_username IS NULL
   OR lower(trim(p_username)) !~ '^[a-z0-9_]{3,24}$' OR lower(trim(p_username)) ~ '^user_[a-f0-9]{19}$'
   OR p_email IS NULL OR trim(p_email) !~ '^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$'
   OR p_email ~* '@auth\.kashke\.local$' THEN RAISE EXCEPTION 'Complete full name, username, email and phone number'; END IF;
 ph:=public.canonical_member_phone(p_phone);
 IF ph IS NULL THEN RAISE EXCEPTION 'Enter a valid Kenyan mobile number'; END IF;
 SELECT phone INTO oldph FROM public.profiles WHERE id=auth.uid() FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Profile unavailable'; END IF;
 IF nullif(trim(oldph),'') IS NOT NULL AND public.canonical_member_phone(oldph) IS DISTINCT FROM ph THEN
   RAISE EXCEPTION 'Phone changes require account verification'; END IF;
 UPDATE public.profiles SET full_name=trim(p_full_name),username=lower(trim(p_username)),profile_email=lower(trim(p_email)),
   phone=CASE WHEN nullif(trim(oldph),'') IS NULL THEN ph ELSE oldph END WHERE id=auth.uid();
END;
$$;

-- Deliberate legacy named-parameter compatibility; preserve hosted composite result.
CREATE FUNCTION public.complete_member_profile(p_email text,p_full_name text,p_username text)
RETURNS public.profiles LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE ph text; result public.profiles%rowtype;
BEGIN
 IF auth.uid() IS NULL THEN RAISE EXCEPTION 'Sign in first' USING ERRCODE='28000'; END IF;
 SELECT phone INTO ph FROM public.profiles WHERE id=auth.uid();
 PERFORM public.complete_member_profile(p_full_name,p_username,p_email,ph);
 SELECT * INTO STRICT result FROM public.profiles WHERE id=auth.uid();
 RETURN result;
END;
$$;

CREATE OR REPLACE FUNCTION public.release_request_offers(p_request_id uuid)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE o public.loan_offers%rowtype;
BEGIN
 -- Internal only. Request lock precedes wallet/offer mutation.
 PERFORM 1 FROM public.loan_requests WHERE id=p_request_id FOR UPDATE;
 PERFORM 1 FROM public.wallets WHERE user_id IN (SELECT lender_id FROM public.loan_offers
   WHERE loan_request_id=p_request_id AND status IN ('OFFERED','TERMS_REQUESTED')) ORDER BY user_id FOR UPDATE;
 FOR o IN SELECT * FROM public.loan_offers WHERE loan_request_id=p_request_id
   AND status IN ('OFFERED','TERMS_REQUESTED') ORDER BY lender_id,id FOR UPDATE LOOP
   UPDATE public.wallets SET locked_balance=locked_balance-o.amount,updated_at=now()
    WHERE user_id=o.lender_id AND locked_balance>=o.amount;
   IF NOT FOUND THEN RAISE EXCEPTION 'Reserved funds unavailable'; END IF;
   UPDATE public.loan_offers SET status='EXPIRED',requested_interest_rate=NULL,requested_repayment_period_days=NULL WHERE id=o.id;
 END LOOP;
END;
$$;

CREATE OR REPLACE FUNCTION public.create_loan_request(p_amount bigint,p_purpose text,p_description text,p_period_days integer,
 p_direct_request_to uuid DEFAULT NULL,p_above_limit_requested boolean DEFAULT false)
RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE p public.profiles%rowtype; rid uuid; c record;
BEGIN
 IF auth.uid() IS NULL THEN RAISE EXCEPTION 'Sign in first' USING ERRCODE='28000'; END IF;
 SELECT * INTO p FROM public.profiles WHERE id=auth.uid() FOR UPDATE;
 IF NOT FOUND OR p.borrowing_frozen OR p.account_status<>'ACTIVE' THEN RAISE EXCEPTION 'Borrowing is unavailable'; END IF;
 IF NOT public.member_profile_complete(p.id) THEN RAISE EXCEPTION 'Complete your profile before making requests'; END IF;
 IF p_amount IS NULL OR p_amount NOT BETWEEN 1 AND 2147483647 OR p_period_days IS NULL
  OR p_period_days NOT BETWEEN 7 AND 365 OR nullif(trim(p_purpose),'') IS NULL OR nullif(trim(p_description),'') IS NULL
  OR p_above_limit_requested IS NULL THEN RAISE EXCEPTION 'Invalid request terms'; END IF;
 IF p_direct_request_to=auth.uid() THEN RAISE EXCEPTION 'Cannot request support from yourself'; END IF;
 IF p_direct_request_to IS NOT NULL AND NOT EXISTS(SELECT 1 FROM public.connections WHERE status='ACCEPTED'
  AND ((requester_id=auth.uid() AND recipient_id=p_direct_request_to) OR (recipient_id=auth.uid() AND requester_id=p_direct_request_to))) THEN
  RAISE EXCEPTION 'Direct requests are available only to connected members'; END IF;
 IF p_above_limit_requested<>(p_amount>p.available_limit) THEN RAISE EXCEPTION 'Above-limit disclosure must match the requested amount'; END IF;
 IF p_amount>p.available_limit AND p_direct_request_to IS NULL THEN RAISE EXCEPTION 'Above-limit requests must be directed to a connected lender'; END IF;
 INSERT INTO public.loan_requests(borrower_id,requested_amount,funded_amount,remaining_amount,purpose,description,
   preferred_period_days,direct_request_to,above_limit_requested)
 VALUES(p.id,p_amount,0,p_amount,trim(p_purpose),trim(p_description),p_period_days,p_direct_request_to,p_above_limit_requested) RETURNING id INTO rid;
 -- Capacity follows funded outstanding principal, not unfunded request creation.
 IF p_direct_request_to IS NOT NULL THEN
  INSERT INTO public.notifications(user_id,type,title,message,related_entity_id) VALUES
   (p_direct_request_to,'DIRECT_REQUEST','Direct support request',format('%s requested KSh %s for %s%s.',p.full_name,p_amount,p_purpose,
    CASE WHEN p_above_limit_requested THEN ' above the disclosed borrowing limit' ELSE '' END),rid);
 ELSE
  FOR c IN SELECT DISTINCT CASE WHEN requester_id=p.id THEN recipient_id ELSE requester_id END other_id
    FROM public.connections WHERE status='ACCEPTED' AND (requester_id=p.id OR recipient_id=p.id) LOOP
   INSERT INTO public.notifications(user_id,type,title,message,related_entity_id)
    VALUES(c.other_id,'NEW_LOAN_REQUEST','Community request',format('%s requested KSh %s for %s.',p.full_name,p_amount,p_purpose),rid);
  END LOOP;
 END IF;
 RETURN rid;
END;
$$;

CREATE OR REPLACE FUNCTION public.edit_loan_request(p_request_id uuid,p_amount bigint,p_purpose text,p_description text,p_period_days integer)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE r public.loan_requests%rowtype; p public.profiles%rowtype;
BEGIN
 IF auth.uid() IS NULL THEN RAISE EXCEPTION 'Sign in first' USING ERRCODE='28000'; END IF;
 SELECT * INTO r FROM public.loan_requests WHERE id=p_request_id AND borrower_id=auth.uid() FOR UPDATE;
 IF NOT FOUND OR r.status<>'REQUESTED' OR r.funded_amount<>0 OR r.expires_at<=now() THEN RAISE EXCEPTION 'Request cannot be edited'; END IF;
 SELECT * INTO p FROM public.profiles WHERE id=auth.uid() FOR UPDATE;
 IF p.borrowing_frozen OR p.account_status<>'ACTIVE' OR NOT public.member_profile_complete(p.id) THEN RAISE EXCEPTION 'Borrowing is unavailable'; END IF;
 IF p_amount IS NULL OR p_amount NOT BETWEEN 1 AND p.available_limit OR p_period_days IS NULL OR p_period_days NOT BETWEEN 7 AND 365
   OR nullif(trim(p_purpose),'') IS NULL OR nullif(trim(p_description),'') IS NULL THEN RAISE EXCEPTION 'Invalid request terms'; END IF;
 PERFORM public.release_request_offers(r.id);
 UPDATE public.loan_requests SET requested_amount=p_amount,remaining_amount=p_amount,purpose=trim(p_purpose),
  description=trim(p_description),preferred_period_days=p_period_days,above_limit_requested=false WHERE id=r.id;
END;
$$;

CREATE OR REPLACE FUNCTION public.cancel_loan_request(p_request_id uuid)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
BEGIN
 IF auth.uid() IS NULL THEN RAISE EXCEPTION 'Sign in first' USING ERRCODE='28000'; END IF;
 PERFORM 1 FROM public.loan_requests WHERE id=p_request_id AND borrower_id=auth.uid() AND status='REQUESTED' AND funded_amount=0 FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Request cannot be cancelled after support begins'; END IF;
 PERFORM public.release_request_offers(p_request_id);
 UPDATE public.loan_requests SET status='CANCELLED' WHERE id=p_request_id;
END;
$$;

-- Internal acceptance is retained for old clients, with the same participant
-- checks as reviewed acceptance. No lending/Trust policy is sourced from JSON.
CREATE OR REPLACE FUNCTION public.accept_loan_offer(p_offer_id uuid)
RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE o public.loan_offers%rowtype; r public.loan_requests%rowtype; p public.profiles%rowtype;
 w public.wallets%rowtype; other record; amount bigint; intr bigint; total bigint; lid uuid; deadline timestamptz;
BEGIN
 IF auth.uid() IS NULL THEN RAISE EXCEPTION 'Sign in first' USING ERRCODE='28000'; END IF;
 SELECT * INTO r FROM public.loan_requests WHERE id=(SELECT loan_request_id FROM public.loan_offers
  WHERE id=p_offer_id AND borrower_id=auth.uid()) FOR UPDATE;
 IF NOT FOUND OR r.borrower_id<>auth.uid() OR r.status NOT IN ('REQUESTED','PARTIALLY_FUNDED') OR r.remaining_amount<=0 OR r.expires_at<=now() THEN
  RAISE EXCEPTION 'Request is closed'; END IF;
 SELECT * INTO o FROM public.loan_offers WHERE id=p_offer_id AND borrower_id=auth.uid() AND status='OFFERED' FOR UPDATE;
 IF NOT FOUND OR o.borrower_id<>r.borrower_id OR o.lender_id=o.borrower_id THEN RAISE EXCEPTION 'Offer is no longer available'; END IF;
 PERFORM 1 FROM public.profiles WHERE id IN (r.borrower_id,o.lender_id) ORDER BY id FOR UPDATE;
 PERFORM 1 FROM public.wallets WHERE user_id=r.borrower_id OR user_id IN
  (SELECT lender_id FROM public.loan_offers WHERE loan_request_id=r.id AND status IN ('OFFERED','TERMS_REQUESTED')) ORDER BY user_id FOR UPDATE;
 SELECT * INTO p FROM public.profiles WHERE id=r.borrower_id;
 IF p.borrowing_frozen OR p.account_status<>'ACTIVE' THEN RAISE EXCEPTION 'Borrowing is frozen'; END IF;
 IF r.direct_request_to IS NOT NULL AND r.direct_request_to<>o.lender_id THEN RAISE EXCEPTION 'This request is private'; END IF;
 IF r.above_limit_requested AND NOT (o.above_limit_override AND r.direct_request_to=o.lender_id) THEN RAISE EXCEPTION 'Above-limit approval required'; END IF;
 IF o.interest_rate<>trunc(o.interest_rate) OR o.interest_rate NOT BETWEEN 0 AND
  (CASE WHEN r.above_limit_requested AND o.above_limit_override AND r.direct_request_to=o.lender_id THEN 15 ELSE 7 END)
  OR o.repayment_period_days NOT BETWEEN 7 AND 365 THEN RAISE EXCEPTION 'Invalid offer terms'; END IF;
 amount:=least(o.amount,r.remaining_amount);
 IF p.available_limit<amount AND NOT (o.above_limit_override AND r.above_limit_requested AND r.direct_request_to=o.lender_id) THEN
  RAISE EXCEPTION 'Borrowing limit exceeded; designated lender approval is required'; END IF;
 SELECT * INTO w FROM public.wallets WHERE user_id=o.lender_id;
 IF NOT FOUND OR w.balance<amount OR w.locked_balance<o.amount THEN RAISE EXCEPTION 'Reserved funds unavailable'; END IF;
 IF NOT EXISTS(SELECT 1 FROM public.wallets WHERE user_id=r.borrower_id) THEN RAISE EXCEPTION 'Borrower wallet not found'; END IF;
 intr:=round(amount::numeric*o.interest_rate*o.repayment_period_days/36500.0)::bigint;
 total:=amount+intr;
 IF total>2147483647 OR (SELECT balance::bigint+amount FROM public.wallets WHERE user_id=r.borrower_id)>2147483647 THEN
  RAISE EXCEPTION 'Funding exceeds supported amount'; END IF;
 deadline:=now()+make_interval(days=>o.repayment_period_days);
 INSERT INTO public.loans(loan_request_id,borrower_id,lender_id,principal,interest_rate,interest_amount,total_repayment,
  amount_paid,principal_paid,lender_contribution,due_date,original_due_date,status)
 VALUES(r.id,r.borrower_id,o.lender_id,amount,o.interest_rate,intr,total,0,0,amount,deadline,deadline,'ACTIVE') RETURNING id INTO lid;
 UPDATE public.wallets SET balance=balance-amount,locked_balance=locked_balance-o.amount,updated_at=now() WHERE user_id=o.lender_id;
 UPDATE public.wallets SET balance=balance+amount,updated_at=now() WHERE user_id=r.borrower_id;
 UPDATE public.loan_offers SET status='ACCEPTED',requested_interest_rate=NULL,requested_repayment_period_days=NULL WHERE id=o.id;
 UPDATE public.loan_requests SET funded_amount=funded_amount+amount,remaining_amount=remaining_amount-amount,
  status=CASE WHEN remaining_amount-amount=0 THEN 'FULLY_FUNDED' ELSE 'PARTIALLY_FUNDED' END WHERE id=r.id;
 -- Close every competing offer, including partial funding, exactly once.
 FOR other IN SELECT * FROM public.loan_offers WHERE loan_request_id=r.id AND id<>o.id
  AND status IN ('OFFERED','TERMS_REQUESTED') ORDER BY lender_id,id FOR UPDATE LOOP
  UPDATE public.wallets SET locked_balance=locked_balance-other.amount,updated_at=now()
   WHERE user_id=other.lender_id AND locked_balance>=other.amount;
  IF NOT FOUND THEN RAISE EXCEPTION 'Competing reserved funds unavailable'; END IF;
  UPDATE public.loan_offers SET status='DECLINED',requested_interest_rate=NULL,requested_repayment_period_days=NULL WHERE id=other.id;
  INSERT INTO public.notifications(user_id,type,title,message,related_entity_id)
   VALUES(other.lender_id,'LOAN_OFFER_DECLINED','Offer automatically closed',
    format('%s already accepted an offer. Your KSh %s offer was closed and reserved funds released.',p.full_name,other.amount),other.id);
 END LOOP;
 INSERT INTO public.transactions(type,sender_id,recipient_id,amount,interest,loan_id,status,description)
 VALUES('LOAN_DISBURSEMENT',o.lender_id,r.borrower_id,amount,intr,lid,'COMPLETED','Support funds disbursed');
 INSERT INTO public.notifications(user_id,type,title,message,related_entity_id) VALUES
  (r.borrower_id,'LOAN_ACCEPTED','Support accepted',format('KSh %s was funded by your supporter.',amount),lid),
  (o.lender_id,'LOAN_ACCEPTED','Offer accepted',format('%s accepted KSh %s.',p.full_name,amount),lid);
 PERFORM public.refresh_profile_limit(r.borrower_id);
 PERFORM public.record_trust_event(o.lender_id,'COMMUNITY_SUPPORT',1,'Supported a member.',lid);
 PERFORM public.refresh_profile_statistics(r.borrower_id);
 PERFORM public.refresh_profile_statistics(o.lender_id);
 RETURN lid;
END;
$$;

CREATE OR REPLACE FUNCTION public.accept_loan_offer_reviewed(p_offer_id uuid,p_expected_amount bigint,p_expected_interest_rate numeric,p_expected_period_days integer)
RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE o public.loan_offers%rowtype;
BEGIN
 IF auth.uid() IS NULL THEN RAISE EXCEPTION 'Sign in first' USING ERRCODE='28000'; END IF;
 PERFORM 1 FROM public.loan_requests WHERE id=(SELECT loan_request_id FROM public.loan_offers
  WHERE id=p_offer_id AND borrower_id=auth.uid()) AND borrower_id=auth.uid() FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Offer unavailable'; END IF;
 SELECT * INTO o FROM public.loan_offers WHERE id=p_offer_id AND borrower_id=auth.uid() AND status='OFFERED' FOR UPDATE;
 IF NOT FOUND OR o.amount IS DISTINCT FROM p_expected_amount OR o.interest_rate IS DISTINCT FROM p_expected_interest_rate
  OR o.repayment_period_days IS DISTINCT FROM p_expected_period_days THEN RAISE EXCEPTION 'Offer terms changed; refresh and review the updated offer'; END IF;
 RETURN public.accept_loan_offer(p_offer_id);
END;
$$;

CREATE OR REPLACE FUNCTION public.repay_loan(p_loan_id uuid,p_amount bigint)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE l public.loans%rowtype; w public.wallets%rowtype; interest_payment bigint; principal_payment bigint; done boolean;
BEGIN
 IF auth.uid() IS NULL THEN RAISE EXCEPTION 'Sign in first' USING ERRCODE='28000'; END IF;
 SELECT * INTO l FROM public.loans WHERE id=p_loan_id AND borrower_id=auth.uid() FOR UPDATE;
 IF NOT FOUND OR l.status NOT IN ('ACTIVE','DUE_SOON','OVERDUE','GRACE_PERIOD','DEFAULTED') THEN RAISE EXCEPTION 'Loan cannot be repaid'; END IF;
 IF p_amount IS NULL OR p_amount<=0 OR p_amount>l.total_repayment-l.amount_paid THEN RAISE EXCEPTION 'Payment must be positive and within outstanding balance'; END IF;
 PERFORM 1 FROM public.profiles WHERE id IN (l.borrower_id,l.lender_id) ORDER BY id FOR UPDATE;
 PERFORM 1 FROM public.wallets WHERE user_id IN (l.borrower_id,l.lender_id) ORDER BY user_id FOR UPDATE;
 SELECT * INTO w FROM public.wallets WHERE user_id=l.borrower_id;
 IF NOT FOUND OR w.balance-w.locked_balance<p_amount THEN RAISE EXCEPTION 'Insufficient available wallet balance'; END IF;
 IF NOT EXISTS(SELECT 1 FROM public.wallets WHERE user_id=l.lender_id) THEN RAISE EXCEPTION 'Lender wallet not found'; END IF;
 IF (SELECT balance::bigint+p_amount FROM public.wallets WHERE user_id=l.lender_id)>2147483647 THEN RAISE EXCEPTION 'Recipient balance exceeds supported amount'; END IF;
 PERFORM public.apply_daily_trust_penalties(l.borrower_id);
 -- Preserve the ACTIVE HOSTED repayment API's interest-first allocation.
 interest_payment:=least(p_amount,greatest(0,l.interest_amount-(l.amount_paid-l.principal_paid)));
 principal_payment:=p_amount-interest_payment;
 IF principal_payment>l.principal-l.principal_paid THEN RAISE EXCEPTION 'Loan allocation is inconsistent'; END IF;
 done:=l.amount_paid+p_amount=l.total_repayment;
 UPDATE public.wallets SET balance=balance-p_amount,updated_at=now() WHERE user_id=l.borrower_id;
 UPDATE public.wallets SET balance=balance+p_amount,updated_at=now() WHERE user_id=l.lender_id;
 UPDATE public.loans SET amount_paid=amount_paid+p_amount,principal_paid=principal_paid+principal_payment,
  status=CASE WHEN done THEN 'REPAID' ELSE status END,completed_at=CASE WHEN done THEN now() ELSE completed_at END WHERE id=l.id;
 INSERT INTO public.loan_payments(loan_id,payer_id,amount,payment_type)
 VALUES(l.id,l.borrower_id,p_amount,CASE WHEN principal_payment=0 THEN 'INTEREST' WHEN interest_payment=0 THEN 'PRINCIPAL' ELSE 'MIXED' END);
 INSERT INTO public.transactions(type,sender_id,recipient_id,amount,interest,loan_id,status,description)
 VALUES('LOAN_REPAYMENT',l.borrower_id,l.lender_id,p_amount,interest_payment,l.id,'COMPLETED','Loan repayment');
 INSERT INTO public.notifications(user_id,type,title,message,related_entity_id) VALUES
  (l.borrower_id,CASE WHEN done THEN 'LOAN_REPAID' ELSE 'LOAN_PAYMENT' END,'Payment sent',format('KSh %s repaid.',p_amount),l.id),
  (l.lender_id,CASE WHEN done THEN 'LOAN_REPAID' ELSE 'LOAN_PAYMENT' END,'Repayment received',format('KSh %s received.',p_amount),l.id);
 UPDATE public.profiles p SET borrowing_frozen=EXISTS(SELECT 1 FROM public.loans x WHERE x.borrower_id=p.id AND x.status IN ('OVERDUE','DEFAULTED')) WHERE p.id=l.borrower_id;
 IF done THEN PERFORM public.record_trust_event(l.borrower_id,'LOAN_COMPLETED',0,'Loan fully repaid.',l.id); END IF;
 PERFORM public.refresh_profile_limit(l.borrower_id);
 PERFORM public.refresh_profile_statistics(l.borrower_id);
 PERFORM public.refresh_profile_statistics(l.lender_id);
END;
$$;

CREATE OR REPLACE FUNCTION public.repay_loan_once(p_loan_id uuid,p_amount bigint,p_operation_id uuid)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE receipt public.loan_payments%rowtype; old_receipt record; payment_id uuid; prior_ids uuid[];
BEGIN
 IF auth.uid() IS NULL OR p_operation_id IS NULL THEN RAISE EXCEPTION 'Authenticated operation ID required'; END IF;
 IF p_amount IS NULL OR p_amount<=0 THEN RAISE EXCEPTION 'Payment amount must be positive'; END IF;
 -- Serializes the operation across loans, then loan-scoped financial writes.
 PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(p_operation_id::text,0));
 PERFORM 1 FROM public.loans WHERE id=p_loan_id AND borrower_id=auth.uid() FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Loan not found'; END IF;
 IF to_regclass('public.repayment_operations') IS NOT NULL THEN
  EXECUTE 'SELECT loan_id,borrower_id,amount FROM public.repayment_operations WHERE operation_id=$1'
   INTO old_receipt USING p_operation_id::text;
  IF old_receipt.loan_id IS NOT NULL THEN
   IF old_receipt.loan_id<>p_loan_id OR old_receipt.borrower_id<>auth.uid() OR old_receipt.amount<>p_amount THEN
    RAISE EXCEPTION 'Operation ID belongs to another payment'; END IF;
   RETURN;
  END IF;
 END IF;
 SELECT * INTO receipt FROM public.loan_payments WHERE operation_id=p_operation_id;
 IF FOUND THEN
  IF receipt.loan_id<>p_loan_id OR receipt.payer_id<>auth.uid() OR receipt.requested_amount IS DISTINCT FROM p_amount THEN
   RAISE EXCEPTION 'Operation ID belongs to another payment'; END IF;
  RETURN;
 END IF;
 SELECT coalesce(array_agg(id),'{}'::uuid[]) INTO prior_ids FROM public.loan_payments WHERE loan_id=p_loan_id;
 PERFORM public.repay_loan(p_loan_id,p_amount);
 SELECT id INTO STRICT payment_id FROM public.loan_payments WHERE loan_id=p_loan_id AND NOT(id=ANY(prior_ids));
 UPDATE public.loan_payments SET operation_id=p_operation_id,requested_amount=p_amount WHERE id=payment_id;
END;
$$;

CREATE OR REPLACE FUNCTION public.grant_grace_period(p_loan_id uuid,p_extra_days integer DEFAULT 7)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE l public.loans%rowtype; deadline timestamptz;
BEGIN
 IF auth.uid() IS NULL THEN RAISE EXCEPTION 'Sign in first' USING ERRCODE='28000'; END IF;
 SELECT * INTO l FROM public.loans WHERE id=p_loan_id AND lender_id=auth.uid() FOR UPDATE;
 IF NOT FOUND OR p_extra_days IS NULL OR p_extra_days NOT BETWEEN 1 AND 30 OR l.grace_granted_at IS NOT NULL
  OR l.status NOT IN ('ACTIVE','DUE_SOON','OVERDUE') OR l.amount_paid>=l.total_repayment
  OR now()<l.original_due_date-interval '3 days' OR now()>=l.original_due_date+interval '3 days'
  OR EXISTS(SELECT 1 FROM public.trust_events WHERE related_loan_id=l.id AND event_type='GRACE_PERIOD') THEN
  RAISE EXCEPTION 'Grace period unavailable'; END IF;
 PERFORM public.apply_daily_trust_penalties(l.borrower_id);
 deadline:=greatest(l.original_due_date,now())+make_interval(days=>p_extra_days);
 UPDATE public.loans SET status='GRACE_PERIOD',due_date=deadline,grace_granted_at=now(),grace_days=p_extra_days WHERE id=l.id;
 UPDATE public.profiles p SET borrowing_frozen=EXISTS(SELECT 1 FROM public.loans x
  WHERE x.borrower_id=p.id AND x.id<>l.id AND x.status IN ('OVERDUE','DEFAULTED')) WHERE p.id=l.borrower_id;
 INSERT INTO public.notifications(user_id,type,title,message,related_entity_id) VALUES
  (l.borrower_id,'GRACE_PERIOD_GRANTED','Grace period granted',format('Your lender granted %s additional days, until %s.',p_extra_days,deadline),l.id),
  (l.lender_id,'GRACE_PERIOD_GRANTED','Grace period confirmed',format('You granted %s additional days.',p_extra_days),l.id);
 PERFORM public.record_trust_event(l.borrower_id,'GRACE_PERIOD',0,'A lender granted a grace period.',l.id);
 PERFORM public.refresh_profile_statistics(l.borrower_id);
END;
$$;

CREATE OR REPLACE FUNCTION public.mark_recovered_externally(p_loan_id uuid)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE l public.loans%rowtype;
BEGIN
 IF auth.uid() IS NULL THEN RAISE EXCEPTION 'Sign in first' USING ERRCODE='28000'; END IF;
 SELECT * INTO l FROM public.loans WHERE id=p_loan_id AND lender_id=auth.uid()
  AND status IN ('OVERDUE','GRACE_PERIOD','DEFAULTED') FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Loan cannot be marked recovered'; END IF;
 PERFORM public.apply_daily_trust_penalties(l.borrower_id);
 UPDATE public.loans SET status='RECOVERED_EXTERNALLY',completed_at=now() WHERE id=l.id;
 UPDATE public.profiles p SET borrowing_frozen=EXISTS(SELECT 1 FROM public.loans x
  WHERE x.borrower_id=p.id AND x.status IN ('OVERDUE','DEFAULTED')) WHERE p.id=l.borrower_id;
 INSERT INTO public.notifications(user_id,type,title,message,related_entity_id)
 VALUES(l.borrower_id,'RECOVERED_EXTERNALLY','Loan recovered externally','Your lender marked this debt as settled outside Kash.ke.',l.id);
 PERFORM public.record_trust_event(l.borrower_id,'EXTERNAL_RECOVERY',0,'A loan was settled outside Kash.ke.',l.id);
 PERFORM public.refresh_profile_limit(l.borrower_id);
 PERFORM public.refresh_profile_statistics(l.borrower_id);
 PERFORM public.refresh_profile_statistics(l.lender_id);
END;
$$;

CREATE OR REPLACE FUNCTION public.maintain_account_data(p_user_id uuid DEFAULT NULL)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE r public.loan_requests%rowtype; l public.loans%rowtype; u uuid;
BEGIN
 -- Not a public RPC: client refresh passes only auth.uid() via its wrapper.
 FOR l IN SELECT * FROM public.loans WHERE status IN ('ACTIVE','DUE_SOON','OVERDUE','GRACE_PERIOD') AND amount_paid<total_repayment
  AND (p_user_id IS NULL OR borrower_id=p_user_id OR lender_id=p_user_id)
  AND (CASE WHEN grace_granted_at IS NOT NULL OR status='GRACE_PERIOD'
    OR EXISTS(SELECT 1 FROM public.trust_events WHERE related_loan_id=loans.id AND event_type='GRACE_PERIOD')
   THEN now()>=due_date ELSE now()>=original_due_date+interval '3 days' END) ORDER BY id FOR UPDATE LOOP
  UPDATE public.loans SET status='DEFAULTED' WHERE id=l.id;
  UPDATE public.profiles SET borrowing_frozen=true WHERE id=l.borrower_id;
  INSERT INTO public.notifications(user_id,type,title,message,related_entity_id) VALUES
   (l.borrower_id,'LOAN_DEFAULTED','Loan defaulted','The repayment deadline passed. No further grace is available. You can still repay this debt.',l.id),
   (l.lender_id,'LOAN_DEFAULTED','Loan defaulted','The unpaid agreement defaulted. No further grace is available.',l.id);
 END LOOP;
 FOR u IN SELECT id FROM public.profiles WHERE p_user_id IS NULL OR id=p_user_id OR id IN
  (SELECT borrower_id FROM public.loans WHERE lender_id=p_user_id) ORDER BY id LOOP
  PERFORM public.apply_daily_trust_penalties(u);
  PERFORM public.refresh_profile_statistics(u);
 END LOOP;
 FOR r IN SELECT * FROM public.loan_requests WHERE status IN ('REQUESTED','PARTIALLY_FUNDED') AND expires_at<=now()
  AND (p_user_id IS NULL OR borrower_id=p_user_id OR EXISTS(SELECT 1 FROM public.loan_offers o
    WHERE o.loan_request_id=loan_requests.id AND o.lender_id=p_user_id)) ORDER BY id FOR UPDATE LOOP
  PERFORM public.release_request_offers(r.id);
  UPDATE public.loan_requests SET status='EXPIRED' WHERE id=r.id;
 END LOOP;
 FOR l IN SELECT * FROM public.loans WHERE status IN ('ACTIVE','DUE_SOON','GRACE_PERIOD')
  AND (p_user_id IS NULL OR borrower_id=p_user_id OR lender_id=p_user_id) ORDER BY id FOR UPDATE LOOP
  IF l.due_date<=now() THEN PERFORM public.mark_loan_overdue(l.id);
  ELSIF l.status='ACTIVE' AND l.due_date<=now()+interval '3 days' THEN
   UPDATE public.loans SET status='DUE_SOON' WHERE id=l.id;
   INSERT INTO public.notifications(user_id,type,title,message,related_entity_id)
   VALUES(l.borrower_id,'LOAN_DUE_SOON','Loan due soon','Your repayment is due within three days.',l.id);
  END IF;
 END LOOP;
END;
$$;

CREATE OR REPLACE FUNCTION public.refresh_account_state()
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
BEGIN
 IF auth.uid() IS NULL THEN RAISE EXCEPTION 'Sign in first' USING ERRCODE='28000'; END IF;
 PERFORM public.ensure_current_profile();
 PERFORM public.maintain_account_data(auth.uid());
 PERFORM public.refresh_profile_limit(auth.uid());
END;
$$;

CREATE OR REPLACE FUNCTION public.send_connection_request(p_recipient_id uuid)
RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE existing public.connections%rowtype; cid uuid;
BEGIN
 IF auth.uid() IS NULL THEN RAISE EXCEPTION 'Sign in first' USING ERRCODE='28000'; END IF;
 IF p_recipient_id IS NULL OR p_recipient_id=auth.uid() THEN RAISE EXCEPTION 'Invalid connection recipient'; END IF;
 IF NOT public.member_profile_complete(auth.uid()) THEN RAISE EXCEPTION 'Complete your profile before making requests'; END IF;
 PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(least(auth.uid(),p_recipient_id)::text||greatest(auth.uid(),p_recipient_id)::text,0));
 SELECT * INTO existing FROM public.connections WHERE (requester_id=auth.uid() AND recipient_id=p_recipient_id)
  OR (requester_id=p_recipient_id AND recipient_id=auth.uid()) ORDER BY created_at DESC,id LIMIT 1 FOR UPDATE;
 IF FOUND THEN
  IF existing.status='ACCEPTED' THEN RAISE EXCEPTION 'You are already connected'; END IF;
  IF existing.status='PENDING' THEN RAISE EXCEPTION 'A connection request already exists'; END IF;
  UPDATE public.connections SET requester_id=auth.uid(),recipient_id=p_recipient_id,status='PENDING',accepted_at=NULL,
   responded_at=NULL,created_at=now() WHERE id=existing.id RETURNING id INTO cid;
 ELSE
  INSERT INTO public.connections(requester_id,recipient_id) VALUES(auth.uid(),p_recipient_id) RETURNING id INTO cid;
 END IF;
 INSERT INTO public.notifications(user_id,type,title,message,related_entity_id)
 VALUES(p_recipient_id,'CONNECTION_REQUEST','Connection request','You received a connection request.',cid);
 RETURN cid;
END;
$$;

CREATE OR REPLACE FUNCTION public.respond_connection_request(p_connection_id uuid,p_accept boolean)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE c public.connections%rowtype;
BEGIN
 IF auth.uid() IS NULL THEN RAISE EXCEPTION 'Sign in first' USING ERRCODE='28000'; END IF;
 SELECT * INTO c FROM public.connections WHERE id=p_connection_id AND recipient_id=auth.uid() AND status='PENDING' FOR UPDATE;
 IF NOT FOUND OR p_accept IS NULL THEN RAISE EXCEPTION 'Pending connection request not found'; END IF;
 UPDATE public.connections SET status=CASE WHEN p_accept THEN 'ACCEPTED' ELSE 'REJECTED' END,responded_at=now(),
  accepted_at=CASE WHEN p_accept THEN now() ELSE NULL END WHERE id=c.id;
 INSERT INTO public.notifications(user_id,type,title,message,related_entity_id)
 VALUES(c.requester_id,CASE WHEN p_accept THEN 'CONNECTION_ACCEPTED' ELSE 'CONNECTION_REJECTED' END,
  'Connection response',CASE WHEN p_accept THEN 'Your connection request was accepted.' ELSE 'Your connection request was declined.' END,c.id);
END;
$$;

-- Privilege tightening: preserve RLS and expose only Android's direct SELECTs
-- and two narrow UPDATE paths. No table access is broadened.
REVOKE CREATE ON SCHEMA public FROM PUBLIC,anon,authenticated;
DO $$ DECLARE t text; cols text; BEGIN
 FOREACH t IN ARRAY ARRAY['profiles','wallets','connections','loan_requests','loan_offers','loans',
  'loan_payments','transactions','notifications','trust_events','kash_trust_state','kash_trust_daily_penalties'] LOOP
  EXECUTE format('REVOKE ALL ON public.%I FROM PUBLIC,anon,authenticated',t);
  SELECT string_agg(quote_ident(attname),',') INTO cols FROM pg_attribute WHERE attrelid=to_regclass('public.'||t) AND attnum>0 AND NOT attisdropped;
  EXECUTE format('REVOKE INSERT (%s), UPDATE (%s), REFERENCES (%s) ON public.%I FROM PUBLIC,anon,authenticated',cols,cols,cols,t);
 END LOOP;
 -- Old repayment receipts are internal only, never an app-owned table.
 IF to_regclass('public.repayment_operations') IS NOT NULL THEN
  REVOKE ALL ON public.repayment_operations FROM PUBLIC,anon,authenticated;
 END IF;
END $$;
GRANT SELECT ON public.profiles,public.wallets,public.connections,public.loan_requests,public.loan_offers,
 public.loans,public.loan_payments,public.transactions,public.notifications,public.trust_events TO authenticated;
GRANT UPDATE(full_name,username,bio,location,profile_image) ON public.profiles TO authenticated;
GRANT UPDATE(read) ON public.notifications TO authenticated;

-- Hosted broad SELECT policies leak owner-only Trust and private direct requests.
-- Replace SELECT policies only; keep existing write policies (writes now RPC-only).
DO $$ DECLARE p record; BEGIN
 FOR p IN SELECT policyname,tablename FROM pg_policies WHERE schemaname='public'
  AND tablename IN ('profiles','loan_requests') AND cmd='SELECT' LOOP
  EXECUTE format('DROP POLICY %I ON public.%I',p.policyname,p.tablename);
 END LOOP;
 IF EXISTS(SELECT 1 FROM pg_policies WHERE schemaname='public' AND tablename IN ('profiles','loan_requests') AND cmd='ALL') THEN
  RAISE EXCEPTION 'Unreviewed ALL policy can bypass private reads'; END IF;
END $$;
CREATE POLICY "Users can view their own profile" ON public.profiles FOR SELECT TO authenticated USING(id=auth.uid());
CREATE POLICY "Authenticated users can view active requests" ON public.loan_requests FOR SELECT TO authenticated USING(
 borrower_id=auth.uid() OR direct_request_to=auth.uid() OR (direct_request_to IS NULL AND EXISTS(
  SELECT 1 FROM public.connections c WHERE c.status='ACCEPTED' AND
   ((c.requester_id=auth.uid() AND c.recipient_id=borrower_id) OR (c.recipient_id=auth.uid() AND c.requester_id=borrower_id)))));

-- Preserve inspected Trust/reliability/public projection function bodies.
-- ACLs are explicit; CREATE OR REPLACE otherwise inherits old PUBLIC grants.
DO $$ DECLARE f record; client_names text[]:=ARRAY['is_username_available','ensure_current_profile','complete_member_profile',
 'refresh_account_state','public_profile_directory','member_profile_details','get_direct_request_borrower_summary',
 'send_connection_request','respond_connection_request','create_loan_request','edit_loan_request','cancel_loan_request',
 'create_loan_offer','request_loan_offer_terms','respond_loan_offer_terms','edit_loan_offer','cancel_loan_offer',
 'accept_loan_offer_reviewed','accept_loan_offer','decline_loan_offer','repay_loan_once','grant_grace_period','mark_recovered_externally'];
BEGIN
 FOR f IN SELECT p.oid::regprocedure signature,p.proname FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
  WHERE n.nspname='public' AND p.proname=ANY(client_names||ARRAY['canonical_member_phone','member_profile_complete',
   'release_request_offers','repay_loan','maintain_account_data','mark_loan_overdue','record_trust_event','apply_daily_trust_penalties',
   'refresh_profile_limit','refresh_profile_statistics','loan_reliability','initialize_kash_trust_state','name_notification_participants',
   'preserve_original_deadline','finalize_repaid_loan','normalize_ke_phone','derive_offer_borrower','guard_member_requests','guard_member_phone',
   'handle_new_auth_user','provision_confirmed_user','touch_updated_at','update_loan_statistics']) LOOP
  EXECUTE format('ALTER FUNCTION %s SET search_path = ''''',f.signature);
  EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC,anon,authenticated',f.signature);
  IF f.proname=ANY(client_names) THEN EXECUTE format('GRANT EXECUTE ON FUNCTION %s TO authenticated',f.signature); END IF;
  IF f.proname='is_username_available' THEN EXECUTE format('GRANT EXECUTE ON FUNCTION %s TO anon',f.signature); END IF;
  IF f.proname IN ('maintain_account_data','mark_loan_overdue') THEN EXECUTE format('GRANT EXECUTE ON FUNCTION %s TO service_role',f.signature); END IF;
 END LOOP;
END $$;

-- Final cache contract: all Android RPCs exist; profile compatibility is the
-- only intentional overload. Unknown type overloads cause rollback, not guessing.
DO $$ DECLARE f record; BEGIN
 FOR f IN SELECT * FROM (VALUES
  ('is_username_available(text)'),('ensure_current_profile()'),('complete_member_profile(text,text,text,text)'),
  ('refresh_account_state()'),('public_profile_directory(text,text[],uuid[])'),('member_profile_details(uuid)'),
  ('get_direct_request_borrower_summary(uuid)'),('send_connection_request(uuid)'),('respond_connection_request(uuid,boolean)'),
  ('create_loan_request(bigint,text,text,integer,uuid,boolean)'),('edit_loan_request(uuid,bigint,text,text,integer)'),('cancel_loan_request(uuid)'),
  ('create_loan_offer(uuid,bigint,numeric,integer,boolean)'),('request_loan_offer_terms(uuid,numeric,integer)'),('respond_loan_offer_terms(uuid,boolean)'),
  ('edit_loan_offer(uuid,bigint,numeric,integer)'),('cancel_loan_offer(uuid)'),('accept_loan_offer_reviewed(uuid,bigint,numeric,integer)'),
  ('decline_loan_offer(uuid)'),('repay_loan_once(uuid,bigint,uuid)'),('grant_grace_period(uuid,integer)'),('mark_recovered_externally(uuid)')) v(sig) LOOP
  IF to_regprocedure('public.'||f.sig) IS NULL THEN RAISE EXCEPTION 'Missing final Android RPC %',f.sig; END IF;
 END LOOP;
 FOR f IN SELECT proname,count(*) signatures FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
  WHERE n.nspname='public' AND proname IN ('is_username_available','ensure_current_profile','complete_member_profile',
   'refresh_account_state','public_profile_directory','member_profile_details','get_direct_request_borrower_summary',
   'send_connection_request','respond_connection_request','create_loan_request','edit_loan_request','cancel_loan_request',
   'create_loan_offer','request_loan_offer_terms','respond_loan_offer_terms','edit_loan_offer','cancel_loan_offer',
   'accept_loan_offer_reviewed','accept_loan_offer','decline_loan_offer','repay_loan_once','grant_grace_period','mark_recovered_externally') GROUP BY proname LOOP
  IF f.signatures<>(CASE WHEN f.proname='complete_member_profile' THEN 2 ELSE 1 END) THEN
   RAISE EXCEPTION 'Unreviewed overloads on %',f.proname; END IF;
 END LOOP;
 FOR f IN SELECT p.oid,p.proname FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
  WHERE n.nspname='public' AND p.proname IN ('canonical_member_phone','member_profile_complete','release_request_offers',
   'repay_loan','maintain_account_data','mark_loan_overdue','record_trust_event','apply_daily_trust_penalties',
   'refresh_profile_limit','refresh_profile_statistics','loan_reliability','initialize_kash_trust_state','name_notification_participants',
   'preserve_original_deadline','finalize_repaid_loan','normalize_ke_phone','derive_offer_borrower','guard_member_requests',
   'guard_member_phone','handle_new_auth_user','provision_confirmed_user','touch_updated_at','update_loan_statistics') LOOP
  IF has_function_privilege('anon',f.oid,'EXECUTE') OR has_function_privilege('authenticated',f.oid,'EXECUTE') THEN
   RAISE EXCEPTION 'Inherited client execution survives on internal function %',f.proname; END IF;
 END LOOP;
END $$;

NOTIFY pgrst, 'reload schema';
COMMIT;
