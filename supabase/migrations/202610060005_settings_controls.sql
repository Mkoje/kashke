begin;

create table if not exists public.support_request_mutes (
    user_id uuid not null references public.profiles(id) on delete cascade,
    muted_user_id uuid not null references public.profiles(id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key (user_id, muted_user_id),
    check (user_id <> muted_user_id)
);
alter table public.support_request_mutes enable row level security;
drop policy if exists own_support_mutes on public.support_request_mutes;
create policy own_support_mutes on public.support_request_mutes for select to authenticated using (user_id = auth.uid());
revoke all on public.support_request_mutes from anon, authenticated;
grant select on public.support_request_mutes to authenticated;

create or replace function public.set_support_request_mute(p_member_id uuid, p_muted boolean)
returns void language plpgsql security definer set search_path = '' as $$
begin
    if auth.uid() is null then raise exception 'Sign in first' using errcode = '28000'; end if;
    if p_member_id = auth.uid() then raise exception 'Cannot mute yourself'; end if;
    if p_muted then
        insert into public.support_request_mutes(user_id, muted_user_id) values (auth.uid(), p_member_id) on conflict do nothing;
        delete from public.notifications n using public.loan_requests r
        where n.user_id = auth.uid() and n.related_entity_id = r.id and r.borrower_id = p_member_id
          and n.type in ('DIRECT_REQUEST', 'NEW_LOAN_REQUEST');
    else
        delete from public.support_request_mutes where user_id = auth.uid() and muted_user_id = p_member_id;
    end if;
end;
$$;

create or replace function public.suppress_muted_support_notification()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
    if new.type in ('DIRECT_REQUEST', 'NEW_LOAN_REQUEST') and exists (
        select 1 from public.support_request_mutes m join public.loan_requests r on r.borrower_id = m.muted_user_id
        where m.user_id = new.user_id and r.id = new.related_entity_id
    ) then return null; end if;
    return new;
end;
$$;
drop trigger if exists suppress_muted_support_notification on public.notifications;
create trigger suppress_muted_support_notification before insert on public.notifications
for each row execute function public.suppress_muted_support_notification();

create or replace function public.disconnect_member(p_member_id uuid)
returns void language plpgsql security definer set search_path = '' as $$
begin
    if auth.uid() is null then raise exception 'Sign in first' using errcode = '28000'; end if;
    update public.connections set status = 'REMOVED'
    where status = 'ACCEPTED' and
      ((requester_id = auth.uid() and recipient_id = p_member_id) or
       (recipient_id = auth.uid() and requester_id = p_member_id));
    if not found then raise exception 'Accepted connection not found'; end if;
end;
$$;

revoke all on function public.set_support_request_mute(uuid,boolean), public.disconnect_member(uuid),
    public.suppress_muted_support_notification() from public, anon, authenticated;
grant execute on function public.set_support_request_mute(uuid,boolean), public.disconnect_member(uuid) to authenticated;
commit;
