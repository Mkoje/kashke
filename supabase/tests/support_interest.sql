-- Exercise every allowed whole rate through creation and acceptance.
begin;
do $$
declare
    borrower uuid := gen_random_uuid(); supporter uuid := gen_random_uuid();
    request_id uuid; offer_id uuid; loan_id uuid; rate integer;
begin
    insert into auth.users(id,email,raw_user_meta_data) values
        (borrower,'support-rates-b@example.test','{"full_name":"Rate borrower","username":"rate_borrower"}'),
        (supporter,'support-rates-s@example.test','{"full_name":"Rate supporter","username":"rate_supporter"}');
    insert into public.connections(requester_id,recipient_id,status) values(borrower,supporter,'ACCEPTED');
    update public.wallets set balance=100000 where user_id=supporter;
    insert into public.lender_preferences(user_id,default_interest_rate) values(supporter,6)
        on conflict (user_id) do update set default_interest_rate=excluded.default_interest_rate;
    update public.lender_preferences set default_interest_rate=7 where user_id=supporter;
    for rate in 0..15 loop
        update public.profiles set borrowing_limit=100000,
            available_limit=case when rate>7 then 1000 else 100000 end where id=borrower;
        perform set_config('request.jwt.claim.sub',borrower::text,true);
        request_id := public.create_loan_request(2000,'Rate test','Rate test',17,
            case when rate>7 then supporter else null end,rate>7);
        perform set_config('request.jwt.claim.sub',supporter::text,true);
        offer_id := public.create_loan_offer(request_id,2000,rate,17,rate>7);
        if (select total_repayment from public.loan_offers where id=offer_id)
            <> 2000+round(2000*rate*17/36500.0)::bigint then
            raise exception 'Incorrect repayment at rate %',rate;
        end if;
        -- Negotiation must also store 6% and 7% without the old requested-rate check.
        if rate in (6,7) then
            perform set_config('request.jwt.claim.sub',borrower::text,true);
            perform public.request_loan_offer_terms(offer_id,rate,17);
            perform set_config('request.jwt.claim.sub',supporter::text,true);
            perform public.respond_loan_offer_terms(offer_id,true);
        end if;
        perform set_config('request.jwt.claim.sub',borrower::text,true);
        loan_id := public.accept_loan_offer(offer_id);
        if (select interest_rate from public.loans where id=loan_id)<>rate then
            raise exception 'Accepted rate changed';
        end if;
        perform set_config('request.jwt.claim.sub',supporter::text,true);
        begin
            perform public.create_loan_offer(request_id,1,rate,17,rate>7);
            raise exception 'Closed request allowed another offer';
        exception when raise_exception then
            if sqlerrm='Closed request allowed another offer' then raise; end if;
        end;
    end loop;
    update public.profiles set borrowing_limit=100000,available_limit=100000 where id=borrower;
    perform set_config('request.jwt.claim.sub',borrower::text,true);
    request_id := public.create_loan_request(2000,'Rate test','Rate ceilings',17);
    perform set_config('request.jwt.claim.sub',supporter::text,true);
    begin
        perform public.create_loan_offer(request_id,2000,8,17);
        raise exception 'Normal ceiling bypassed';
    exception when raise_exception then
        if sqlerrm='Normal ceiling bypassed' then raise; end if;
    end;
    update public.profiles set available_limit=1000 where id=borrower;
    perform set_config('request.jwt.claim.sub',borrower::text,true);
    request_id := public.create_loan_request(2000,'Rate test','Direct ceiling',17,supporter,true);
    perform set_config('request.jwt.claim.sub',supporter::text,true);
    begin
        perform public.create_loan_offer(request_id,2000,16,17,true);
        raise exception 'Direct ceiling bypassed';
    exception when raise_exception then
        if sqlerrm='Direct ceiling bypassed' then raise; end if;
    end;
    begin
        perform public.create_loan_offer(request_id,2000,15,17,false);
        raise exception 'Explicit approval bypassed';
    exception when raise_exception then
        if sqlerrm='Explicit approval bypassed' then raise; end if;
    end;
end $$;
rollback;
