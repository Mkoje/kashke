CREATE OR REPLACE FUNCTION public.create_loan_offer(p_request_id uuid, p_amount bigint, p_interest_rate numeric, p_period_days integer, p_approve_above_limit boolean DEFAULT false)
 RETURNS uuid
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
DECLARE
    v_request public.loan_requests%rowtype;
    v_wallet public.wallets%rowtype;
    v_id uuid;
    v_total bigint;
    v_max_interest numeric;

BEGIN

    IF auth.uid() IS NULL THEN
        RAISE EXCEPTION 'Sign in first'
            USING ERRCODE = '28000';
    END IF;


    SELECT *
    INTO v_request

    FROM public.loan_requests

    WHERE id = p_request_id

    FOR UPDATE;


    IF NOT FOUND
       OR v_request.status NOT IN (
           'REQUESTED',
           'PARTIALLY_FUNDED'
       )
       OR v_request.expires_at <= now()
    THEN
        RAISE EXCEPTION
            'Request is no longer available';
    END IF;


    IF v_request.direct_request_to IS NOT NULL
       AND v_request.direct_request_to <> auth.uid()
    THEN
        RAISE EXCEPTION
            'This request is private';
    END IF;


    IF v_request.direct_request_to IS NULL
       AND NOT EXISTS (
            SELECT 1
            FROM public.connections c
            WHERE c.status = 'ACCEPTED'
              AND (
                  (
                      c.requester_id = auth.uid()
                      AND c.recipient_id =
                          v_request.borrower_id
                  )
                  OR
                  (
                      c.recipient_id = auth.uid()
                      AND c.requester_id =
                          v_request.borrower_id
                  )
              )
       )
    THEN
        RAISE EXCEPTION
            'Connect with this borrower first';
    END IF;


    IF v_request.borrower_id = auth.uid()
       OR p_amount <= 0
       OR p_amount > v_request.remaining_amount
    THEN
        RAISE EXCEPTION
            'Invalid offer amount';
    END IF;


    IF v_request.above_limit_requested
       AND v_request.direct_request_to
           IS DISTINCT FROM auth.uid()
    THEN
        RAISE EXCEPTION
            'Above-limit requests are direct only';
    END IF;


    IF v_request.above_limit_requested
       AND NOT p_approve_above_limit
    THEN
        RAISE EXCEPTION
            'Lender must explicitly approve this above-limit request';
    END IF;


    IF p_approve_above_limit
       AND (
           NOT v_request.above_limit_requested
           OR v_request.direct_request_to
               IS DISTINCT FROM auth.uid()
       )
    THEN
        RAISE EXCEPTION
            'Only the designated lender may approve an above-limit request';
    END IF;


    v_max_interest :=
        CASE
            WHEN v_request.above_limit_requested
                THEN 15
            ELSE 7
        END;


    IF p_interest_rate
        NOT BETWEEN 0 AND v_max_interest
       OR p_period_days
        NOT BETWEEN 7
        AND v_request.preferred_period_days
    THEN
        RAISE EXCEPTION
            'Interest exceeds the limit for this request or the repayment period is invalid';
    END IF;


    SELECT *
    INTO v_wallet

    FROM public.wallets

    WHERE user_id = auth.uid()

    FOR UPDATE;


    IF NOT FOUND
       OR v_wallet.balance
          - v_wallet.locked_balance
          < p_amount
    THEN
        RAISE EXCEPTION
            'Insufficient available wallet balance';
    END IF;


    IF EXISTS (
        SELECT 1
        FROM public.lender_preferences lp

        WHERE lp.user_id = auth.uid()
          AND lp.maximum_lending_amount > 0
          AND p_amount >
              lp.maximum_lending_amount
    )
    THEN
        RAISE EXCEPTION
            'Offer exceeds your maximum lending amount';
    END IF;


    v_total :=
        p_amount
        + round(
            p_amount
            * p_interest_rate
            * p_period_days
            / 36500.0
        )::bigint;


    UPDATE public.wallets

    SET
        locked_balance =
            locked_balance + p_amount,

        updated_at = now()

    WHERE user_id = auth.uid();


    INSERT INTO public.loan_offers(
        loan_request_id,
        lender_id,
        borrower_id,
        amount,
        interest_rate,
        repayment_period_days,
        total_repayment,
        above_limit_override
    )
    VALUES(
        p_request_id,
        auth.uid(),
        v_request.borrower_id,
        p_amount,
        p_interest_rate,
        p_period_days,
        v_total,
        p_approve_above_limit
    )
    RETURNING id INTO v_id;


    INSERT INTO public.notifications(
        user_id,
        type,
        title,
        message,
        related_entity_id
    )
    VALUES(
        v_request.borrower_id,
        'LOAN_OFFER',
        'New support offer',

        format(
            'A supporter offered KSh %s at %s%% for %s days%s.',
            p_amount,
            p_interest_rate,
            p_period_days,

            CASE
                WHEN v_request.above_limit_requested
                    THEN ' and approved your disclosed above-limit request'
                ELSE ''
            END
        ),

        v_id
    );


    RETURN v_id;

END;
$function$
