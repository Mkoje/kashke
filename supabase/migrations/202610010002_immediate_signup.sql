-- Supersedes confirmation-gated provisioning. Hosted Auth must also have
-- Confirm email disabled to issue a session immediately after signup.
create or replace function public.provision_confirmed_user(p_user_id uuid)
returns void language plpgsql security definer set search_path = '' as $$
declare
    v_user auth.users%rowtype;
    v_username text;
begin
    select * into v_user from auth.users where id = p_user_id for update;
    if not found then raise exception 'Account does not exist.'; end if;
    if not exists (select 1 from public.profiles where id = p_user_id) then
        v_username := lower(trim(coalesce(v_user.raw_user_meta_data ->> 'username', '')));
        if v_username !~ '^[a-z0-9_]{3,24}$' then
            raise exception 'Username must be 3-24 letters, numbers, or underscores.';
        end if;
        -- Serialize competing signups for the same normalized username.
        perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(v_username, 0));
        if exists (select 1 from public.profiles where lower(username) = v_username) then
            raise exception 'That username is already in use.';
        end if;
        insert into public.profiles (id, full_name, username, phone)
        values (p_user_id,
            coalesce(nullif(trim(v_user.raw_user_meta_data ->> 'full_name'), ''), split_part(v_user.email, '@', 1)),
            v_username, coalesce(v_user.raw_user_meta_data ->> 'phone', ''));
    end if;
    insert into public.wallets (user_id) values (p_user_id) on conflict (user_id) do nothing;
    insert into public.lender_preferences (user_id) values (p_user_id) on conflict (user_id) do nothing;
end;
$$;
revoke all on function public.provision_confirmed_user(uuid) from public, anon, authenticated;

create or replace function public.handle_new_auth_user()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
    perform public.provision_confirmed_user(new.id);
    return new;
end;
$$;
drop trigger if exists on_auth_user_email_confirmed on auth.users;
drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created after insert on auth.users
for each row execute function public.handle_new_auth_user();

-- Exposes only availability, without granting anonymous access to profiles.
create or replace function public.is_username_available(p_username text)
returns boolean language sql stable security definer set search_path = '' as $$
    select lower(trim(p_username)) ~ '^[a-z0-9_]{3,24}$'
       and not exists (select 1 from public.profiles where lower(username) = lower(trim(p_username)));
$$;
revoke all on function public.is_username_available(text) from public, anon, authenticated;
grant execute on function public.is_username_available(text) to anon, authenticated;
-- profiles_username_lower_idx from the original schema remains the final
-- authority for uniqueness, including profile edits and concurrent requests.
