CREATE OR REPLACE FUNCTION public.apply_daily_trust_penalties(p_user_id uuid)
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
DECLARE
    d date;
    first_day date;
    last_day date;
    loan_id uuid;
    before_limit integer;
    after_limit integer;

BEGIN

    PERFORM 1
    FROM public.profiles
    WHERE id = p_user_id
    FOR UPDATE;


    INSERT INTO public.kash_trust_state(
        user_id,
        cycle_peak,
        clean_cycle
    )
    SELECT
        id,
        trust_score,
        trust_score = 0
    FROM public.profiles
    WHERE id = p_user_id
    ON CONFLICT DO NOTHING;


    SELECT greatest(
        min(
            (l.due_date AT TIME ZONE 'Africa/Nairobi')::date + 1
        ),
        (
            s.policy_started_at
            AT TIME ZONE 'Africa/Nairobi'
        )::date
    )
    INTO first_day

    FROM public.loans l

    CROSS JOIN public.kash_trust_state s

    WHERE l.borrower_id = p_user_id
      AND s.user_id = p_user_id
      AND l.status NOT IN (
          'REPAID',
          'RECOVERED_EXTERNALLY'
      )
      AND l.due_date < now()

    GROUP BY s.policy_started_at;


    last_day :=
        (
            now()
            AT TIME ZONE 'Africa/Nairobi'
        )::date;


    IF first_day IS NULL
       OR first_day > last_day
    THEN
        RETURN;
    END IF;


    first_day :=
        greatest(
            first_day,
            (
                SELECT max(penalty_date) + 1
                FROM public.kash_trust_daily_penalties
                WHERE user_id = p_user_id
            )
        );


    IF first_day > last_day THEN
        RETURN;
    END IF;


    FOR d IN

        SELECT generate_series(
            first_day::timestamp,
            last_day::timestamp,
            interval '1 day'
        )::date

    LOOP

        SELECT id
        INTO loan_id

        FROM public.loans

        WHERE borrower_id = p_user_id
          AND status NOT IN (
              'REPAID',
              'RECOVERED_EXTERNALLY'
          )
          AND (
              due_date
              AT TIME ZONE 'Africa/Nairobi'
          )::date < d

        ORDER BY due_date, id
        LIMIT 1;


        IF loan_id IS NULL THEN
            CONTINUE;
        END IF;


        SELECT borrowing_limit
        INTO before_limit

        FROM public.profiles
        WHERE id = p_user_id;


        INSERT INTO public.kash_trust_daily_penalties(
            user_id,
            penalty_date,
            related_loan_id,
            limit_before,
            limit_after
        )
        VALUES(
            p_user_id,
            d,
            loan_id,
            before_limit,
            before_limit
        )
        ON CONFLICT DO NOTHING;


        IF NOT FOUND THEN
            CONTINUE;
        END IF;


        PERFORM public.record_trust_event(
            p_user_id,
            'DAILY_DELAY',
            -10,

            format(
                'Payment delayed on %s; daily limit reduction approximately 0.288194%%.',
                d
            ),

            loan_id
        );


        SELECT borrowing_limit
        INTO after_limit

        FROM public.profiles
        WHERE id = p_user_id;


        UPDATE public.kash_trust_daily_penalties

        SET limit_after = after_limit

        WHERE user_id = p_user_id
          AND penalty_date = d;

    END LOOP;

END;
$function$
