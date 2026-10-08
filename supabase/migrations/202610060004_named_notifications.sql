-- Name participants before notifications reach either the inbox or push webhook.
CREATE OR REPLACE FUNCTION public.name_notification_participants()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE first_id uuid; second_id uuid; actor_id uuid; actor_name text; label text;
BEGIN
 IF NEW.type IN ('CONNECTION_REQUEST','CONNECTION_ACCEPTED','CONNECTION_REJECTED') THEN
  SELECT requester_id,recipient_id INTO first_id,second_id FROM public.connections WHERE id=NEW.related_entity_id;
 ELSIF NEW.type IN ('LOAN_OFFER','LOAN_OFFER_TERMS_REQUESTED','LOAN_OFFER_UPDATED','LOAN_OFFER_DECLINED','LOAN_OFFER_CANCELLED') THEN
  SELECT borrower_id,lender_id INTO first_id,second_id FROM public.loan_offers WHERE id=NEW.related_entity_id;
 ELSIF NEW.type IN ('DIRECT_REQUEST','NEW_LOAN_REQUEST') THEN
  SELECT borrower_id,direct_request_to INTO first_id,second_id FROM public.loan_requests WHERE id=NEW.related_entity_id;
 ELSIF NEW.type IN ('LOAN_ACCEPTED','LOAN_REPAID','LOAN_PAYMENT','LOAN_DUE_SOON','LOAN_OVERDUE','LOAN_DEFAULTED','GRACE_PERIOD_GRANTED','RECOVERED_EXTERNALLY') THEN
  SELECT borrower_id,lender_id INTO first_id,second_id FROM public.loans WHERE id=NEW.related_entity_id;
 ELSE RETURN NEW;
 END IF;
 -- Never infer the actor from auth.uid(): reminders run without a signed-in actor.
 IF NEW.user_id=first_id THEN actor_id:=second_id;
 ELSIF NEW.user_id=second_id OR NEW.type='NEW_LOAN_REQUEST' THEN actor_id:=first_id;
 ELSE RETURN NEW;
 END IF;
 SELECT coalesce(nullif(btrim(full_name),''),nullif(btrim(username),'')) INTO actor_name
 FROM public.profiles WHERE id=actor_id;
 IF actor_name IS NULL THEN RETURN NEW; END IF;
 -- Literal replacements avoid treating punctuation in names as regex syntax.
 FOREACH label IN ARRAY ARRAY['Kash.ke member','Kash.ke Member','The borrower','the borrower','Your borrower','your borrower',
  'A supporter','a supporter','The supporter','the supporter','Your supporter','your supporter',
  'Your lender','your lender','A lender','a lender'] LOOP
  NEW.message:=replace(NEW.message,label,actor_name);
  NEW.title:=replace(NEW.title,label,actor_name);
 END LOOP;
 NEW.message:=replace(NEW.message,'A loan you supported',actor_name || '''s loan');
 IF NEW.message IN ('You received a connection request.','Your connection request was accepted.','Your connection request was declined.') THEN
  NEW.message:=actor_name || CASE NEW.type WHEN 'CONNECTION_REQUEST' THEN ' sent you a connection request.'
   WHEN 'CONNECTION_ACCEPTED' THEN ' accepted your connection request.' ELSE ' declined your connection request.' END;
 ELSIF NEW.type IN ('LOAN_PAYMENT','LOAN_REPAID') THEN
  IF NEW.user_id=first_id AND NEW.message ~ '^KSh [0-9]+ repaid\.$' THEN
   NEW.message:=replace(NEW.message,' repaid.',' repaid to ' || actor_name || '.');
  ELSIF NEW.user_id=second_id AND NEW.message ~ '^KSh [0-9]+ received\.$' THEN
   NEW.message:=replace(NEW.message,' received.',' received from ' || actor_name || '.');
  END IF;
 ELSIF NEW.type='LOAN_DUE_SOON' AND NEW.message='Your repayment is due within three days.' THEN
  NEW.message:='Your repayment to ' || actor_name || ' is due within three days.';
 ELSIF NEW.type='LOAN_DEFAULTED' THEN
  IF NEW.message='The unpaid agreement defaulted. No further grace is available.' THEN
   NEW.message:=actor_name || '''s unpaid agreement defaulted. No further grace is available.';
  ELSIF NEW.message='The repayment deadline passed. No further grace is available. You can still repay this debt.' THEN
   NEW.message:='The repayment deadline for your agreement with ' || actor_name || ' passed. No further grace is available. You can still repay this debt.';
  END IF;
 ELSIF NEW.type='GRACE_PERIOD_GRANTED' AND NEW.user_id=second_id AND NEW.message ~ '^You granted [0-9]+ additional days\.$' THEN
  NEW.message:=replace(NEW.message,' additional days.',' additional days to ' || actor_name || '.');
 END IF;
 RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION public.name_notification_participants() FROM PUBLIC,anon,authenticated;
DROP TRIGGER IF EXISTS name_notification_participants ON public.notifications;
CREATE TRIGGER name_notification_participants BEFORE INSERT OR UPDATE OF message,title
 ON public.notifications FOR EACH ROW EXECUTE FUNCTION public.name_notification_participants();
-- Preserve IDs, timestamps and read state while repairing existing inbox messages.
UPDATE public.notifications SET message=message;
