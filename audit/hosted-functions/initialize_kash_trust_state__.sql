CREATE OR REPLACE FUNCTION public.initialize_kash_trust_state()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
BEGIN

    INSERT INTO public.kash_trust_state(
        user_id,
        cycle_peak,
        clean_cycle
    )
    VALUES(
        NEW.id,
        NEW.trust_score,
        NEW.trust_score = 0
    )
    ON CONFLICT DO NOTHING;

    RETURN NEW;
END;
$function$
