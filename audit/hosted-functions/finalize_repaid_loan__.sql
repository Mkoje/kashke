CREATE OR REPLACE FUNCTION public.finalize_repaid_loan()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
DECLARE
    v_borrower_name text;
BEGIN

    -- Extra safety:
    -- this function should only do anything when a loan
    -- actually transitions INTO REPAID.
    IF NEW.status <> 'REPAID'
       OR OLD.status = 'REPAID'
    THEN
        RETURN NEW;
    END IF;


    -- --------------------------------------------------------
    -- Get the borrower's database name for the trust event.
    -- --------------------------------------------------------

    SELECT p.full_name
    INTO v_borrower_name
    FROM public.profiles p
    WHERE p.id = NEW.borrower_id;


    -- --------------------------------------------------------
    -- Record completion in the existing Kash Trust system.
    --
    -- record_trust_event() already determines:
    --
    -- completed_at <= original_due_date -> +2
    -- completed_at <= extended due_date -> +1
    -- later than due_date               -> +0
    --
    -- It also prevents duplicate LOAN_COMPLETED rewards for
    -- the same loan.
    -- --------------------------------------------------------

    PERFORM public.record_trust_event(
        NEW.borrower_id,
        'LOAN_COMPLETED',
        0,
        'Loan fully repaid.',
        NEW.id
    );


    -- --------------------------------------------------------
    -- Refresh public/private borrower statistics including
    -- reliability_score and reliability_sample_size.
    -- --------------------------------------------------------

    PERFORM public.refresh_profile_statistics(
        NEW.borrower_id
    );


    -- --------------------------------------------------------
    -- Refresh lender statistics as the agreement is complete.
    -- --------------------------------------------------------

    PERFORM public.refresh_profile_statistics(
        NEW.lender_id
    );


    -- --------------------------------------------------------
    -- Recalculate borrowing capacity from the remaining
    -- outstanding principal.
    -- --------------------------------------------------------

    PERFORM public.refresh_profile_limit(
        NEW.borrower_id
    );


    RETURN NEW;
END;
$function$
