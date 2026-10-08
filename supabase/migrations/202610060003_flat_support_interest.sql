-- Interest is a one-time percentage of principal for the agreed support term.
-- Repayment days determine the due date, never the percentage charged.
begin;
create or replace function public.support_interest_amount(p_principal bigint,p_rate numeric)
returns bigint language sql immutable strict set search_path='' as $$
 select round(p_principal::numeric*p_rate/100.0)::bigint
$$;
revoke all on function public.support_interest_amount(bigint,numeric) from public,anon,authenticated;

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
 total:=p_amount+public.support_interest_amount(p_amount,p_interest_rate);
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

CREATE OR REPLACE FUNCTION public.edit_loan_offer(p_offer_id uuid,p_amount bigint,p_interest_rate numeric,p_period_days integer)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE o public.loan_offers%rowtype; r public.loan_requests%rowtype; w public.wallets%rowtype; total bigint;
BEGIN
 IF auth.uid() IS NULL THEN RAISE EXCEPTION 'Sign in first' USING ERRCODE='28000'; END IF;
 SELECT * INTO r FROM public.loan_requests WHERE id=(SELECT loan_request_id FROM public.loan_offers
  WHERE id=p_offer_id AND lender_id=auth.uid()) FOR UPDATE;
 SELECT * INTO o FROM public.loan_offers WHERE id=p_offer_id AND lender_id=auth.uid() AND status IN ('OFFERED','TERMS_REQUESTED') FOR UPDATE;
 IF NOT FOUND OR r.id IS NULL OR r.status NOT IN ('REQUESTED','PARTIALLY_FUNDED') OR r.expires_at<=now() THEN RAISE EXCEPTION 'Offer unavailable'; END IF;
 IF p_amount IS NULL OR p_amount NOT BETWEEN 1 AND r.remaining_amount OR p_interest_rate IS NULL OR p_interest_rate<>trunc(p_interest_rate)
  OR p_interest_rate NOT BETWEEN 0 AND (CASE WHEN r.above_limit_requested AND o.above_limit_override AND r.direct_request_to=o.lender_id THEN 15 ELSE 7 END)
  OR p_period_days IS NULL OR p_period_days NOT BETWEEN 7 AND r.preferred_period_days THEN RAISE EXCEPTION 'Invalid offer terms'; END IF;
 total:=p_amount+public.support_interest_amount(p_amount,p_interest_rate);
 IF total>2147483647 THEN RAISE EXCEPTION 'Offer total exceeds supported amount'; END IF;
 IF EXISTS(SELECT 1 FROM public.lender_preferences WHERE user_id=auth.uid() AND maximum_lending_amount>0 AND maximum_lending_amount<p_amount) THEN
  RAISE EXCEPTION 'Maximum lending amount exceeded'; END IF;
 SELECT * INTO w FROM public.wallets WHERE user_id=auth.uid() FOR UPDATE;
 IF NOT FOUND OR w.locked_balance<o.amount OR w.balance-w.locked_balance+o.amount<p_amount THEN RAISE EXCEPTION 'Insufficient available wallet balance'; END IF;
 UPDATE public.wallets SET locked_balance=locked_balance-o.amount+p_amount,updated_at=now() WHERE user_id=auth.uid();
 UPDATE public.loan_offers SET status='OFFERED',amount=p_amount,interest_rate=p_interest_rate,repayment_period_days=p_period_days,total_repayment=total,
  requested_interest_rate=NULL,requested_repayment_period_days=NULL WHERE id=o.id;
 INSERT INTO public.notifications(user_id,type,title,message,related_entity_id)
 VALUES(r.borrower_id,'LOAN_OFFER_UPDATED','Offer updated',format('Your lender updated the offer to KSh %s at %s%% for %s days. Review before accepting.',p_amount,p_interest_rate,p_period_days),o.id);
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
 total:=o.amount+public.support_interest_amount(o.amount,rate);
 IF total>2147483647 THEN RAISE EXCEPTION 'Offer total exceeds supported amount'; END IF;
 UPDATE public.loan_offers SET status='OFFERED',interest_rate=rate,repayment_period_days=days,total_repayment=total,
  requested_interest_rate=NULL,requested_repayment_period_days=NULL WHERE id=o.id;
 INSERT INTO public.notifications(user_id,type,title,message,related_entity_id)
 VALUES(o.borrower_id,'LOAN_OFFER_UPDATED','Offer terms updated',format('The supporter responded with %s%% interest for %s days.',rate,days),o.id);
END;
$$;

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
 intr:=public.support_interest_amount(amount,o.interest_rate);
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

-- Pending offers must display the same total that acceptance will charge.
-- Accepted loans and their historical transactions retain their agreed amounts.
update public.loan_offers set total_repayment=amount+public.support_interest_amount(amount,interest_rate)
where status in ('OFFERED','TERMS_REQUESTED');
commit;
