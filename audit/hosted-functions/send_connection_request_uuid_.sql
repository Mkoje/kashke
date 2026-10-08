CREATE OR REPLACE FUNCTION public.send_connection_request(p_recipient_id uuid)
 RETURNS uuid
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
DECLARE
    v_requester_id uuid := auth.uid();
    v_connection_id uuid;
    v_existing public.connections%ROWTYPE;
    v_requester_name text;
BEGIN

    IF v_requester_id IS NULL THEN
        RAISE EXCEPTION 'Not authenticated';
    END IF;

    IF p_recipient_id IS NULL THEN
        RAISE EXCEPTION 'Recipient is required';
    END IF;

    IF p_recipient_id = v_requester_id THEN
        RAISE EXCEPTION 'You cannot connect with yourself';
    END IF;


    -- Make sure recipient exists.
    IF NOT EXISTS (
        SELECT 1
        FROM public.profiles
        WHERE id = p_recipient_id
    ) THEN
        RAISE EXCEPTION 'Recipient does not exist';
    END IF;


    -- Get requester's display name for notification.
    SELECT full_name
    INTO v_requester_name
    FROM public.profiles
    WHERE id = v_requester_id;


    -- Check whether these two users already have a connection.
    SELECT *
    INTO v_existing
    FROM public.connections
    WHERE
        (
            requester_id = v_requester_id
            AND recipient_id = p_recipient_id
        )
        OR
        (
            requester_id = p_recipient_id
            AND recipient_id = v_requester_id
        )
    ORDER BY created_at DESC
    LIMIT 1;


    IF FOUND THEN

        IF v_existing.status = 'ACCEPTED' THEN
            RAISE EXCEPTION 'You are already connected';
        END IF;

        IF v_existing.status = 'PENDING' THEN
            RAISE EXCEPTION 'A connection request already exists';
        END IF;


        -- Reuse rejected/removed connection.
        UPDATE public.connections
        SET
            requester_id = v_requester_id,
            recipient_id = p_recipient_id,
            status = 'PENDING',
            accepted_at = NULL,
            responded_at = NULL,
            created_at = now()
        WHERE id = v_existing.id
        RETURNING id INTO v_connection_id;

    ELSE

        INSERT INTO public.connections (
            requester_id,
            recipient_id,
            status
        )
        VALUES (
            v_requester_id,
            p_recipient_id,
            'PENDING'
        )
        RETURNING id INTO v_connection_id;

    END IF;


    -- ========================================================
    -- NOTIFY RECIPIENT
    -- ========================================================

    INSERT INTO public.notifications (
        user_id,
        type,
        title,
        message,
        related_entity_id
    )
    VALUES (
        p_recipient_id,
        'CONNECTION_REQUEST',
        'New connection request',
        coalesce(v_requester_name, 'Someone') ||
            ' sent you a connection request.',
        v_connection_id
    );


    RETURN v_connection_id;

END;
$function$
