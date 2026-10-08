CREATE OR REPLACE FUNCTION public.repay_loan_once(p_loan_id uuid, p_amount integer, p_operation_id text)
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
DECLARE

    v_borrower_id uuid := auth.uid();

    v_loan public.loans%ROWTYPE;

    v_remaining integer;

    v_interest_already_paid integer;
    v_interest_remaining integer;

    v_interest_payment integer;
    v_principal_payment integer;

    v_new_amount_paid integer;
    v_new_principal_paid integer;

    v_fully_repaid boolean;

BEGIN

    -- ========================================================
    -- AUTHENTICATION
    -- ========================================================

    IF v_borrower_id IS NULL THEN
        RAISE EXCEPTION 'Not authenticated';
    END IF;


    IF p_operation_id IS NULL
       OR btrim(p_operation_id) = '' THEN

        RAISE EXCEPTION
            'Payment operation ID is required';

    END IF;


    IF p_amount IS NULL
       OR p_amount <= 0 THEN

        RAISE EXCEPTION
            'Repayment amount must be greater than zero';

    END IF;


    -- ========================================================
    -- IDEMPOTENCY
    --
    -- If the phone retries the same payment because of a
    -- timeout/network issue, DO NOT charge the borrower twice.
    -- ========================================================

    IF EXISTS (
        SELECT 1
        FROM public.repayment_operations
        WHERE operation_id = p_operation_id
          AND loan_id = p_loan_id
          AND borrower_id = v_borrower_id
          AND amount = p_amount
    ) THEN

        RETURN;

    END IF;


    -- Same operation ID cannot be reused for another payment.

    IF EXISTS (
        SELECT 1
        FROM public.repayment_operations
        WHERE operation_id = p_operation_id
    ) THEN

        RAISE EXCEPTION
            'Payment operation ID has already been used';

    END IF;


    -- ========================================================
    -- LOCK THE LOAN
    -- ========================================================

    SELECT *
    INTO v_loan
    FROM public.loans
    WHERE id = p_loan_id
    FOR UPDATE;


    IF NOT FOUND THEN
        RAISE EXCEPTION 'Loan not found';
    END IF;


    IF v_loan.borrower_id <> v_borrower_id THEN

        RAISE EXCEPTION
            'Only the borrower can repay this loan';

    END IF;


    IF v_loan.status NOT IN (
        'ACTIVE',
        'DUE_SOON',
        'OVERDUE',
        'GRACE_PERIOD'
    ) THEN

        RAISE EXCEPTION
            'This loan cannot currently receive repayments';

    END IF;


    -- ========================================================
    -- OUTSTANDING BALANCE
    -- ========================================================

    v_remaining :=
        v_loan.total_repayment -
        v_loan.amount_paid;


    IF v_remaining <= 0 THEN

        RAISE EXCEPTION
            'This loan has already been repaid';

    END IF;


    -- Do not allow overpayment.

    IF p_amount > v_remaining THEN

        RAISE EXCEPTION
            'Repayment exceeds outstanding loan balance';

    END IF;


    -- ========================================================
    -- LOCK + CHECK BORROWER WALLET
    -- ========================================================

    PERFORM 1
    FROM public.wallets
    WHERE user_id = v_borrower_id
      AND balance >= p_amount
    FOR UPDATE;


    IF NOT FOUND THEN

        RAISE EXCEPTION
            'Insufficient wallet balance';

    END IF;


    -- ========================================================
    -- LOCK LENDER WALLET
    -- ========================================================

    PERFORM 1
    FROM public.wallets
    WHERE user_id = v_loan.lender_id
    FOR UPDATE;


    IF NOT FOUND THEN

        RAISE EXCEPTION
            'Lender wallet not found';

    END IF;


    -- ========================================================
    -- PAYMENT ALLOCATION
    --
    -- Interest is paid first, then principal.
    --
    -- Example:
    --
    -- Principal = 2000
    -- Interest  = 20
    -- Payment   = 500
    --
    -- Interest payment = 20
    -- Principal payment = 480
    -- ========================================================


    v_interest_already_paid :=
        greatest(
            0,
            v_loan.amount_paid -
            v_loan.principal_paid
        );


    v_interest_remaining :=
        greatest(
            0,
            v_loan.interest_amount -
            v_interest_already_paid
        );


    v_interest_payment :=
        least(
            p_amount,
            v_interest_remaining
        );


    v_principal_payment :=
        p_amount -
        v_interest_payment;


    v_new_amount_paid :=
        v_loan.amount_paid +
        p_amount;


    v_new_principal_paid :=
        least(
            v_loan.principal,
            v_loan.principal_paid +
            v_principal_payment
        );


    v_fully_repaid :=
        v_new_amount_paid >=
        v_loan.total_repayment;


    -- ========================================================
    -- REGISTER OPERATION
    --
    -- This is in the SAME PostgreSQL transaction.
    -- If anything below fails, this insert is rolled back too.
    -- ========================================================

    INSERT INTO public.repayment_operations (
        operation_id,
        loan_id,
        borrower_id,
        amount
    )
    VALUES (
        p_operation_id,
        p_loan_id,
        v_borrower_id,
        p_amount
    );


    -- ========================================================
    -- MOVE MONEY
    -- BORROWER -> LENDER
    -- ========================================================

    UPDATE public.wallets
    SET
        balance = balance - p_amount,
        updated_at = now()
    WHERE user_id = v_borrower_id;


    UPDATE public.wallets
    SET
        balance = balance + p_amount,
        updated_at = now()
    WHERE user_id = v_loan.lender_id;


    -- ========================================================
    -- UPDATE LOAN
    -- ========================================================

    UPDATE public.loans
    SET

        amount_paid =
            v_new_amount_paid,

        principal_paid =
            v_new_principal_paid,

        status =
            CASE
                WHEN v_fully_repaid
                    THEN 'REPAID'
                ELSE status
            END,

        completed_at =
            CASE
                WHEN v_fully_repaid
                    THEN now()
                ELSE completed_at
            END

    WHERE id = p_loan_id;


    -- ========================================================
    -- TRANSACTION HISTORY
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
        'LOAN_REPAYMENT',
        v_borrower_id,
        v_loan.lender_id,
        p_amount,
        v_interest_payment,
        p_loan_id,
        'COMPLETED',

        CASE
            WHEN v_fully_repaid
                THEN 'Loan repaid in full'
            ELSE
                'Partial loan repayment'
        END
    );


    -- ========================================================
    -- RESTORE BORROWING CAPACITY
    --
    -- Only principal restores borrowing capacity.
    -- Interest does not.
    -- ========================================================

    UPDATE public.profiles
    SET

        available_limit =
            least(
                borrowing_limit,
                available_limit +
                v_principal_payment
            ),

        total_repaid =
            total_repaid +
            p_amount

    WHERE id = v_borrower_id;


    -- ========================================================
    -- LENDER INTEREST STATISTICS
    -- ========================================================

    IF v_interest_payment > 0 THEN

        UPDATE public.profiles
        SET
            total_earned_from_interest =
                total_earned_from_interest +
                v_interest_payment
        WHERE id = v_loan.lender_id;

    END IF;


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

        v_loan.lender_id,

        CASE
            WHEN v_fully_repaid
                THEN 'LOAN_REPAID'
            ELSE
                'LOAN_PAYMENT'
        END,

        CASE
            WHEN v_fully_repaid
                THEN 'Loan repaid'
            ELSE
                'Repayment received'
        END,

        CASE
            WHEN v_fully_repaid
                THEN
                    'The borrower has completed repayment of this loan.'
            ELSE
                'The borrower made a partial repayment on this loan.'
        END,

        p_loan_id
    );


    -- ========================================================
    -- FULL REPAYMENT ONLY
    -- ========================================================

    IF v_fully_repaid THEN


        -- Notify borrower.

        INSERT INTO public.notifications (
            user_id,
            type,
            title,
            message,
            related_entity_id
        )
        VALUES (
            v_borrower_id,
            'LOAN_REPAID',
            'Loan repaid',
            'You have successfully completed repayment of this loan.',
            p_loan_id
        );


        -- Only mark the original support request REPAID when
        -- every associated loan has finished.

        IF NOT EXISTS (

            SELECT 1
            FROM public.loans

            WHERE loan_request_id =
                  v_loan.loan_request_id

              AND id <> p_loan_id

              AND status NOT IN (
                  'REPAID',
                  'RECOVERED_EXTERNALLY',
                  'CANCELLED'
              )

        ) THEN

            UPDATE public.loan_requests
            SET status = 'REPAID'
            WHERE id =
                  v_loan.loan_request_id;

        END IF;


    END IF;

END;
$function$
