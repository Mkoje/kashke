CREATE OR REPLACE FUNCTION public.refresh_profile_statistics(p_user_id uuid)
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
BEGIN

    UPDATE public.profiles p

    SET
        completed_loans = (
            SELECT count(*)
            FROM public.loans
            WHERE borrower_id = p.id
              AND status = 'REPAID'
        ),

        overdue_loans = (
            SELECT count(*)
            FROM public.loans
            WHERE borrower_id = p.id
              AND status IN (
                  'OVERDUE',
                  'DEFAULTED'
              )
        ),

        active_loans = (
            SELECT count(*)
            FROM public.loans
            WHERE borrower_id = p.id
              AND status NOT IN (
                  'REPAID',
                  'RECOVERED_EXTERNALLY'
              )
        ),

        total_borrowed = (
            SELECT coalesce(sum(principal), 0)
            FROM public.loans
            WHERE borrower_id = p.id
        ),

        total_repaid = (
            SELECT coalesce(sum(amount_paid), 0)
            FROM public.loans
            WHERE borrower_id = p.id
        ),

        total_supported = (
            SELECT coalesce(sum(principal), 0)
            FROM public.loans
            WHERE lender_id = p.id
        ),

        total_earned_from_interest = (
            SELECT coalesce(
                sum(amount_paid - principal_paid),
                0
            )
            FROM public.loans
            WHERE lender_id = p.id
        ),

        repayment_ratio =
            coalesce(
                (
                    SELECT score
                    FROM public.loan_reliability(p.id)
                ),
                0
            ),

        reliability_score = (
            SELECT score
            FROM public.loan_reliability(p.id)
        ),

        reliability_sample_size = (
            SELECT sample_size
            FROM public.loan_reliability(p.id)
        )

    WHERE p.id = p_user_id;

END;
$function$
