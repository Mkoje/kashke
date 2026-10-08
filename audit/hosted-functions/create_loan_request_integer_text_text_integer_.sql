CREATE OR REPLACE FUNCTION public.create_loan_request(p_amount integer, p_purpose text, p_description text, p_period_days integer)
 RETURNS uuid
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
DECLARE
    v_user_id uuid := auth.uid();
    v_request_id uuid;
    v_available_limit integer;
    v_frozen boolean;
    v_status text;
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Not authenticated';
    END IF;

    IF p_amount IS NULL OR p_amount <= 0 THEN
        RAISE EXCEPTION 'Amount must be greater than zero';
    END IF;

    IF p_purpose IS NULL OR btrim(p_purpose) = '' THEN
        RAISE EXCEPTION 'Purpose is required';
    END IF;

    IF p_period_days IS NULL
       OR p_period_days < 7
       OR p_period_days > 365 THEN
        RAISE EXCEPTION 'Repayment period must be between 7 and 365 days';
    END IF;

    SELECT
        available_limit,
        borrowing_frozen,
        account_status
    INTO
        v_available_limit,
        v_frozen,
        v_status
    FROM public.profiles
    WHERE id = v_user_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Profile not found';
    END IF;

    IF v_status <> 'ACTIVE' THEN
        RAISE EXCEPTION 'Account is not active';
    END IF;

    IF v_frozen THEN
        RAISE EXCEPTION 'Borrowing is currently frozen';
    END IF;

    IF p_amount > v_available_limit THEN
        RAISE EXCEPTION
            'Requested amount exceeds available borrowing limit';
    END IF;

    INSERT INTO public.loan_requests (
        borrower_id,
        requested_amount,
        funded_amount,
        remaining_amount,
        purpose,
        description,
        status,
        preferred_period_days,
        direct_request_to,
        above_limit_requested
    )
    VALUES (
        v_user_id,
        p_amount,
        0,
        p_amount,
        btrim(p_purpose),
        coalesce(btrim(p_description), ''),
        'REQUESTED',
        p_period_days,
        NULL,
        false
    )
    RETURNING id INTO v_request_id;

    -- Reserve this part of the user's borrowing capacity.
    UPDATE public.profiles
    SET available_limit = available_limit - p_amount
    WHERE id = v_user_id;

    RETURN v_request_id;
END;
$function$
