begin;

create table if not exists public.contact_requests (
    id uuid primary key,
    user_id uuid not null references public.profiles(id) on delete cascade,
    full_name text not null check (length(full_name) between 2 and 80 and full_name ~ '^[[:alpha:][:space:].''-]+$' and full_name !~ '[[:cntrl:]]'),
    reply_email text not null check (length(reply_email) <= 254 and reply_email ~ '^[A-Za-z0-9][A-Za-z0-9._%+-]{0,63}@[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)+$'),
    subject text not null check (length(subject) between 3 and 120 and subject ~ '^[[:alnum:] .,''!?():;/@+_#%&$=-]+$'),
    message text not null check (length(message) between 10 and 4000 and message ~ '^[[:alnum:][:space:] .,''!?():;/@+_#%&$=-]+$' and translate(message, E'\n\r', '') !~ '[[:cntrl:]]'),
    created_at timestamptz not null default now(),
    email_status text not null default 'PENDING' check (email_status in ('PENDING','SENDING','SENT','FAILED')),
    email_attempts integer not null default 0,
    next_email_attempt_at timestamptz not null default now(),
    email_lease_id uuid,
    email_lease_until timestamptz,
    email_sent_at timestamptz,
    provider_message_id text
);
create index if not exists contact_requests_user_time_idx on public.contact_requests(user_id,created_at desc);
create index if not exists contact_requests_mail_queue_idx on public.contact_requests(next_email_attempt_at) where email_status in ('PENDING','SENDING');
alter table public.contact_requests enable row level security;
revoke all on public.contact_requests from public, anon, authenticated;
grant all on public.contact_requests to service_role;

create or replace function public.submit_contact_request(
    p_submission_id uuid, p_full_name text, p_reply_email text, p_subject text, p_message text
) returns uuid language plpgsql security definer set search_path = '' as $$
declare existing public.contact_requests%rowtype;
begin
    if auth.uid() is null then raise exception 'Sign in first' using errcode='28000'; end if;
    if p_submission_id is null then raise exception 'Submission ID required'; end if;
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('contact:' || auth.uid()::text,0));
    select * into existing from public.contact_requests where id=p_submission_id;
    if found then
        if existing.user_id<>auth.uid() then raise exception 'Invalid submission ID'; end if;
        return existing.id;
    end if;
    if (select count(*) from public.contact_requests where user_id=auth.uid() and created_at>now()-interval '1 hour') >= 5 then
        raise exception 'Too many messages. Please try again in an hour.';
    end if;
    if exists(select 1 from public.contact_requests where user_id=auth.uid() and created_at>now()-interval '30 seconds') then
        raise exception 'Please wait 30 seconds before sending another message.';
    end if;
    insert into public.contact_requests(id,user_id,full_name,reply_email,subject,message)
    values(p_submission_id,auth.uid(),trim(p_full_name),lower(trim(p_reply_email)),trim(p_subject),trim(replace(p_message,E'\r\n',E'\n')));
    return p_submission_id;
end;
$$;

create or replace function public.claim_contact_email_batch(p_limit integer default 10)
returns setof public.contact_requests language plpgsql security definer set search_path='' as $$
begin
    update public.contact_requests set email_status='FAILED',email_lease_id=null,email_lease_until=null
    where email_status='SENDING' and email_lease_until<now() and email_attempts>=8;
    return query
    with ready as (
        select id from public.contact_requests
        where (email_status='PENDING' or (email_status='SENDING' and email_lease_until<now()))
          and next_email_attempt_at<=now() and email_attempts<8
        order by created_at for update skip locked limit greatest(1,least(coalesce(p_limit,10),10))
    )
    update public.contact_requests c
    set email_status='SENDING', email_attempts=c.email_attempts+1,
        email_lease_id=gen_random_uuid(), email_lease_until=now()+interval '5 minutes'
    from ready where c.id=ready.id returning c.*;
end;
$$;

create or replace function public.finish_contact_email(p_id uuid,p_lease_id uuid,p_sent boolean,p_provider_id text default null)
returns void language plpgsql security definer set search_path='' as $$
begin
    update public.contact_requests set
        email_status=case when p_sent then 'SENT' when email_attempts>=8 then 'FAILED' else 'PENDING' end,
        email_sent_at=case when p_sent then now() else null end,
        provider_message_id=case when p_sent then left(p_provider_id,200) else null end,
        next_email_attempt_at=now()+make_interval(mins=>least(60,2^email_attempts)::integer),
        email_lease_until=null, email_lease_id=null
    where id=p_id and email_status='SENDING' and email_lease_id=p_lease_id;
end;
$$;

revoke all on function public.submit_contact_request(uuid,text,text,text,text),
    public.claim_contact_email_batch(integer), public.finish_contact_email(uuid,uuid,boolean,text)
from public, anon, authenticated;
grant execute on function public.submit_contact_request(uuid,text,text,text,text) to authenticated;
grant execute on function public.claim_contact_email_batch(integer), public.finish_contact_email(uuid,uuid,boolean,text) to service_role;
commit;
