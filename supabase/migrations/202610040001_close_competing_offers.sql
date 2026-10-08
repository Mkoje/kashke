-- Close competing offers atomically on every acceptance, including partial funding.
create or replace function public.accept_loan_offer(p_offer_id uuid)
returns uuid language plpgsql security definer set search_path = '' as $$
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
    if auth.uid() is null then raise exception 'Sign in first' using errcode = '28000'; end if;
    perform 1 from public.loan_requests where id = (select loan_request_id from public.loan_offers where id = p_offer_id) for update;
    select * into v_offer from public.loan_offers where id = p_offer_id and status = 'OFFERED' for update;
    if not found then raise exception 'Offer is no longer available'; end if;
    select * into v_request from public.loan_requests where id = v_offer.loan_request_id for update;
    if v_request.borrower_id <> auth.uid() then raise exception 'Only the borrower can accept this offer'; end if;
    -- All money movements lock participant profiles and wallets in UUID order.
    perform 1 from public.profiles where id in (v_request.borrower_id,v_offer.lender_id) order by id for update;
    perform 1 from public.wallets where user_id = v_request.borrower_id or user_id in (
        select lender_id from public.loan_offers where loan_request_id = v_request.id
            and status in ('OFFERED', 'TERMS_REQUESTED')
    ) order by user_id for update;
    if v_request.status not in ('REQUESTED', 'PARTIALLY_FUNDED') or v_request.remaining_amount <= 0 then raise exception 'Request is closed'; end if;
    if v_request.expires_at <= now() then raise exception 'Request expired'; end if;
    v_amount := least(v_offer.amount, v_request.remaining_amount);
    select * into v_borrower from public.profiles where id = v_request.borrower_id for update;
    if v_borrower.borrowing_frozen then raise exception 'Borrowing is frozen'; end if;
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
    -- The request lock serializes acceptance, submission, terms and cancellation.
    -- Release only these offers, preserving reservations on other requests.
    update public.wallets w set locked_balance = w.locked_balance - reserved.amount, updated_at = now()
        from (
            select lender_id, sum(amount) as amount from public.loan_offers
            where loan_request_id = v_request.id and id <> p_offer_id
                and status in ('OFFERED', 'TERMS_REQUESTED')
            group by lender_id
        ) reserved
        where w.user_id = reserved.lender_id;
    with closed as (
        update public.loan_offers set status = 'DECLINED',
            requested_interest_rate = null, requested_repayment_period_days = null
        where loan_request_id = v_request.id and id <> p_offer_id
            and status in ('OFFERED', 'TERMS_REQUESTED')
        returning id, lender_id, amount
    )
    insert into public.notifications (user_id, type, title, message, related_entity_id)
        select lender_id, 'LOAN_OFFER_DECLINED', 'Offer automatically closed',
            format('%s already accepted an offer from another person. Your KSh %s offer has been closed and its reserved funds are now available in your wallet.',
                v_borrower.full_name, amount), id
        from closed;
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

notify pgrst, 'reload schema';
