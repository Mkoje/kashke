-- Forward-only repair: no user rows are dropped or reset.
alter table public.loan_offers add column if not exists borrower_id uuid references public.profiles(id);
update public.loan_offers o set borrower_id = r.borrower_id
from public.loan_requests r where r.id = o.loan_request_id and o.borrower_id is distinct from r.borrower_id;
alter table public.loan_offers alter column borrower_id set not null;

create or replace function public.derive_offer_borrower()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
    select borrower_id into new.borrower_id from public.loan_requests where id = new.loan_request_id;
    if new.borrower_id is null or new.borrower_id = new.lender_id then raise exception 'Invalid offer participants'; end if;
    return new;
end;
$$;
revoke all on function public.derive_offer_borrower() from public, anon, authenticated;
drop trigger if exists derive_offer_borrower on public.loan_offers;
create trigger derive_offer_borrower before insert or update of loan_request_id, borrower_id, lender_id
on public.loan_offers for each row execute function public.derive_offer_borrower();

alter table public.connections add column if not exists responded_at timestamptz;
alter table public.profiles add column if not exists updated_at timestamptz not null default now();
alter table public.loan_requests add column if not exists updated_at timestamptz not null default now();
alter table public.loan_offers add column if not exists updated_at timestamptz not null default now();
alter table public.loans add column if not exists updated_at timestamptz not null default now();
alter table public.transactions add column if not exists interest bigint not null default 0;

-- Aggregates are updated by the database, never trusted from Android.
alter table public.profiles add column if not exists completed_loans integer not null default 0;
alter table public.profiles add column if not exists overdue_loans integer not null default 0;
alter table public.profiles add column if not exists repayment_ratio integer not null default 0;
alter table public.profiles add column if not exists total_borrowed bigint not null default 0;
alter table public.profiles add column if not exists total_repaid bigint not null default 0;
alter table public.profiles add column if not exists total_supported bigint not null default 0;
alter table public.profiles add column if not exists total_earned_from_interest bigint not null default 0;
alter table public.profiles add column if not exists active_loans integer not null default 0;

create or replace function public.touch_updated_at()
returns trigger language plpgsql set search_path = '' as $$
begin new.updated_at = now(); return new; end;
$$;
revoke all on function public.touch_updated_at() from public, anon, authenticated;
do $$ declare t text; begin
    foreach t in array array['profiles','wallets','loan_requests','loan_offers','loans','lender_preferences'] loop
        execute format('drop trigger if exists touch_updated_at on public.%I', t);
        execute format('create trigger touch_updated_at before update on public.%I for each row execute function public.touch_updated_at()', t);
    end loop;
end $$;

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
        repayment_ratio = (select coalesce(round(100.0 * count(*) filter (where status = 'REPAID' and completed_at <= due_date)
            / nullif(count(*) filter (where status = 'REPAID'),0)),0)::integer from public.loans where borrower_id = p.id)
    where p.id = p_user_id;
end;
$$;
revoke all on function public.refresh_profile_statistics(uuid) from public, anon, authenticated;

create or replace function public.update_loan_statistics()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
    perform public.refresh_profile_statistics(least(new.borrower_id,new.lender_id));
    perform public.refresh_profile_statistics(greatest(new.borrower_id,new.lender_id));
    return new;
end;
$$;
revoke all on function public.update_loan_statistics() from public, anon, authenticated;
drop trigger if exists update_loan_statistics on public.loans;
create trigger update_loan_statistics after insert or update on public.loans
for each row execute function public.update_loan_statistics();

-- Legacy accounts may lack registration metadata. A deterministic fallback makes
-- both login repair and Auth triggers safe without overwriting existing profiles.
create or replace function public.provision_confirmed_user(p_user_id uuid)
returns void language plpgsql security definer set search_path = '' as $$
declare v_user auth.users%rowtype; v_username text;
begin
    select * into v_user from auth.users where id = p_user_id for update;
    if not found then raise exception 'Account does not exist'; end if;
    if not exists (select 1 from public.profiles where id = p_user_id) then
        v_username := lower(trim(coalesce(v_user.raw_user_meta_data ->> 'username','')));
        if v_username !~ '^[a-z0-9_]{3,24}$' then
            v_username := 'user_' || left(replace(p_user_id::text,'-',''),19);
        end if;
        perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(v_username,0));
        if exists (select 1 from public.profiles where lower(username) = v_username) then
            raise exception 'That username is already in use.';
        end if;
        insert into public.profiles(id,full_name,username,phone)
        values (p_user_id,coalesce(nullif(trim(v_user.raw_user_meta_data ->> 'full_name'),''),nullif(split_part(v_user.email,'@',1),''),'Member'),
            v_username,coalesce(v_user.raw_user_meta_data ->> 'phone','')) on conflict(id) do nothing;
    end if;
    insert into public.wallets(user_id) values(p_user_id) on conflict(user_id) do nothing;
    insert into public.lender_preferences(user_id) values(p_user_id) on conflict(user_id) do nothing;
end;
$$;
revoke all on function public.provision_confirmed_user(uuid) from public, anon, authenticated;
create or replace function public.ensure_current_profile()
returns void language plpgsql security definer set search_path = '' as $$
begin
    if auth.uid() is null then raise exception 'Sign in first' using errcode = '28000'; end if;
    perform public.provision_confirmed_user(auth.uid());
end;
$$;
revoke all on function public.ensure_current_profile() from public, anon, authenticated;
grant execute on function public.ensure_current_profile() to authenticated;
revoke all on function public.handle_new_auth_user() from public, anon, authenticated;

-- Lock the request before releasing offers, in the same order as offer creation.
create or replace function public.release_request_offers(p_request_id uuid)
returns void language plpgsql security definer set search_path = '' as $$
declare o public.loan_offers%rowtype;
begin
    for o in select * from public.loan_offers where loan_request_id = p_request_id
        and status in ('OFFERED','TERMS_REQUESTED') order by lender_id,id for update loop
        update public.wallets set locked_balance = locked_balance - o.amount where user_id = o.lender_id;
        update public.loan_offers set status = 'EXPIRED' where id = o.id;
    end loop;
end;
$$;
revoke all on function public.release_request_offers(uuid) from public, anon, authenticated;
create or replace function public.cancel_loan_request(p_request_id uuid)
returns void language plpgsql security definer set search_path = '' as $$
begin
    if auth.uid() is null then raise exception 'Sign in first'; end if;
    perform 1 from public.loan_requests where id = p_request_id and borrower_id = auth.uid()
        and status = 'REQUESTED' and funded_amount = 0 for update;
    if not found then raise exception 'Request cannot be cancelled after support begins'; end if;
    perform public.release_request_offers(p_request_id);
    update public.loan_requests set status = 'CANCELLED' where id = p_request_id;
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
        (l.borrower_id,'LOAN_OVERDUE','Loan overdue','Your loan is overdue; new borrowing is frozen.',l.id),
        (l.lender_id,'LOAN_OVERDUE','Loan overdue','A loan you supported is overdue.',l.id);
    perform public.record_trust_event(l.borrower_id,'OVERDUE',-40,'A loan became overdue.',l.id);
end;
$$;
revoke all on function public.mark_loan_overdue(uuid) from public, anon, authenticated;
grant execute on function public.mark_loan_overdue(uuid) to service_role;

-- Scheduled maintenance and authenticated catch-up share the same implementation.
-- Null scope is server-only; clients can refresh only loans/requests they participate in.
create or replace function public.maintain_account_data(p_user_id uuid default null)
returns void language plpgsql security definer set search_path = '' as $$
declare r public.loan_requests%rowtype; l public.loans%rowtype;
begin
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
            values(l.borrower_id,'LOAN_DUE_SOON','Loan due soon','Your repayment is due within three days.',l.id);
        end if;
    end loop;
end;
$$;
revoke all on function public.maintain_account_data(uuid) from public, anon, authenticated;
grant execute on function public.maintain_account_data(uuid) to service_role;
create or replace function public.refresh_account_state()
returns void language plpgsql security definer set search_path = '' as $$
begin
    if auth.uid() is null then raise exception 'Sign in first'; end if;
    perform public.maintain_account_data(auth.uid());
end;
$$;
revoke all on function public.refresh_account_state() from public, anon, authenticated;
grant execute on function public.refresh_account_state() to authenticated;

create index if not exists connections_recipient_idx on public.connections(recipient_id,status);
create index if not exists connections_requester_idx on public.connections(requester_id,status);
create index if not exists loan_offers_borrower_idx on public.loan_offers(borrower_id,status);
create index if not exists loan_requests_direct_idx on public.loan_requests(direct_request_to);
create index if not exists loans_due_idx on public.loans(due_date) where status in ('ACTIVE','DUE_SOON','GRACE_PERIOD');
create index if not exists loan_payments_loan_idx on public.loan_payments(loan_id);
create index if not exists trust_events_user_idx on public.trust_events(user_id,created_at desc);
create index if not exists transactions_sender_idx on public.transactions(sender_id,created_at desc);
create index if not exists transactions_recipient_idx on public.transactions(recipient_id,created_at desc);

-- Direct requests stay private to their borrower and designated lender.
drop policy if exists "Authenticated users can view active requests" on public.loan_requests;
create policy "Authenticated users can view active requests" on public.loan_requests for select to authenticated using (
    borrower_id = auth.uid() or direct_request_to = auth.uid() or
    (direct_request_to is null and exists(select 1 from public.connections c where c.status = 'ACCEPTED' and
        ((c.requester_id = auth.uid() and c.recipient_id = borrower_id) or (c.recipient_id = auth.uid() and c.requester_id = borrower_id))))
);
drop policy if exists "Offer participants can view offers" on public.loan_offers;
create policy "Offer participants can view offers" on public.loan_offers for select to authenticated
using (lender_id = auth.uid() or borrower_id = auth.uid());

-- No client-created schema objects may shadow SECURITY DEFINER dependencies.
revoke create on schema public from public, anon, authenticated;
grant usage on schema public to anon, authenticated;
do $$ declare f record; begin
    for f in select p.oid::regprocedure as signature from pg_proc p join pg_namespace n on n.oid=p.pronamespace
        where n.nspname='public' and p.proname in ('refresh_profile_limit','record_trust_event','send_connection_request',
        'respond_connection_request','create_loan_request','edit_loan_request','get_direct_request_borrower_summary',
        'create_loan_offer','request_loan_offer_terms','respond_loan_offer_terms','accept_loan_offer','decline_loan_offer',
        'repay_loan','grant_grace_period','mark_recovered_externally') loop
        execute format('alter function %s set search_path = ''''', f.signature);
    end loop;
end $$;

do $$ declare u uuid; begin
    for u in select id from auth.users loop perform public.provision_confirmed_user(u); end loop;
    for u in select id from public.profiles loop perform public.refresh_profile_statistics(u); end loop;
end $$;



-- Replace earlier RPC bodies with explicit authentication and authorization.
create or replace function public.send_connection_request(p_recipient_id uuid)
returns uuid language plpgsql security definer set search_path = '' as $$
declare v_id uuid;
begin
    if auth.uid() is null then raise exception 'Sign in first' using errcode = '28000'; end if;
    if p_recipient_id = auth.uid() then raise exception 'Cannot connect to yourself'; end if;
    insert into public.connections (requester_id, recipient_id) values (auth.uid(), p_recipient_id) returning id into v_id;
    insert into public.notifications (user_id, type, title, message, related_entity_id)
    values (p_recipient_id, 'CONNECTION_REQUEST', 'Connection request', 'You received a connection request.', v_id);
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
        case when p_accept then 'Your connection request was accepted.' else 'Your connection request was declined.' end,
        p_connection_id);
end;
$$;

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
    if p_amount > v_profile.available_limit and (p_direct_request_to is null or not p_above_limit_requested) then
        raise exception 'Above-limit requests must be directed to a lender and explicitly disclosed';
    end if;
    if length(trim(p_purpose)) = 0 or length(trim(p_description)) = 0 then raise exception 'Purpose and description are required'; end if;
    if p_direct_request_to = auth.uid() then raise exception 'Cannot request support from yourself'; end if;
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
returns void language plpgsql security definer set search_path = '' as $$
begin
    if auth.uid() is null then raise exception 'Sign in first' using errcode = '28000'; end if;
    if p_amount <= 0 or p_period_days not between 7 and 365 then raise exception 'Invalid request terms'; end if;
    if length(trim(p_purpose)) = 0 or length(trim(p_description)) = 0 then raise exception 'Purpose and description are required'; end if;
    perform 1 from public.loan_requests r where r.id=p_request_id and r.borrower_id=auth.uid() and r.status='REQUESTED' and r.funded_amount=0 for update;
    if not found then raise exception 'Request cannot be edited'; end if;
    perform public.release_request_offers(p_request_id);
    update public.loan_requests r set requested_amount = p_amount, remaining_amount = p_amount,
        purpose = trim(p_purpose), description = trim(p_description), preferred_period_days = p_period_days
     where r.id = p_request_id and r.borrower_id = auth.uid() and r.status = 'REQUESTED' and r.funded_amount = 0
       and p_amount <= (select available_limit from public.profiles where id = auth.uid());
    if not found then raise exception 'Request cannot be edited after support begins'; end if;
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
set search_path = ''
as $$
declare
    v_request public.loan_requests%rowtype;
begin
    if auth.uid() is null then raise exception 'Sign in first' using errcode = '28000'; end if;
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
returns uuid language plpgsql security definer set search_path = '' as $$
declare
    v_request public.loan_requests%rowtype;
    v_wallet public.wallets%rowtype;
    v_id uuid;
    v_total bigint;
begin
    if auth.uid() is null then raise exception 'Sign in first' using errcode = '28000'; end if;
    select * into v_request from public.loan_requests where id = p_request_id for update;
    if not found or v_request.status not in ('REQUESTED', 'PARTIALLY_FUNDED') or v_request.expires_at <= now() then
        raise exception 'Request is no longer available';
    end if;
    if v_request.direct_request_to is not null and v_request.direct_request_to <> auth.uid() then raise exception 'This request is private'; end if;
    if v_request.direct_request_to is null and not exists(select 1 from public.connections c where c.status='ACCEPTED' and
        ((c.requester_id=auth.uid() and c.recipient_id=v_request.borrower_id) or (c.recipient_id=auth.uid() and c.requester_id=v_request.borrower_id))) then
        raise exception 'Connect with this borrower first';
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
        format('The borrower requests %s%% for %s days.', p_interest_rate, p_period_days), p_offer_id);
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
        format('The supporter responded with %s%% interest for %s days.', v_rate, v_days), p_offer_id);
end;
$$;

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
    perform 1 from public.wallets where user_id in (v_request.borrower_id,v_offer.lender_id) order by user_id for update;
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
    values (v_offer.lender_id, 'LOAN_OFFER_DECLINED', 'Offer declined', 'The borrower declined your support offer.', p_offer_id);
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
    values ('LOAN_REPAYMENT', v_loan.borrower_id, v_loan.lender_id, v_amount, p_loan_id, 'Loan repayment', v_amount - v_principal);
    update public.profiles set available_limit = least(borrowing_limit, available_limit + v_principal) where id = v_loan.borrower_id;
    insert into public.notifications (user_id, type, title, message, related_entity_id) values
        (v_loan.borrower_id, case when v_completed then 'LOAN_REPAID' else 'LOAN_PAYMENT' end,
         case when v_completed then 'Loan repaid' else 'Repayment recorded' end, format('KSh %s repayment recorded.', v_amount), p_loan_id),
        (v_loan.lender_id, 'LOAN_REPAID', 'Repayment received', format('%s paid KSh %s.', v_borrower.full_name, v_amount), p_loan_id);
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
returns void language plpgsql security definer set search_path = '' as $$
declare v_loan public.loans%rowtype;
begin
    if auth.uid() is null then raise exception 'Sign in first' using errcode = '28000'; end if;
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

notify pgrst, 'reload schema';
