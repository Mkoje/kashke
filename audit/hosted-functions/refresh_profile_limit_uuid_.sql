CREATE OR REPLACE FUNCTION public.refresh_profile_limit(p_user_id uuid)
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
BEGIN

    UPDATE public.profiles p

    SET available_limit =
        greatest(
            0,
            p.borrowing_limit -
            (
                SELECT coalesce(
                    sum(principal - principal_paid),
                    0
                )
                FROM public.loans
                WHERE borrower_id = p.id
                  AND status NOT IN (
                      'REPAID',
                      'RECOVERED_EXTERNALLY'
                  )
            )
        )

    WHERE p.id = p_user_id;

END;
$function$
