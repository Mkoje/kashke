export function env(name: string): string {
 const value = Deno.env.get(name);
 if (!value) throw new Error('Missing server configuration');
 return value;
}
export class DepositLimitError extends Error {}
export class InvalidBodyError extends Error {}
export async function limitedJson(request: Request, maxBytes: number) {
 const reader = request.body?.getReader();
 if (!reader) throw new InvalidBodyError();
 const chunks: Uint8Array[] = []; let total = 0;
 try {
  while (true) {
   const {done,value} = await reader.read();
   if (done) break;
   total += value.byteLength;
   if (total > maxBytes) { await reader.cancel(); throw new InvalidBodyError(); }
   chunks.push(value);
  }
  const bytes = new Uint8Array(total); let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk,offset); offset += chunk.byteLength; }
  const body = JSON.parse(new TextDecoder().decode(bytes));
  if (!body || typeof body !== 'object' || Array.isArray(body)) throw new InvalidBodyError();
  return body;
 } catch { throw new InvalidBodyError(); }
 finally { reader.releaseLock(); }
}
export async function database(path: string, method = 'GET', body?: unknown) {
 const key = env('SUPABASE_SERVICE_ROLE_KEY');
 const r = await fetch(`${env('SUPABASE_URL')}/rest/v1/${path}`, {
  method, headers: {apikey:key, Authorization:`Bearer ${key}`, 'Content-Type':'application/json', Prefer:'return=representation'},
  body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(15000)
 });
 if (!r.ok) {
  const failure = await r.json().catch(() => ({}));
  if (failure.code === 'P0001' && failure.message === 'Deposit request limit reached') throw new DepositLimitError();
  throw new Error('Database operation failed');
 }
 const text = await r.text();
 return text ? JSON.parse(text) : null;
}
export function phone(value: string): string {
 if (!/^(?:0[17][0-9]{8}|\+?254[17][0-9]{8})$/.test(value)) throw new Error('Invalid phone');
 const clean = value.replace(/^\+/, '');
 const normalized = /^0[17]\d{8}$/.test(clean) ? `254${clean.slice(1)}` : clean;
 if (!/^254[17]\d{8}$/.test(normalized)) throw new Error('Invalid phone');
 return normalized;
}
function providerBase() {
 const mode = env('MPESA_ENVIRONMENT');
 if (!['production','sandbox'].includes(mode)) throw new Error('Invalid environment');
 return mode === 'production' ? 'https://api.safaricom.co.ke' : 'https://sandbox.safaricom.co.ke';
}
export async function providerToken() {
 const base = providerBase();
 const auth = await fetch(`${base}/oauth/v1/generate?grant_type=client_credentials`, {
  headers:{Authorization:`Basic ${btoa(`${env('MPESA_CONSUMER_KEY')}:${env('MPESA_CONSUMER_SECRET')}`)}`}, signal:AbortSignal.timeout(15000)
 });
 if (!auth.ok) throw new Error('Provider authorization failed');
 const token = (await auth.json()).access_token;
 if (!token) throw new Error('Provider authorization failed');
 return token;
}
export async function daraja(path: string, body: Record<string, unknown>, accessToken?: string) {
 const base = providerBase();
 const token = accessToken ?? await providerToken();
 const timestamp = new Date().toISOString().replace(/\D/g,'').slice(0,14);
 const shortcode = env('MPESA_SHORTCODE');
 const response = await fetch(`${base}/mpesa/${path}`, {
  method:'POST', headers:{Authorization:`Bearer ${token}`, 'Content-Type':'application/json'},
  body:JSON.stringify({BusinessShortCode:shortcode, Password:btoa(shortcode + env('MPESA_PASSKEY') + timestamp), Timestamp:timestamp, ...body}),
  signal:AbortSignal.timeout(25000)
 });
 // A transport/server error is ambiguous: retain the pending deposit; never
 // automatically retry a charge that may already have reached Safaricom.
 if (!response.ok) throw new Error('Provider request uncertain');
 return await response.json();
}

// Retain callback data before contacting Daraja so transient provider outages
// can be recovered when the member checks deposit status.
export async function reconcile(d: Record<string, any>) {
 const c = d.callback_payload;
 const checkout = d.checkout_id ?? c?.CheckoutRequestID;
 if (!checkout || d.status !== 'PENDING') return;
 const verified = await daraja('stkpushquery/v1/query',{CheckoutRequestID:checkout});
 if (String(verified.ResponseCode) !== '0' || verified.ResultCode === undefined) return;
 const code = Number(verified.ResultCode);
 if (!c && [1,1032,1037,2001].includes(code)) {
  await database(`mpesa_deposits?id=eq.${d.id}&status=eq.PENDING`,'PATCH',{status:'FAILED'});
  return;
 }
 if (!c || code !== c.ResultCode) return;
 if (!d.checkout_id) {
  await database(`mpesa_deposits?id=eq.${d.id}&checkout_id=is.null`,'PATCH',{checkout_id:c.CheckoutRequestID,merchant_id:c.MerchantRequestID});
  d.checkout_id = c.CheckoutRequestID;
  d.merchant_id = c.MerchantRequestID;
 }
 if (code !== 0) {
  await database(`mpesa_deposits?id=eq.${d.id}&status=eq.PENDING`,'PATCH',{status:'FAILED'});
  return;
 }
 const items = c.CallbackMetadata?.Item;
 if (!Array.isArray(items)) throw new Error('Invalid metadata');
 const item = (name: string) => items.find((x: {Name: string}) => x.Name === name)?.Value;
 const amount = Number(item('Amount')), msisdn = String(item('PhoneNumber')), receipt = item('MpesaReceiptNumber');
 if (amount !== d.amount || msisdn !== d.phone || typeof receipt !== 'string' || !/^[A-Z0-9]{10}$/.test(receipt)) throw new Error('Payment mismatch');
 await database('rpc/settle_mpesa_deposit','POST',{p_id:d.id,p_checkout:d.checkout_id,p_merchant:d.merchant_id,p_token:d.callback_token,p_amount:amount,p_phone:msisdn,p_receipt:receipt});
}
