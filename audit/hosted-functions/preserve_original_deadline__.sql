CREATE OR REPLACE FUNCTION public.preserve_original_deadline()
 RETURNS trigger
 LANGUAGE plpgsql
 SET search_path TO ''
AS $function$
BEGIN

    IF TG_OP = 'INSERT' THEN
        NEW.original_due_date := NEW.due_date;
    ELSE
        NEW.original_due_date := OLD.original_due_date;
    END IF;

    RETURN NEW;
END;
$function$
