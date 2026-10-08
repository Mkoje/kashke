create or replace function public.refresh_profile_limit(p_user_id uuid)
returns void language plpgsql security definer set search_path = public as $$
declare
    v_score integer;
    v_borrowed bigint;
    v_overdue integer;
    v_outstanding bigint;
    v_limit integer;
begin
    select trust_score into v_score from public.profiles where id = p_user_id for update;
    if not found then return; end if;
    select coalesce(sum(principal), 0), count(*) filter (where status in ('OVERDUE', 'DEFAULTED'))
      into v_borrowed, v_overdue from public.loans where borrower_id = p_user_id;
    select coalesce(sum(principal - principal_paid), 0) into v_outstanding
      from public.loans where borrower_id = p_user_id and status not in ('REPAID', 'RECOVERED_EXTERNALLY');
    v_limit := greatest(2000, 2000 + (v_score * 60) + (v_borrowed / 7)::integer - (v_overdue * 4000));
    update public.profiles set borrowing_limit = v_limit,
        available_limit = greatest(0, v_limit - v_outstanding::integer) where id = p_user_id;
end;
$$;

create or replace function public.record_trust_event(
    p_user_id uuid, p_event_type text, p_points integer, p_description text, p_loan_id uuid default null
)
returns uuid language plpgsql security definer set search_path = public as $$
declare v_id uuid;
begin
    insert into public.trust_events (user_id, event_type, points, description, related_loan_id)
    values (p_user_id, p_event_type, p_points, p_description, p_loan_id) returning id into v_id;
    update public.profiles set trust_score = greatest(0, least(1000, trust_score + p_points)) where id = p_user_id;
    perform public.refresh_profile_limit(p_user_id);
    insert into public.notifications (user_id, type, title, message, related_entity_id)
    values (p_user_id, 'TRUST_UPDATED', 'Trust activity', p_description, p_loan_id);
    return v_id;
end;
$$;

create or replace function public.send_connection_request(p_recipient_id uuid)
returns uuid language plpgsql security definer set search_path = public, auth as $$
declare v_id uuid;
begin
    if p_recipient_id = auth.uid() then raise exception 'Cannot connect to yourself'; end if;
    insert into public.connections (requester_id, recipient_id) values (auth.uid(), p_recipient_id) returning id into v_id;
    insert into public.notifications (user_id, type, title, message, related_entity_id)
    values (p_recipient_id, 'CONNECTION_REQUEST', 'Connection request', 'You received a connection request.', v_id);
    return v_id;
end;
$$;

create or replace function public.respond_connection_request(p_connection_id uuid, p_accept boolean)
returns void language plpgsql security definer set search_path = public, auth as $$
declare v_connection public.connections%rowtype;
begin
    select * into v_connection from public.connections
     where id = p_connection_id and recipient_id = auth.uid() and status = 'PENDING' for update;
    if not found then raise exception 'Pending connection request not found'; end if;
    update public.connections set status = case when p_accept then 'ACCEPTED' else 'REJECTED' end,
        accepted_at = case when p_accept then now() else null end where id = p_connection_id;
    insert into public.notifications (user_id, type, title, message, related_entity_id)
    values (v_connection.requester_id,
        case when p_accept then 'CONNECTION_ACCEPTED' else 'CONNECTION_REJECTED' end,
        case when p_accept then 'Connection accepted' else 'Connection declined' end,
        case when p_accept then 'Your connection request was accepted.' else 'Your connection request was declined.' end,
        p_connection_id);
end;
$$;

create or replace function public.create_loan_request(
    p_amount bigint, p_purpose text, p_description text, p_period_days integer,
    p_direct_request_to uuid default null, p_above_limit_requested boolean default false
)
returns uuid language plpgsql security definer set search_path = public, auth as $$
declare
    v_profile public.profiles%rowtype;
    v_id uuid;
    v_connection record;
begin
    select * into v_profile from public.profiles where id = auth.uid() for update;
    if not found or v_profile.borrowing_frozen then raise exception 'Borrowing is unavailable'; end if;
    if p_amount <= 0 or p_period_days not between 7 and 365 then raise exception 'Invalid request terms'; end if;
    if p_amount > v_profile.available_limit and (p_direct_request_to is null or not p_above_limit_requested) then
        raise exception 'Above-limit requests must be directed to a lender and explicitly disclosed';
    end if;
    insert into public.loan_requests (
        borrower_id, requested_amount, funded_amount, remaining_amount, purpose, description,
        preferred_period_days, direct_request_to, above_limit_requested
    ) values (auth.uid(), p_amount, 0, p_amount, trim(p_purpose), trim(p_description),
        p_period_days, p_direct_request_to, p_above_limit_requested) returning id into v_id;
    if p_direct_request_to is not null then
        insert into public.notifications (user_id, type, title, message, related_entity_id)
        values (p_direct_request_to, 'DIRECT_REQUEST', 'Direct support request',
            format('%s requested KSh %s for %s.', v_profile.full_name, p_amount, p_purpose), v_id);
    else
        for v_connection in
            select case when requester_id = auth.uid() then recipient_id else requester_id end as other_user
            from public.connections where status = 'ACCEPTED' and (requester_id = auth.uid() or recipient_id = auth.uid())
        loop
            insert into public.notifications (user_id, type, title, message, related_entity_id)
            values (v_connection.other_user, 'NEW_LOAN_REQUEST', 'Community request',
                format('%s requested KSh %s for %s.', v_profile.full_name, p_amount, p_purpose), v_id);
        end loop;
    end if;
    return v_id;
end;
$$;

create or replace function public.edit_loan_request(
    p_request_id uuid, p_amount bigint, p_purpose text, p_description text, p_period_days integer
)
returns void language plpgsql security definer set search_path = public, auth as $$
begin
    if p_amount <= 0 or p_period_days not between 7 and 365 then raise exception 'Invalid request terms'; end if;
    update public.loan_requests r set requested_amount = p_amount, remaining_amount = p_amount,
        purpose = trim(p_purpose), description = trim(p_description), preferred_period_days = p_period_days
     where r.id = p_request_id and r.borrower_id = auth.uid() and r.status = 'REQUESTED' and r.funded_amount = 0
       and p_amount <= (select available_limit from public.profiles where id = auth.uid());
    if not found then raise exception 'Request cannot be edited after support begins'; end if;
end;
$$;

create or replace function public.cancel_loan_request(p_request_id uuid)
returns void language plpgsql security definer set search_path = public, auth as $$
begin
    update public.loan_requests set status = 'CANCELLED'
     where id = p_request_id and borrower_id = auth.uid() and status = 'REQUESTED' and funded_amount = 0;
    if not found then raise exception 'Request cannot be cancelled after support begins'; end if;
end;
$$;

create or replace function public.get_direct_request_borrower_summary(p_request_id uuid)
returns table (
    borrower_id uuid,
    full_name text,
    trust_score integer,
    borrowing_limit integer,
    available_limit integer,
    requested_amount bigint,
    completed_loans bigint,
    overdue_loans bigint,
    repayment_count bigint,
    on_time_repayments bigint,
    total_borrowed bigint
)
language plpgsql
security definer
set search_path = public, auth
as $$
declare
    v_request public.loan_requests%rowtype;
begin
    select * into v_request from public.loan_requests
     where id = p_request_id and direct_request_to = auth.uid() and above_limit_requested;
    if not found then raise exception 'This is not a direct above-limit request assigned to you'; end if;

    return query
    select p.id, p.full_name, p.trust_score, p.borrowing_limit, p.available_limit,
        v_request.requested_amount,
        count(distinct l.id) filter (where l.status = 'REPAID'),
        count(distinct l.id) filter (where l.status in ('OVERDUE', 'DEFAULTED')),
        count(lp.id),
        count(distinct l.id) filter (where l.status = 'REPAID' and l.completed_at <= l.due_date),
        coalesce((select sum(l2.principal)::bigint from public.loans l2 where l2.borrower_id = p.id), 0)
    from public.profiles p
    left join public.loans l on l.borrower_id = p.id
    left join public.loan_payments lp on lp.loan_id = l.id
    where p.id = v_request.borrower_id
    group by p.id, p.full_name, p.trust_score, p.borrowing_limit, p.available_limit;
end;
$$;

create or replace function public.create_loan_offer(
    p_request_id uuid, p_amount bigint, p_interest_rate numeric, p_period_days integer,
    p_approve_above_limit boolean default false
)
returns uuid language plpgsql security definer set search_path = public, auth as $$
declare
    v_request public.loan_requests%rowtype;
    v_wallet public.wallets%rowtype;
    v_id uuid;
    v_total bigint;
begin
    select * into v_request from public.loan_requests where id = p_request_id for update;
    if not found or v_request.status not in ('REQUESTED', 'PARTIALLY_FUNDED') or v_request.expires_at <= now() then
        raise exception 'Request is no longer available';
    end if;
    if v_request.borrower_id = auth.uid() or p_amount <= 0 or p_amount > v_request.remaining_amount then raise exception 'Invalid offer amount'; end if;
    if v_request.above_limit_requested and v_request.direct_request_to is distinct from auth.uid() then raise exception 'Above-limit requests are direct only'; end if;
    if v_request.above_limit_requested and not p_approve_above_limit then
        raise exception 'Lender must explicitly approve this above-limit request';
    end if;
    if p_approve_above_limit and (not v_request.above_limit_requested or v_request.direct_request_to is distinct from auth.uid()) then
        raise exception 'Only the designated lender may approve an above-limit request';
    end if;
    if p_interest_rate not between 0 and 5 or p_period_days not between 7 and v_request.preferred_period_days then
        raise exception 'Initial offers must be 0-5%% and no longer than the requested period';
    end if;
    select * into v_wallet from public.wallets where user_id = auth.uid() for update;
    if not found or v_wallet.balance - v_wallet.locked_balance < p_amount then raise exception 'Insufficient available wallet balance'; end if;
    if exists (
        select 1 from public.lender_preferences lp
        where lp.user_id = auth.uid() and lp.maximum_lending_amount > 0 and p_amount > lp.maximum_lending_amount
    ) then raise exception 'Offer exceeds your maximum lending amount'; end if;
    if p_approve_above_limit and not coalesce((
        select allow_above_limit_requests from public.lender_preferences where user_id = auth.uid()
    ), false) then raise exception 'Enable above-limit lending in your preferences before overriding'; end if;
    v_total := p_amount + round(p_amount * p_interest_rate * p_period_days / 36500.0)::bigint;
    update public.wallets set locked_balance = locked_balance + p_amount, updated_at = now() where user_id = auth.uid();
    insert into public.loan_offers (loan_request_id, lender_id, amount, interest_rate, repayment_period_days, total_repayment, above_limit_override)
    values (p_request_id, auth.uid(), p_amount, p_interest_rate, p_period_days, v_total, p_approve_above_limit) returning id into v_id;
    insert into public.notifications (user_id, type, title, message, related_entity_id)
    values (v_request.borrower_id, 'LOAN_OFFER', 'New support offer',
        format('A supporter offered KSh %s at %s%% for %s days.', p_amount, p_interest_rate, p_period_days), v_id);
    return v_id;
end;
$$;

create or replace function public.request_loan_offer_terms(p_offer_id uuid, p_interest_rate numeric, p_period_days integer)
returns void language plpgsql security definer set search_path = public, auth as $$
declare v_offer public.loan_offers%rowtype;
begin
    select * into v_offer from public.loan_offers where id = p_offer_id and borrower_id = auth.uid() and status = 'OFFERED' for update;
    if not found then raise exception 'Offer is no longer available'; end if;
    if p_interest_rate not between 0 and v_offer.interest_rate or p_period_days not between 7 and 365 then
        raise exception 'Requested terms must use equal-or-lower interest and a 7-365 day period';
    end if;
    update public.loan_offers set status = 'TERMS_REQUESTED', requested_interest_rate = p_interest_rate,
        requested_repayment_period_days = p_period_days where id = p_offer_id;
    insert into public.notifications (user_id, type, title, message, related_entity_id)
    values (v_offer.lender_id, 'LOAN_OFFER_TERMS_REQUESTED', 'Terms requested',
        format('The borrower requests %s%% for %s days.', p_interest_rate, p_period_days), p_offer_id);
end;
$$;

create or replace function public.respond_loan_offer_terms(p_offer_id uuid, p_accept_terms boolean)
returns void language plpgsql security definer set search_path = public, auth as $$
declare
    v_offer public.loan_offers%rowtype;
    v_rate numeric;
    v_days integer;
begin
    select * into v_offer from public.loan_offers where id = p_offer_id and lender_id = auth.uid() and status = 'TERMS_REQUESTED' for update;
    if not found then raise exception 'Terms request is no longer available'; end if;
    v_rate := case when p_accept_terms then v_offer.requested_interest_rate else v_offer.interest_rate end;
    v_days := case when p_accept_terms then v_offer.requested_repayment_period_days else v_offer.repayment_period_days end;
    update public.loan_offers set status = 'OFFERED', interest_rate = v_rate, repayment_period_days = v_days,
        total_repayment = amount + round(amount * v_rate * v_days / 36500.0)::bigint,
        requested_interest_rate = null, requested_repayment_period_days = null where id = p_offer_id;
    insert into public.notifications (user_id, type, title, message, related_entity_id)
    values (v_offer.borrower_id, 'LOAN_OFFER_UPDATED', 'Offer terms updated',
        format('The supporter responded with %s%% interest for %s days.', v_rate, v_days), p_offer_id);
end;
$$;

create or replace function public.accept_loan_offer(p_offer_id uuid)
returns uuid language plpgsql security definer set search_path = public, auth as $$
declare
    v_offer public.loan_offers%rowtype;
    v_request public.loan_requests%rowtype;
    v_wallet public.wallets%rowtype;
    v_borrower public.profiles%rowtype;
    v_lender public.profiles%rowtype;
    v_amount bigint;
    v_interest bigint;
    v_total bigint;
    v_loan_id uuid;
begin
    select * into v_offer from public.loan_offers where id = p_offer_id and status = 'OFFERED' for update;
    if not found then raise exception 'Offer is no longer available'; end if;
    select * into v_request from public.loan_requests where id = v_offer.loan_request_id for update;
    if v_request.borrower_id <> auth.uid() then raise exception 'Only the borrower can accept this offer'; end if;
    if v_request.status not in ('REQUESTED', 'PARTIALLY_FUNDED') or v_request.remaining_amount <= 0 then raise exception 'Request is closed'; end if;
    v_amount := least(v_offer.amount, v_request.remaining_amount);
    select * into v_borrower from public.profiles where id = v_request.borrower_id for update;
    if v_borrower.available_limit < v_amount and not (v_offer.above_limit_override and
        v_request.direct_request_to = v_offer.lender_id and v_request.above_limit_requested) then
        raise exception 'Borrowing limit exceeded; designated lender approval is required';
    end if;
    select * into v_wallet from public.wallets where user_id = v_offer.lender_id for update;
    if not found or v_wallet.locked_balance < v_offer.amount or v_wallet.balance < v_amount then raise exception 'Reserved funds unavailable'; end if;
    select * into v_lender from public.profiles where id = v_offer.lender_id for update;
    v_interest := round(v_amount * v_offer.interest_rate * v_offer.repayment_period_days / 36500.0)::bigint;
    v_total := v_amount + v_interest;
    insert into public.loans (loan_request_id, borrower_id, lender_id, principal, interest_rate, interest_amount,
        total_repayment, due_date, above_limit_override)
    values (v_request.id, v_request.borrower_id, v_offer.lender_id, v_amount, v_offer.interest_rate,
        v_interest, v_total, now() + make_interval(days => v_offer.repayment_period_days), v_offer.above_limit_override)
    returning id into v_loan_id;
    update public.wallets set balance = balance - v_amount, locked_balance = locked_balance - v_offer.amount, updated_at = now()
     where user_id = v_offer.lender_id;
    update public.wallets set balance = balance + v_amount, updated_at = now() where user_id = v_request.borrower_id;
    update public.loan_offers set status = 'ACCEPTED' where id = p_offer_id;
    update public.loan_requests set funded_amount = funded_amount + v_amount, remaining_amount = remaining_amount - v_amount,
        status = case when remaining_amount - v_amount = 0 then 'FULLY_FUNDED' else 'PARTIALLY_FUNDED' end where id = v_request.id;
        if v_request.remaining_amount - v_amount = 0 then
                update public.wallets w set locked_balance = greatest(0, w.locked_balance - reserved.amount), updated_at = now()
                    from (
                            select lender_id, sum(amount) as amount from public.loan_offers
                             where loan_request_id = v_request.id and id <> p_offer_id
                                 and status in ('OFFERED', 'TERMS_REQUESTED')
                             group by lender_id
                    ) reserved
                 where w.user_id = reserved.lender_id;
                update public.loan_offers set status = 'EXPIRED'
                 where loan_request_id = v_request.id and id <> p_offer_id and status in ('OFFERED', 'TERMS_REQUESTED');
        end if;
    insert into public.transactions (type, sender_id, recipient_id, amount, loan_id, description)
    values ('LOAN_FUNDED', v_offer.lender_id, v_request.borrower_id, v_amount, v_loan_id, format('KSh %s funded to %s', v_amount, v_borrower.full_name));
    insert into public.notifications (user_id, type, title, message, related_entity_id) values
        (v_request.borrower_id, 'LOAN_ACCEPTED', 'Support accepted', format('KSh %s was funded by %s.', v_amount, v_lender.full_name), v_loan_id),
        (v_offer.lender_id, 'LOAN_ACCEPTED', 'Offer accepted', format('%s accepted your KSh %s offer.', v_borrower.full_name, v_amount), v_loan_id);
    update public.profiles set available_limit = greatest(0, available_limit - v_amount) where id = v_request.borrower_id;
    perform public.record_trust_event(v_offer.lender_id, 'COMMUNITY_SUPPORT', 1, format('Supported %s with KSh %s.', v_borrower.full_name, v_amount), v_loan_id);
    perform public.refresh_profile_limit(v_request.borrower_id);
    return v_loan_id;
end;
$$;

create or replace function public.decline_loan_offer(p_offer_id uuid)
returns void language plpgsql security definer set search_path = public, auth as $$
declare v_offer public.loan_offers%rowtype;
begin
    select * into v_offer from public.loan_offers where id = p_offer_id and borrower_id = auth.uid() and status in ('OFFERED', 'TERMS_REQUESTED') for update;
    if not found then raise exception 'Offer is no longer available'; end if;
    update public.loan_offers set status = 'DECLINED' where id = p_offer_id;
    update public.wallets set locked_balance = greatest(0, locked_balance - v_offer.amount), updated_at = now() where user_id = v_offer.lender_id;
    insert into public.notifications (user_id, type, title, message, related_entity_id)
    values (v_offer.lender_id, 'LOAN_OFFER_DECLINED', 'Offer declined', 'The borrower declined your support offer.', p_offer_id);
end;
$$;

create or replace function public.repay_loan(p_loan_id uuid, p_amount bigint)
returns void language plpgsql security definer set search_path = public, auth as $$
declare
    v_loan public.loans%rowtype;
    v_wallet public.wallets%rowtype;
    v_borrower public.profiles%rowtype;
    v_amount bigint;
    v_principal bigint;
    v_completed boolean;
begin
    select * into v_loan from public.loans where id = p_loan_id and borrower_id = auth.uid() for update;
    if not found or v_loan.status not in ('ACTIVE', 'DUE_SOON', 'OVERDUE', 'GRACE_PERIOD') then raise exception 'Loan cannot be repaid'; end if;
    v_amount := least(p_amount, v_loan.total_repayment - v_loan.amount_paid);
    if v_amount <= 0 then raise exception 'Payment amount must be positive'; end if;
    select * into v_wallet from public.wallets where user_id = v_loan.borrower_id for update;
    if v_wallet.balance < v_amount then raise exception 'Insufficient wallet balance'; end if;
    select * into v_borrower from public.profiles where id = v_loan.borrower_id for update;
    v_principal := least(v_amount, v_loan.principal - v_loan.principal_paid);
    v_completed := v_loan.amount_paid + v_amount >= v_loan.total_repayment;
    update public.wallets set balance = balance - v_amount, updated_at = now() where user_id = v_loan.borrower_id;
    update public.wallets set balance = balance + v_amount, updated_at = now() where user_id = v_loan.lender_id;
    update public.loans set amount_paid = amount_paid + v_amount, principal_paid = principal_paid + v_principal,
        status = case when v_completed then 'REPAID' else status end,
        completed_at = case when v_completed then now() else completed_at end where id = p_loan_id;
    insert into public.loan_payments (loan_id, payer_id, amount, payment_type)
    values (p_loan_id, auth.uid(), v_amount,
        case when v_principal = 0 then 'INTEREST' when v_principal = v_amount then 'PRINCIPAL' else 'MIXED' end);
    insert into public.transactions (type, sender_id, recipient_id, amount, loan_id, description)
    values ('LOAN_REPAYMENT', v_loan.borrower_id, v_loan.lender_id, v_amount, p_loan_id, 'Loan repayment');
    update public.profiles set available_limit = least(borrowing_limit, available_limit + v_principal) where id = v_loan.borrower_id;
    insert into public.notifications (user_id, type, title, message, related_entity_id) values
        (v_loan.borrower_id, case when v_completed then 'LOAN_REPAID' else 'LOAN_PAYMENT' end,
         case when v_completed then 'Loan repaid' else 'Repayment recorded' end, format('KSh %s repayment recorded.', v_amount), p_loan_id),
        (v_loan.lender_id, 'LOAN_REPAID', 'Repayment received', format('%s paid KSh %s.', v_borrower.full_name, v_amount), p_loan_id);
    if v_completed then
        perform public.record_trust_event(v_loan.borrower_id, 'LOAN_COMPLETED', 1, 'Repaid a loan in full.', p_loan_id);
        perform public.refresh_profile_limit(v_loan.borrower_id);
    end if;
end;
$$;

create or replace function public.mark_loan_overdue(p_loan_id uuid)
returns void language plpgsql security definer set search_path = public as $$
declare v_loan public.loans%rowtype; v_borrower public.profiles%rowtype;
begin
    select * into v_loan from public.loans where id = p_loan_id for update;
    if not found or v_loan.status not in ('ACTIVE', 'DUE_SOON') or v_loan.due_date > now() then return; end if;
    update public.loans set status = 'OVERDUE' where id = p_loan_id;
    update public.profiles set borrowing_frozen = true where id = v_loan.borrower_id returning * into v_borrower;
    insert into public.notifications (user_id, type, title, message, related_entity_id) values
        (v_loan.borrower_id, 'LOAN_OVERDUE', 'Loan overdue', 'Your loan is overdue; new borrowing is frozen.', p_loan_id),
        (v_loan.lender_id, 'LOAN_OVERDUE', 'Loan overdue', format('%s has an overdue loan.', v_borrower.full_name), p_loan_id);
    perform public.record_trust_event(v_loan.borrower_id, 'OVERDUE', -40, 'A loan became overdue.', p_loan_id);
    perform public.refresh_profile_limit(v_loan.borrower_id);
end;
$$;

create or replace function public.grant_grace_period(p_loan_id uuid, p_extra_days integer default 7)
returns void language plpgsql security definer set search_path = public, auth as $$
declare v_loan public.loans%rowtype;
begin
    select * into v_loan from public.loans where id = p_loan_id and lender_id = auth.uid() and status = 'OVERDUE' for update;
    if not found or p_extra_days not between 1 and 30 then raise exception 'Grace period unavailable'; end if;
    update public.loans set status = 'GRACE_PERIOD', due_date = greatest(due_date, now()) + make_interval(days => p_extra_days) where id = p_loan_id;
        update public.profiles p set borrowing_frozen = exists (
                select 1 from public.loans l where l.borrower_id = p.id and l.id <> p_loan_id
                    and l.status in ('OVERDUE', 'DEFAULTED')
        ) where p.id = v_loan.borrower_id;
    insert into public.notifications (user_id, type, title, message, related_entity_id)
    values (v_loan.borrower_id, 'GRACE_PERIOD_GRANTED', 'Grace period granted', format('Your lender granted %s additional days.', p_extra_days), p_loan_id);
    perform public.record_trust_event(v_loan.borrower_id, 'GRACE_PERIOD', 0, 'A lender granted a grace period.', p_loan_id);
end;
$$;

create or replace function public.mark_recovered_externally(p_loan_id uuid)
returns void language plpgsql security definer set search_path = public, auth as $$
declare v_loan public.loans%rowtype;
begin
    select * into v_loan from public.loans where id = p_loan_id and lender_id = auth.uid() and status in ('OVERDUE', 'GRACE_PERIOD') for update;
    if not found then raise exception 'Loan cannot be marked recovered'; end if;
    update public.loans set status = 'RECOVERED_EXTERNALLY', completed_at = now() where id = p_loan_id;
        update public.profiles p set borrowing_frozen = exists (
                select 1 from public.loans l where l.borrower_id = p.id and l.id <> p_loan_id
                    and l.status in ('OVERDUE', 'DEFAULTED')
        ) where p.id = v_loan.borrower_id;
    insert into public.notifications (user_id, type, title, message, related_entity_id)
    values (v_loan.borrower_id, 'RECOVERED_EXTERNALLY', 'Loan recovered externally', 'Your lender marked this loan as settled outside Kash.ke.', p_loan_id);
    perform public.record_trust_event(v_loan.borrower_id, 'EXTERNAL_RECOVERY', -20, 'A loan was settled outside Kash.ke.', p_loan_id);
    perform public.refresh_profile_limit(v_loan.borrower_id);
end;
$$;
