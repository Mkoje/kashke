# Agreement, offers and phone signup implementation report

Deployment SQL is contained here, as requested. No new migration file is created. Apply the Deployment SQL block after the existing dashboard setup/migrations, then deploy the Android build. Do not re-run the old dashboard setup afterwards: it replaces these functions. The verification tool tests this report's exact SQL.

## Deployment SQL

```sql
begin;
-- Preserve the exact installed function definitions for rollback, only on first deployment.
create schema if not exists agreement_release_backup;
revoke all on schema agreement_release_backup from public,anon,authenticated;
create table if not exists agreement_release_backup.functions(signature text primary key, definition text not null);
revoke all on agreement_release_backup.functions from public,anon,authenticated;
insert into agreement_release_backup.functions
select p.oid::regprocedure::text, pg_get_functiondef(p.oid)
from pg_proc p join pg_namespace n on n.oid=p.pronamespace
where n.nspname='public' and p.proname in ('grant_grace_period','maintain_account_data','provision_confirmed_user','repay_loan')
on conflict do nothing;

alter table public.loans add column if not exists grace_granted_at timestamptz;
alter table public.loans add column if not exists grace_days integer check(grace_days between 1 and 30);
-- Existing grace recipients also consume their one extension.
update public.loans l set grace_granted_at=coalesce((select min(created_at) from public.trust_events t where t.related_loan_id=l.id and t.event_type='GRACE_PERIOD'),l.created_at)
where grace_granted_at is null and (status='GRACE_PERIOD' or exists(select 1 from public.trust_events t where t.related_loan_id=l.id and t.event_type='GRACE_PERIOD'));
alter table public.profiles drop constraint if exists profile_name_nonempty;
alter table public.profiles add column if not exists profile_email text not null default '';
-- Preserve existing account emails; this remains an owner-only contact field.
update public.profiles p set profile_email=u.email from auth.users u
where u.id=p.id and p.profile_email='' and coalesce(u.email,'')<>'';

create or replace function public.canonical_member_phone(p_phone text)
returns text language sql immutable set search_path='' as $$
select case
 when d ~ '^254[17][0-9]{8}$' then '+'||d
 when d ~ '^0[17][0-9]{8}$' then '+254'||substring(d from 2)
 when d ~ '^[17][0-9]{8}$' then '+254'||d
 else null end from (select regexp_replace(coalesce(p_phone,''),'[^0-9]','','g') d) s;
$$;
-- This index intentionally fails the entire transaction if legacy duplicates exist.
create unique index if not exists profiles_unique_mobile on public.profiles(public.canonical_member_phone(phone))
where public.canonical_member_phone(phone) is not null;

create or replace function public.provision_confirmed_user(p_user_id uuid)
returns void language plpgsql security definer set search_path='' as $$
declare u auth.users%rowtype; v_phone text; v_username text;
begin
 select * into u from auth.users where id=p_user_id for update;
 if not found then raise exception 'Account does not exist'; end if;
 if not exists(select 1 from public.profiles where id=p_user_id) then
   v_phone:=public.canonical_member_phone(coalesce(nullif(u.phone,''),u.raw_user_meta_data->>'phone',''));
   if nullif(u.phone,'') is not null and v_phone is null then raise exception 'Enter a valid Kenyan mobile number'; end if;
   v_username:=lower(trim(coalesce(u.raw_user_meta_data->>'username','')));
   if v_username !~ '^[a-z0-9_]{3,24}$' then v_username:='user_'||left(replace(p_user_id::text,'-',''),19); end if;
   perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(v_username,0));
   insert into public.profiles(id,full_name,username,phone,profile_email)
   values(p_user_id,case when nullif(u.phone,'') is not null then '' else coalesce(nullif(trim(u.raw_user_meta_data->>'full_name'),''),nullif(split_part(u.email,'@',1),''),'Member') end,
     v_username,coalesce(v_phone,u.raw_user_meta_data->>'phone',''),coalesce(u.email,''));
 end if;
 insert into public.wallets(user_id) values(p_user_id) on conflict do nothing;
 insert into public.lender_preferences(user_id) values(p_user_id) on conflict do nothing;
end;
$$;
revoke all on function public.provision_confirmed_user(uuid) from public,anon,authenticated;

create or replace function public.member_profile_complete(p_user_id uuid)
returns boolean language sql stable security definer set search_path='' as $$
select coalesce((select length(trim(full_name))>0 and username ~ '^[a-zA-Z0-9_]{3,24}$'
 and username !~ '^user_[a-f0-9]{19}$' and trim(profile_email) ~ '^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$'
 from public.profiles where id=p_user_id),false);
$$;
revoke all on function public.member_profile_complete(uuid) from public,anon,authenticated;

-- Remove the previous four-field API when upgrading this report's earlier version.
drop function if exists public.complete_member_profile(text,text,text,text);
create or replace function public.complete_member_profile(p_full_name text,p_username text,p_email text)
returns void language plpgsql security definer set search_path='' as $$
begin
 if auth.uid() is null then raise exception 'Sign in first'; end if;
 if p_full_name is null or length(trim(p_full_name))=0 or p_username is null
 or lower(trim(p_username)) !~ '^[a-z0-9_]{3,24}$' or lower(trim(p_username)) ~ '^user_[a-f0-9]{19}$'
 or p_email is null or trim(p_email) !~ '^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$' then raise exception 'Complete full name, username and email'; end if;
 update public.profiles set full_name=trim(p_full_name),username=lower(trim(p_username)),profile_email=lower(trim(p_email)) where id=auth.uid();
 if not found then raise exception 'Profile unavailable'; end if;
end;
$$;
revoke all on function public.complete_member_profile(text,text,text) from public,anon,authenticated;
grant execute on function public.complete_member_profile(text,text,text) to authenticated;

create or replace function public.guard_member_requests()
returns trigger language plpgsql security definer set search_path='' as $$
begin
 if TG_TABLE_NAME='loan_requests' then
   if not public.member_profile_complete(new.borrower_id) then raise exception 'Complete your profile before making requests'; end if;
 elsif TG_TABLE_NAME='connections' then
   if new.status='PENDING' and (TG_OP='INSERT' or old.status is distinct from new.status)
   and not public.member_profile_complete(new.requester_id) then raise exception 'Complete your profile before making requests'; end if;
 elsif new.status='TERMS_REQUESTED' and old.status is distinct from new.status then
   if not public.member_profile_complete((select borrower_id from public.loan_requests where id=new.loan_request_id)) then raise exception 'Complete your profile before making requests'; end if;
 end if;
 return new;
end;
$$;
revoke all on function public.guard_member_requests() from public,anon,authenticated;
drop trigger if exists require_member_profile on public.loan_requests;
create trigger require_member_profile before insert or update of requested_amount,purpose,description,preferred_period_days on public.loan_requests for each row execute function public.guard_member_requests();
drop trigger if exists require_member_profile on public.connections;
create trigger require_member_profile before insert or update of status,requester_id,recipient_id on public.connections for each row execute function public.guard_member_requests();
drop trigger if exists require_member_profile on public.loan_offers;
create trigger require_member_profile before update of status on public.loan_offers for each row execute function public.guard_member_requests();

-- Profile phone is the login identifier; changes require a separate verified Auth flow.
create or replace function public.guard_member_phone()
returns trigger language plpgsql security definer set search_path='' as $$
begin
 if auth.uid() is not null and new.phone is distinct from old.phone then raise exception 'Phone changes require account verification'; end if;
 return new;
end;
$$;
revoke all on function public.guard_member_phone() from public,anon,authenticated;
drop trigger if exists guard_member_phone on public.profiles;
create trigger guard_member_phone before update of phone on public.profiles for each row execute function public.guard_member_phone();

create or replace function public.grant_grace_period(p_loan_id uuid,p_extra_days integer default 7)
returns void language plpgsql security definer set search_path='' as $$
declare l public.loans%rowtype; deadline timestamptz;
begin
 if auth.uid() is null then raise exception 'Sign in first'; end if;
 select * into l from public.loans where id=p_loan_id and lender_id=auth.uid() for update;
 if not found or p_extra_days is null or p_extra_days not between 1 and 30 or l.grace_granted_at is not null
 or l.status not in ('ACTIVE','DUE_SOON','OVERDUE') or l.amount_paid>=l.total_repayment
 or now()<l.original_due_date-interval '3 days' or now()>=l.original_due_date+interval '3 days' then raise exception 'Grace period unavailable'; end if;
 perform public.apply_daily_trust_penalties(l.borrower_id);
 deadline:=greatest(l.original_due_date,now())+make_interval(days=>p_extra_days);
 update public.loans set status='GRACE_PERIOD',due_date=deadline,grace_granted_at=now(),grace_days=p_extra_days where id=l.id;
 update public.profiles p set borrowing_frozen=exists(select 1 from public.loans x where x.borrower_id=p.id and x.id<>l.id and x.status in ('OVERDUE','DEFAULTED')) where p.id=l.borrower_id;
 insert into public.notifications(user_id,type,title,message,related_entity_id) values
 (l.borrower_id,'GRACE_PERIOD_GRANTED','Grace period granted',format('Your lender granted %s additional days. New deadline: %s. This is the only grace period for this agreement.',p_extra_days,deadline),l.id),
 (l.lender_id,'GRACE_PERIOD_GRANTED','Grace period confirmed',format('You granted %s additional days. New deadline: %s.',p_extra_days,deadline),l.id);
 perform public.record_trust_event(l.borrower_id,'GRACE_PERIOD',0,'A lender granted a grace period.',l.id);
 perform public.refresh_profile_statistics(l.borrower_id);
end;
$$;
revoke all on function public.grant_grace_period(uuid,integer) from public,anon,authenticated;
grant execute on function public.grant_grace_period(uuid,integer) to authenticated;

create or replace function public.edit_loan_offer(p_offer_id uuid,p_amount bigint,p_interest_rate numeric,p_period_days integer)
returns void language plpgsql security definer set search_path='' as $$
declare o public.loan_offers%rowtype; r public.loan_requests%rowtype; w public.wallets%rowtype;
begin
 if auth.uid() is null then raise exception 'Sign in first'; end if;
 select * into r from public.loan_requests where id=(select loan_request_id from public.loan_offers where id=p_offer_id) for update;
 select * into o from public.loan_offers where id=p_offer_id and lender_id=auth.uid() and status='OFFERED' for update;
 if not found or r.status not in ('REQUESTED','PARTIALLY_FUNDED') or r.expires_at<=now() then raise exception 'Offer unavailable'; end if;
 if p_amount is null or p_amount<=0 or p_amount>r.remaining_amount or p_interest_rate is null
 or p_interest_rate<>trunc(p_interest_rate) or p_interest_rate not between 0 and (case when r.above_limit_requested then 15 else 7 end)
 or p_period_days is null or p_period_days not between 7 and r.preferred_period_days then raise exception 'Invalid offer terms'; end if;
 if exists(select 1 from public.lender_preferences where user_id=auth.uid() and maximum_lending_amount>0 and maximum_lending_amount<p_amount) then raise exception 'Maximum lending amount exceeded'; end if;
 select * into w from public.wallets where user_id=auth.uid() for update;
 if not found or w.balance-w.locked_balance+o.amount<p_amount then raise exception 'Insufficient available wallet balance'; end if;
 update public.wallets set locked_balance=locked_balance-o.amount+p_amount,updated_at=now() where user_id=auth.uid();
 update public.loan_offers set amount=p_amount,interest_rate=p_interest_rate,repayment_period_days=p_period_days,
 total_repayment=p_amount+round(p_amount*p_interest_rate*p_period_days/36500.0)::bigint,
 requested_interest_rate=null,requested_repayment_period_days=null where id=o.id;
 insert into public.notifications(user_id,type,title,message,related_entity_id) values
 (r.borrower_id,'LOAN_OFFER_UPDATED','Offer updated',format('Your lender updated the offer to KSh %s at %s%% for %s days. Review the updated terms before accepting.',p_amount,p_interest_rate,p_period_days),o.id);
end;
$$;
revoke all on function public.edit_loan_offer(uuid,bigint,numeric,integer) from public,anon,authenticated;
grant execute on function public.edit_loan_offer(uuid,bigint,numeric,integer) to authenticated;

create or replace function public.cancel_loan_offer(p_offer_id uuid)
returns void language plpgsql security definer set search_path='' as $$
declare o public.loan_offers%rowtype; r public.loan_requests%rowtype;
begin
 if auth.uid() is null then raise exception 'Sign in first'; end if;
 select * into r from public.loan_requests where id=(select loan_request_id from public.loan_offers where id=p_offer_id) for update;
 select * into o from public.loan_offers where id=p_offer_id and lender_id=auth.uid() and status in ('OFFERED','TERMS_REQUESTED') for update;
 if not found then raise exception 'Offer unavailable'; end if;
 perform 1 from public.wallets where user_id=auth.uid() for update;
 update public.wallets set locked_balance=locked_balance-o.amount,updated_at=now() where user_id=auth.uid();
 -- Clear obsolete action notifications. Keep the cancellation notification independent of deleted offer.
 delete from public.notifications where related_entity_id=o.id and type in ('LOAN_OFFER','LOAN_OFFER_UPDATED','LOAN_OFFER_TERMS_REQUESTED');
 delete from public.loan_offers where id=o.id;
 insert into public.notifications(user_id,type,title,message,related_entity_id) values
 (r.borrower_id,'LOAN_OFFER_CANCELLED','Offer cancelled',format('Your lender cancelled the KSh %s offer. It is no longer available.',o.amount),null);
end;
$$;
revoke all on function public.cancel_loan_offer(uuid) from public,anon,authenticated;
grant execute on function public.cancel_loan_offer(uuid) to authenticated;

create or replace function public.maintain_account_data(p_user_id uuid default null)
returns void language plpgsql security definer set search_path = '' as $$
declare r public.loan_requests%rowtype; l public.loans%rowtype; u uuid;
begin
    for l in select * from public.loans where status in ('ACTIVE','DUE_SOON','OVERDUE','GRACE_PERIOD')
        and amount_paid < total_repayment and (p_user_id is null or borrower_id=p_user_id or lender_id=p_user_id)
        and ((grace_granted_at is null and now()>=original_due_date+interval '3 days')
            or (grace_granted_at is not null and now()>=due_date)) order by id for update loop
        update public.loans set status='DEFAULTED' where id=l.id;
        update public.profiles set borrowing_frozen=true where id=l.borrower_id;
        insert into public.notifications(user_id,type,title,message,related_entity_id) values
            (l.borrower_id,'LOAN_DEFAULTED','Loan defaulted','The repayment deadline has passed. No further grace is available. You can still repay this debt.',l.id),
            (l.lender_id,'LOAN_DEFAULTED','Loan defaulted','The unpaid agreement has defaulted. No further grace is available.',l.id);
    end loop;
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
    if not found or v_loan.status not in ('ACTIVE', 'DUE_SOON', 'OVERDUE', 'GRACE_PERIOD', 'DEFAULTED') then raise exception 'Loan cannot be repaid'; end if;
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
revoke all on function public.repay_loan(uuid,bigint) from public,anon,authenticated;

-- Ensure the borrower accepts the exact terms displayed, even during a concurrent edit.
create or replace function public.accept_loan_offer_reviewed(p_offer_id uuid,p_expected_amount bigint,p_expected_interest_rate numeric,p_expected_period_days integer)
returns uuid language plpgsql security definer set search_path='' as $$
declare o public.loan_offers%rowtype;
begin
 if auth.uid() is null then raise exception 'Sign in first'; end if;
 perform 1 from public.loan_requests where id=(select loan_request_id from public.loan_offers where id=p_offer_id) and borrower_id=auth.uid() for update;
 if not found then raise exception 'Offer unavailable'; end if;
 select * into o from public.loan_offers where id=p_offer_id and status='OFFERED' for update;
 if not found or o.amount is distinct from p_expected_amount or o.interest_rate is distinct from p_expected_interest_rate
 or o.repayment_period_days is distinct from p_expected_period_days then raise exception 'Offer terms changed; refresh and review the updated offer'; end if;
 return public.accept_loan_offer(p_offer_id);
end;
$$;
revoke all on function public.accept_loan_offer_reviewed(uuid,bigint,numeric,integer) from public,anon,authenticated;
grant execute on function public.accept_loan_offer_reviewed(uuid,bigint,numeric,integer) to authenticated;
notify pgrst, 'reload schema';
commit;
```

## Rollback SQL

Pause client rollout, run this block, then deploy the previous Android build. This restores the exact pre-deployment function definitions captured above, retaining the improved repayment body only while unpaid defaulted debts still need it. Financial actions, notifications, deleted offers, granted deadlines, profile edits and new Auth accounts are deliberately preserved; rolling those back requires an audited financial recovery, not blind deletion. Defaults already recorded remain defaulted. Leave the additive grace and contact-email columns and phone uniqueness in place so historical grants and identity uniqueness survive rollback. To return to the old nonempty-name constraint, first complete profiles with blank names, then validate that constraint in a separate maintenance step.

```sql
begin;
drop trigger if exists require_member_profile on public.loan_requests;
drop trigger if exists require_member_profile on public.connections;
drop trigger if exists require_member_profile on public.loan_offers;
drop trigger if exists guard_member_phone on public.profiles;
-- Older APKs recognize these existing notification types; preserve all messages.
update public.notifications set type='LOAN_OFFER_DECLINED' where type='LOAN_OFFER_CANCELLED';
update public.notifications set type='LOAN_OVERDUE' where type='LOAN_DEFAULTED';
do $$ declare f record; begin
 for f in select definition from agreement_release_backup.functions
 where not (signature::regprocedure='public.repay_loan(uuid,bigint)'::regprocedure
   and exists(select 1 from public.loans where status='DEFAULTED' and amount_paid<total_repayment))
 order by signature loop execute f.definition; end loop;
end $$;
drop function if exists public.accept_loan_offer_reviewed(uuid,bigint,numeric,integer);
drop function if exists public.edit_loan_offer(uuid,bigint,numeric,integer);
drop function if exists public.cancel_loan_offer(uuid);
drop function if exists public.complete_member_profile(text,text,text,text);
drop function if exists public.complete_member_profile(text,text,text);
drop function if exists public.guard_member_requests();
drop function if exists public.guard_member_phone();
drop function if exists public.member_profile_complete(uuid);
-- Optional, after all blank names have been completed (run separately once):
-- alter table public.profiles add constraint profile_name_nonempty check(length(trim(full_name))>0) not valid;
-- alter table public.profiles validate constraint profile_name_nonempty;
commit;
```



## Behavior and implementation

- Grace belongs to the shared agreement, so both borrower and lender see the same revised due date, grace state and original deadline. Both receive a persisted confirmation notification. The lender chooses 1–30 whole days. The button enables at original deadline minus 72 hours and disables at original deadline plus 72 hours (the closing boundary is exclusive). Server UTC timestamps enforce this even if a phone's clock is wrong. The new deadline is the later of the original deadline and grant time, plus the selected days; an early grant never shortens the existing term. Grace is allowed once, including existing agreements with recorded grace events. It pauses future daily delay deductions until the revised deadline; already applied deductions remain.
- Without grace, an unpaid agreement becomes DEFAULTED at the original deadline plus 72 hours. With grace, it defaults when the revised deadline expires. Default forbids further grace permanently, while debt remains payable through the existing idempotent repayment endpoint. Partial repayments do not reset deadlines or the grace entitlement. Fully repaid and externally settled agreements cannot receive grace. Default notifications are sent once to each participant.
- Offered lending entries have Edit offer and Cancel offer actions. Editing changes principal amount, annual interest and repayment days; the server enforces remaining request amount, available wallet funds, lender maximum, normal 0–7% rate or approved above-limit 0–15%, and 7 days through the borrower's requested period. Wallet reservations change only by the difference. Accepted/closed offers cannot be edited or cancelled; negotiating offers can be cancelled but must finish their negotiation before editing. Updated terms notify the borrower. Acceptance submits the exact displayed terms and rejects a stale screen if the offer has changed.
- Cancellation physically deletes the pending offer, clears obsolete offer/terms notifications, unlocks its reserved principal exactly once, and creates an independent borrower cancellation notification. It preserves other offers and wallet reservations. Cancellation, editing, acceptance and expiry lock the request before the offer and wallet; stale actions fail transactionally. Both clients remove the offer on their next refresh. Successful mutations refresh the acting account immediately; the other active client polls every 15 seconds, and catches up after reconnecting. This release uses existing in-app notifications; it does not add an operating-system push delivery service.
- Registration has only phone and password. The country dropdown contains 🇰🇪 +254 only. Kenyan mobile numbers beginning 7 or 1 are canonicalized to +254 E.164; local 07/01 and pasted international formats resolve to one identity. Supabase Phone password Auth handles the account identifier, and a canonical database unique index also prevents duplicate profile phones, including alternate legacy formats and concurrent provisioning. Passwords require at least 8 characters. The remembered-account password unlock supports phone-only accounts. Existing email accounts can still sign in.
- New phone profiles start with an empty name and a generated reserved username. Complete profile now asks only for full name, username and email. These three fields are required for request eligibility; bio, location and profile image do not block requests. The Edit profile button is hidden once all three required fields are valid. The contact email is stored privately in profiles.profile_email and is not exposed by the public profile APIs; it does not change the phone login identifier or configure an Auth email login. Existing Auth emails are copied for legacy accounts. A fixed red error-colored banner directs incomplete users to Profile and remains across dashboard tabs. Request Support is disabled; repository guards and database triggers also reject public/direct borrowing requests, request edits, connection requests and new offer-terms requests until completion. Unique usernames remain enforced by the existing index. Account phone cannot be changed through ordinary profile editing because it is the login identifier.
- Future country selection and geolocation resolver code are present but commented out in `AgreementPolicy.kt`. It needs location consent, additional country entries, international server validation and Auth rollout before activation. No location permission is requested for the Kenya launch.

## Hosted deployment instructions

1. Keep a copy of the currently deployed APK/source and take a database backup. First confirm all existing migrations through `202610040003_member_profile_details.sql` are already installed. Fresh databases need the existing schema history before this report's SQL. Do not use the current incremental `dashboard_setup.sql` as a complete bootstrap.
2. Check legacy phone duplicates before deployment: `select public.canonical_member_phone(phone), count(*) from public.profiles group by 1 having public.canonical_member_phone(phone) is not null and count(*) > 1;` can be run after creating the canonical function, or inspect normalized 07/01/+254 matches beforehand. If the Deployment SQL unique index detects duplicates, the whole transaction rolls back. Resolve ownership of those legacy records before rerunning; do not silently delete accounts or reassign a user's login number. Existing non-Kenyan/empty legacy profile phones are retained; new phone signups require Kenyan mobile numbers.
3. Run the complete Deployment SQL block in the hosted Supabase SQL Editor as the database owner. It is rerunnable and snapshots the original installed definitions once in a private schema. Check the commit succeeded; do not deploy the new APK against the old API. Future schema changes must preserve these functions/guards. Replaying an older migration or dashboard setup after this block would overwrite behavior.
4. In Supabase Authentication, enable the Phone provider and phone signups, turn OFF phone confirmation for the requested two-field immediate-session signup, and set the minimum password length to 8. Disable new email signups while retaining existing email account sign-in. The local `config.toml` mirrors these settings but does not change the hosted dashboard. Confirm-phone mode requires an OTP verification screen and SMS provider; that is a different signup flow from this release. Saving a profile contact email does not link an Auth email login or enable email password recovery. Phone-only users cannot use the existing email password-reset action; it is disabled for phone identifiers. A phone OTP recovery flow is not implemented in this release.
5. Enable Supabase Cron and run the existing `supabase/schedule_maintenance.sql` to retain the five-minute background maintenance schedule. This invokes the replaced maintenance implementation. Authenticated refresh also enforces the policy, so inactive clients cannot receive late grace. The visible DEFAULTED status and notifications are materialized on the next maintenance/refresh; grace authorization checks the actual deadline immediately.
6. Install the new debug APK at `.build-output/app/outputs/apk/debug/app-debug.apk` for testing, or build/sign the release variant using your production signing configuration. This work verifies the debug build; it does not publish an app-store release.
7. Use two accounts/devices: sign up a phone user, verify the banner and blocked requests, complete all three fields, create/connect/request/offer, edit amount and terms, review the borrower notification, cancel and verify both lists and reservations. For a separate accepted agreement, verify lender-only grace within the window, both confirmations, the revised deadline and blocked second grant. Confirm an expired grace and an unextended loan past the window default, yet still accept repayment. Check account switching and offline/reconnect catch-up.

Hosted Auth/admin settings and hosted SQL were not modified by this workspace build: no privileged database/Auth administration connection is available in the session. SQL is delivered in this report for deployment, as requested. PostgreSQL tests use an Auth schema shim; they do not prove hosted GoTrue, SMS, device UI or network delivery end to end.

## Rollback procedure and limits

Pause rollout/new account signup, run the Rollback SQL in a maintenance window, then restore the previous APK/source and hosted Auth settings (email signup enabled and previous password/phone settings as appropriate). The older APK must not be started before notification types are made compatible by the rollback block. Existing phone accounts and financial ledger entries are retained. After rollback, older clients may need the normal sign-in screen instead of their old remembered-email lock screen for phone-only accounts.

The rollback restores prior function bodies exactly and removes the new APIs and request/phone guards. When unpaid DEFAULTED agreements exist, it deliberately retains the improved internal repayment function so those debts remain payable through the existing idempotent endpoint on older builds. Rerun the Rollback SQL after those debts are settled to restore the original repayment body too. It keeps additive grace and contact-email columns, the canonical phone helper/unique index, the backup schema and actual financial/user data. It does not refund accepted loans, delete new accounts, reconstitute cancelled offers, remove legitimate notifications or retract granted deadlines. Defaults recorded by this release remain. A data-destructive reset is not a safe rollback for a lending ledger.

For optional schema cleanup after all incomplete profiles are completed, reinstate `profile_name_nonempty` with the original definition `check (length(trim(full_name)) > 0)` and validate it. Do not remove grace history or the phone unique index during an operational rollback. Keep the private function snapshot until the rollout is accepted; it contains definitions, not account passwords. Restoring an older source checkout should also revert `config.toml`, the changed app files and README; retain this report for the deployed SQL history.

## Verification

- Existing schema/integration verifier: `node tools/sql-check/verify.mjs` — 52 checks passed, plus the existing signup, competing-offer, trust/reliability and member-profile suites.
- New report SQL verifier: `node tools/sql-check/verify-agreements.mjs` — 74 checks passed, including required/valid contact email, completion with empty bio and location, persisted private email, legacy account email backfill, and removal of the old four-field API, including rerunnable deployment, alternate-format phone duplicate rejection, profile gating, authorization, reservation changes, cancellation notification/deletion, repeat cancellation, stale displayed terms, legacy-grace upgrade protection, early/late/one-time grace, default behavior, defaulted debt repayment, anonymous denial, exact saved-function rollback and redeployment. The full Deployment SQL is rerunnable for this correction, including upgrades from the earlier four-field profile API.
- Android policy tests cover exact 72-hour boundaries, already-used/settled/defaulted grace, canonical Kenyan identities and required profile fields. Existing Android tests also run.
- Final Android build/test/lint passed: `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug` returned BUILD SUCCESSFUL; 59 unit tests, zero failures. Lint completed with zero errors and 13 warnings. No new migration files were generated. The exact deployment and rollback SQL above are the inputs to the new PostgreSQL verifier; no separate deployment SQL file is maintained.

Supabase provider setup reference: [Password-based Auth](https://supabase.com/docs/guides/auth/passwords), [Kotlin phone signup](https://supabase.com/docs/reference/kotlin/auth-signup).



Final debug APK: `.build-output/app/outputs/apk/debug/app-debug.apk` (23889984 bytes). SHA-256: `379FDFE6F5A5DBDF43F1CF8B5670A079EF0B38FCDDA07506F8A77FF6F71D9776`.




