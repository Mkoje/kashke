CREATE OR REPLACE FUNCTION public.ensure_current_profile()
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
    v_user auth.users%rowtype;
    v_username text;
    v_full_name text;
    v_phone text;
begin

    if auth.uid() is null then
        raise exception 'Not authenticated';
    end if;

    select *
    into v_user
    from auth.users
    where id = auth.uid();

    v_full_name :=
        coalesce(
            nullif(v_user.raw_user_meta_data ->> 'full_name', ''),
            'KashKe User'
        );

    v_username :=
        lower(
            coalesce(
                nullif(v_user.raw_user_meta_data ->> 'username', ''),
                'user_' || substr(v_user.id::text, 1, 8)
            )
        );

    v_phone :=
        coalesce(
            v_user.raw_user_meta_data ->> 'phone',
            ''
        );

    insert into public.profiles (
        id,
        full_name,
        username,
        phone
    )
    values (
        v_user.id,
        v_full_name,
        v_username,
        v_phone
    )
    on conflict (id) do nothing;

    insert into public.wallets (
        user_id,
        balance,
        locked_balance
    )
    values (
        v_user.id,
        0,
        0
    )
    on conflict (user_id) do nothing;

end;
$function$
