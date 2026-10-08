-- Public member card projection and live reliability; all fixtures roll back.
begin;
do $$
declare john uuid:=gen_random_uuid(); viewer uuid:=gen_random_uuid(); empty_member uuid:=gen_random_uuid();
    request_id uuid; row_data record; large_loan uuid;
begin
    insert into auth.users(id,email,raw_user_meta_data) values
    (john,'member-details-john@example.test',jsonb_build_object('username','details_john','full_name','John Kamau','phone','0712345678')),
    (viewer,'member-details-viewer@example.test',jsonb_build_object('username','details_viewer','full_name','Immanuel Wechuli')),
    (empty_member,'member-details-empty@example.test',jsonb_build_object('username','details_empty','full_name','Juma'));
    update public.profiles set borrowing_limit=10000,available_limit=10000,trust_score=700 where id=john;
    insert into public.loan_requests(borrower_id,requested_amount,remaining_amount,purpose,description)
    values(john,600,600,'Profile test','Profile test') returning id into request_id;
    insert into public.loans(loan_request_id,borrower_id,lender_id,principal,interest_rate,interest_amount,total_repayment,amount_paid,principal_paid,status,due_date,completed_at)
    values
        (request_id,john,viewer,100,0,0,100,100,100,'REPAID',now()-interval '1 day',now()-interval '2 days'),
        (request_id,john,viewer,200,0,0,200,50,50,'ACTIVE',now()+interval '1 day',null),
        (request_id,john,viewer,300,0,0,300,0,0,'OVERDUE',now()-interval '1 day',null);
    insert into public.transactions(type,sender_id,recipient_id,amount,status,description) values
        ('LOAN_FUNDED',viewer,john,100,'COMPLETED','Funded John'),
        ('LOAN_REPAYMENT',john,viewer,50,'COMPLETED','John paid'),
        ('LOAN_FUNDED',viewer,john,200,'PENDING','Pending excluded');
    perform set_config('request.jwt.claim.sub',viewer::text,true);
    set local role authenticated;
    select * into strict row_data from public.member_profile_details(john);
    if row_data.full_name<>'John Kamau' or row_data.phone<>'0712345678' then raise exception 'Member identity missing'; end if;
    if row_data.reliability_score<>50 or row_data.reliability_sample_size<>2 then raise exception 'Due-only reliability incorrect'; end if;
    if row_data.current_debt_total<>450 or row_data.current_debt_count<>2 then raise exception 'Outstanding debt must subtract partial payments'; end if;
    if row_data.borrowing_limit<>10000 or row_data.available_limit<>9550 then raise exception 'Live capacity incorrect'; end if;
    if row_data.previous_transaction_count<>2 or row_data.borrowing_count<>3 or row_data.completed_borrowing_count<>1 then raise exception 'History counts incorrect'; end if;
    if row_data.minimum_borrowed<>100 or row_data.maximum_borrowed<>300 or row_data.average_borrowed<>200 then raise exception 'Borrowing range/average incorrect'; end if;
    if row_data.member_since !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$' then raise exception 'Member since missing'; end if;
    if to_jsonb(row_data) ?| array['trust_score','wallet_balance','email','trust_events','cycle_peak'] then raise exception 'Private data leaked'; end if;
    if exists(select 1 from public.profiles where id=john) then raise exception 'Private profile row exposed'; end if;
    select * into strict row_data from public.member_profile_details(empty_member);
    if row_data.reliability_score is not null or row_data.reliability_sample_size<>0 then raise exception 'Empty history must be inconclusive'; end if;
    if row_data.minimum_borrowed is not null or row_data.average_borrowed is not null or row_data.current_debt_total<>0 then raise exception 'Empty history fabricated'; end if;
    if exists(select 1 from public.member_profile_details(gen_random_uuid())) then raise exception 'Unknown profile must return no rows'; end if;
    reset role;
    -- The index is live even if no owner's refresh/maintenance has run.
    update public.loans set due_date=now()-interval '1 day' where borrower_id=john and status='ACTIVE';
    select * into strict row_data from public.member_profile_details(john);
    if row_data.reliability_score<>33 or row_data.reliability_sample_size<>3 then raise exception 'Live denominator did not change'; end if;
    -- Sum can exceed Android Int even though every individual loan fits Int.
    insert into public.loans(loan_request_id,borrower_id,lender_id,principal,interest_rate,interest_amount,total_repayment,due_date)
    values (request_id,john,viewer,2000000000,0,0,2000000000,now()+interval '1 day'),
           (request_id,john,viewer,2000000000,0,0,2000000000,now()+interval '1 day');
    select * into strict row_data from public.member_profile_details(john);
    if row_data.current_debt_total<>4000000450 then raise exception 'Aggregate debt overflowed'; end if;
    if (select trust_score from public.profiles where id=john)<>700 then raise exception 'Viewing profile changed private points'; end if;
    set local role anon;
    begin
        perform public.member_profile_details(john);
        raise exception 'Anonymous profile access unexpectedly allowed';
    exception when insufficient_privilege then null; end;
    reset role;
end $$;
rollback;
