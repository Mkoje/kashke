CREATE OR REPLACE FUNCTION public.edit_loan_request(p_request_id uuid, p_amount integer, p_purpose text, p_description text, p_period_days integer)
 RETURNS void
 LANGUAGE plpgsql
 SET search_path TO 'public'
AS $function$
declare
    v_request public.loan_requests%rowtype;
begin
    if auth.uid() is null then
        raise exception 'Authentication required';
    end if;

    if p_amount <= 0 then
        raise exception 'Amount must be greater than zero';
    end if;

    if p_period_days <= 0 then
        raise exception 'Repayment period must be greater than zero';
    end if;

    select *
    into v_request
    from public.loan_requests
    where id = p_request_id;

    if not found then
        raise exception 'Loan request not found';
    end if;

    if v_request.borrower_id <> auth.uid() then
        raise exception 'You can only edit your own loan request';
    end if;

    -- Once somebody has contributed, the request cannot be edited.
    if coalesce(v_request.funded_amount, 0) > 0 then
        raise exception 'A funded request cannot be edited';
    end if;

    -- Also protect against an offer existing even if funded_amount
    -- has not yet been synchronised.
    if exists (
        select 1
        from public.loan_offers
        where loan_request_id = p_request_id
    ) then
        raise exception 'A request with an offer cannot be edited';
    end if;

    update public.loan_requests
    set
        requested_amount = p_amount,
        remaining_amount = p_amount,
        purpose = trim(p_purpose),
        description = trim(p_description),
        preferred_period_days = p_period_days
    where id = p_request_id
      and borrower_id = auth.uid();
end;
$function$
