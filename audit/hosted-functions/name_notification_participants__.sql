CREATE OR REPLACE FUNCTION public.name_notification_participants()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
DECLARE
    first_id uuid;
    second_id uuid;
    actor_name text;

BEGIN

    IF NEW.type IN (
        'CONNECTION_REQUEST',
        'CONNECTION_ACCEPTED',
        'CONNECTION_REJECTED'
    )
    THEN

        SELECT
            requester_id,
            recipient_id

        INTO
            first_id,
            second_id

        FROM public.connections

        WHERE id =
            NEW.related_entity_id;


    ELSIF NEW.type IN (
        'LOAN_OFFER',
        'LOAN_OFFER_TERMS_REQUESTED',
        'LOAN_OFFER_UPDATED',
        'LOAN_OFFER_DECLINED'
    )
    THEN

        SELECT
            borrower_id,
            lender_id

        INTO
            first_id,
            second_id

        FROM public.loan_offers

        WHERE id =
            NEW.related_entity_id;


    ELSIF NEW.type IN (
        'DIRECT_REQUEST',
        'NEW_LOAN_REQUEST'
    )
    THEN

        SELECT
            borrower_id,
            direct_request_to

        INTO
            first_id,
            second_id

        FROM public.loan_requests

        WHERE id =
            NEW.related_entity_id;


    ELSIF NEW.type IN (
        'LOAN_ACCEPTED',
        'LOAN_REPAID',
        'LOAN_PAYMENT',
        'LOAN_DUE_SOON',
        'LOAN_OVERDUE',
        'GRACE_PERIOD_GRANTED',
        'RECOVERED_EXTERNALLY'
    )
    THEN

        SELECT
            borrower_id,
            lender_id

        INTO
            first_id,
            second_id

        FROM public.loans

        WHERE id =
            NEW.related_entity_id;


    ELSE
        RETURN NEW;
    END IF;


    SELECT full_name
    INTO actor_name

    FROM public.profiles

    WHERE id =
        CASE
            WHEN first_id =
                 NEW.user_id
                THEN second_id
            ELSE first_id
        END;


    IF actor_name IS NULL THEN
        RETURN NEW;
    END IF;


    NEW.message :=
        replace(
            replace(
                replace(
                    replace(
                        replace(
                            NEW.message,
                            'The borrower',
                            actor_name
                        ),
                        'A supporter',
                        actor_name
                    ),
                    'The supporter',
                    actor_name
                ),
                'Your lender',
                actor_name
            ),
            'A loan you supported',
            actor_name || '''s loan'
        );


    IF NEW.message IN (
        'You received a connection request.',
        'Your connection request was accepted.',
        'Your connection request was declined.'
    )
    THEN

        NEW.message :=
            actor_name ||

            CASE NEW.type
                WHEN 'CONNECTION_REQUEST'
                    THEN ' sent you a connection request.'
                WHEN 'CONNECTION_ACCEPTED'
                    THEN ' accepted your connection request.'
                ELSE
                    ' declined your connection request.'
            END;

    END IF;


    RETURN NEW;

END;
$function$
