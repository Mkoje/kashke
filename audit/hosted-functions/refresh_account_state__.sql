CREATE OR REPLACE FUNCTION public.refresh_account_state()
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
begin

    if auth.uid() is null then
        raise exception 'Not authenticated';
    end if;

    perform public.ensure_current_profile();

end;
$function$
