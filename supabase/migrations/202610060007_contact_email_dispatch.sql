begin;
create extension if not exists pg_net with schema extensions;
create extension if not exists pg_cron;
create extension if not exists supabase_vault with schema vault;
revoke all on vault.decrypted_secrets,vault.secrets from public,anon,authenticated;

-- Secrets are installed by the server deployment script, never embedded in app code.
create or replace function public.dispatch_contact_email()
returns void language plpgsql security definer set search_path='' as $$
declare endpoint text; webhook_secret text;
begin
    select decrypted_secret into endpoint from vault.decrypted_secrets where name='contact_mail_function_url' limit 1;
    select decrypted_secret into webhook_secret from vault.decrypted_secrets where name='contact_mail_webhook_secret' limit 1;
    if endpoint is null or webhook_secret is null then return; end if;
    perform net.http_post(url:=endpoint,headers:=jsonb_build_object('Content-Type','application/json','x-contact-secret',webhook_secret),body:='{}'::jsonb,timeout_milliseconds:=30000);
end;
$$;
create or replace function public.dispatch_new_contact_request()
returns trigger language plpgsql security definer set search_path='' as $$
begin
    -- Delivery issues must never discard a successfully saved form.
    begin perform public.dispatch_contact_email(); exception when others then null; end;
    return new;
end;
$$;
revoke all on function public.dispatch_contact_email(),public.dispatch_new_contact_request() from public,anon,authenticated;
drop trigger if exists contact_email_after_insert on public.contact_requests;
create trigger contact_email_after_insert after insert on public.contact_requests
for each statement execute function public.dispatch_new_contact_request();
do $$
begin
    if exists(select 1 from cron.job where jobname='kashke-contact-email-retry') then
        perform cron.unschedule('kashke-contact-email-retry');
    end if;
    perform cron.schedule('kashke-contact-email-retry','*/5 * * * *','select public.dispatch_contact_email()');
end;
$$;
commit;
