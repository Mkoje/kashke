create or replace function public.normalize_ke_phone(p_phone text)
returns text
language sql
immutable
strict
set search_path = ''
as $$
    with digits as (
        select pg_catalog.regexp_replace(p_phone, '[^0-9]', '', 'g') as value
    )
    select case
        when value ~ '^0[17][0-9]{8}$' then '254' || pg_catalog.substr(value, 2)
        when value ~ '^[17][0-9]{8}$' then '254' || value
        when value ~ '^254[17][0-9]{8}$' then value
        else value
    end
    from digits;
$$;
revoke all on function public.normalize_ke_phone(text) from public, anon, authenticated;

create index if not exists profiles_phone_normalized_idx
    on public.profiles (public.normalize_ke_phone(phone));

drop policy if exists "Authenticated users can view profiles" on public.profiles;
drop policy if exists "Users can view their own profile" on public.profiles;
create policy "Users can view their own profile" on public.profiles
    for select to authenticated using (id = auth.uid());

create or replace function public.public_profile_directory(
    p_query text default '',
    p_phone_numbers text[] default array[]::text[],
    p_profile_ids uuid[] default array[]::uuid[]
)
returns table (
    id uuid,
    full_name text,
    username text,
    phone text,
    bio text,
    location text,
    profile_image text,
    trust_score integer,
    borrowing_limit integer,
    available_limit integer,
    account_status text,
    created_at timestamptz,
    matched_phone text
)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
    v_query text := pg_catalog.lower(pg_catalog.btrim(coalesce(p_query, '')));
    v_query_digits text := pg_catalog.regexp_replace(coalesce(p_query, ''), '[^0-9]', '', 'g');
    v_phone_numbers text[] := coalesce(p_phone_numbers, array[]::text[]);
    v_profile_ids uuid[] := coalesce(p_profile_ids, array[]::uuid[]);
begin
    if auth.uid() is null then
        raise exception 'Sign in first' using errcode = '28000';
    end if;
    if pg_catalog.length(p_query) > 80 or pg_catalog.cardinality(v_phone_numbers) > 100
        or pg_catalog.cardinality(v_profile_ids) > 100 then
        raise exception 'Directory request is too large';
    end if;

    return query
    select
        profile.id,
        profile.full_name,
        profile.username,
        ''::text,
        profile.bio,
        profile.location,
        profile.profile_image,
        profile.trust_score,
        profile.borrowing_limit,
        profile.available_limit,
        profile.account_status,
        profile.created_at,
        matched.phone
    from public.profiles as profile
    left join lateral (
        select public.normalize_ke_phone(input.phone) as phone
        from pg_catalog.unnest(v_phone_numbers) as input(phone)
        where public.normalize_ke_phone(input.phone) = public.normalize_ke_phone(profile.phone)
        order by public.normalize_ke_phone(input.phone)
        limit 1
    ) as matched on true
    where
        (
            v_query = '' and pg_catalog.cardinality(v_phone_numbers) = 0
            and pg_catalog.cardinality(v_profile_ids) = 0
        )
        or (
            v_query <> '' and (
                pg_catalog.strpos(pg_catalog.lower(profile.full_name), v_query) > 0
                or pg_catalog.strpos(pg_catalog.lower(profile.username), v_query) > 0
                or (
                    pg_catalog.length(v_query_digits) >= 4
                    and pg_catalog.strpos(public.normalize_ke_phone(profile.phone), v_query_digits) > 0
                )
            )
        )
        or matched.phone is not null
        or profile.id = any(v_profile_ids)
    order by profile.full_name, profile.id;
end;
$$;
revoke all on function public.public_profile_directory(text, text[], uuid[]) from public, anon, authenticated;
grant execute on function public.public_profile_directory(text, text[], uuid[]) to authenticated;