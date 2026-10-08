CREATE OR REPLACE FUNCTION public.accept_loan_offer(p_offer_id uuid)
 RETURNS uuid
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
DECLARE
    v_borrower_id uuid := auth.uid();

    v_offer public.loan_offers%ROWTYPE;
    v_request public.loan_requests%ROWTYPE;
    v_lender_wallet public.wallets%ROWTYPE;

    v_loan_id uuid;
    v_interest_amount integer;
    v_new_funded integer;
    v_new_remaining integer;
    v_new_request_status text;
BEGIN

    -- ========================================================
    -- AUTHENTICATION
    -- ========================================================

    IF v_borrower_id IS NULL THEN
        RAISE EXCEPTION 'Not authenticated';
    END IF;


    -- ========================================================
    -- LOCK OFFER
    -- ========================================================

    SELECT *
    INTO v_offer
    FROM public.loan_offers
    WHERE id = p_offer_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Offer not found';
    END IF;

    IF v_offer.borrower_id <> v_borrower_id THEN
        RAISE EXCEPTION
            'Only the borrower can accept this offer';
    END IF;

    IF v_offer.status <> 'OFFERED' THEN
        RAISE EXCEPTION
            'This offer is no longer available';
    END IF;


    -- ========================================================
    -- LOCK RELATED REQUEST
    -- ========================================================

    SELECT *
    INTO v_request
    FROM public.loan_requests
    WHERE id = v_offer.loan_request_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION
            'Related support request not found';
    END IF;

    IF v_request.borrower_id <> v_borrower_id THEN
        RAISE EXCEPTION
            'Support request does not belong to borrower';
    END IF;

    -- IMPORTANT:
    -- Partially funded requests must still accept more offers.
    IF v_request.status NOT IN (
        'REQUESTED',
        'PARTIALLY_FUNDED'
    ) THEN
        RAISE EXCEPTION
            'This support request is no longer accepting funding';
    END IF;

    IF v_request.remaining_amount <= 0 THEN
        RAISE EXCEPTION
            'This support request is already fully funded';
    END IF;

    IF v_offer.amount > v_request.remaining_amount THEN
        RAISE EXCEPTION
            'Offer exceeds the amount still required';
    END IF;


    -- ========================================================
    -- LOCK LENDER WALLET
    -- ========================================================

    SELECT *
    INTO v_lender_wallet
    FROM public.wallets
    WHERE user_id = v_offer.lender_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION
            'Lender wallet not found';
    END IF;

    -- Offer creation reserved these funds.
    IF v_lender_wallet.locked_balance < v_offer.amount THEN
        RAISE EXCEPTION
            'Reserved support funds are no longer available';
    END IF;

    IF v_lender_wallet.balance < v_offer.amount THEN
        RAISE EXCEPTION
            'Lender wallet balance is insufficient';
    END IF;


    -- ========================================================
    -- ENSURE BORROWER WALLET EXISTS
    -- ========================================================

    INSERT INTO public.wallets (
        user_id,
        balance,
        locked_balance
    )
    VALUES (
        v_borrower_id,
        0,
        0
    )
    ON CONFLICT (user_id)
    DO NOTHING;


    -- ========================================================
    -- CALCULATE NEW STATE
    -- ========================================================

    v_interest_amount :=
        v_offer.total_repayment - v_offer.amount;

    v_new_funded :=
        v_request.funded_amount + v_offer.amount;

    v_new_remaining :=
        greatest(
            0,
            v_request.remaining_amount - v_offer.amount
        );

    IF v_new_remaining = 0 THEN
        v_new_request_status := 'FULLY_FUNDED';
    ELSE
        v_new_request_status := 'PARTIALLY_FUNDED';
    END IF;


    -- ========================================================
    -- TRANSFER PRINCIPAL
    -- ========================================================

    UPDATE public.wallets
    SET
        balance = balance - v_offer.amount,

        locked_balance = greatest(
            0,
            locked_balance - v_offer.amount
        ),

        updated_at = now()

    WHERE user_id = v_offer.lender_id;


    UPDATE public.wallets
    SET
        balance = balance + v_offer.amount,
        updated_at = now()

    WHERE user_id = v_borrower_id;


    -- ========================================================
    -- CREATE LOAN AGREEMENT
    -- ========================================================

    INSERT INTO public.loans (
        borrower_id,
        lender_id,
        loan_request_id,

        principal,
        interest_rate,
        interest_amount,
        total_repayment,

        amount_paid,
        principal_paid,
        lender_contribution,

        due_date,
        status
    )
    VALUES (
        v_borrower_id,
        v_offer.lender_id,
        v_offer.loan_request_id,

        v_offer.amount,
        v_offer.interest_rate,
        v_interest_amount,
        v_offer.total_repayment,

        0,
        0,
        v_offer.amount,

        now() + make_interval(
            days => v_offer.repayment_period_days
        ),

        'ACTIVE'
    )
    RETURNING id INTO v_loan_id;


    -- ========================================================
    -- MARK OFFER ACCEPTED
    -- ========================================================

    UPDATE public.loan_offers
    SET status = 'ACCEPTED'
    WHERE id = p_offer_id;


    -- ========================================================
    -- UPDATE ORIGINAL REQUEST
    -- ========================================================

    UPDATE public.loan_requests
    SET
        funded_amount = v_new_funded,
        remaining_amount = v_new_remaining,
        status = v_new_request_status
    WHERE id = v_offer.loan_request_id;


    -- ========================================================
    -- RECORD DISBURSEMENT
    -- ========================================================

    INSERT INTO public.transactions (
        type,
        sender_id,
        recipient_id,
        amount,
        interest,
        loan_id,
        status,
        description
    )
    VALUES (
        'LOAN_DISBURSEMENT',
        v_offer.lender_id,
        v_borrower_id,
        v_offer.amount,
        v_interest_amount,
        v_loan_id,
        'COMPLETED',
        'Support funds disbursed'
    );


    -- ========================================================
    -- NOTIFY BORROWER
    -- ========================================================

    INSERT INTO public.notifications (
        user_id,
        type,
        title,
        message,
        related_entity_id
    )
    VALUES (
        v_borrower_id,
        'LOAN_ACCEPTED',
        'Support received',
        'Your support request received funding and the funds were added to your wallet.',
        v_loan_id
    );


    -- ========================================================
    -- NOTIFY LENDER
    -- ========================================================

    INSERT INTO public.notifications (
        user_id,
        type,
        title,
        message,
        related_entity_id
    )
    VALUES (
        v_offer.lender_id,
        'LOAN_ACCEPTED',
        'Support offer accepted',
        'Your support offer was accepted and the loan is now active.',
        v_loan_id
    );


    RETURN v_loan_id;

END;
$function$
