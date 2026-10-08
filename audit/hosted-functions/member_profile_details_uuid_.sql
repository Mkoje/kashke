CREATE OR REPLACE FUNCTION public.member_profile_details(p_profile_id uuid)
 RETURNS TABLE(id uuid, full_name text, username text, phone text, bio text, location text, member_since text, reliability_score integer, reliability_sample_size integer, current_debt_total bigint, current_debt_count integer, borrowing_limit bigint, available_limit bigint, previous_transaction_count integer, borrowing_count integer, completed_borrowing_count integer, minimum_borrowed bigint, maximum_borrowed bigint, average_borrowed bigint)
 LANGUAGE plpgsql
 STABLE SECURITY DEFINER
 SET search_path TO ''
AS $function$
BEGIN

    IF auth.uid() IS NULL THEN
        RAISE EXCEPTION 'Sign in first'
            USING ERRCODE = '28000';
    END IF;


    IF p_profile_id IS NULL THEN
        RAISE EXCEPTION
            'Choose a profile';
    END IF;


    RETURN QUERY

    SELECT
        p.id,
        p.full_name,
        p.username,
        p.phone,
        p.bio,
        p.location,

        to_char(
            p.created_at
            AT TIME ZONE 'Africa/Nairobi',
            'YYYY-MM-DD'
        ),

        reliability.score,
        reliability.sample_size,

        coalesce(
            history.current_debt_total,
            0
        )::bigint,

        history.current_debt_count,

        p.borrowing_limit::bigint,

        greatest(
            0,
            p.borrowing_limit
            - coalesce(
                history.outstanding_principal,
                0
            )
        )::bigint,


        (
            SELECT count(*)::integer

            FROM public.transactions t

            WHERE (
                t.sender_id = p.id
                OR t.recipient_id = p.id
            )
            AND t.status = 'COMPLETED'
        ),


        history.borrowing_count,
        history.completed_borrowing_count,

        history.minimum_borrowed,
        history.maximum_borrowed,
        history.average_borrowed


    FROM public.profiles p


    CROSS JOIN LATERAL
        public.loan_reliability(
            p.id
        ) reliability


    CROSS JOIN LATERAL (

        SELECT

            sum(
                l.total_repayment
                - l.amount_paid
            )
            FILTER (
                WHERE l.status NOT IN (
                    'REPAID',
                    'RECOVERED_EXTERNALLY'
                )
            )
            AS current_debt_total,


            count(*)
            FILTER (
                WHERE l.status NOT IN (
                    'REPAID',
                    'RECOVERED_EXTERNALLY'
                )
            )::integer
            AS current_debt_count,


            sum(
                l.principal
                - l.principal_paid
            )
            FILTER (
                WHERE l.status NOT IN (
                    'REPAID',
                    'RECOVERED_EXTERNALLY'
                )
            )
            AS outstanding_principal,


            count(*)::integer
            AS borrowing_count,


            count(*)
            FILTER (
                WHERE l.status = 'REPAID'
            )::integer
            AS completed_borrowing_count,


            min(l.principal)::bigint
            AS minimum_borrowed,


            max(l.principal)::bigint
            AS maximum_borrowed,


            trunc(
                avg(l.principal)
            )::bigint
            AS average_borrowed


        FROM public.loans l

        WHERE l.borrower_id =
            p.id

    ) history


    WHERE p.id =
        p_profile_id;

END;
$function$
