-- Explicit grants protect financial rows even when a permissive RLS policy is
-- accidentally introduced later. No client may write authoritative money.
revoke all on public.wallets,public.transactions,public.loan_payments,
 public.trust_events,public.loans,public.loan_requests,public.loan_offers,
 public.mpesa_deposits from public,anon,authenticated;
grant select on public.wallets,public.transactions,public.loan_payments,
 public.trust_events,public.loans,public.loan_requests,public.loan_offers to authenticated;
grant select(id,user_id,amount,phone,status,receipt,created_at,completed_at) on public.mpesa_deposits to authenticated;
revoke truncate,references,trigger on public.lender_preferences from public,anon,authenticated;
revoke all on public.lender_preferences from anon;
-- TRUNCATE does not honor row-level security.
do $$ declare t text; begin
 foreach t in array array['profiles','wallets','connections','loan_requests','loan_offers',
 'loans','loan_payments','trust_events','transactions','notifications','lender_preferences','mpesa_deposits'] loop
  execute format('alter table public.%I enable row level security',t);
  execute format('revoke truncate,references,trigger on public.%I from public,anon,authenticated',t);
 end loop;
end $$;

create function public.guard_strict_profile_input() returns trigger
language plpgsql set search_path = public as $$
begin
 -- Validate changes without invalidating historical profile rows.
 if (tg_op='INSERT' or new.full_name is distinct from old.full_name) and
  (length(trim(new.full_name)) not between 2 and 80 or new.full_name !~ '^[[:alpha:] ]+$')
 then raise exception 'Use 2-80 letters and spaces for your full name'; end if;
 if (tg_op='INSERT' or new.username is distinct from old.username) and
  new.username !~ '^[a-zA-Z0-9_]{3,24}$'
 then raise exception 'Invalid username'; end if;
 if (tg_op='INSERT' or new.phone is distinct from old.phone) and new.phone<>'' and
  new.phone !~ '^(0[17][0-9]{8}|\+?254[17][0-9]{8})$'
 then raise exception 'Invalid Kenyan phone number'; end if;
 if (tg_op='INSERT' or new.profile_email is distinct from old.profile_email) and new.profile_email<>'' and
  (length(new.profile_email)>254 or length(split_part(new.profile_email,'@',1))>64 or
   new.profile_email !~ '^[a-zA-Z0-9]+(\.[a-zA-Z0-9]+)*@[a-zA-Z0-9]+(\.[a-zA-Z0-9]+)*\.[a-zA-Z]{2,63}$')
 then raise exception 'Invalid email format'; end if;
 return new;
end $$;
revoke all on function public.guard_strict_profile_input() from public,anon,authenticated;
create trigger aaa_strict_profile_input before insert or update on public.profiles
 for each row execute function public.guard_strict_profile_input();

create index mpesa_phone_time on public.mpesa_deposits(phone,created_at);
create index mpesa_member_time on public.mpesa_deposits(user_id,created_at);
alter table public.mpesa_deposits add column last_query_at timestamptz;
create function public.guard_mpesa_deposit() returns trigger
language plpgsql security definer set search_path = public as $$
begin
 if tg_op='INSERT' then
  -- Serializing by recipient prevents several accounts evading the phone limit.
  perform pg_advisory_xact_lock(hashtextextended('mpesa-phone:'||new.phone,0));
  if (select count(*) from public.mpesa_deposits where phone=new.phone and created_at>now()-interval '10 minutes')>=5
   or (select count(*) from public.mpesa_deposits where user_id=new.user_id and created_at>now()-interval '1 hour')>=10
   or (select count(*) from public.mpesa_deposits where phone=new.phone and created_at>now()-interval '1 day')>=30
  then raise exception 'Deposit request limit reached'; end if;
  if not exists(select 1 from public.profiles where id=new.user_id and account_status='ACTIVE')
  then raise exception 'Account is unavailable'; end if;
  if new.status<>'PENDING' or new.receipt is not null or new.callback_payload is not null
  then raise exception 'New deposits must be pending'; end if;
 else
  if row(new.id,new.user_id,new.amount,new.phone,new.callback_token,new.created_at)
   is distinct from row(old.id,old.user_id,old.amount,old.phone,old.callback_token,old.created_at)
  then raise exception 'Deposit identity is immutable'; end if;
  if old.checkout_id is not null and row(new.checkout_id,new.merchant_id) is distinct from row(old.checkout_id,old.merchant_id)
  then raise exception 'Provider identifiers are immutable'; end if;
  if old.status<>'PENDING' and row(new.status,new.receipt,new.completed_at) is distinct from row(old.status,old.receipt,old.completed_at)
  then raise exception 'Settled deposits are immutable'; end if;
 end if;
 return new;
end $$;
revoke all on function public.guard_mpesa_deposit() from public,anon,authenticated;
create trigger guard_mpesa_deposit before insert or update on public.mpesa_deposits
 for each row execute function public.guard_mpesa_deposit();

-- Keep future tables/functions private until a migration deliberately grants access.
alter default privileges in schema public revoke all on tables from anon,authenticated;
alter default privileges in schema public revoke execute on functions from public,anon,authenticated;

create or replace function public.complete_member_profile(p_full_name text,p_username text,p_email text,p_phone text)
returns void language plpgsql security definer set search_path='' as $$
declare ph text; oldph text;
begin
 if auth.uid() is null then raise exception 'Sign in first' using errcode='28000'; end if;
 if p_full_name is null or length(trim(p_full_name)) not between 2 and 80 or p_full_name !~ '^[[:alpha:] ]+$'
 or p_username is null or lower(trim(p_username)) !~ '^[a-z0-9_]{3,24}$' or lower(trim(p_username)) ~ '^user_[a-f0-9]{19}$'
 or p_email is null or length(p_email)>254 or length(split_part(p_email,'@',1))>64
 or p_email !~ '^[a-zA-Z0-9]+(\.[a-zA-Z0-9]+)*@[a-zA-Z0-9]+(\.[a-zA-Z0-9]+)*\.[a-zA-Z]{2,63}$'
 or p_email ~* '@auth\.kashke\.local$' then raise exception 'Invalid profile details'; end if;
 if p_phone is null or p_phone !~ '^(0[17][0-9]{8}|\+?254[17][0-9]{8})$'
 then raise exception 'Enter a valid Kenyan mobile number'; end if;
 ph:=public.canonical_member_phone(p_phone);
 select phone into oldph from public.profiles where id=auth.uid() for update;
 if not found then raise exception 'Profile unavailable'; end if;
 if nullif(trim(oldph),'') is not null and public.canonical_member_phone(oldph) is distinct from ph
 then raise exception 'Phone changes require account verification'; end if;
 update public.profiles set full_name=trim(p_full_name),username=lower(trim(p_username)),profile_email=lower(p_email),
 phone=case when nullif(trim(oldph),'') is null then ph else oldph end where id=auth.uid();
end $$;
revoke all on function public.complete_member_profile(text,text,text,text) from public,anon;
grant execute on function public.complete_member_profile(text,text,text,text) to authenticated;

create function public.guard_request_input() returns trigger language plpgsql set search_path=public as $$
begin
 if (tg_op='INSERT' or new.purpose is distinct from old.purpose) and
 (length(trim(new.purpose)) not between 1 and 20 or new.purpose !~ '^[[:alnum:] .,]+$')
 then raise exception 'Purpose must use 1-20 letters, numbers, spaces, commas or full stops'; end if;
 if (tg_op='INSERT' or new.description is distinct from old.description) and
 (length(trim(new.description)) not between 1 and 150 or new.description !~ '^[[:alnum:] .,]+$')
 then raise exception 'Description must use 1-150 letters, numbers, spaces, commas or full stops'; end if;
 return new;
end $$;
revoke all on function public.guard_request_input() from public,anon,authenticated;
create trigger strict_request_input before insert or update on public.loan_requests
 for each row execute function public.guard_request_input();

-- Upgrade the installed settlement function as well as the fresh migration.
do $$ declare definition text; begin
 select pg_get_functiondef('public.settle_mpesa_deposit(uuid,text,text,uuid,integer,text,text)'::regprocedure) into definition;
 execute replace(definition,'d.callback_token <> p_token','d.callback_token is distinct from p_token');
end $$;
