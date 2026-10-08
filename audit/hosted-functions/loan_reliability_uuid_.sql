CREATE OR REPLACE FUNCTION public.loan_reliability(p_user_id uuid)
 RETURNS TABLE(score integer, sample_size integer)
 LANGUAGE sql
 STABLE SECURITY DEFINER
 SET search_path TO ''
AS $function$
    SELECT
        trunc(
            100.0
            * count(*) FILTER (
                WHERE status = 'REPAID'
                  AND completed_at <= due_date
            )
            / nullif(count(*), 0)
        )::integer,

        count(*)::integer

    FROM public.loans

    WHERE borrower_id = p_user_id
      AND (
          status IN (
              'REPAID',
              'RECOVERED_EXTERNALLY'
          )
          OR due_date <= now()
      );
$function$
