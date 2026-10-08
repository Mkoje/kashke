-- Editing a pending offer replaces its terms on the same row and clears negotiation.
BEGIN;
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
 total:=p_amount+round(p_amount*p_interest_rate*p_period_days/36500.0)::bigint;
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

COMMIT;

