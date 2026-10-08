-- Hosted installations retain named checks from the original schema.
-- Match the current RPC policy: up to 7% normally, 15% with direct approval.
-- RPCs enforce the request-specific ceiling; storage must allow both cases.
alter table public.loan_offers
    drop constraint if exists loan_offer_interest_valid,
    drop constraint if exists loan_offers_interest_rate_check,
    drop constraint if exists loan_offers_requested_interest_rate_check;
alter table public.loan_offers
    add constraint loan_offers_interest_rate_check check (interest_rate between 0 and 15) not valid,
    add constraint loan_offers_requested_interest_rate_check
        check (requested_interest_rate is null or requested_interest_rate between 0 and 15) not valid;

-- Apply the same ceiling to agreements created when an offer is accepted.
alter table public.loans
    drop constraint if exists loan_interest_valid,
    drop constraint if exists loans_interest_rate_check;
alter table public.loans
    add constraint loans_interest_rate_check check (interest_rate between 0 and 15) not valid;
