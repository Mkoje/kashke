CREATE OR REPLACE FUNCTION public.get_direct_request_borrower_summary(p_request_id uuid)
 RETURNS TABLE(borrower_id uuid, full_name text, available_limit integer)
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
DECLARE
    r public.loan_requests%rowtype;

BEGIN

    IF auth.uid() IS NULL THEN
        RAISE EXCEPTION 'Sign in first'
            USING ERRCODE = '28000';
    END IF;


    SELECT *
    INTO r

    FROM public.loan_requests

    WHERE id = p_request_id
      AND direct_request_to =
          auth.uid()
      AND above_limit_requested;


    IF NOT FOUND THEN
        RAISE EXCEPTION
            'This is not a direct above-limit request assigned to you';
    END IF;


    RETURN QUERY

    SELECT
        p.id,
        p.full_name,
        p.available_limit

    FROM public.profiles p

    WHERE p.id =
        r.borrower_id;

END;
$function$
