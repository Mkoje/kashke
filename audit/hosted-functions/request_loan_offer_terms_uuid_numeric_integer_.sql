CREATE OR REPLACE FUNCTION public.request_loan_offer_terms(p_offer_id uuid, p_interest_rate numeric, p_period_days integer)
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
DECLARE
    v_offer public.loan_offers%rowtype;

BEGIN

    IF auth.uid() IS NULL THEN
        RAISE EXCEPTION 'Sign in first'
            USING ERRCODE = '28000';
    END IF;


    PERFORM 1
    FROM public.loan_requests

    WHERE id = (
        SELECT loan_request_id
        FROM public.loan_offers
        WHERE id = p_offer_id
    )

    FOR UPDATE;


    SELECT *
    INTO v_offer

    FROM public.loan_offers

    WHERE id = p_offer_id
      AND borrower_id = auth.uid()
      AND status = 'OFFERED'

    FOR UPDATE;


    IF NOT FOUND THEN
        RAISE EXCEPTION
            'Offer is no longer available';
    END IF;


    IF p_interest_rate
        NOT BETWEEN 0
        AND v_offer.interest_rate
       OR p_period_days
        NOT BETWEEN 7 AND 365
    THEN
        RAISE EXCEPTION
            'Requested terms must use equal-or-lower interest and a 7-365 day period';
    END IF;


    UPDATE public.loan_offers

    SET
        status = 'TERMS_REQUESTED',
        requested_interest_rate =
            p_interest_rate,
        requested_repayment_period_days =
            p_period_days

    WHERE id = p_offer_id;


    INSERT INTO public.notifications(
        user_id,
        type,
        title,
        message,
        related_entity_id
    )
    VALUES(
        v_offer.lender_id,
        'LOAN_OFFER_TERMS_REQUESTED',
        'Terms requested',

        format(
            '%s requested %s%% for %s days.',
            (
                SELECT full_name
                FROM public.profiles
                WHERE id = auth.uid()
            ),
            p_interest_rate,
            p_period_days
        ),

        p_offer_id
    );

END;
$function$
