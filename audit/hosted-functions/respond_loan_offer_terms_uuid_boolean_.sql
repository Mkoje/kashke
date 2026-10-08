CREATE OR REPLACE FUNCTION public.respond_loan_offer_terms(p_offer_id uuid, p_accept_terms boolean)
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
DECLARE
    v_offer public.loan_offers%rowtype;
    v_rate numeric;
    v_days integer;

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
      AND lender_id = auth.uid()
      AND status = 'TERMS_REQUESTED'

    FOR UPDATE;


    IF NOT FOUND THEN
        RAISE EXCEPTION
            'Terms request is no longer available';
    END IF;


    v_rate :=
        CASE
            WHEN p_accept_terms
                THEN v_offer.requested_interest_rate
            ELSE v_offer.interest_rate
        END;


    v_days :=
        CASE
            WHEN p_accept_terms
                THEN v_offer.requested_repayment_period_days
            ELSE v_offer.repayment_period_days
        END;


    UPDATE public.loan_offers

    SET
        status = 'OFFERED',

        interest_rate = v_rate,

        repayment_period_days =
            v_days,

        total_repayment =
            amount
            + round(
                amount
                * v_rate
                * v_days
                / 36500.0
            )::bigint,

        requested_interest_rate = NULL,

        requested_repayment_period_days =
            NULL

    WHERE id = p_offer_id;


    INSERT INTO public.notifications(
        user_id,
        type,
        title,
        message,
        related_entity_id
    )
    VALUES(
        v_offer.borrower_id,
        'LOAN_OFFER_UPDATED',
        'Offer terms updated',

        format(
            '%s responded with %s%% interest for %s days.',
            (
                SELECT full_name
                FROM public.profiles
                WHERE id = auth.uid()
            ),
            v_rate,
            v_days
        ),

        p_offer_id
    );

END;
$function$
