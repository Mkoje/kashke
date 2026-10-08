-- Run on a local migrated database as postgres. All fixture data is rolled back.
begin;
do $$
declare
    v_id uuid := gen_random_uuid();
    v_duplicate_id uuid := gen_random_uuid();
    v_username text := 'test_' || left(replace(v_id::text, '-', ''), 16);
    v_duplicate_rejected boolean := false;
begin
    if not public.is_username_available(v_username) then
        raise exception 'Fresh username must be available';
    end if;
    insert into auth.users (id, email, raw_user_meta_data)
    values (v_id, v_id::text || '@example.test',
        jsonb_build_object('full_name', 'Signup test', 'username', v_username, 'phone', '+254700000000'));
    if not exists (select 1 from public.profiles where id = v_id and full_name = 'Signup test' and phone = '+254700000000')
       or not exists (select 1 from public.wallets where user_id = v_id)
       or not exists (select 1 from public.lender_preferences where user_id = v_id) then
        raise exception 'Signup must provision all application rows without confirmation';
    end if;
    if public.is_username_available(upper(v_username)) then
        raise exception 'Username checks must be case insensitive';
    end if;
    perform public.provision_confirmed_user(v_id);
    begin
        insert into auth.users (id, email, raw_user_meta_data)
        values (v_duplicate_id, v_duplicate_id::text || '@example.test',
            jsonb_build_object('full_name', 'Duplicate test', 'username', upper(v_username)));
    exception when others then
        if sqlerrm <> 'That username is already in use.' then raise; end if;
        v_duplicate_rejected := true;
    end;
    if not v_duplicate_rejected or exists (select 1 from auth.users where id = v_duplicate_id) then
        raise exception 'Duplicate signup must roll back the Auth user';
    end if;
end;
$$;
rollback;
