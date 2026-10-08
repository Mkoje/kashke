-- Run as postgres after migrations. Fixtures are rolled back.
begin;
do $$
declare
    b uuid := gen_random_uuid();
    l uuid := gen_random_uuid();
    other uuid := gen_random_uuid();
    r uuid;
    unrelated uuid;
    accepted uuid;
    pending uuid;
    terms uuid;
    keep uuid;
    funded bigint;
begin
    insert into auth.users (id, email, raw_user_meta_data)
    select id, id::text || '@example.test', jsonb_build_object('full_name', name, 'username', 'test_' || replace(id::text, '-', ''))
    from (values (b, 'John Isaac'), (l, 'Chosen supporter'), (other, 'Other supporter')) users(id, name);
    update public.wallets set balance = 10000 where user_id in (l, other);
    insert into public.connections(requester_id, recipient_id, status) values (b,l,'ACCEPTED'), (b,other,'ACCEPTED');
    foreach funded in array array[500,1000]::bigint[] loop
        perform set_config('request.jwt.claim.sub', b::text, true);
        r := public.create_loan_request(1000,'Test','Competing offers',30);
        unrelated := public.create_loan_request(200,'Test','Unrelated reservation',30);
        perform set_config('request.jwt.claim.sub', l::text, true);
        accepted := public.create_loan_offer(r,funded,2,30);
        perform set_config('request.jwt.claim.sub', other::text, true);
        pending := public.create_loan_offer(r,300,2,30);
        terms := public.create_loan_offer(r,400,2,30);
        keep := public.create_loan_offer(unrelated,200,2,30);
        perform set_config('request.jwt.claim.sub', b::text, true);
        perform public.request_loan_offer_terms(terms,1,30);
        perform public.accept_loan_offer(accepted);
        if (select locked_balance from public.wallets where user_id=other) <> 200
            or (select balance from public.wallets where user_id=other) <> 10000 then
            raise exception 'Competing reservations must be released without spending balance or releasing unrelated funds';
        end if;
        if (select count(*) from public.loan_offers where id in (pending,terms) and status='DECLINED'
            and requested_interest_rate is null and requested_repayment_period_days is null) <> 2
            or (select status from public.loan_offers where id=keep) <> 'OFFERED'
            or (select status from public.loan_offers where id=accepted) <> 'ACCEPTED' then
            raise exception 'Only competing active offers must close, including terms requests';
        end if;
        if (select count(*) from public.notifications where user_id=other and related_entity_id in (pending,terms)
            and type='LOAN_OFFER_DECLINED' and message like 'John Isaac already accepted an offer from another person.%') <> 2 then
            raise exception 'Each competing offer must notify its owner with the borrower name';
        end if;
        begin
            perform public.accept_loan_offer(accepted);
            raise exception 'Duplicate acceptance unexpectedly succeeded';
        exception when others then
            if sqlerrm <> 'Offer is no longer available' then raise; end if;
        end;
        begin
            perform public.accept_loan_offer(pending);
            raise exception 'Closed offer acceptance unexpectedly succeeded';
        exception when others then
            if sqlerrm <> 'Offer is no longer available' then raise; end if;
        end;
        if (select count(*) from public.notifications where user_id=other and related_entity_id in (pending,terms)
            and type='LOAN_OFFER_DECLINED') <> 2 then
            raise exception 'Retry must not duplicate notifications';
        end if;
        perform public.cancel_loan_request(unrelated);
    end loop;
end;
$$;
rollback;
