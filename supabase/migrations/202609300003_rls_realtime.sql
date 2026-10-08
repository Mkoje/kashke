alter table public.profiles enable row level security;
alter table public.wallets enable row level security;
alter table public.connections enable row level security;
alter table public.loan_requests enable row level security;
alter table public.loan_offers enable row level security;
alter table public.loans enable row level security;
alter table public.loan_payments enable row level security;
alter table public.trust_events enable row level security;
alter table public.transactions enable row level security;
alter table public.notifications enable row level security;
alter table public.lender_preferences enable row level security;

create policy "Authenticated users can view profiles" on public.profiles
    for select to authenticated using (true);
create policy "Users can update their own profile" on public.profiles
    for update to authenticated using (id = auth.uid()) with check (id = auth.uid());
create policy "Users can view their wallet" on public.wallets
    for select to authenticated using (user_id = auth.uid());
create policy "Participants can view connections" on public.connections
    for select to authenticated using (requester_id = auth.uid() or recipient_id = auth.uid());
create policy "Authenticated users can view active requests" on public.loan_requests
    for select to authenticated using (
        borrower_id = auth.uid() or direct_request_to = auth.uid() or
        exists (
            select 1 from public.connections c where c.status = 'ACCEPTED' and
                ((c.requester_id = auth.uid() and c.recipient_id = borrower_id) or
                 (c.recipient_id = auth.uid() and c.requester_id = borrower_id))
        )
    );
create policy "Offer participants can view offers" on public.loan_offers
    for select to authenticated using (lender_id = auth.uid() or exists (
        select 1 from public.loan_requests r where r.id = loan_request_id and r.borrower_id = auth.uid()
    ));
create policy "Loan participants can view loans" on public.loans
    for select to authenticated using (borrower_id = auth.uid() or lender_id = auth.uid());
create policy "Loan participants can view payments" on public.loan_payments
    for select to authenticated using (
        payer_id = auth.uid() or exists (
            select 1 from public.loans l where l.id = loan_id and
                (l.borrower_id = auth.uid() or l.lender_id = auth.uid())
        )
    );
create policy "Users can view their trust events" on public.trust_events
    for select to authenticated using (
        user_id = auth.uid() or exists (
            select 1 from public.loan_requests r where r.borrower_id = user_id
                and r.direct_request_to = auth.uid() and r.above_limit_requested
        )
    );
create policy "Transaction participants can view transactions" on public.transactions
    for select to authenticated using (sender_id = auth.uid() or recipient_id = auth.uid());
create policy "Users can view their notifications" on public.notifications
    for select to authenticated using (user_id = auth.uid());
create policy "Users can mark their notifications read" on public.notifications
    for update to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy "Users can manage their lender preferences" on public.lender_preferences
    for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());

revoke all on public.profiles, public.wallets, public.connections, public.loan_requests,
    public.loan_offers, public.loans, public.loan_payments, public.trust_events,
    public.transactions, public.notifications, public.lender_preferences from anon, authenticated;

grant select on public.profiles to authenticated;
grant update (full_name, username, phone, bio, location, profile_image) on public.profiles to authenticated;
grant select on public.wallets, public.connections, public.loan_requests, public.loan_offers,
    public.loans, public.loan_payments, public.trust_events, public.transactions, public.notifications to authenticated;
grant update (read) on public.notifications to authenticated;
grant select, insert, update, delete on public.lender_preferences to authenticated;

revoke all on function public.refresh_profile_limit(uuid) from public, anon, authenticated;
revoke all on function public.record_trust_event(uuid, text, integer, text, uuid) from public, anon, authenticated;
revoke all on function public.send_connection_request(uuid) from public, anon, authenticated;
revoke all on function public.respond_connection_request(uuid, boolean) from public, anon, authenticated;
revoke all on function public.create_loan_request(bigint, text, text, integer, uuid, boolean) from public, anon, authenticated;
revoke all on function public.edit_loan_request(uuid, bigint, text, text, integer) from public, anon, authenticated;
revoke all on function public.cancel_loan_request(uuid) from public, anon, authenticated;
revoke all on function public.get_direct_request_borrower_summary(uuid) from public, anon, authenticated;
revoke all on function public.create_loan_offer(uuid, bigint, numeric, integer, boolean) from public, anon, authenticated;
revoke all on function public.request_loan_offer_terms(uuid, numeric, integer) from public, anon, authenticated;
revoke all on function public.respond_loan_offer_terms(uuid, boolean) from public, anon, authenticated;
revoke all on function public.accept_loan_offer(uuid) from public, anon, authenticated;
revoke all on function public.decline_loan_offer(uuid) from public, anon, authenticated;
revoke all on function public.repay_loan(uuid, bigint) from public, anon, authenticated;
revoke all on function public.mark_loan_overdue(uuid) from public, anon, authenticated;
revoke all on function public.grant_grace_period(uuid, integer) from public, anon, authenticated;
revoke all on function public.mark_recovered_externally(uuid) from public, anon, authenticated;

grant execute on function public.send_connection_request(uuid) to authenticated;
grant execute on function public.respond_connection_request(uuid, boolean) to authenticated;
grant execute on function public.create_loan_request(bigint, text, text, integer, uuid, boolean) to authenticated;
grant execute on function public.edit_loan_request(uuid, bigint, text, text, integer) to authenticated;
grant execute on function public.cancel_loan_request(uuid) to authenticated;
grant execute on function public.get_direct_request_borrower_summary(uuid) to authenticated;
grant execute on function public.create_loan_offer(uuid, bigint, numeric, integer, boolean) to authenticated;
grant execute on function public.request_loan_offer_terms(uuid, numeric, integer) to authenticated;
grant execute on function public.respond_loan_offer_terms(uuid, boolean) to authenticated;
grant execute on function public.accept_loan_offer(uuid) to authenticated;
grant execute on function public.decline_loan_offer(uuid) to authenticated;
grant execute on function public.repay_loan(uuid, bigint) to authenticated;
grant execute on function public.mark_loan_overdue(uuid) to service_role;
grant execute on function public.grant_grace_period(uuid, integer) to authenticated;
grant execute on function public.mark_recovered_externally(uuid) to authenticated;

do $$
declare
    v_table text;
begin
    if exists (select 1 from pg_publication where pubname = 'supabase_realtime') then
        foreach v_table in array array[
            'profiles', 'wallets', 'connections', 'loan_requests', 'loan_offers', 'loans',
            'loan_payments', 'trust_events', 'transactions', 'notifications'
        ] loop
            if not exists (
                select 1 from pg_publication_tables
                 where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = v_table
            ) then
                execute format('alter publication supabase_realtime add table public.%I', v_table);
            end if;
        end loop;
    end if;
end;
$$;

-- The service role bypasses RLS and must remain server-side only.
do $$
declare
    v_table text;
begin
    if exists (select 1 from pg_publication where pubname = 'supabase_realtime') then
        foreach v_table in array array[
            'profiles', 'connections', 'loan_requests', 'loan_offers', 'loans',
            'loan_payments', 'trust_events', 'transactions', 'notifications'
        ] loop
            if not exists (
                select 1 from pg_publication_tables
                 where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = v_table
            ) then
                execute format('alter publication supabase_realtime add table public.%I', v_table);
            end if;
        end loop;
    end if;
end;
$$;
