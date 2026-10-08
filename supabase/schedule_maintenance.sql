-- Run in the hosted SQL Editor as postgres after enabling Supabase Cron (pg_cron).
-- A named schedule replaces the same job when this file is run again.
select cron.schedule(
    'kash-ke-account-maintenance',
    '*/5 * * * *',
    'select public.maintain_account_data(null);'
);
