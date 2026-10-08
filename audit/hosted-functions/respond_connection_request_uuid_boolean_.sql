CREATE OR REPLACE FUNCTION public.respond_connection_request(p_connection_id uuid, p_accept boolean)
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
DECLARE
    v_user_id uuid := auth.uid();
    v_connection public.connections%ROWTYPE;
    v_responder_name text;
BEGIN

    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Not authenticated';
    END IF;


    SELECT *
    INTO v_connection
    FROM public.connections
    WHERE id = p_connection_id
    FOR UPDATE;


    IF NOT FOUND THEN
        RAISE EXCEPTION 'Connection request not found';
    END IF;


    -- Only the person receiving the request may respond.
    IF v_connection.recipient_id <> v_user_id THEN
        RAISE EXCEPTION
            'Only the recipient can respond to this request';
    END IF;


    IF v_connection.status <> 'PENDING' THEN
        RAISE EXCEPTION
            'This connection request has already been handled';
    END IF;


    SELECT full_name
    INTO v_responder_name
    FROM public.profiles
    WHERE id = v_user_id;


    -- ========================================================
    -- ACCEPT
    -- ========================================================

    IF p_accept THEN

        UPDATE public.connections
        SET
            status = 'ACCEPTED',
            accepted_at = now(),
            responded_at = now()
        WHERE id = p_connection_id;


        -- Notify original requester.
        INSERT INTO public.notifications (
            user_id,
            type,
            title,
            message,
            related_entity_id
        )
        VALUES (
            v_connection.requester_id,
            'CONNECTION_ACCEPTED',
            'Connection accepted',
            coalesce(v_responder_name, 'Someone') ||
                ' accepted your connection request.',
            p_connection_id
        );


    -- ========================================================
    -- REJECT
    -- ========================================================

    ELSE

        UPDATE public.connections
        SET
            status = 'REJECTED',
            responded_at = now()
        WHERE id = p_connection_id;


        -- Notify original requester.
        INSERT INTO public.notifications (
            user_id,
            type,
            title,
            message,
            related_entity_id
        )
        VALUES (
            v_connection.requester_id,
            'CONNECTION_REJECTED',
            'Connection request declined',
            coalesce(v_responder_name, 'Someone') ||
                ' declined your connection request.',
            p_connection_id
        );

    END IF;

END;
$function$
