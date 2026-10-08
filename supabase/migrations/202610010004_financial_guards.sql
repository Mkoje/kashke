-- Existing rows are preserved. NOT VALID checks constrain new/changed rows
-- without making deployment fail on legacy development fixtures.
do $$ declare c record; begin
    for c in select * from (values
        ('profiles','profile_name_nonempty','length(trim(full_name)) > 0'),
        ('profiles','profile_username_format','lower(username) ~ ''^[a-z0-9_]{3,24}$'''),
        ('profiles','profile_available_limit','available_limit <= borrowing_limit'),
        ('wallets','wallet_android_amount','balance <= 2147483647'),
        ('loan_requests','request_android_amount','requested_amount <= 2147483647'),
        ('loan_requests','request_content','length(trim(purpose)) > 0 and length(trim(description)) > 0'),
        ('loan_requests','request_direct_participants','direct_request_to is null or direct_request_to <> borrower_id'),
        ('loan_offers','offer_android_amount','total_repayment <= 2147483647'),
        ('loan_offers','offer_whole_rate','interest_rate = trunc(interest_rate) and (requested_interest_rate is null or requested_interest_rate = trunc(requested_interest_rate))'),
        ('loans','loan_android_amount','total_repayment <= 2147483647'),
        ('loans','loan_participants','borrower_id <> lender_id'),
        ('loans','loan_repayment_total','total_repayment = principal + interest_amount and amount_paid <= total_repayment'),
        ('loans','loan_whole_rate','interest_rate = trunc(interest_rate)'),
        ('transactions','transaction_interest_valid','interest between 0 and amount')
    ) as checks(table_name,constraint_name,expression) loop
        if not exists(select 1 from pg_constraint where conname=c.constraint_name and conrelid=('public.'||c.table_name)::regclass) then
            execute format('alter table public.%I add constraint %I check (%s) not valid',c.table_name,c.constraint_name,c.expression);
        end if;
    end loop;
end $$;

-- The existing payment ledger also stores retry receipts; no extra entity is needed.
alter table public.loan_payments add column if not exists operation_id uuid;
alter table public.loan_payments add column if not exists requested_amount bigint;
create unique index if not exists loan_payments_operation_idx on public.loan_payments(operation_id) where operation_id is not null;

create or replace function public.repay_loan_once(p_loan_id uuid,p_amount bigint,p_operation_id uuid)
returns void language plpgsql security definer set search_path = '' as $$
declare receipt public.loan_payments%rowtype; payment_id uuid; prior_ids uuid[];
begin
    if auth.uid() is null or p_operation_id is null then raise exception 'Authenticated operation ID required'; end if;
    if p_amount is null or p_amount <= 0 then raise exception 'Payment amount must be positive'; end if;
    perform 1 from public.loans where id=p_loan_id and borrower_id=auth.uid() for update;
    if not found then raise exception 'Loan not found'; end if;
    select * into receipt from public.loan_payments where operation_id=p_operation_id;
    if found then
        if receipt.loan_id<>p_loan_id or receipt.payer_id<>auth.uid() or receipt.requested_amount<>p_amount then
            raise exception 'Operation ID belongs to another payment';
        end if;
        return;
    end if;
    -- A loan lock serializes payments. Identify the newly created ledger row by
    -- its absence before calling the internal repayment implementation.
    select coalesce(array_agg(id),'{}'::uuid[]) into prior_ids from public.loan_payments where loan_id=p_loan_id;
    perform public.repay_loan(p_loan_id,p_amount);
    select id into strict payment_id from public.loan_payments where loan_id=p_loan_id and not(id=any(prior_ids));
    update public.loan_payments set operation_id=p_operation_id,requested_amount=p_amount where id=payment_id;
end;
$$;
revoke all on function public.repay_loan(uuid,bigint) from public,anon,authenticated;
revoke all on function public.repay_loan_once(uuid,bigint,uuid) from public,anon,authenticated;
grant execute on function public.repay_loan_once(uuid,bigint,uuid) to authenticated;

-- Repair reservations stranded by earlier cancellation code.
do $$ declare r uuid; begin
    for r in select id from public.loan_requests where status in ('CANCELLED','EXPIRED','FULLY_FUNDED') order by id for update loop
        perform public.release_request_offers(r);
    end loop;
    perform public.maintain_account_data(null);
end $$;
notify pgrst, 'reload schema';
