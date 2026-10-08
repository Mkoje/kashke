CREATE OR REPLACE FUNCTION public.decline_loan_offer(p_offer_id uuid)
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
      AND status IN (
          'OFFERED',
          'TERMS_REQUESTED'
      )

    FOR UPDATE;


    IF NOT FOUND THEN
        RAISE EXCEPTION
            'Offer is no longer available';
    END IF;


    UPDATE public.loan_offers
    SET status = 'DECLINED'
    WHERE id = p_offer_id;


    UPDATE public.wallets

    SET
        locked_balance =
            greatest(
                0,
                locked_balance - v_offer.amount
            ),

        updated_at = now()

    WHERE user_id =
        v_offer.lender_id;


    INSERT INTO public.notifications(
        user_id,
        type,
        title,
        message,
        related_entity_id
    )
    VALUES(
        v_offer.lender_id,
        'LOAN_OFFER_DECLINED',
        'Offer declined',

        format(
            '%s declined your support offer.',
            (
                SELECT full_name
                FROM public.profiles
                WHERE id = auth.uid()
            )
        ),

        p_offer_id
    );

END;
$function$
