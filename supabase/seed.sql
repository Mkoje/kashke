-- Local development only. Creates non-production Auth users with a shared demo password.
-- Run only against `supabase start` / `supabase db reset`, never against production.
insert into auth.users (
    id, instance_id, aud, role, email, encrypted_password, email_confirmed_at,
    raw_app_meta_data, raw_user_meta_data, created_at, updated_at
) values
    ('10000000-0000-4000-8000-000000000001', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'sarah.demo@kashke.local', crypt('kashke-demo-only', gen_salt('bf')), now(), '{"provider":"email","providers":["email"]}', '{"full_name":"Sarah Wanjiku","username":"sarah_demo","phone":"+254700000001","is_demo":true}', now(), now()),
    ('10000000-0000-4000-8000-000000000002', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'david.demo@kashke.local', crypt('kashke-demo-only', gen_salt('bf')), now(), '{"provider":"email","providers":["email"]}', '{"full_name":"David Kamau","username":"david_demo","phone":"+254700000002","is_demo":true}', now(), now()),
    ('10000000-0000-4000-8000-000000000003', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'mary.demo@kashke.local', crypt('kashke-demo-only', gen_salt('bf')), now(), '{"provider":"email","providers":["email"]}', '{"full_name":"Mary Achieng","username":"mary_demo","phone":"+2547000000003","is_demo":true}', now(), now()),
    ('10000000-0000-4000-8000-000000000004', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'john.demo@kashke.local', crypt('kashke-demo-only', gen_salt('bf')), now(), '{"provider":"email","providers":["email"]}', '{"full_name":"John Mwangi","username":"john_demo","phone":"+2547000000004","is_demo":true}', now(), now()),
    ('10000000-0000-4000-8000-000000000005', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'imman.demo@kashke.local', crypt('kashke-demo-only', gen_salt('bf')), now(), '{"provider":"email","providers":["email"]}', '{"full_name":"Mkoje Emmanuel","username":"imman_demo","phone":"+2547000000005","is_demo":true}', now(), now()),
    ('10000000-0000-4000-8000-000000000006', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'faith.demo@kashke.local', crypt('kashke-demo-only', gen_salt('bf')), now(), '{"provider":"email","providers":["email"]}', '{"full_name":"Faith Mumo","username":"faith_demo","phone":"+2547000000006","is_demo":true}', now(), now())
on conflict (id) do nothing;

update public.profiles set
    trust_score = case id
        when '10000000-0000-4000-8000-000000000001' then 847
        when '10000000-0000-4000-8000-000000000002' then 782
        when '10000000-0000-4000-8000-000000000003' then 831
        when '10000000-0000-4000-8000-000000000004' then 912
        when '10000000-0000-4000-8000-000000000005' then 956
        else 803 end,
    borrowing_limit = 50000,
    available_limit = 35000,
    bio = 'Kash.ke local demo profile',
    location = case when username = 'sarah_demo' then 'Nairobi' else 'Kenya' end,
    is_demo = true
where id in (
    '10000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000002',
    '10000000-0000-4000-8000-000000000003', '10000000-0000-4000-8000-000000000004',
    '10000000-0000-4000-8000-000000000005', '10000000-0000-4000-8000-000000000006'
);

insert into public.connections (id, requester_id, recipient_id, status, accepted_at)
values
    ('20000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000002', 'ACCEPTED', now()),
    ('20000000-0000-4000-8000-000000000002', '10000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000003', 'ACCEPTED', now()),
    ('20000000-0000-4000-8000-000000000003', '10000000-0000-4000-8000-000000000002', '10000000-0000-4000-8000-000000000004', 'ACCEPTED', now()),
    ('20000000-0000-4000-8000-000000000004', '10000000-0000-4000-8000-000000000003', '10000000-0000-4000-8000-000000000004', 'ACCEPTED', now())
on conflict (id) do nothing;

insert into public.loan_requests (
    id, borrower_id, requested_amount, funded_amount, remaining_amount, purpose, description, status,
    expires_at, preferred_period_days
) values
    ('30000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000001', 5000, 2000, 3000,
     'School fees', 'Partial funding example for a term payment.', 'PARTIALLY_FUNDED', now() + interval '5 days', 30),
    ('30000000-0000-4000-8000-000000000002', '10000000-0000-4000-8000-000000000003', 12000, 0, 12000,
     'Emergency expense', 'Open request available for partial lenders.', 'REQUESTED', now() + interval '7 days', 45),
    ('30000000-0000-4000-8000-000000000003', '10000000-0000-4000-8000-000000000004', 10000, 10000, 0,
     'Business stock', 'Active loan scenario.', 'FULLY_FUNDED', now() + interval '1 day', 60),
    ('30000000-0000-4000-8000-000000000004', '10000000-0000-4000-8000-000000000006', 7000, 7000, 0,
     'Farm supplies', 'Overdue loan scenario.', 'FULLY_FUNDED', now() - interval '20 days', 30)
on conflict (id) do nothing;

insert into public.loan_offers (
    id, loan_request_id, lender_id, amount, interest_rate, repayment_period_days, total_repayment, status
) values
    ('40000000-0000-4000-8000-000000000001', '30000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000002', 2000, 2, 30, 2003, 'ACCEPTED'),
    ('40000000-0000-4000-8000-000000000002', '30000000-0000-4000-8000-000000000003', '10000000-0000-4000-8000-000000000005', 10000, 2, 60, 10033, 'ACCEPTED'),
    ('40000000-0000-4000-8000-000000000003', '30000000-0000-4000-8000-000000000004', '10000000-0000-4000-8000-000000000004', 7000, 3, 30, 7017, 'ACCEPTED')
on conflict (id) do nothing;

insert into public.loans (
    id, loan_request_id, borrower_id, lender_id, principal, interest_rate, interest_amount,
    total_repayment, amount_paid, principal_paid, due_date, status, completed_at
) values
    ('50000000-0000-4000-8000-000000000001', '30000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000002', 2000, 2, 3, 2003, 0, 0, now() + interval '30 days', 'ACTIVE', null),
    ('50000000-0000-4000-8000-000000000002', '30000000-0000-4000-8000-000000000003', '10000000-0000-4000-8000-000000000004', '10000000-0000-4000-8000-000000000005', 10000, 2, 33, 10033, 10033, 10000, now() - interval '3 days', 'REPAID', now() - interval '4 days'),
    ('50000000-0000-4000-8000-000000000003', '30000000-0000-4000-8000-000000000004', '10000000-0000-4000-8000-000000000006', '10000000-0000-4000-8000-000000000004', 7000, 3, 17, 7017, 0, 0, now() - interval '5 days', 'OVERDUE', null)
on conflict (id) do nothing;

update public.profiles set borrowing_frozen = true where id = '10000000-0000-4000-8000-000000000006';

insert into public.wallets (user_id, balance, locked_balance)
values
    ('10000000-0000-4000-8000-000000000001', 2000, 0),
    ('10000000-0000-4000-8000-000000000002', 18000, 0),
    ('10000000-0000-4000-8000-000000000003', 12000, 0),
    ('10000000-0000-4000-8000-000000000004', 0, 0),
    ('10000000-0000-4000-8000-000000000005', 10000, 0),
    ('10000000-0000-4000-8000-000000000006', 0, 0)
on conflict (user_id) do update set balance = excluded.balance, locked_balance = excluded.locked_balance;

insert into public.trust_events (user_id, event_type, points, description, related_loan_id)
values
    ('10000000-0000-4000-8000-000000000002', 'COMMUNITY_SUPPORT', 1, 'Supported Sarah with a partial loan.', '50000000-0000-4000-8000-000000000001'),
    ('10000000-0000-4000-8000-000000000005', 'LOAN_COMPLETED', 1, 'Completed a demo loan.', '50000000-0000-4000-8000-000000000002'),
    ('10000000-0000-4000-8000-000000000006', 'OVERDUE', -40, 'A demo loan became overdue.', '50000000-0000-4000-8000-000000000003')
on conflict do nothing;

insert into public.transactions (type, sender_id, recipient_id, amount, status, loan_id, description)
values
    ('LOAN_FUNDED', '10000000-0000-4000-8000-000000000002', '10000000-0000-4000-8000-000000000001', 2000, 'COMPLETED', '50000000-0000-4000-8000-000000000001', 'Partial demo funding'),
    ('LOAN_FUNDED', '10000000-0000-4000-8000-000000000005', '10000000-0000-4000-8000-000000000004', 10000, 'COMPLETED', '50000000-0000-4000-8000-000000000002', 'Completed demo funding'),
    ('LOAN_REPAYMENT', '10000000-0000-4000-8000-000000000004', '10000000-0000-4000-8000-000000000005', 10033, 'COMPLETED', '50000000-0000-4000-8000-000000000002', 'Completed demo repayment')
on conflict do nothing;

insert into public.notifications (user_id, type, title, message, related_entity_id)
values
    ('10000000-0000-4000-8000-000000000001', 'LOAN_OFFER', 'Support from David', 'David offered KSh 2,000 at 2% for 30 days.', '40000000-0000-4000-8000-000000000001'),
    ('10000000-0000-4000-8000-000000000002', 'NEW_LOAN_REQUEST', 'Mary needs support', 'Mary posted a KSh 12,000 request.', '30000000-0000-4000-8000-000000000002'),
    ('10000000-0000-4000-8000-000000000006', 'LOAN_OVERDUE', 'Loan overdue', 'Your demo loan is overdue.', '50000000-0000-4000-8000-000000000003')
on conflict do nothing;
