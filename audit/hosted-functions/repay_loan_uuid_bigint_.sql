CREATE OR REPLACE FUNCTION public.repay_loan(p_loan_id uuid, p_amount bigint)
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
DECLARE
    v_loan public.loans%rowtype;
    v_wallet public.wallets%rowtype;
    v_borrower public.profiles%rowtype;

    v_amount bigint;
    v_principal bigint;
    v_completed boolean;

BEGIN

    IF auth.uid() IS NULL THEN
        RAISE EXCEPTION 'Sign in first'
            USING ERRCODE = '28000';
    END IF;


    SELECT *
    INTO v_loan

    FROM public.loans

    WHERE id = p_loan_id
      AND borrower_id = auth.uid()

    FOR UPDATE;


    IF NOT FOUND
       OR v_loan.status NOT IN (
           'ACTIVE',
           'DUE_SOON',
           'OVERDUE',
           'GRACE_PERIOD'
       )
    THEN
        RAISE EXCEPTION 'Loan cannot be repaid';
    END IF;


    PERFORM public.apply_daily_trust_penalties(
        v_loan.borrower_id
    );


    PERFORM 1
    FROM public.profiles
    WHERE id IN (
        v_loan.borrower_id,
        v_loan.lender_id
    )
    ORDER BY id
    FOR UPDATE;


    PERFORM 1
    FROM public.wallets
    WHERE user_id IN (
        v_loan.borrower_id,
        v_loan.lender_id
    )
    ORDER BY user_id
    FOR UPDATE;


    v_amount :=
        least(
            p_amount,
            v_loan.total_repayment
            - v_loan.amount_paid
        );


    IF v_amount <= 0 THEN
        RAISE EXCEPTION
            'Payment amount must be positive';
    END IF;


    SELECT *
    INTO v_wallet

    FROM public.wallets

    WHERE user_id =
        v_loan.borrower_id

    FOR UPDATE;


    IF v_wallet.balance < v_amount THEN
        RAISE EXCEPTION
            'Insufficient wallet balance';
    END IF;


    SELECT *
    INTO v_borrower

    FROM public.profiles

    WHERE id =
        v_loan.borrower_id

    FOR UPDATE;


    v_principal :=
        least(
            v_amount,
            v_loan.principal
            - v_loan.principal_paid
        );


    v_completed :=
        v_loan.amount_paid
        + v_amount
        >= v_loan.total_repayment;


    UPDATE public.wallets

    SET
        balance =
            balance - v_amount,

        updated_at = now()

    WHERE user_id =
        v_loan.borrower_id;


    UPDATE public.wallets

    SET
        balance =
            balance + v_amount,

        updated_at = now()

    WHERE user_id =
        v_loan.lender_id;


    UPDATE public.loans

    SET
        amount_paid =
            amount_paid + v_amount,

        principal_paid =
            principal_paid + v_principal,

        status =
            CASE
                WHEN v_completed
                    THEN 'REPAID'
                ELSE status
            END,

        completed_at =
            CASE
                WHEN v_completed
                    THEN now()
                ELSE completed_at
            END

    WHERE id =
        v_loan.id;


    INSERT INTO public.loan_payments(
        loan_id,
        payer_id,
        amount,
        payment_type
    )
    VALUES(
        v_loan.id,
        v_loan.borrower_id,
        v_amount,

        CASE
            WHEN v_principal = v_amount
                THEN 'PRINCIPAL'
            WHEN v_principal = 0
                THEN 'INTEREST'
            ELSE 'MIXED'
        END
    );


    INSERT INTO public.transactions(
        sender_id,
        recipient_id,
        amount,
        type,
        status,
        related_entity_id
    )
    VALUES(
        v_loan.borrower_id,
        v_loan.lender_id,
        v_amount,
        'LOAN_REPAYMENT',
        'COMPLETED',
        v_loan.id
    );


    INSERT INTO public.notifications(
        user_id,
        type,
        title,
        message,
        related_entity_id
    )
    VALUES
    (
        v_loan.borrower_id,
        'LOAN_PAYMENT',
        'Payment sent',

        format(
            '%s, KSh %s was paid to %s.',
            v_borrower.full_name,
            v_amount,
            (
                SELECT full_name
                FROM public.profiles
                WHERE id =
                    v_loan.lender_id
            )
        ),

        v_loan.id
    ),
    (
        v_loan.lender_id,
        'LOAN_PAYMENT',
        'Payment received',

        format(
            '%s paid you KSh %s.',
            v_borrower.full_name,
            v_amount
        ),

        v_loan.id
    );


    IF v_completed THEN

        PERFORM public.record_trust_event(
            v_loan.borrower_id,
            'LOAN_COMPLETED',
            0,
            'Loan fully repaid.',
            v_loan.id
        );

    END IF;


    PERFORM public.refresh_profile_statistics(
        v_loan.borrower_id
    );

    PERFORM public.refresh_profile_statistics(
        v_loan.lender_id
    );

    PERFORM public.refresh_profile_limit(
        v_loan.borrower_id
    );

END;
$function$
