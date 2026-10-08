-- Incremental update AFTER 202610040002_trust_reliability.sql.
-- No new tables/columns/rows; no rewards, points or limits are recomputed here.
-- A distinct typed RPC keeps public lending context separate from Kash Trust.
-- Rollback: use the previous APK and drop member_profile_details(uuid). Keep
-- previous trust/reliability migration intact; financial ledgers are unchanged.
do $$ begin
    if to_regprocedure('public.loan_reliability(uuid)') is null then
        raise exception 'Apply 202610040002_trust_reliability.sql before this incremental profile update';
    end if;
end $$;
create or replace function public.member_profile_details(p_profile_id uuid)
returns table (
    id uuid,
    full_name text,
    username text,
    phone text,
    bio text,
    location text,
    member_since text,
    reliability_score integer,
    reliability_sample_size integer,
    current_debt_total bigint,
    current_debt_count integer,
    borrowing_limit bigint,
    available_limit bigint,
    previous_transaction_count integer,
    borrowing_count integer,
    completed_borrowing_count integer,
    minimum_borrowed bigint,
    maximum_borrowed bigint,
    average_borrowed bigint
)
language plpgsql stable security definer set search_path = '' as $$
begin
    if auth.uid() is null then raise exception 'Sign in first' using errcode='28000'; end if;
    if p_profile_id is null then raise exception 'Choose a profile'; end if;
    return query
    select p.id,p.full_name,p.username,p.phone,p.bio,p.location,
        to_char(p.created_at at time zone 'Africa/Nairobi','YYYY-MM-DD'),
        reliability.score,reliability.sample_size,
        coalesce(history.current_debt_total,0)::bigint,history.current_debt_count,
        p.borrowing_limit::bigint,
        -- Derive capacity from current principal instead of a stale cache.
        greatest(0,p.borrowing_limit-coalesce(history.outstanding_principal,0))::bigint,
        (select count(*)::integer from public.transactions t
         where (t.sender_id=p.id or t.recipient_id=p.id) and t.status='COMPLETED'),
        history.borrowing_count,history.completed_borrowing_count,
        history.minimum_borrowed,history.maximum_borrowed,history.average_borrowed
    from public.profiles p
    cross join lateral public.loan_reliability(p.id) reliability
    cross join lateral (
        select
            sum(l.total_repayment-l.amount_paid) filter(where l.status not in ('REPAID','RECOVERED_EXTERNALLY')) as current_debt_total,
            count(*) filter(where l.status not in ('REPAID','RECOVERED_EXTERNALLY'))::integer as current_debt_count,
            sum(l.principal-l.principal_paid) filter(where l.status not in ('REPAID','RECOVERED_EXTERNALLY')) as outstanding_principal,
            count(*)::integer as borrowing_count,
            count(*) filter(where l.status='REPAID')::integer as completed_borrowing_count,
            min(l.principal)::bigint as minimum_borrowed,
            max(l.principal)::bigint as maximum_borrowed,
            trunc(avg(l.principal))::bigint as average_borrowed
        from public.loans l where l.borrower_id=p.id
    ) history
    where p.id=p_profile_id;
end;
$$;
revoke all on function public.member_profile_details(uuid) from public,anon,authenticated;
grant execute on function public.member_profile_details(uuid) to authenticated;
comment on function public.member_profile_details(uuid) is
    'One selected member: phone and aggregate lending history; no Kash Trust, wallet, email, counterparties or individual loan records.';
notify pgrst, 'reload schema';
