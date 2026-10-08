import {database, daraja, env, phone, reconcile, providerToken, limitedJson, InvalidBodyError, DepositLimitError} from '../_shared/mpesa.ts';
Deno.serve(async (request: Request) => {
 if (request.method !== 'POST') return new Response('Method not allowed',{status:405});
 try {
  const authorization = request.headers.get('Authorization') ?? '';
  if (!authorization.startsWith('Bearer ')) return Response.json({error:'Sign in to deposit.'},{status:401});
  const auth = await fetch(`${env('SUPABASE_URL')}/auth/v1/user`,{headers:{Authorization:authorization,apikey:env('SUPABASE_SERVICE_ROLE_KEY')},signal:AbortSignal.timeout(15000)});
  if (!auth.ok) return Response.json({error:'Sign in to deposit.'},{status:401});
  const user = await auth.json();
  const input = await limitedJson(request,2048);
  const safe = (d: Record<string, unknown>) => ({id:d.id,status:d.status,amount:d.amount,receipt:d.receipt});
  if (input.action === 'status') {
   const deposits = await database(`mpesa_deposits?user_id=eq.${encodeURIComponent(user.id)}&order=created_at.desc&limit=1`);
   if (deposits[0]?.status === 'PENDING' && Date.now() - Date.parse(deposits[0].created_at) > 15000) {
    const cutoff = new Date(Date.now()-10000).toISOString();
    const claimed = await database(`mpesa_deposits?id=eq.${deposits[0].id}&or=(last_query_at.is.null,last_query_at.lt.${encodeURIComponent(cutoff)})`,'PATCH',{last_query_at:new Date().toISOString()});
    if (claimed.length) try { await reconcile(deposits[0]); } catch { /* Keep pending until verified. */ }
    const [updated] = await database(`mpesa_deposits?id=eq.${deposits[0].id}`);
    return Response.json(safe(updated));
   }
   return Response.json(deposits.length ? safe(deposits[0]) : {status:'NONE'});
  }
  if (input.action !== 'deposit' || !Number.isInteger(input.amount) || input.amount < 1 || input.amount > 150000 || typeof input.phone !== 'string')
   return Response.json({error:'Enter a whole amount between KSh 1 and 150,000 and a valid phone number.'},{status:400});
  let msisdn: string;
  try { msisdn = phone(input.phone); } catch { return Response.json({error:'Enter a valid Kenyan phone number.'},{status:400}); }
  // Validate configuration before reserving a pending request.
  ['MPESA_ENVIRONMENT','MPESA_CONSUMER_KEY','MPESA_CONSUMER_SECRET','MPESA_PASSKEY','MPESA_SHORTCODE','MPESA_TILL'].forEach(env);
  const callbackBase = new URL(env('SUPABASE_URL') + '/functions/v1/mpesa-callback');
  if (callbackBase.protocol !== 'https:') throw new Error('HTTPS callback required');
  const pending = await database(`mpesa_deposits?user_id=eq.${encodeURIComponent(user.id)}&status=eq.PENDING&limit=1`);
  if (pending.length) return Response.json(safe(pending[0]));
  // Bad OAuth credentials cannot create an unresolvable pending charge.
  const accessToken = await providerToken();
  let d;
  try { [d] = await database('mpesa_deposits','POST',{user_id:user.id,amount:input.amount,phone:msisdn}); }
  catch (error) {
   if (error instanceof DepositLimitError) return Response.json({error:'Too many deposit requests. Please try again later.'},{status:429});
   const concurrent = await database(`mpesa_deposits?user_id=eq.${encodeURIComponent(user.id)}&status=eq.PENDING&limit=1`);
   if (concurrent.length) return Response.json(safe(concurrent[0]));
   throw new Error('Reservation failed');
  }
  callbackBase.searchParams.set('id',d.id);
  callbackBase.searchParams.set('token',d.callback_token);
  const result = await daraja('stkpush/v1/processrequest',{
   TransactionType:'CustomerBuyGoodsOnline', Amount:d.amount, PartyA:msisdn, PartyB:env('MPESA_TILL'), PhoneNumber:msisdn,
   CallBackURL:callbackBase.toString(), AccountReference:`KASH${d.id.replaceAll('-','').slice(0,8)}`, TransactionDesc:'Kash wallet deposit'
  }, accessToken);
  if (String(result.ResponseCode) !== '0') {
   await database(`mpesa_deposits?id=eq.${d.id}&status=eq.PENDING`,'PATCH',{status:'FAILED'});
   return Response.json({error:'M-Pesa could not send the prompt. Please try again.'},{status:422});
  }
  if (!result.CheckoutRequestID || !result.MerchantRequestID) throw new Error('Missing provider identifiers');
  await database(`mpesa_deposits?id=eq.${d.id}`,'PATCH',{checkout_id:result.CheckoutRequestID,merchant_id:result.MerchantRequestID});
  return Response.json(safe(d));
 } catch (error) {
  if (error instanceof InvalidBodyError) return Response.json({error:'Invalid request.'},{status:400});
  console.error('Deposit request failed; inspect server configuration and pending deposits.');
  return Response.json({error:'Unable to confirm the request. Check deposit status before trying again.'},{status:503});
 }
});
