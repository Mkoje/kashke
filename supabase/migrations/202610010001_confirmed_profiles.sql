-- Auth holds pending registration metadata; application rows require verified email.
create or replace function public.provision_confirmed_user(p_user_id uuid)
returns void language plpgsql security definer set search_path = '' as $$
declare
    v_user auth.users%rowtype;
    v_username text;
begin
    select * into v_user from auth.users where id = p_user_id for update;
    if not found or v_user.email_confirmed_at is null then
        raise exception 'Confirm your email address before creating your profile.';
    end if;
    v_username := lower(trim(coalesce(v_user.raw_user_meta_data ->> 'username', '')));
    if v_username !~ '^[a-z0-9_]{3,24}$' then
        v_username := 'user_' || left(replace(p_user_id::text, '-', ''), 12);
    end if;
    -- A username may have been claimed while the confirmation email was pending.
    -- Keep confirmation working; the user can choose another username afterwards.
    if exists (select 1 from public.profiles where lower(username) = v_username and id <> p_user_id) then
        v_username := 'user_' || replace(p_user_id::text, '-', '');
    end if;
    insert into public.profiles (id, full_name, username, phone)
    values (p_user_id,
        coalesce(nullif(trim(v_user.raw_user_meta_data ->> 'full_name'), ''), split_part(v_user.email, '@', 1)),
        v_username, coalesce(v_user.raw_user_meta_data ->> 'phone', ''))
    on conflict (id) do nothing;
    insert into public.wallets (user_id) values (p_user_id) on conflict (user_id) do nothing;
    insert into public.lender_preferences (user_id) values (p_user_id) on conflict (user_id) do nothing;
end;
$$;
revoke all on function public.provision_confirmed_user(uuid) from public, anon, authenticated;

create or replace function public.handle_new_auth_user()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
    if new.email_confirmed_at is not null then
        perform public.provision_confirmed_user(new.id);
    end if;
    return new;
end;
$$;
drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created after insert on auth.users
for each row execute function public.handle_new_auth_user();
drop trigger if exists on_auth_user_email_confirmed on auth.users;
create trigger on_auth_user_email_confirmed
after update of email_confirmed_at on auth.users
for each row when (old.email_confirmed_at is null and new.email_confirmed_at is not null)
execute function public.handle_new_auth_user();

-- Repair confirmed accounts at login without allowing clients to choose an ID,
-- financial defaults, or confirmation status.
create or replace function public.ensure_current_profile()
returns void language plpgsql security definer set search_path = '' as $$
begin
    if auth.uid() is null then raise exception 'Sign in first.'; end if;
    perform public.provision_confirmed_user(auth.uid());
end;
$$;
revoke all on function public.ensure_current_profile() from public, anon, authenticated;
grant execute on function public.ensure_current_profile() to authenticated;

-- Preserve existing rows, and recover previously confirmed registrations.
do $$
declare v_id uuid;
begin
    for v_id in select id from auth.users where email_confirmed_at is not null loop
        perform public.provision_confirmed_user(v_id);
    end loop;
end;
$$;
