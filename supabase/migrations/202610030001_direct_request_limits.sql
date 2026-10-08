alter table public.loan_offers
    drop constraint if exists loan_offers_interest_rate_check,
    drop constraint if exists loan_offers_requested_interest_rate_check;
alter table public.loan_offers
    add constraint loan_offers_interest_rate_check check (interest_rate between 0 and 15),
    add constraint loan_offers_requested_interest_rate_check
        check (requested_interest_rate is null or requested_interest_rate between 0 and 15);

alter table public.loans
    drop constraint if exists loans_interest_rate_check;
alter table public.loans
    add constraint loans_interest_rate_check check (interest_rate between 0 and 15);

alter table public.lender_preferences
    drop constraint if exists lender_preferences_default_interest_rate_check;
alter table public.lender_preferences
    add constraint lender_preferences_default_interest_rate_check check (default_interest_rate between 0 and 7);

create or replace function public.create_loan_request(
    p_amount bigint, p_purpose text, p_description text, p_period_days integer,
    p_direct_request_to uuid default null, p_above_limit_requested boolean default false
)
returns uuid language plpgsql security definer set search_path = '' as $$
declare
    v_profile public.profiles%rowtype;
    v_id uuid;
    v_connection record;
begin
    if auth.uid() is null then raise exception 'Sign in first' using errcode = '28000'; end if;
    select * into v_profile from public.profiles where id = auth.uid() for update;
    if not found or v_profile.borrowing_frozen then raise exception 'Borrowing is unavailable'; end if;
    if p_amount <= 0 or p_period_days not between 7 and 365 then raise exception 'Invalid request terms'; end if;
    if length(trim(p_purpose)) = 0 or length(trim(p_description)) = 0 then
        raise exception 'Purpose and description are required';
    end if;
    if p_direct_request_to = auth.uid() then raise exception 'Cannot request support from yourself'; end if;
    if p_direct_request_to is not null and not exists (
        select 1 from public.connections c where c.status = 'ACCEPTED'
            and ((c.requester_id = auth.uid() and c.recipient_id = p_direct_request_to)
                or (c.recipient_id = auth.uid() and c.requester_id = p_direct_request_to))
    ) then
        raise exception 'Direct requests are available only to connected members';
    end if;
    if p_above_limit_requested <> (p_amount > v_profile.available_limit) then
        raise exception 'Above-limit disclosure must match the requested amount';
    end if;
    if p_amount > v_profile.available_limit and p_direct_request_to is null then
        raise exception 'Above-limit requests must be directed to a connected lender';
    end if;

    insert into public.loan_requests (
        borrower_id, requested_amount, funded_amount, remaining_amount, purpose, description,
        preferred_period_days, direct_request_to, above_limit_requested
    ) values (auth.uid(), p_amount, 0, p_amount, trim(p_purpose), trim(p_description),
        p_period_days, p_direct_request_to, p_above_limit_requested) returning id into v_id;
    if p_direct_request_to is not null then
        insert into public.notifications (user_id, type, title, message, related_entity_id)
        values (p_direct_request_to, 'DIRECT_REQUEST', 'Direct support request',
            case when p_above_limit_requested then
                format('%s requested KSh %s for %s, exceeding their available borrowing limit by KSh %s.',
                    v_profile.full_name, p_amount, trim(p_purpose), p_amount - v_profile.available_limit)
            else format('%s requested KSh %s for %s.', v_profile.full_name, p_amount, trim(p_purpose))
            end,
            v_id);
    else
        for v_connection in
            select case when requester_id = auth.uid() then recipient_id else requester_id end as other_user
            from public.connections where status = 'ACCEPTED'
                and (requester_id = auth.uid() or recipient_id = auth.uid())
        loop
            insert into public.notifications (user_id, type, title, message, related_entity_id)
            values (v_connection.other_user, 'NEW_LOAN_REQUEST', 'Community request',
                format('%s requested KSh %s for %s.', v_profile.full_name, p_amount, trim(p_purpose)), v_id);
        end loop;
    end if;
    return v_id;
end;
$$;

create or replace function public.create_loan_offer(
    p_request_id uuid, p_amount bigint, p_interest_rate numeric, p_period_days integer,
    p_approve_above_limit boolean default false
)
returns uuid language plpgsql security definer set search_path = '' as $$
declare
    v_request public.loan_requests%rowtype;
    v_wallet public.wallets%rowtype;
    v_id uuid;
    v_total bigint;
    v_max_interest numeric;
begin
    if auth.uid() is null then raise exception 'Sign in first' using errcode = '28000'; end if;
    select * into v_request from public.loan_requests where id = p_request_id for update;
    if not found or v_request.status not in ('REQUESTED', 'PARTIALLY_FUNDED') or v_request.expires_at <= now() then
        raise exception 'Request is no longer available';
    end if;
    if v_request.direct_request_to is not null and v_request.direct_request_to <> auth.uid() then
        raise exception 'This request is private';
    end if;
    if v_request.direct_request_to is null and not exists (
        select 1 from public.connections c where c.status = 'ACCEPTED'
            and ((c.requester_id = auth.uid() and c.recipient_id = v_request.borrower_id)
                or (c.recipient_id = auth.uid() and c.requester_id = v_request.borrower_id))
    ) then
        raise exception 'Connect with this borrower first';
    end if;
    if v_request.borrower_id = auth.uid() or p_amount <= 0 or p_amount > v_request.remaining_amount then
        raise exception 'Invalid offer amount';
    end if;
    if v_request.above_limit_requested and v_request.direct_request_to is distinct from auth.uid() then
        raise exception 'Above-limit requests are direct only';
    end if;
    if v_request.above_limit_requested and not p_approve_above_limit then
        raise exception 'Lender must explicitly approve this above-limit request';
    end if;
    if p_approve_above_limit and
        (not v_request.above_limit_requested or v_request.direct_request_to is distinct from auth.uid()) then
        raise exception 'Only the designated lender may approve an above-limit request';
    end if;
    v_max_interest := case when v_request.above_limit_requested then 15 else 7 end;
    if p_interest_rate not between 0 and v_max_interest
        or p_period_days not between 7 and v_request.preferred_period_days then
        raise exception 'Interest exceeds the limit for this request or the repayment period is invalid';
    end if;

    select * into v_wallet from public.wallets where user_id = auth.uid() for update;
    if not found or v_wallet.balance - v_wallet.locked_balance < p_amount then
        raise exception 'Insufficient available wallet balance';
    end if;
    if exists (
        select 1 from public.lender_preferences lp
        where lp.user_id = auth.uid() and lp.maximum_lending_amount > 0 and p_amount > lp.maximum_lending_amount
    ) then raise exception 'Offer exceeds your maximum lending amount'; end if;

    v_total := p_amount + round(p_amount * p_interest_rate * p_period_days / 36500.0)::bigint;
    update public.wallets set locked_balance = locked_balance + p_amount, updated_at = now()
        where user_id = auth.uid();
    insert into public.loan_offers (
        loan_request_id, lender_id, amount, interest_rate, repayment_period_days, total_repayment, above_limit_override
    ) values (
        p_request_id, auth.uid(), p_amount, p_interest_rate, p_period_days, v_total, p_approve_above_limit
    ) returning id into v_id;
    insert into public.notifications (user_id, type, title, message, related_entity_id)
    values (v_request.borrower_id, 'LOAN_OFFER', 'New support offer',
        format('A supporter offered KSh %s at %s%% for %s days%s.', p_amount, p_interest_rate, p_period_days,
            case when v_request.above_limit_requested then ' and approved your disclosed above-limit request' else '' end),
        v_id);
    return v_id;
end;
$$;
