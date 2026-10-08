create table public.mpesa_deposits (
 id uuid primary key default gen_random_uuid(),
 user_id uuid not null references public.profiles(id),
 amount integer not null check (amount between 1 and 150000),
 phone text not null check (phone ~ '^254[17][0-9]{8}$'),
 status text not null default 'PENDING' check (status in ('PENDING','COMPLETED','FAILED')),
 checkout_id text unique,
 merchant_id text,
 callback_token uuid not null default gen_random_uuid(),
 callback_payload jsonb,
 receipt text unique,
 created_at timestamptz not null default now(),
 completed_at timestamptz
);
create unique index mpesa_one_pending on public.mpesa_deposits(user_id) where status = 'PENDING';
alter table public.mpesa_deposits enable row level security;
revoke all on public.mpesa_deposits from anon, authenticated;
grant select (id,user_id,amount,phone,status,receipt,created_at,completed_at) on public.mpesa_deposits to authenticated;
grant all on public.mpesa_deposits to service_role;
create policy mpesa_owner_read on public.mpesa_deposits for select to authenticated using (user_id = auth.uid());

-- Only the backend can settle. Row locking, a unique receipt, and the transaction
-- make callback retries harmless and keep the ledger and balance consistent.
create function public.settle_mpesa_deposit(p_id uuid, p_checkout text, p_merchant text,
 p_token uuid, p_amount integer, p_phone text, p_receipt text)
returns void language plpgsql security definer set search_path = public as $$
declare d public.mpesa_deposits%rowtype;
begin
 select * into d from public.mpesa_deposits where id = p_id for update;
 if not found or d.callback_token is distinct from p_token or d.checkout_id is distinct from p_checkout
 or d.merchant_id is distinct from p_merchant or d.amount is distinct from p_amount
 or d.phone is distinct from p_phone or p_receipt is null or p_receipt !~ '^[A-Z0-9]{10}$'
 then raise exception 'Invalid payment confirmation'; end if;
 if d.status = 'COMPLETED' then
  if d.receipt is distinct from p_receipt then raise exception 'Receipt mismatch'; end if;
  return;
 end if;
 if d.status <> 'PENDING' then raise exception 'Payment is not pending'; end if;
 update public.wallets set balance = balance + d.amount, updated_at = now() where user_id = d.user_id;
 if not found then raise exception 'Wallet missing'; end if;
 insert into public.transactions(type,sender_id,recipient_id,amount,status,description)
 values ('DEPOSIT',d.user_id,d.user_id,d.amount,'COMPLETED','M-Pesa deposit ' || p_receipt);
 update public.mpesa_deposits set status = 'COMPLETED',receipt = p_receipt,completed_at = now() where id = d.id;
 insert into public.notifications(user_id,type,title,message,read,related_entity_id)
 values(d.user_id,'MPESA_DEPOSIT','Deposit received','KSh ' || d.amount || ' added to your wallet.',false,d.id);
end;
$$;
revoke all on function public.settle_mpesa_deposit(uuid,text,text,uuid,integer,text,text) from public, anon, authenticated;
grant execute on function public.settle_mpesa_deposit(uuid,text,text,uuid,integer,text,text) to service_role;
