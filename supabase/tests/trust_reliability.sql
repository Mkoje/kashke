-- Isolated financial policy scenarios. Fixtures roll back after assertions.
begin;
do $$
declare a uuid:=gen_random_uuid(); b uuid:=gen_random_uuid(); r uuid; l uuid;
    score integer; samples integer; value integer; prior integer; points integer;
    peak integer; expected_rate integer; repayment_operation uuid:=gen_random_uuid();
begin
    insert into auth.users(id,email,raw_user_meta_data) values
        (a,'trust-v2-a@example.test',jsonb_build_object('full_name','Mkoje Emmanuel','username','trust_v2_a')),
        (b,'trust-v2-b@example.test',jsonb_build_object('full_name','Juma','username','trust_v2_b'));
    select trust_score into value from public.profiles where id=a;
    if value<>0 then raise exception 'New points must start at zero'; end if;
    select * into score,samples from public.loan_reliability(a);
    if score is not null or samples<>0 then raise exception 'No debt must be inconclusive'; end if;
    insert into public.loan_requests(borrower_id,requested_amount,remaining_amount,purpose,description)
    values(a,100,100,'Policy test','Policy test') returning id into r;
    insert into public.loans(loan_request_id,borrower_id,lender_id,principal,interest_rate,interest_amount,total_repayment,due_date)
    values(r,a,b,100,0,0,100,now()+interval '6 days') returning id into l;
    select * into score,samples from public.loan_reliability(a);
    if score is not null or samples<>0 then raise exception 'Future debt excluded'; end if;
    update public.loans set due_date=now()-interval '2 days' where id=l;
    select * into score,samples from public.loan_reliability(a);
    if score<>0 or samples<>1 then raise exception 'Unpaid due debt lowers reliability'; end if;
    update public.kash_trust_state set policy_started_at=now()-interval '10 days' where user_id=a;
    perform public.apply_daily_trust_penalties(a);
    select trust_score,borrowing_limit into points,value from public.profiles where id=a;
    if points<>980 or value<>trunc(trunc(32000*power(0.98::numeric,1.0/7))*power(0.98::numeric,1.0/7)) then
        raise exception 'Two days must wrap points and truncate daily limits: %, %',points,value;
    end if;
    perform public.apply_daily_trust_penalties(a);
    if (select count(*) from public.kash_trust_daily_penalties where user_id=a)<>2 then raise exception 'Daily retries duplicate'; end if;
    perform set_config('request.jwt.claim.sub',b::text,true);
    perform public.mark_loan_overdue(l);
    perform public.grant_grace_period(l,7);
    select * into score,samples from public.loan_reliability(a);
    if score is not null or samples<>0 then raise exception 'Active grace excluded'; end if;
    prior:=value;
    perform public.apply_daily_trust_penalties(a);
    if (select borrowing_limit from public.profiles where id=a)<>prior then raise exception 'Grace must pause penalty'; end if;
    if (select original_due_date=due_date from public.loans where id=l) then raise exception 'Original deadline overwritten'; end if;
    update public.wallets set balance=1000 where user_id=a;
    perform set_config('request.jwt.claim.sub',a::text,true);
    select trust_score into prior from public.profiles where id=a;
    perform public.repay_loan_once(l,100,repayment_operation);
    perform public.refresh_account_state();
    if (select trust_score from public.profiles where id=a)<>prior+2 then
        raise exception 'Repayment points must persist on the profile after account refresh';
    end if;
    if not exists(select 1 from public.notifications where user_id=a and related_entity_id=l
        and type='TRUST_INCREASED') then raise exception 'Profile reward must accompany trust notification'; end if;
    perform public.repay_loan_once(l,100,repayment_operation);
    if (select trust_score from public.profiles where id=a)<>prior+2 then
        raise exception 'Payment retry must not duplicate profile points';
    end if;
    select * into score,samples from public.loan_reliability(a);
    if score<>100 or samples<>1 then raise exception 'Grace settlement counts as compliant'; end if;
    -- Fixture's original deadline was future, so reward is two points.
    if (select e.points from public.trust_events e where user_id=a and event_type='LOAN_COMPLETED')<>2 then raise exception 'On-time completion reward'; end if;
    insert into public.loans(loan_request_id,borrower_id,lender_id,principal,interest_rate,interest_amount,total_repayment,due_date)
    values(r,a,b,100,0,0,100,now()-interval '1 day') returning id into l;
    perform set_config('request.jwt.claim.sub',b::text,true);
    perform public.mark_loan_overdue(l);
    perform public.grant_grace_period(l,7);
    perform set_config('request.jwt.claim.sub',a::text,true);
    perform public.repay_loan_once(l,50,gen_random_uuid());
    if exists(select 1 from public.trust_events where related_loan_id=l and event_type='LOAN_COMPLETED') then raise exception 'Partial repayment must not award points'; end if;
    perform public.repay_loan_once(l,50,gen_random_uuid());
    if (select e.points from public.trust_events e where related_loan_id=l and event_type='LOAN_COMPLETED')<>1 then raise exception 'Grace reward must be one point'; end if;
    -- A fresh unresolved overdue agreement now makes two of three compliant.
    insert into public.loans(loan_request_id,borrower_id,lender_id,principal,interest_rate,interest_amount,total_repayment,due_date)
    values(r,a,b,100,0,0,100,now()-interval '1 day');
    select * into score,samples from public.loan_reliability(a);
    if score<>66 or samples<>3 then raise exception 'Reliability must include unresolved debts and exclude partial transaction counts'; end if;
    perform public.record_trust_event(b,'COMMUNITY_SUPPORT',1,'Supported Mkoje Emmanuel',l);
    perform public.record_trust_event(b,'COMMUNITY_SUPPORT',1,'Repeated',l);
    if (select trust_score from public.profiles where id=b)<>2 then raise exception 'Support reward exactly once'; end if;
    update public.profiles set trust_score=998,borrowing_limit=12345,available_limit=12345 where id=b;
    update public.kash_trust_state set clean_cycle=true,cycle_peak=998 where user_id=b;
    perform public.record_trust_event(b,'COMMUNITY_SUPPORT',1,'Milestone',null);
    if (select trust_score from public.profiles where id=b)<>0 or
        (select borrowing_limit from public.profiles where id=b)<>trunc(trunc(12345*1.02)*1.02) then
        raise exception 'Clean milestone must compound two truncated bonuses'; end if;
    update public.profiles set trust_score=998,borrowing_limit=12345,available_limit=12345 where id=b;
    update public.kash_trust_state set clean_cycle=false,cycle_peak=998 where user_id=b;
    perform public.record_trust_event(b,'COMMUNITY_SUPPORT',1,'Milestone with deduction',null);
    if (select borrowing_limit from public.profiles where id=b)<>trunc(12345*1.02) then raise exception 'Unclean milestone gets only base reward'; end if;
    for peak,expected_rate in select * from (values(500,0),(501,10),(600,10),(601,15),(700,15),(701,25),(800,25),(801,30),(900,30),(901,35),(1000,40)) t loop
        update public.profiles set trust_score=10,borrowing_limit=10000,available_limit=10000 where id=b;
        update public.kash_trust_state set cycle_peak=peak,clean_cycle=false,decline_cycles=0 where user_id=b;
        perform public.record_trust_event(b,'DAILY_DELAY',-10,'Threshold test',null);
        if (select borrowing_limit from public.profiles where id=b)<>trunc(trunc(10000*power(0.98::numeric,1.0/7))*(1-expected_rate/100.0)) then
            raise exception 'Threshold % failed',peak; end if;
    end loop;
    update public.profiles set trust_score=10,borrowing_limit=10000,available_limit=10000 where id=b;
    update public.kash_trust_state set cycle_peak=501,decline_cycles=1 where user_id=b;
    perform public.record_trust_event(b,'DAILY_DELAY',-10,'Repeat decline',null);
    if (select borrowing_limit from public.profiles where id=b)<>trunc(trunc(10000*power(0.98::numeric,1.0/7))*0.85) then raise exception 'Repeat offence adds five percent'; end if;
    if exists(select 1 from information_schema.parameters where specific_schema='public'
        and (specific_name like 'public_profile_directory%' or specific_name like 'get_direct_request_borrower_summary%')
        and parameter_name='trust_score') then raise exception 'Public RPC exposes private points'; end if;
    if not exists(select 1 from public.notifications where user_id=b and message like 'Mkoje Emmanuel%overdue%') then raise exception 'Overdue notice must name Mkoje Emmanuel'; end if;
end $$;
rollback;
