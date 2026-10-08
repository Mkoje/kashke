CREATE OR REPLACE FUNCTION public.public_profile_directory(p_query text DEFAULT ''::text, p_phone_numbers text[] DEFAULT ARRAY[]::text[], p_profile_ids uuid[] DEFAULT ARRAY[]::uuid[])
 RETURNS TABLE(id uuid, full_name text, username text, phone text, bio text, location text, profile_image text, reliability_score integer, reliability_sample_size integer, account_status text, created_at timestamp with time zone, matched_phone text)
 LANGUAGE plpgsql
 STABLE SECURITY DEFINER
 SET search_path TO 'public', 'pg_temp'
AS $function$
DECLARE
    v_query text :=
        lower(
            btrim(
                coalesce(
                    p_query,
                    ''
                )
            )
        );

    v_query_digits text :=
        regexp_replace(
            coalesce(
                p_query,
                ''
            ),
            '[^0-9]',
            '',
            'g'
        );

    v_phone_numbers text[] :=
        coalesce(
            p_phone_numbers,
            array[]::text[]
        );

    v_profile_ids uuid[] :=
        coalesce(
            p_profile_ids,
            array[]::uuid[]
        );

BEGIN

    IF auth.uid() IS NULL THEN
        RAISE EXCEPTION 'Sign in first'
            USING ERRCODE = '28000';
    END IF;


    IF length(
        coalesce(
            p_query,
            ''
        )
    ) > 80
    OR cardinality(v_phone_numbers) > 100
    OR cardinality(v_profile_ids) > 100
    THEN
        RAISE EXCEPTION
            'Directory request is too large';
    END IF;


    RETURN QUERY

    SELECT
        profile.id,
        profile.full_name,
        profile.username,

        ''::text,

        profile.bio,
        profile.location,
        profile.profile_image,

        (
            SELECT score
            FROM public.loan_reliability(
                profile.id
            )
        ),

        (
            SELECT sample_size
            FROM public.loan_reliability(
                profile.id
            )
        ),

        profile.account_status,
        profile.created_at,

        matched.phone


    FROM public.profiles AS profile


    LEFT JOIN LATERAL (

        SELECT
            public.normalize_ke_phone(
                input.phone
            ) AS phone

        FROM unnest(
            v_phone_numbers
        ) AS input(phone)

        WHERE
            public.normalize_ke_phone(
                input.phone
            )
            =
            public.normalize_ke_phone(
                profile.phone
            )

        ORDER BY
            public.normalize_ke_phone(
                input.phone
            )

        LIMIT 1

    ) AS matched
    ON true


    WHERE

        (
            v_query = ''
            AND cardinality(
                v_phone_numbers
            ) = 0
            AND cardinality(
                v_profile_ids
            ) = 0
        )

        OR

        (
            v_query <> ''
            AND (
                strpos(
                    lower(
                        profile.full_name
                    ),
                    v_query
                ) > 0

                OR

                strpos(
                    lower(
                        profile.username
                    ),
                    v_query
                ) > 0

                OR

                (
                    length(
                        v_query_digits
                    ) >= 4

                    AND strpos(
                        public.normalize_ke_phone(
                            profile.phone
                        ),
                        v_query_digits
                    ) > 0
                )
            )
        )

        OR matched.phone IS NOT NULL

        OR profile.id =
            ANY(v_profile_ids)


    ORDER BY
        profile.full_name,
        profile.id;

END;
$function$
