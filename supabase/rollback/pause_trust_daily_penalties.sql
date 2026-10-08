-- Emergency operational pause only: run as postgres in hosted SQL Editor.
-- Leaves receipts, points, limits and money ledgers intact. This does not undo
-- penalties already applied and does not revert the Android/public RPC contract.
-- Restore normal processing by reapplying 202610040002_trust_reliability.sql.
begin;
create or replace function public.apply_daily_trust_penalties(p_user_id uuid)
returns void language plpgsql security definer set search_path = '' as $$
begin
    return;
end;
$$;
revoke all on function public.apply_daily_trust_penalties(uuid) from public,anon,authenticated;
commit;
