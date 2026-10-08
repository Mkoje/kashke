CREATE OR REPLACE FUNCTION public.is_username_available(p_username text)
 RETURNS boolean
 LANGUAGE sql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
    select not exists (
        select 1
        from public.profiles
        where lower(username) =
              lower(trim(leading '@' from p_username))
    );
$function$
