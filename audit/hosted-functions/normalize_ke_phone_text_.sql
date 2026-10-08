CREATE OR REPLACE FUNCTION public.normalize_ke_phone(p_phone text)
 RETURNS text
 LANGUAGE sql
 IMMUTABLE STRICT
 SET search_path TO ''
AS $function$
    WITH digits AS (
        SELECT pg_catalog.regexp_replace(
            p_phone,
            '[^0-9]',
            '',
            'g'
        ) AS value
    )
    SELECT CASE
        WHEN value ~ '^0[17][0-9]{8}$'
            THEN '254' || pg_catalog.substr(value, 2)
        WHEN value ~ '^[17][0-9]{8}$'
            THEN '254' || value
        WHEN value ~ '^254[17][0-9]{8}$'
            THEN value
        ELSE value
    END
    FROM digits;
$function$
