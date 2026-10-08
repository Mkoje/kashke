CREATE OR REPLACE FUNCTION public.complete_member_profile(p_email text, p_full_name text, p_username text)
 RETURNS profiles
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
    v_user_id uuid;
    v_profile public.profiles;
begin
    -- The logged-in Supabase Auth user
    v_user_id := auth.uid();

    if v_user_id is null then
        raise exception 'Not authenticated';
    end if;

    -- Basic validation
    if nullif(trim(p_full_name), '') is null then
        raise exception 'Full name is required';
    end if;

    if nullif(trim(p_username), '') is null then
        raise exception 'Username is required';
    end if;

    -- Update the existing profile.
    update public.profiles
    set
        full_name = trim(p_full_name),
        username = trim(p_username)
    where id = v_user_id
    returning * into v_profile;

    -- If no profile exists yet, create it using your table defaults.
    if not found then
        insert into public.profiles (
            id,
            full_name,
            username
        )
        values (
            v_user_id,
            trim(p_full_name),
            trim(p_username)
        )
        returning * into v_profile;
    end if;

    return v_profile;
end;
$function$
