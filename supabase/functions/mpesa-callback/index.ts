import {database, reconcile, limitedJson, InvalidBodyError} from '../_shared/mpesa.ts';
Deno.serve(async (request: Request) => {
 if (request.method !== 'POST') return new Response('Method not allowed',{status:405});
 try {
  const url = new URL(request.url), id = url.searchParams.get('id'), token = url.searchParams.get('token');
  const uuid = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i;
  if (!uuid.test(id ?? '') || !uuid.test(token ?? '')) return new Response('Unauthorized',{status:401});
  const [d] = await database(`mpesa_deposits?id=eq.${id}`);
  if (!d || d.callback_token !== token) return new Response('Unauthorized',{status:401});
  const c = (await limitedJson(request,8192)).Body?.stkCallback;
  if (!c || !Number.isInteger(c.ResultCode) || typeof c.CheckoutRequestID !== 'string' || typeof c.MerchantRequestID !== 'string'
   || (d.checkout_id && c.CheckoutRequestID !== d.checkout_id) || (d.merchant_id && c.MerchantRequestID !== d.merchant_id))
   return new Response('Invalid payment identifiers',{status:400});
  if (d.status === 'COMPLETED') return Response.json({ResultCode:0,ResultDesc:'Accepted'});
  // Save before querying: callbacks may arrive before initiation saves its IDs.
  await database(`mpesa_deposits?id=eq.${id}&status=eq.PENDING`,'PATCH',{callback_payload:c});
  try { await reconcile({...d,callback_payload:c}); }
  catch { console.error('Callback retained for reconciliation.'); }
  return Response.json({ResultCode:0,ResultDesc:'Accepted'});
 } catch (error) {
  if (error instanceof InvalidBodyError) return new Response('Invalid callback',{status:400});
  console.error('Payment callback could not be retained.');
  return new Response('Confirmation pending',{status:503});
 }
});
