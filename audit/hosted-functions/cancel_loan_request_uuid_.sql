CREATE OR REPLACE FUNCTION public.cancel_loan_request(p_request_id uuid)
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

    select *
    into v_request
    from public.loan_requests
    where id = p_request_id;

    if not found then
        raise exception 'Loan request not found';
    end if;

    if v_request.borrower_id <> auth.uid() then
        raise exception 'You can only cancel your own loan request';
    end if;

    -- Cannot cancel once funding has started.
    if coalesce(v_request.funded_amount, 0) > 0 then
        raise exception 'A funded request cannot be cancelled';
    end if;

    if exists (
        select 1
        from public.loan_offers
        where loan_request_id = p_request_id
    ) then
        raise exception 'A request with an offer cannot be cancelled';
    end if;

    -- Restore the amount reserved when this request was created.
    update public.profiles
    set available_limit = least(
        borrowing_limit,
        available_limit + v_request.requested_amount
    )
    where id = auth.uid();

    -- Remove the request.
    delete from public.loan_requests
    where id = p_request_id
      and borrower_id = auth.uid();

    if not found then
        raise exception 'Loan request could not be cancelled';
    end if;
end;
$function$
