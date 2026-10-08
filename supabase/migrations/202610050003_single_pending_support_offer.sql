-- Serialize creation on the request row before checking for an existing pending offer.
-- Existing offers are preserved; cancelling an offer allows a new offer.
BEGIN;
CREATE OR REPLACE FUNCTION public.create_loan_offer(p_request_id uuid,p_amount bigint,p_interest_rate numeric,p_period_days integer,
 p_approve_above_limit boolean DEFAULT false)
RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE r public.loan_requests%rowtype; w public.wallets%rowtype; oid uuid; total bigint; ceiling integer;
BEGIN
 IF auth.uid() IS NULL THEN RAISE EXCEPTION 'Sign in first' USING ERRCODE='28000'; END IF;
 SELECT * INTO r FROM public.loan_requests WHERE id=p_request_id FOR UPDATE;
 IF NOT FOUND OR r.status NOT IN ('REQUESTED','PARTIALLY_FUNDED') OR r.expires_at<=now() THEN RAISE EXCEPTION 'Request is no longer available'; END IF;
 IF EXISTS (SELECT 1 FROM public.loan_offers WHERE loan_request_id=r.id AND lender_id=auth.uid()
  AND status IN ('OFFERED','TERMS_REQUESTED')) THEN
  RAISE EXCEPTION 'You already have a pending offer for this request. Edit or cancel it first.';
 END IF;
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


COMMIT;
