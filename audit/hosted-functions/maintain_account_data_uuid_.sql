CREATE OR REPLACE FUNCTION public.maintain_account_data(p_user_id uuid DEFAULT NULL::uuid)
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
DECLARE
    r public.loan_requests%rowtype;
    l public.loans%rowtype;
    u uuid;

BEGIN

    FOR u IN

        SELECT id
        FROM public.profiles

        WHERE p_user_id IS NULL
           OR id = p_user_id
           OR id IN (
               SELECT borrower_id
               FROM public.loans
               WHERE lender_id = p_user_id
           )

        ORDER BY id

    LOOP

        PERFORM public.apply_daily_trust_penalties(u);
        PERFORM public.refresh_profile_statistics(u);

    END LOOP;


    FOR r IN

        SELECT *
        FROM public.loan_requests

        WHERE status IN (
            'REQUESTED',
            'PARTIALLY_FUNDED'
        )
          AND expires_at <= now()
          AND (
              p_user_id IS NULL
              OR borrower_id = p_user_id
              OR EXISTS (
                  SELECT 1
                  FROM public.loan_offers o
                  WHERE o.loan_request_id =
                        loan_requests.id
                    AND o.lender_id =
                        p_user_id
              )
          )

        ORDER BY id
        FOR UPDATE

    LOOP

        PERFORM public.release_request_offers(r.id);

        UPDATE public.loan_requests
        SET status = 'EXPIRED'
        WHERE id = r.id;

    END LOOP;


    FOR l IN

        SELECT *
        FROM public.loans

        WHERE status IN (
            'ACTIVE',
            'DUE_SOON',
            'GRACE_PERIOD'
        )
          AND (
              p_user_id IS NULL
              OR borrower_id = p_user_id
              OR lender_id = p_user_id
          )

        ORDER BY id
        FOR UPDATE

    LOOP

        IF l.due_date <= now() THEN

            PERFORM public.mark_loan_overdue(l.id);


        ELSIF l.status = 'ACTIVE'
          AND l.due_date <= now() + interval '3 days'
        THEN

            UPDATE public.loans
            SET status = 'DUE_SOON'
            WHERE id = l.id;


            INSERT INTO public.notifications(
                user_id,
                type,
                title,
                message,
                related_entity_id
            )
            VALUES(
                l.borrower_id,
                'LOAN_DUE_SOON',
                'Loan due soon',

                format(
                    '%s, your repayment to %s is due within three days.',
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
            );

        END IF;

    END LOOP;

END;
$function$
