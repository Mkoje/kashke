BEGIN;
CREATE TABLE IF NOT EXISTS public.push_devices (
  token text PRIMARY KEY CHECK (length(token) BETWEEN 20 AND 4096),
  user_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
  updated_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE public.push_devices ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.push_devices FROM anon, authenticated;
GRANT ALL ON public.push_devices TO service_role;
CREATE INDEX IF NOT EXISTS push_devices_user_id_idx ON public.push_devices(user_id);

-- The server gets the account from the authenticated session, never the client payload.
CREATE OR REPLACE FUNCTION public.register_push_device(p_token text)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
BEGIN
  IF auth.uid() IS NULL THEN RAISE EXCEPTION 'Sign in first' USING ERRCODE='28000'; END IF;
  IF p_token IS NULL OR length(p_token) NOT BETWEEN 20 AND 4096 THEN RAISE EXCEPTION 'Invalid push token'; END IF;
  INSERT INTO public.push_devices(token, user_id) VALUES(p_token, auth.uid())
    ON CONFLICT(token) DO UPDATE SET user_id=excluded.user_id, updated_at=now();
END;
$$;
REVOKE ALL ON FUNCTION public.register_push_device(text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.register_push_device(text) TO authenticated;
COMMIT;
