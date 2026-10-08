CREATE OR REPLACE FUNCTION public.mark_loan_overdue(p_loan_id uuid)
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
DECLARE
    l public.loans%rowtype;

BEGIN

    SELECT *
    INTO l
    FROM public.loans
    WHERE id = p_loan_id
    FOR UPDATE;


    IF NOT FOUND
       OR l.status NOT IN (
           'ACTIVE',
           'DUE_SOON',
           'GRACE_PERIOD'
       )
       OR l.due_date > now()
    THEN
        RETURN;
    END IF;


    UPDATE public.loans
    SET status = 'OVERDUE'
    WHERE id = l.id;


    UPDATE public.profiles
    SET borrowing_frozen = true
    WHERE id = l.borrower_id;


    INSERT INTO public.notifications(
        user_id,
        type,
        title,
        message,
        related_entity_id
    )
    VALUES
    (
        l.borrower_id,
        'LOAN_OVERDUE',
        'Loan overdue',

        format(
            '%s, your repayment to %s is overdue; new borrowing is frozen.',
            (
                SELECT full_name
                FROM public.profiles
                WHERE id = l.borrower_id
            ),
            (
                SELECT full_name
                FROM public.profiles
                WHERE id = l.lender_id
            )
        ),

        l.id
    ),
    (
        l.lender_id,
        'LOAN_OVERDUE',
        'Loan overdue',

        format(
            '%s has an overdue repayment.',
            (
                SELECT full_name
                FROM public.profiles
                WHERE id = l.borrower_id
            )
        ),

        l.id
    );


    PERFORM public.record_trust_event(
        l.borrower_id,
        'OVERDUE',
        -40,
        'A loan became overdue.',
        l.id
    );

END;
$function$
