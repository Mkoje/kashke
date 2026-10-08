// Server-only worker. The app never receives the delivery address or provider key.
Deno.serve(async (request: Request) => {
  if (request.method !== 'POST') return new Response('Method not allowed', {status:405});
  const secret = Deno.env.get('CONTACT_WEBHOOK_SECRET');
  if (!secret || request.headers.get('x-contact-secret') !== secret) return new Response('Unauthorized', {status:401});
  const url = Deno.env.get('SUPABASE_URL');
  const service = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY');
  const provider = Deno.env.get('RESEND_API_KEY');
  const sender = Deno.env.get('CONTACT_MAIL_FROM');
  const recipient = Deno.env.get('CONTACT_MAIL_TO');
  if (!url || !service || !provider || !sender || !recipient || /[\r\n]/.test(sender + recipient)) {
    return Response.json({error:'Email delivery is not configured.'}, {status:503});
  }
  const rpc = async (name: string, data: Record<string, unknown>) => {
    const response = await fetch(`${url}/rest/v1/rpc/${name}`, {
      method:'POST', headers:{apikey:service, Authorization:`Bearer ${service}`, 'Content-Type':'application/json'},
      body:JSON.stringify(data), signal:AbortSignal.timeout(15000),
    });
    if (!response.ok) throw new Error('Contact queue operation failed');
    const body = await response.text();
    return body ? JSON.parse(body) : null;
  };
  try {
    const rows = await rpc('claim_contact_email_batch', {p_limit:5});
    let sent = 0, retrying = 0;
    const deliveryErrors: {status: number; code: string; reason: string}[] = [];
    for (const row of rows) {
      let delivered = false, providerId: string | null = null;
      try {
        const response = await fetch('https://api.resend.com/emails', {
          method:'POST', headers:{Authorization:`Bearer ${provider}`, 'Content-Type':'application/json', 'Idempotency-Key':`contact/${row.id}`},
          body:JSON.stringify({from:sender, to:[recipient], reply_to:row.reply_email,
            subject:`Kash.ke contact request ${row.id}`,
            text:`New Kash.ke contact request\n\nReference: ${row.id}\nReceived: ${row.created_at}\nName: ${row.full_name}\nReply email: ${row.reply_email}\nSubject: ${row.subject}\n\n${row.message}`}),
          signal:AbortSignal.timeout(10000),
        });
        if (response.ok) {
          const payload = await response.json();
          if (typeof payload.id === 'string' && payload.id.length > 0) { delivered = true; providerId = payload.id; }
        } else {
          const payload = await response.json().catch(() => ({}));
          const code = typeof payload.name === 'string' && /^[a-z_]{1,80}$/.test(payload.name) ? payload.name : 'provider_rejected';
          const message = typeof payload.message === 'string' ? payload.message : '';
          const reason = /domain.*not verified/i.test(message) ? 'sender_domain_unverified'
            : /only send testing emails|testing emails.*your own/i.test(message) ? 'test_sender_recipient_restriction'
            : /api key.*invalid|invalid.*api key/i.test(message) ? 'invalid_provider_key'
            : 'provider_validation';
          deliveryErrors.push({status:response.status,code,reason});
        }
      } catch { /* A saved submission stays queued for retry. */ }
      await rpc('finish_contact_email', {p_id:row.id, p_lease_id:row.email_lease_id, p_sent:delivered, p_provider_id:providerId});
      if (delivered) sent++; else retrying++;
    }
    return Response.json({sent,retrying,deliveryErrors});
  } catch {
    console.error('Contact email queue processing failed. Saved requests remain available for retry.');
    return Response.json({error:'Queue processing failed.'}, {status:502});
  }
});
