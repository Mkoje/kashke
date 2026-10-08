-- Kash Trust v2: points are private rewards; reliability is derived from loans.
-- Existing balances/limits/points are preserved; only future events use v2.
alter table public.profiles alter column trust_score set default 0;
alter table public.profiles add column if not exists reliability_score integer;
alter table public.profiles add column if not exists reliability_sample_size integer not null default 0;
alter table public.loans add column if not exists original_due_date timestamptz;
update public.loans set original_due_date = due_date where original_due_date is null;
alter table public.loans alter column original_due_date set not null;

create table if not exists public.kash_trust_state (
    user_id uuid primary key references public.profiles(id) on delete cascade,
    cycle_peak integer not null default 0 check (cycle_peak between 0 and 1000),
    clean_cycle boolean not null default true,
    decline_cycles integer not null default 0 check (decline_cycles >= 0),
    policy_started_at timestamptz not null default now()
);
create table if not exists public.kash_trust_daily_penalties (
    user_id uuid not null references public.profiles(id) on delete cascade,
    penalty_date date not null,
    related_loan_id uuid not null references public.loans(id),
    limit_before integer not null,
    limit_after integer not null,
    created_at timestamptz not null default now(),
    primary key(user_id, penalty_date)
);
insert into public.kash_trust_state(user_id,cycle_peak,clean_cycle)
select id,trust_score,trust_score=0 from public.profiles on conflict do nothing;
alter table public.kash_trust_state enable row level security;
alter table public.kash_trust_daily_penalties enable row level security;
revoke all on public.kash_trust_state,public.kash_trust_daily_penalties from anon,authenticated;
drop policy if exists "Users can view their trust events" on public.trust_events;
create policy "Users can view their trust events" on public.trust_events
for select to authenticated using (user_id = auth.uid());

-- Start the clock at registration, even if the borrower never opens the app.
create or replace function public.initialize_kash_trust_state()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
    insert into public.kash_trust_state(user_id,cycle_peak,clean_cycle)
    values(new.id,new.trust_score,new.trust_score=0) on conflict do nothing;
    return new;
end;
$$;
revoke all on function public.initialize_kash_trust_state() from public,anon,authenticated;
drop trigger if exists initialize_kash_trust_state on public.profiles;
create trigger initialize_kash_trust_state after insert on public.profiles
for each row execute function public.initialize_kash_trust_state();

create or replace function public.preserve_original_deadline()
returns trigger language plpgsql set search_path = '' as $$
begin
    if TG_OP = 'INSERT' then new.original_due_date := new.due_date;
    else new.original_due_date := old.original_due_date; end if;
    return new;
end;
$$;
drop trigger if exists preserve_original_deadline on public.loans;
create trigger preserve_original_deadline before insert or update on public.loans
for each row execute function public.preserve_original_deadline();

-- A completed agreement counts once, even when paid in many instalments.
-- Approved grace is the effective deadline. Future unresolved debts are excluded.
create or replace function public.loan_reliability(p_user_id uuid)
returns table(score integer, sample_size integer)
language sql stable security definer set search_path = '' as $$
    select trunc(100.0 * count(*) filter(where status='REPAID' and completed_at <= due_date)
        / nullif(count(*),0))::integer, count(*)::integer
    from public.loans where borrower_id=p_user_id
        and (status in ('REPAID','RECOVERED_EXTERNALLY') or due_date <= now());
$$;

-- Never recalculate the total limit from points. Only recover available capacity.
create or replace function public.refresh_profile_limit(p_user_id uuid)
returns void language plpgsql security definer set search_path = '' as $$
begin
    update public.profiles p set available_limit=greatest(0, p.borrowing_limit -
        (select coalesce(sum(principal-principal_paid),0) from public.loans
         where borrower_id=p.id and status not in ('REPAID','RECOVERED_EXTERNALLY')))
    where p.id=p_user_id;
end;
$$;

create or replace function public.record_trust_event(
    p_user_id uuid,p_event_type text,p_points integer,p_description text,p_loan_id uuid default null
)
returns uuid language plpgsql security definer set search_path = '' as $$
declare p public.profiles%rowtype; s public.kash_trust_state%rowtype;
    v_id uuid; v_points integer; v_score integer; v_limit integer; v_rate integer;
begin
    -- Settle pending deductions before a reward can claim a clean milestone.
    if p_event_type in ('COMMUNITY_SUPPORT','LOAN_COMPLETED') then
        perform public.apply_daily_trust_penalties(p_user_id);
    end if;
    select * into strict p from public.profiles where id=p_user_id for update;
    insert into public.kash_trust_state(user_id,cycle_peak,clean_cycle)
    values(p.id,p.trust_score,p.trust_score=0) on conflict do nothing;
    select * into strict s from public.kash_trust_state where user_id=p.id for update;
    -- Old RPC callers remain compatible, but no longer impose obsolete penalties.
    v_points := case p_event_type when 'COMMUNITY_SUPPORT' then 2
        when 'LOAN_COMPLETED' then (select case
            when completed_at <= original_due_date then 2
            when completed_at <= due_date then 1 else 0 end from public.loans where id=p_loan_id)
        when 'DAILY_DELAY' then -10 else 0 end;
    v_points := coalesce(v_points,0);
    if p_event_type in ('COMMUNITY_SUPPORT','LOAN_COMPLETED') and exists(
        select 1 from public.trust_events where user_id=p.id and event_type=p_event_type
            and related_loan_id=p_loan_id) then return null; end if;
    insert into public.trust_events(user_id,event_type,points,description,related_loan_id)
    values(p.id,p_event_type,v_points,p_description,p_loan_id) returning id into v_id;
    v_score := p.trust_score + v_points; v_limit := p.borrowing_limit;
    if v_points < 0 then
        s.clean_cycle := false;
        -- Exact compound daily multiplier: seven days give 0.98 before
        -- per-day whole-shilling truncation. Daily loss is ~0.288194%.
        v_limit := trunc(v_limit::numeric * power(0.98::numeric,1.0/7))::integer;
        if p.trust_score > 0 and v_score <= 0 then
            v_rate := case when s.cycle_peak=1000 then 40 when s.cycle_peak>900 then 35
                when s.cycle_peak>800 then 30 when s.cycle_peak>700 then 25
                when s.cycle_peak>600 then 15 when s.cycle_peak>500 then 10 else 0 end;
            if v_rate > 0 then
                v_rate := least(100,v_rate + 5*s.decline_cycles);
                v_limit := trunc(v_limit::numeric * (1-v_rate/100.0))::integer;
                s.decline_cycles := s.decline_cycles + 1;
                insert into public.notifications(user_id,type,title,message,related_entity_id)
                values(p.id,'LIMIT_DECREASED','Kash Trust decline cycle',
                    format('%s, your Kash Trust fell from a peak of %s to zero. An additional %s%% reduction leaves your borrowing limit at KSh %s.',p.full_name,s.cycle_peak,v_rate,v_limit),p_loan_id);
            end if;
            s.cycle_peak := 0;
        end if;
        -- Exactly zero remains zero. The next negative step wraps to 990.
        if v_score < 0 then v_score := 1000 + v_score; s.cycle_peak := 1000; end if;
    elsif v_points > 0 then
        if v_score >= 1000 then
            v_limit := least(2147483647,trunc(v_limit::numeric*1.02))::integer;
            if s.clean_cycle then
                v_limit := least(2147483647,trunc(v_limit::numeric*1.02))::integer;
            end if;
            insert into public.notifications(user_id,type,title,message,related_entity_id)
            values(p.id,'LIMIT_INCREASED','Kash Trust milestone',format(
                '%s, you reached 1000 Kash Trust points. Your limit increased by 2%%%s to KSh %s.',
                p.full_name,case when s.clean_cycle then ' plus a further 2% for a deduction-free cycle' else '' end,v_limit),p_loan_id);
            v_score := v_score-1000; s.cycle_peak := v_score; s.clean_cycle := true;
        else s.cycle_peak := greatest(s.cycle_peak,v_score); end if;
    end if;
    update public.profiles set trust_score=v_score,borrowing_limit=v_limit,
        available_limit=least(available_limit,v_limit) where id=p.id;
    update public.kash_trust_state set cycle_peak=s.cycle_peak,clean_cycle=s.clean_cycle,
        decline_cycles=s.decline_cycles where user_id=p.id;
    perform public.refresh_profile_limit(p.id);
    if v_points <> 0 then
        insert into public.notifications(user_id,type,title,message,related_entity_id)
        values(p.id,case when v_points<0 then 'TRUST_DECREASED' else 'TRUST_INCREASED' end,
            'Kash Trust activity',format('%s, %s Kash Trust points: %s Your points are %s/1000; borrowing limit KSh %s.',
                p.full_name,v_points,p_description,v_score,v_limit),p_loan_id);
    end if;
    return v_id;
end;
$$;

-- Catch up one account, once per Nairobi calendar day, even after days offline.
-- No retroactive v2 penalties before deployment; grace pauses daily deductions.
create or replace function public.apply_daily_trust_penalties(p_user_id uuid)
returns void language plpgsql security definer set search_path = '' as $$
declare d date; first_day date; last_day date; loan_id uuid; before_limit integer; after_limit integer;
begin
    perform 1 from public.profiles where id=p_user_id for update;
    insert into public.kash_trust_state(user_id,cycle_peak,clean_cycle)
    select id,trust_score,trust_score=0 from public.profiles where id=p_user_id on conflict do nothing;
    select greatest(min((l.due_date at time zone 'Africa/Nairobi')::date+1),
        (s.policy_started_at at time zone 'Africa/Nairobi')::date)
    into first_day from public.loans l cross join public.kash_trust_state s
    where l.borrower_id=p_user_id and s.user_id=p_user_id
        and l.status not in ('REPAID','RECOVERED_EXTERNALLY') and l.due_date<now()
    group by s.policy_started_at;
    last_day := (now() at time zone 'Africa/Nairobi')::date;
    if first_day is null or first_day>last_day then return; end if;
    -- Do not rescan an entire old debt on every five-minute Cron invocation.
    first_day := greatest(first_day,(select max(penalty_date)+1
        from public.kash_trust_daily_penalties where user_id=p_user_id));
    if first_day>last_day then return; end if;
    for d in select generate_series(first_day::timestamp,last_day::timestamp,interval '1 day')::date loop
        select id into loan_id from public.loans where borrower_id=p_user_id
            and status not in ('REPAID','RECOVERED_EXTERNALLY')
            and (due_date at time zone 'Africa/Nairobi')::date<d order by due_date,id limit 1;
        if loan_id is null then continue; end if;
        select borrowing_limit into before_limit from public.profiles where id=p_user_id;
        insert into public.kash_trust_daily_penalties(user_id,penalty_date,related_loan_id,limit_before,limit_after)
        values(p_user_id,d,loan_id,before_limit,before_limit) on conflict do nothing;
        if not found then continue; end if;
        perform public.record_trust_event(p_user_id,'DAILY_DELAY',-10,
            format('Payment delayed on %s; daily limit reduction approximately 0.288194%%.',d),loan_id);
        select borrowing_limit into after_limit from public.profiles where id=p_user_id;
        update public.kash_trust_daily_penalties set limit_after=after_limit
        where user_id=p_user_id and penalty_date=d;
    end loop;
end;
$$;

-- Existing authorized RPCs retain wallet locking, receipts and offer closure.
create or replace function public.refresh_profile_statistics(p_user_id uuid)
returns void language plpgsql security definer set search_path = '' as $$
begin
    update public.profiles p set
        completed_loans = (select count(*) from public.loans where borrower_id = p.id and status = 'REPAID'),
        overdue_loans = (select count(*) from public.loans where borrower_id = p.id and status in ('OVERDUE','DEFAULTED')),
        active_loans = (select count(*) from public.loans where borrower_id = p.id and status not in ('REPAID','RECOVERED_EXTERNALLY')),
        total_borrowed = (select coalesce(sum(principal),0) from public.loans where borrower_id = p.id),
        total_repaid = (select coalesce(sum(amount_paid),0) from public.loans where borrower_id = p.id),
        total_supported = (select coalesce(sum(principal),0) from public.loans where lender_id = p.id),
        total_earned_from_interest = (select coalesce(sum(amount_paid - principal_paid),0) from public.loans where lender_id = p.id),
        repayment_ratio = coalesce((select score from public.loan_reliability(p.id)),0),
        reliability_score = (select score from public.loan_reliability(p.id)),
        reliability_sample_size = (select sample_size from public.loan_reliability(p.id))
    where p.id = p_user_id;
end;
$$;
create or replace function public.maintain_account_data(p_user_id uuid default null)
returns void language plpgsql security definer set search_path = '' as $$
declare r public.loan_requests%rowtype; l public.loans%rowtype; u uuid;
begin
    for u in select id from public.profiles where p_user_id is null or id=p_user_id
        or id in (select borrower_id from public.loans where lender_id=p_user_id) order by id loop
        perform public.apply_daily_trust_penalties(u);
        perform public.refresh_profile_statistics(u);
    end loop;
    for r in select * from public.loan_requests where status in ('REQUESTED','PARTIALLY_FUNDED') and expires_at <= now()
        and (p_user_id is null or borrower_id = p_user_id or exists(select 1 from public.loan_offers o where o.loan_request_id = loan_requests.id and o.lender_id = p_user_id))
        order by id for update loop
        perform public.release_request_offers(r.id);
        update public.loan_requests set status = 'EXPIRED' where id = r.id;
    end loop;
    for l in select * from public.loans where status in ('ACTIVE','DUE_SOON','GRACE_PERIOD')
        and (p_user_id is null or borrower_id = p_user_id or lender_id = p_user_id) order by id for update loop
        if l.due_date <= now() then perform public.mark_loan_overdue(l.id);
        elsif l.status = 'ACTIVE' and l.due_date <= now() + interval '3 days' then
            update public.loans set status = 'DUE_SOON' where id = l.id;
            insert into public.notifications(user_id,type,title,message,related_entity_id)
            values(l.borrower_id,'LOAN_DUE_SOON','Loan due soon',format('%s, your repayment to %s is due within three days.', (select full_name from public.profiles where id=l.borrower_id),(select full_name from public.profiles where id=l.lender_id)),l.id);
        end if;
    end loop;
end;
$$;
create or replace function public.mark_loan_overdue(p_loan_id uuid)
returns void language plpgsql security definer set search_path = '' as $$
declare l public.loans%rowtype;
begin
    select * into l from public.loans where id = p_loan_id for update;
    if not found or l.status not in ('ACTIVE','DUE_SOON','GRACE_PERIOD') or l.due_date > now() then return; end if;
    update public.loans set status = 'OVERDUE' where id = l.id;
    update public.profiles set borrowing_frozen = true where id = l.borrower_id;
    insert into public.notifications(user_id,type,title,message,related_entity_id) values
        (l.borrower_id,'LOAN_OVERDUE','Loan overdue',format('%s, your repayment to %s is overdue; new borrowing is frozen.',(select full_name from public.profiles where id=l.borrower_id),(select full_name from public.profiles where id=l.lender_id)),l.id),
        (l.lender_id,'LOAN_OVERDUE','Loan overdue',format('%s has an overdue repayment.',(select full_name from public.profiles where id=l.borrower_id)),l.id);
    perform public.record_trust_event(l.borrower_id,'OVERDUE',-40,'A loan became overdue.',l.id);
end;
$$;
create or replace function public.repay_loan(p_loan_id uuid, p_amount bigint)
returns void language plpgsql security definer set search_path = '' as $$
declare
    v_loan public.loans%rowtype;
    v_wallet public.wallets%rowtype;
    v_borrower public.profiles%rowtype;
    v_amount bigint;
    v_principal bigint;
    v_completed boolean;
begin
    if auth.uid() is null then raise exception 'Sign in first' using errcode = '28000'; end if;
    select * into v_loan from public.loans where id = p_loan_id and borrower_id = auth.uid() for update;
    if not found or v_loan.status not in ('ACTIVE', 'DUE_SOON', 'OVERDUE', 'GRACE_PERIOD') then raise exception 'Loan cannot be repaid'; end if;
    perform public.apply_daily_trust_penalties(v_loan.borrower_id);
    perform 1 from public.profiles where id in (v_loan.borrower_id,v_loan.lender_id) order by id for update;
    perform 1 from public.wallets where user_id in (v_loan.borrower_id,v_loan.lender_id) order by user_id for update;
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
    insert into public.transactions (type, sender_id, recipient_id, amount, loan_id, description, interest)
    values ('LOAN_REPAYMENT', v_loan.borrower_id, v_loan.lender_id, v_amount, p_loan_id, format('%s repaid %s.',v_borrower.full_name,(select full_name from public.profiles where id=v_loan.lender_id)), v_amount - v_principal);
    update public.profiles set available_limit = least(borrowing_limit, available_limit + v_principal) where id = v_loan.borrower_id;
    insert into public.notifications (user_id, type, title, message, related_entity_id) values
        (v_loan.borrower_id, case when v_completed then 'LOAN_REPAID' else 'LOAN_PAYMENT' end,
         case when v_completed then 'Loan repaid' else 'Repayment recorded' end, format('%s, KSh %s was repaid to %s.',v_borrower.full_name,v_amount,(select full_name from public.profiles where id=v_loan.lender_id)), p_loan_id),
        (v_loan.lender_id, 'LOAN_REPAID', 'Repayment received', format('%s %s KSh %s.',v_borrower.full_name,case when v_completed then 'completed repayment of' else 'paid' end,v_amount), p_loan_id);
    update public.profiles p set borrowing_frozen = exists(select 1 from public.loans l where l.borrower_id=p.id and l.status in ('OVERDUE','DEFAULTED')) where p.id=v_loan.borrower_id;
    if v_completed then
        perform public.record_trust_event(v_loan.borrower_id, 'LOAN_COMPLETED', 1, 'Repaid a loan in full.', p_loan_id);
        perform public.refresh_profile_limit(v_loan.borrower_id);
    end if;
end;
$$;
create or replace function public.grant_grace_period(p_loan_id uuid, p_extra_days integer default 7)
returns void language plpgsql security definer set search_path = '' as $$
declare v_loan public.loans%rowtype;
begin
    if auth.uid() is null then raise exception 'Sign in first' using errcode = '28000'; end if;
    select * into v_loan from public.loans where id = p_loan_id and lender_id = auth.uid() and status = 'OVERDUE' for update;
    if not found or p_extra_days not between 1 and 30 then raise exception 'Grace period unavailable'; end if;
    perform public.apply_daily_trust_penalties(v_loan.borrower_id);
    update public.loans set status = 'GRACE_PERIOD', due_date = greatest(due_date, now()) + make_interval(days => p_extra_days) where id = p_loan_id;
        update public.profiles p set borrowing_frozen = exists (
                select 1 from public.loans l where l.borrower_id = p.id and l.id <> p_loan_id
                    and l.status in ('OVERDUE', 'DEFAULTED')
        ) where p.id = v_loan.borrower_id;
    insert into public.notifications (user_id, type, title, message, related_entity_id)
    values (v_loan.borrower_id, 'GRACE_PERIOD_GRANTED', 'Grace period granted', format('%s granted you %s additional days.',(select full_name from public.profiles where id=v_loan.lender_id),p_extra_days), p_loan_id);
    perform public.record_trust_event(v_loan.borrower_id, 'GRACE_PERIOD', 0, 'A lender granted a grace period.', p_loan_id);
end;
$$;
create or replace function public.mark_recovered_externally(p_loan_id uuid)
returns void language plpgsql security definer set search_path = '' as $$
declare v_loan public.loans%rowtype;
begin
    if auth.uid() is null then raise exception 'Sign in first' using errcode = '28000'; end if;
    select * into v_loan from public.loans where id = p_loan_id and lender_id = auth.uid() and status in ('OVERDUE', 'GRACE_PERIOD') for update;
    if not found then raise exception 'Loan cannot be marked recovered'; end if;
    perform public.apply_daily_trust_penalties(v_loan.borrower_id);
    update public.loans set status = 'RECOVERED_EXTERNALLY', completed_at = now() where id = p_loan_id;
        update public.profiles p set borrowing_frozen = exists (
                select 1 from public.loans l where l.borrower_id = p.id and l.id <> p_loan_id
                    and l.status in ('OVERDUE', 'DEFAULTED')
        ) where p.id = v_loan.borrower_id;
    insert into public.notifications (user_id, type, title, message, related_entity_id)
    values (v_loan.borrower_id, 'RECOVERED_EXTERNALLY', 'Loan recovered externally', format('%s marked your repayment as settled outside Kash.ke.',(select full_name from public.profiles where id=v_loan.lender_id)), p_loan_id);
    perform public.record_trust_event(v_loan.borrower_id, 'EXTERNAL_RECOVERY', 0, 'A loan was settled outside Kash.ke.', p_loan_id);
    perform public.refresh_profile_limit(v_loan.borrower_id);
end;
$$;
create or replace function public.send_connection_request(p_recipient_id uuid)
returns uuid language plpgsql security definer set search_path = '' as $$
declare v_id uuid;
begin
    if auth.uid() is null then raise exception 'Sign in first' using errcode = '28000'; end if;
    if p_recipient_id = auth.uid() then raise exception 'Cannot connect to yourself'; end if;
    insert into public.connections (requester_id, recipient_id) values (auth.uid(), p_recipient_id) returning id into v_id;
    insert into public.notifications (user_id, type, title, message, related_entity_id)
    values (p_recipient_id, 'CONNECTION_REQUEST', 'Connection request', format('%s sent you a connection request.',(select full_name from public.profiles where id=auth.uid())), v_id);
    return v_id;
end;
$$;
create or replace function public.respond_connection_request(p_connection_id uuid, p_accept boolean)
returns void language plpgsql security definer set search_path = '' as $$
declare v_connection public.connections%rowtype;
begin
    if auth.uid() is null then raise exception 'Sign in first' using errcode = '28000'; end if;
    select * into v_connection from public.connections
     where id = p_connection_id and recipient_id = auth.uid() and status = 'PENDING' for update;
    if not found then raise exception 'Pending connection request not found'; end if;
    update public.connections set status = case when p_accept then 'ACCEPTED' else 'REJECTED' end,
        responded_at = now(), accepted_at = case when p_accept then now() else null end where id = p_connection_id;
    insert into public.notifications (user_id, type, title, message, related_entity_id)
    values (v_connection.requester_id,
        case when p_accept then 'CONNECTION_ACCEPTED' else 'CONNECTION_REJECTED' end,
        case when p_accept then 'Connection accepted' else 'Connection declined' end,
        case when p_accept then format('%s accepted your connection request.',(select full_name from public.profiles where id=auth.uid())) else format('%s declined your connection request.',(select full_name from public.profiles where id=auth.uid())) end,
        p_connection_id);
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
create or replace function public.request_loan_offer_terms(p_offer_id uuid, p_interest_rate numeric, p_period_days integer)
returns void language plpgsql security definer set search_path = '' as $$
declare v_offer public.loan_offers%rowtype;
begin
    if auth.uid() is null then raise exception 'Sign in first' using errcode = '28000'; end if;
    perform 1 from public.loan_requests where id = (select loan_request_id from public.loan_offers where id = p_offer_id) for update;
    select * into v_offer from public.loan_offers where id = p_offer_id and borrower_id = auth.uid() and status = 'OFFERED' for update;
    if not found then raise exception 'Offer is no longer available'; end if;
    if p_interest_rate not between 0 and v_offer.interest_rate or p_period_days not between 7 and 365 then
        raise exception 'Requested terms must use equal-or-lower interest and a 7-365 day period';
    end if;
    update public.loan_offers set status = 'TERMS_REQUESTED', requested_interest_rate = p_interest_rate,
        requested_repayment_period_days = p_period_days where id = p_offer_id;
    insert into public.notifications (user_id, type, title, message, related_entity_id)
    values (v_offer.lender_id, 'LOAN_OFFER_TERMS_REQUESTED', 'Terms requested',
        format('%s requested %s%% for %s days.',(select full_name from public.profiles where id=auth.uid()),p_interest_rate,p_period_days), p_offer_id);
end;
$$;
create or replace function public.respond_loan_offer_terms(p_offer_id uuid, p_accept_terms boolean)
returns void language plpgsql security definer set search_path = '' as $$
declare
    v_offer public.loan_offers%rowtype;
    v_rate numeric;
    v_days integer;
begin
    if auth.uid() is null then raise exception 'Sign in first' using errcode = '28000'; end if;
    perform 1 from public.loan_requests where id = (select loan_request_id from public.loan_offers where id = p_offer_id) for update;
    select * into v_offer from public.loan_offers where id = p_offer_id and lender_id = auth.uid() and status = 'TERMS_REQUESTED' for update;
    if not found then raise exception 'Terms request is no longer available'; end if;
    v_rate := case when p_accept_terms then v_offer.requested_interest_rate else v_offer.interest_rate end;
    v_days := case when p_accept_terms then v_offer.requested_repayment_period_days else v_offer.repayment_period_days end;
    update public.loan_offers set status = 'OFFERED', interest_rate = v_rate, repayment_period_days = v_days,
        total_repayment = amount + round(amount * v_rate * v_days / 36500.0)::bigint,
        requested_interest_rate = null, requested_repayment_period_days = null where id = p_offer_id;
    insert into public.notifications (user_id, type, title, message, related_entity_id)
    values (v_offer.borrower_id, 'LOAN_OFFER_UPDATED', 'Offer terms updated',
        format('%s responded with %s%% interest for %s days.',(select full_name from public.profiles where id=auth.uid()),v_rate,v_days), p_offer_id);
end;
$$;
create or replace function public.decline_loan_offer(p_offer_id uuid)
returns void language plpgsql security definer set search_path = '' as $$
declare v_offer public.loan_offers%rowtype;
begin
    if auth.uid() is null then raise exception 'Sign in first' using errcode = '28000'; end if;
    perform 1 from public.loan_requests where id = (select loan_request_id from public.loan_offers where id = p_offer_id) for update;
    select * into v_offer from public.loan_offers where id = p_offer_id and borrower_id = auth.uid() and status in ('OFFERED', 'TERMS_REQUESTED') for update;
    if not found then raise exception 'Offer is no longer available'; end if;
    update public.loan_offers set status = 'DECLINED' where id = p_offer_id;
    update public.wallets set locked_balance = greatest(0, locked_balance - v_offer.amount), updated_at = now() where user_id = v_offer.lender_id;
    insert into public.notifications (user_id, type, title, message, related_entity_id)
    values (v_offer.lender_id, 'LOAN_OFFER_DECLINED', 'Offer declined', format('%s declined your support offer.',(select full_name from public.profiles where id=auth.uid())), p_offer_id);
end;
$$;
drop function if exists public.public_profile_directory(text, text[], uuid[]);
drop function if exists public.public_profile_directory(text, text[], text[]);
drop function if exists public.public_profile_directory(text, jsonb);
drop function if exists public.public_profile_directory(text, text[]);

drop function if exists public.public_profile_directory();

create function public.public_profile_directory(
    p_query text default '',
    p_phone_numbers text[] default array[]::text[],
    p_profile_ids uuid[] default array[]::uuid[]
)
returns table (
    id uuid,
    full_name text,
    username text,
    phone text,
    bio text,
    location text,
    profile_image text,
    reliability_score integer,
    reliability_sample_size integer,
    account_status text,
    created_at timestamptz,
    matched_phone text
)
language plpgsql
stable
security definer
set search_path = public, pg_temp
as $$
declare
    v_query text := lower(btrim(coalesce(p_query, '')));
    v_query_digits text := regexp_replace(coalesce(p_query, ''), '[^0-9]', '', 'g');
    v_phone_numbers text[] := coalesce(p_phone_numbers, array[]::text[]);
    v_profile_ids uuid[] := coalesce(p_profile_ids, array[]::uuid[]);
begin
    if auth.uid() is null then
        raise exception 'Sign in first' using errcode = '28000';
    end if;

    if length(coalesce(p_query, '')) > 80
        or cardinality(v_phone_numbers) > 100
        or cardinality(v_profile_ids) > 100 then
        raise exception 'Directory request is too large';
    end if;

    return query
    select
        profile.id,
        profile.full_name,
        profile.username,
        ''::text,
        profile.bio,
        profile.location,
        profile.profile_image,
        (select score from public.loan_reliability(profile.id)),
        (select sample_size from public.loan_reliability(profile.id)),
        profile.account_status,
        profile.created_at,
        matched.phone
    from public.profiles as profile
    left join lateral (
        select public.normalize_ke_phone(input.phone) as phone
        from unnest(v_phone_numbers) as input(phone)
        where public.normalize_ke_phone(input.phone) = public.normalize_ke_phone(profile.phone)
        order by public.normalize_ke_phone(input.phone)
        limit 1
    ) as matched on true
    where
        (
            v_query = '' and cardinality(v_phone_numbers) = 0 and cardinality(v_profile_ids) = 0
        )
        or (
            v_query <> '' and (
                strpos(lower(profile.full_name), v_query) > 0
                or strpos(lower(profile.username), v_query) > 0
                or (
                    length(v_query_digits) >= 4
                    and strpos(public.normalize_ke_phone(profile.phone), v_query_digits) > 0
                )
            )
        )
        or matched.phone is not null
        or profile.id = any(v_profile_ids)
    order by profile.full_name, profile.id;
end;
$$;

revoke all on function public.normalize_ke_phone(text) from public, anon, authenticated;
revoke all on function public.public_profile_directory(text, text[], uuid[]) from public, anon, authenticated;
grant execute on function public.public_profile_directory(text, text[], uuid[]) to authenticated;

notify pgrst, 'reload schema';


-- All new helpers are internal. Existing RPC grants remain as before.
-- Above-limit disclosures must not leak private milestone points either.
drop function if exists public.get_direct_request_borrower_summary(uuid);
create function public.get_direct_request_borrower_summary(p_request_id uuid)
returns table(borrower_id uuid,full_name text,available_limit integer)
language plpgsql security definer set search_path = '' as $$
declare r public.loan_requests%rowtype;
begin
    if auth.uid() is null then raise exception 'Sign in first' using errcode='28000'; end if;
    select * into r from public.loan_requests where id=p_request_id
        and direct_request_to=auth.uid() and above_limit_requested;
    if not found then raise exception 'This is not a direct above-limit request assigned to you'; end if;
    return query select p.id,p.full_name,p.available_limit from public.profiles p where p.id=r.borrower_id;
end;
$$;
revoke all on function public.get_direct_request_borrower_summary(uuid) from public,anon,authenticated;
grant execute on function public.get_direct_request_borrower_summary(uuid) to authenticated;
revoke all on function public.preserve_original_deadline() from public,anon,authenticated;
revoke all on function public.loan_reliability(uuid) from public,anon,authenticated;
revoke all on function public.apply_daily_trust_penalties(uuid) from public,anon,authenticated;
revoke all on function public.record_trust_event(uuid,text,integer,text,uuid) from public,anon,authenticated;
revoke all on function public.repay_loan(uuid,bigint) from public,anon,authenticated;

-- Repair legacy participant wording as notifications are inserted or revisited.
-- Actor identities come from the referenced entity, never client message text.
create or replace function public.name_notification_participants()
returns trigger language plpgsql security definer set search_path = '' as $$
declare first_id uuid; second_id uuid; actor_name text;
begin
    if new.type in ('CONNECTION_REQUEST','CONNECTION_ACCEPTED','CONNECTION_REJECTED') then
        select requester_id,recipient_id into first_id,second_id from public.connections where id=new.related_entity_id;
    elsif new.type in ('LOAN_OFFER','LOAN_OFFER_TERMS_REQUESTED','LOAN_OFFER_UPDATED','LOAN_OFFER_DECLINED') then
        select borrower_id,lender_id into first_id,second_id from public.loan_offers where id=new.related_entity_id;
    elsif new.type in ('DIRECT_REQUEST','NEW_LOAN_REQUEST') then
        select borrower_id,direct_request_to into first_id,second_id from public.loan_requests where id=new.related_entity_id;
    elsif new.type in ('LOAN_ACCEPTED','LOAN_REPAID','LOAN_PAYMENT','LOAN_DUE_SOON','LOAN_OVERDUE','GRACE_PERIOD_GRANTED','RECOVERED_EXTERNALLY') then
        select borrower_id,lender_id into first_id,second_id from public.loans where id=new.related_entity_id;
    else return new; end if;
    select full_name into actor_name from public.profiles
    where id=case when first_id=new.user_id then second_id else first_id end;
    if actor_name is null then return new; end if;
    new.message := replace(replace(replace(replace(replace(new.message,
        'The borrower',actor_name),'A supporter',actor_name),'The supporter',actor_name),
        'Your lender',actor_name),'A loan you supported',actor_name||'''s loan');
    if new.message in ('You received a connection request.','Your connection request was accepted.','Your connection request was declined.') then
        new.message := actor_name || case new.type when 'CONNECTION_REQUEST' then ' sent you a connection request.'
            when 'CONNECTION_ACCEPTED' then ' accepted your connection request.' else ' declined your connection request.' end;
    end if;
    return new;
end;
$$;
revoke all on function public.name_notification_participants() from public,anon,authenticated;
drop trigger if exists name_notification_participants on public.notifications;
create trigger name_notification_participants before insert or update of message on public.notifications
for each row execute function public.name_notification_participants();
-- Preserve read flags, timestamps and references while upgrading old messages.
update public.notifications set message=message
where message ~ '(The borrower|A supporter|The supporter|Your lender|A loan you supported|You received a connection request|Your connection request was)';
do $$ declare u uuid; begin
    for u in select id from public.profiles loop
        perform public.refresh_profile_statistics(u);
        perform public.refresh_profile_limit(u);
    end loop;
end $$;
notify pgrst, 'reload schema';
