-- Email Auth signup; a real Kenyan mobile is required at profile completion.
-- No Auth accounts or financial records are changed.
alter table public.profiles add column if not exists profile_email text not null default '';

create or replace function public.canonical_member_phone(p_phone text)
returns text language sql immutable set search_path='' as $$
select case
 when d ~ '^254[17][0-9]{8}$' then '+'||d
 when d ~ '^0[17][0-9]{8}$' then '+254'||substring(d from 2)
 when d ~ '^[17][0-9]{8}$' then '+254'||d
 else null end from (select regexp_replace(coalesce(p_phone,''),'[^0-9]','','g') d) s;
$$;
revoke all on function public.canonical_member_phone(text) from public,anon,authenticated;

create unique index if not exists profiles_unique_mobile
on public.profiles(public.canonical_member_phone(phone))
where public.canonical_member_phone(phone) is not null;

create or replace function public.member_profile_complete(p_user_id uuid)
returns boolean language sql stable security definer set search_path='' as $$
select coalesce((select length(trim(full_name))>0 and username ~ '^[a-zA-Z0-9_]{3,24}$'
 and username !~ '^user_[a-f0-9]{19}$'
 and trim(profile_email) ~ '^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$'
 and public.canonical_member_phone(phone) is not null
 from public.profiles where id=p_user_id),false);
$$;
revoke all on function public.member_profile_complete(uuid) from public,anon,authenticated;

-- Permit the authenticated owner to fill an empty phone exactly once.
create or replace function public.guard_member_phone()
returns trigger language plpgsql security definer set search_path='' as $$
begin
 if auth.uid() is not null and new.phone is distinct from old.phone then
   if old.id<>auth.uid() or nullif(trim(old.phone),'') is not null then
     raise exception 'Phone changes require account verification';
   end if;
   new.phone:=public.canonical_member_phone(new.phone);
   if new.phone is null then raise exception 'Enter a valid Kenyan mobile number'; end if;
 end if;
 return new;
end;
$$;
revoke all on function public.guard_member_phone() from public,anon,authenticated;
drop trigger if exists guard_member_phone on public.profiles;
create trigger guard_member_phone before update of phone on public.profiles
for each row execute function public.guard_member_phone();

-- Keep the previous three-argument API for existing clients. The new app uses
-- this overload to save all required profile fields atomically.
create or replace function public.complete_member_profile(
 p_full_name text,p_username text,p_email text,p_phone text)
returns void language plpgsql security definer set search_path='' as $$
declare v_phone text; v_existing text;
begin
 if auth.uid() is null then raise exception 'Sign in first'; end if;
 v_phone:=public.canonical_member_phone(p_phone);
 if v_phone is null then raise exception 'Enter a valid Kenyan mobile number'; end if;
 if p_full_name is null or length(trim(p_full_name))=0 or p_username is null
 or lower(trim(p_username)) !~ '^[a-z0-9_]{3,24}$'
 or lower(trim(p_username)) ~ '^user_[a-f0-9]{19}$'
 or p_email is null or trim(p_email) !~ '^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$'
 then raise exception 'Complete full name, username, email and phone number'; end if;
 select phone into v_existing from public.profiles where id=auth.uid() for update;
 if not found then raise exception 'Profile unavailable'; end if;
 if nullif(trim(v_existing),'') is not null
 and public.canonical_member_phone(v_existing) is distinct from v_phone
 then raise exception 'Phone changes require account verification'; end if;
 update public.profiles set full_name=trim(p_full_name),username=lower(trim(p_username)),
 profile_email=lower(trim(p_email)),
 phone=case when nullif(trim(v_existing),'') is null then v_phone else v_existing end
 where id=auth.uid();
end;
$$;
revoke all on function public.complete_member_profile(text,text,text,text) from public,anon,authenticated;
grant execute on function public.complete_member_profile(text,text,text,text) to authenticated;
