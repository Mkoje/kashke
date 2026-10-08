CREATE OR REPLACE FUNCTION public.request_loan_offer_terms(p_offer_id uuid, p_interest_rate integer, p_period_days integer)
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
DECLARE
    v_user_id uuid := auth.uid();
    v_offer public.loan_offers%ROWTYPE;
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Not authenticated';
    END IF;

    IF p_interest_rate IS NULL OR p_interest_rate < 0 THEN
        RAISE EXCEPTION 'Interest rate cannot be negative';
    END IF;

    IF p_period_days IS NULL
       OR p_period_days < 7
       OR p_period_days > 365 THEN
        RAISE EXCEPTION 'Repayment period must be between 7 and 365 days';
    END IF;

    SELECT *
    INTO v_offer
    FROM public.loan_offers
    WHERE id = p_offer_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Offer not found';
    END IF;

    IF v_offer.borrower_id <> v_user_id THEN
        RAISE EXCEPTION 'Only the borrower can request different terms';
    END IF;

    IF v_offer.status <> 'OFFERED' THEN
        RAISE EXCEPTION 'This offer can no longer be negotiated';
    END IF;

    UPDATE public.loan_offers
    SET
        requested_interest_rate = p_interest_rate,
        requested_repayment_period_days = p_period_days,
        status = 'TERMS_REQUESTED'
    WHERE id = p_offer_id;

    INSERT INTO public.notifications (
        user_id, type, title, message, related_entity_id
    )
    VALUES (
        v_offer.lender_id,
        'LOAN_OFFER_TERMS_REQUESTED',
        'Different terms requested',
        'The borrower requested different terms for your support offer.',
        p_offer_id
    );
END;
$function$
