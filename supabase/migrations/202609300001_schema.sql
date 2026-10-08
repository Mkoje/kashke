create extension if not exists pgcrypto;

create table public.profiles (
    id uuid primary key references auth.users(id) on delete cascade,
    full_name text not null,
    username text not null,
    phone text not null default '',
    bio text not null default '',
    location text not null default '',
    profile_image text not null default '',
    trust_score integer not null default 500 check (trust_score between 0 and 1000),
    borrowing_limit integer not null default 32000 check (borrowing_limit >= 0),
    available_limit integer not null default 32000 check (available_limit >= 0),
    account_status text not null default 'ACTIVE',
    borrowing_frozen boolean not null default false,
    is_demo boolean not null default false,
    created_at timestamptz not null default now()
);
create unique index profiles_username_lower_idx on public.profiles (lower(username));

create table public.wallets (
    user_id uuid primary key references public.profiles(id) on delete cascade,
    balance bigint not null default 0 check (balance >= 0),
    locked_balance bigint not null default 0 check (locked_balance >= 0 and locked_balance <= balance),
    updated_at timestamptz not null default now()
);

create table public.connections (
    id uuid primary key default gen_random_uuid(),
    requester_id uuid not null references public.profiles(id) on delete cascade,
    recipient_id uuid not null references public.profiles(id) on delete cascade,
    status text not null default 'PENDING' check (status in ('PENDING', 'ACCEPTED', 'REJECTED', 'REMOVED')),
    created_at timestamptz not null default now(),
    accepted_at timestamptz,
    check (requester_id <> recipient_id)
);
create unique index connections_pair_unique_idx on public.connections
    (least(requester_id, recipient_id), greatest(requester_id, recipient_id));

create table public.loan_requests (
    id uuid primary key default gen_random_uuid(),
    borrower_id uuid not null references public.profiles(id) on delete cascade,
    requested_amount bigint not null check (requested_amount > 0),
    funded_amount bigint not null default 0 check (funded_amount >= 0),
    remaining_amount bigint not null check (remaining_amount >= 0),
    purpose text not null,
    description text not null,
    status text not null default 'REQUESTED' check (status in ('REQUESTED', 'PARTIALLY_FUNDED', 'FULLY_FUNDED', 'EXPIRED', 'CANCELLED')),
    created_at timestamptz not null default now(),
    expires_at timestamptz not null default (now() + interval '7 days'),
    preferred_period_days integer not null default 30 check (preferred_period_days between 7 and 365),
    direct_request_to uuid references public.profiles(id),
    above_limit_requested boolean not null default false,
    check (funded_amount + remaining_amount = requested_amount)
);
create index loan_requests_open_idx on public.loan_requests (status, created_at desc);
create index loan_requests_borrower_idx on public.loan_requests (borrower_id, created_at desc);

create table public.loan_offers (
    id uuid primary key default gen_random_uuid(),
    loan_request_id uuid not null references public.loan_requests(id) on delete cascade,
    lender_id uuid not null references public.profiles(id) on delete cascade,
    amount bigint not null check (amount > 0),
    interest_rate numeric(4,2) not null check (interest_rate between 0 and 5),
    repayment_period_days integer not null check (repayment_period_days between 7 and 365),
    total_repayment bigint not null check (total_repayment >= amount),
    status text not null default 'OFFERED' check (status in ('OFFERED', 'TERMS_REQUESTED', 'ACCEPTED', 'DECLINED', 'EXPIRED')),
    created_at timestamptz not null default now(),
    requested_interest_rate numeric(4,2) check (requested_interest_rate between 0 and 5),
    requested_repayment_period_days integer check (requested_repayment_period_days between 7 and 365),
    above_limit_override boolean not null default false
);
create index loan_offers_request_idx on public.loan_offers (loan_request_id, status);
create index loan_offers_lender_idx on public.loan_offers (lender_id, status);

create table public.loans (
    id uuid primary key default gen_random_uuid(),
    loan_request_id uuid not null references public.loan_requests(id),
    borrower_id uuid not null references public.profiles(id),
    lender_id uuid not null references public.profiles(id),
    principal bigint not null check (principal > 0),
    interest_rate numeric(4,2) not null check (interest_rate between 0 and 5),
    interest_amount bigint not null check (interest_amount >= 0),
    total_repayment bigint not null check (total_repayment >= principal),
    amount_paid bigint not null default 0 check (amount_paid >= 0),
    principal_paid bigint not null default 0 check (principal_paid between 0 and principal),
    due_date timestamptz not null,
    status text not null default 'ACTIVE' check (status in ('ACTIVE', 'DUE_SOON', 'OVERDUE', 'GRACE_PERIOD', 'REPAID', 'RECOVERED_EXTERNALLY', 'DEFAULTED')),
    created_at timestamptz not null default now(),
    completed_at timestamptz,
    above_limit_override boolean not null default false
);
create index loans_borrower_idx on public.loans (borrower_id, status);
create index loans_lender_idx on public.loans (lender_id, status);

create table public.loan_payments (
    id uuid primary key default gen_random_uuid(),
    loan_id uuid not null references public.loans(id),
    payer_id uuid not null references public.profiles(id),
    amount bigint not null check (amount > 0),
    payment_type text not null check (payment_type in ('PRINCIPAL', 'INTEREST', 'MIXED')),
    created_at timestamptz not null default now()
);

create table public.trust_events (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references public.profiles(id) on delete cascade,
    event_type text not null,
    points integer not null,
    description text not null,
    related_loan_id uuid references public.loans(id),
    created_at timestamptz not null default now()
);

create table public.transactions (
    id uuid primary key default gen_random_uuid(),
    type text not null,
    sender_id uuid not null references public.profiles(id),
    recipient_id uuid not null references public.profiles(id),
    amount bigint not null check (amount > 0),
    status text not null default 'COMPLETED',
    loan_id uuid references public.loans(id),
    created_at timestamptz not null default now(),
    description text not null
);

create table public.notifications (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references public.profiles(id) on delete cascade,
    type text not null,
    title text not null,
    message text not null,
    read boolean not null default false,
    related_entity_id uuid,
    created_at timestamptz not null default now()
);
create index notifications_user_created_idx on public.notifications (user_id, created_at desc);

create table public.lender_preferences (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null unique references public.profiles(id) on delete cascade,
    default_interest_rate numeric(4,2) not null default 2 check (default_interest_rate between 0 and 5),
    default_repayment_period_days integer not null default 30 check (default_repayment_period_days between 7 and 365),
    maximum_lending_amount bigint not null default 0 check (maximum_lending_amount >= 0),
    allow_above_limit_requests boolean not null default false,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create or replace function public.handle_new_auth_user()
returns trigger language plpgsql security definer set search_path = public, auth as $$
begin
    insert into public.profiles (id, full_name, username, phone, is_demo)
    values (
        new.id,
        coalesce(new.raw_user_meta_data ->> 'full_name', split_part(new.email, '@', 1)),
        coalesce(new.raw_user_meta_data ->> 'username', 'user_' || left(replace(new.id::text, '-', ''), 12)),
        coalesce(new.raw_user_meta_data ->> 'phone', ''),
        false
    ) on conflict (id) do nothing;
    insert into public.wallets (user_id) values (new.id) on conflict (user_id) do nothing;
    insert into public.lender_preferences (user_id) values (new.id) on conflict (user_id) do nothing;
    return new;
end;
$$;
create trigger on_auth_user_created after insert on auth.users
    for each row execute procedure public.handle_new_auth_user();
