CREATE OR REPLACE FUNCTION public.record_trust_event(p_user_id uuid, p_event_type text, p_points integer, p_description text, p_loan_id uuid DEFAULT NULL::uuid)
 RETURNS uuid
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
DECLARE
    p public.profiles%rowtype;
    s public.kash_trust_state%rowtype;

    v_id uuid;
    v_points integer;
    v_score integer;
    v_limit integer;
    v_rate integer;

BEGIN

    IF p_event_type IN (
        'COMMUNITY_SUPPORT',
        'LOAN_COMPLETED'
    )
    THEN
        PERFORM public.apply_daily_trust_penalties(
            p_user_id
        );
    END IF;


    SELECT *
    INTO STRICT p
    FROM public.profiles
    WHERE id = p_user_id
    FOR UPDATE;


    INSERT INTO public.kash_trust_state(
        user_id,
        cycle_peak,
        clean_cycle
    )
    VALUES(
        p.id,
        p.trust_score,
        p.trust_score = 0
    )
    ON CONFLICT DO NOTHING;


    SELECT *
    INTO STRICT s
    FROM public.kash_trust_state
    WHERE user_id = p.id
    FOR UPDATE;


    v_points :=
        CASE p_event_type

            WHEN 'COMMUNITY_SUPPORT'
                THEN 2

            WHEN 'LOAN_COMPLETED'
                THEN (
                    SELECT CASE
                        WHEN completed_at <= original_due_date
                            THEN 2
                        WHEN completed_at <= due_date
                            THEN 1
                        ELSE 0
                    END
                    FROM public.loans
                    WHERE id = p_loan_id
                )

            WHEN 'DAILY_DELAY'
                THEN -10

            ELSE 0

        END;


    v_points := coalesce(v_points, 0);


    IF p_event_type IN (
        'COMMUNITY_SUPPORT',
        'LOAN_COMPLETED'
    )
    AND EXISTS (
        SELECT 1
        FROM public.trust_events
        WHERE user_id = p.id
          AND event_type = p_event_type
          AND related_loan_id = p_loan_id
    )
    THEN
        RETURN NULL;
    END IF;


    INSERT INTO public.trust_events(
        user_id,
        event_type,
        points,
        description,
        related_loan_id
    )
    VALUES(
        p.id,
        p_event_type,
        v_points,
        p_description,
        p_loan_id
    )
    RETURNING id INTO v_id;


    v_score := p.trust_score + v_points;
    v_limit := p.borrowing_limit;


    IF v_points < 0 THEN

        s.clean_cycle := false;

        v_limit :=
            trunc(
                v_limit::numeric
                * power(0.98::numeric, 1.0 / 7)
            )::integer;


        IF p.trust_score > 0
           AND v_score <= 0
        THEN

            v_rate :=
                CASE
                    WHEN s.cycle_peak = 1000 THEN 40
                    WHEN s.cycle_peak > 900 THEN 35
                    WHEN s.cycle_peak > 800 THEN 30
                    WHEN s.cycle_peak > 700 THEN 25
                    WHEN s.cycle_peak > 600 THEN 15
                    WHEN s.cycle_peak > 500 THEN 10
                    ELSE 0
                END;


            IF v_rate > 0 THEN

                v_rate :=
                    least(
                        100,
                        v_rate + 5 * s.decline_cycles
                    );


                v_limit :=
                    trunc(
                        v_limit::numeric
                        * (1 - v_rate / 100.0)
                    )::integer;


                s.decline_cycles :=
                    s.decline_cycles + 1;


                INSERT INTO public.notifications(
                    user_id,
                    type,
                    title,
                    message,
                    related_entity_id
                )
                VALUES(
                    p.id,
                    'LIMIT_DECREASED',
                    'Kash Trust decline cycle',

                    format(
                        '%s, your Kash Trust fell from a peak of %s to zero. An additional %s%% reduction leaves your borrowing limit at KSh %s.',
                        p.full_name,
                        s.cycle_peak,
                        v_rate,
                        v_limit
                    ),

                    p_loan_id
                );

            END IF;


            s.cycle_peak := 0;

        END IF;


        IF v_score < 0 THEN

            v_score := 1000 + v_score;
            s.cycle_peak := 1000;

        END IF;


    ELSIF v_points > 0 THEN

        IF v_score >= 1000 THEN

            v_limit :=
                least(
                    2147483647,
                    trunc(
                        v_limit::numeric * 1.02
                    )
                )::integer;


            IF s.clean_cycle THEN

                v_limit :=
                    least(
                        2147483647,
                        trunc(
                            v_limit::numeric * 1.02
                        )
                    )::integer;

            END IF;


            INSERT INTO public.notifications(
                user_id,
                type,
                title,
                message,
                related_entity_id
            )
            VALUES(
                p.id,
                'LIMIT_INCREASED',
                'Kash Trust milestone',

                format(
                    '%s, you reached 1000 Kash Trust points. Your limit increased by 2%%%s to KSh %s.',
                    p.full_name,
                    CASE
                        WHEN s.clean_cycle
                            THEN ' plus a further 2% for a deduction-free cycle'
                        ELSE ''
                    END,
                    v_limit
                ),

                p_loan_id
            );


            v_score := v_score - 1000;

            s.cycle_peak := v_score;
            s.clean_cycle := true;


        ELSE

            s.cycle_peak :=
                greatest(
                    s.cycle_peak,
                    v_score
                );

        END IF;

    END IF;


    UPDATE public.profiles

    SET
        trust_score = v_score,
        borrowing_limit = v_limit,
        available_limit =
            least(
                available_limit,
                v_limit
            )

    WHERE id = p.id;


    UPDATE public.kash_trust_state

    SET
        cycle_peak = s.cycle_peak,
        clean_cycle = s.clean_cycle,
        decline_cycles = s.decline_cycles

    WHERE user_id = p.id;


    PERFORM public.refresh_profile_limit(
        p.id
    );


    IF v_points <> 0 THEN

        INSERT INTO public.notifications(
            user_id,
            type,
            title,
            message,
            related_entity_id
        )
        VALUES(
            p.id,

            CASE
                WHEN v_points < 0
                    THEN 'TRUST_DECREASED'
                ELSE 'TRUST_INCREASED'
            END,

            'Kash Trust activity',

            format(
                '%s, %s Kash Trust points: %s Your points are %s/1000; borrowing limit KSh %s.',
                p.full_name,
                v_points,
                p_description,
                v_score,
                v_limit
            ),

            p_loan_id
        );

    END IF;


    RETURN v_id;

END;
$function$
