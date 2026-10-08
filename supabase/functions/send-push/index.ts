type Account = {project_id: string; client_email: string; private_key: string};
const enc = (b: Uint8Array) => btoa(String.fromCharCode(...b)).replace(/=/g,'').replace(/\+/g,'-').replace(/\//g,'_');
const json = (x: unknown) => enc(new TextEncoder().encode(JSON.stringify(x)));
async function authorize(a: Account) {
 const now = Math.floor(Date.now()/1000);
 const jwt = `${json({alg:'RS256',typ:'JWT'})}.${json({iss:a.client_email,scope:'https://www.googleapis.com/auth/firebase.messaging',aud:'https://oauth2.googleapis.com/token',iat:now,exp:now+3600})}`;
 const pem = a.private_key.replace(/-----BEGIN PRIVATE KEY-----|-----END PRIVATE KEY-----|\s/g,'');
 const key = await crypto.subtle.importKey('pkcs8',Uint8Array.from(atob(pem),c=>c.charCodeAt(0)),{name:'RSASSA-PKCS1-v1_5',hash:'SHA-256'},false,['sign']);
 const signature = await crypto.subtle.sign('RSASSA-PKCS1-v1_5',key,new TextEncoder().encode(jwt));
 const response = await fetch('https://oauth2.googleapis.com/token',{method:'POST',body:new URLSearchParams({grant_type:'urn:ietf:params:oauth:grant-type:jwt-bearer',assertion:`${jwt}.${enc(new Uint8Array(signature))}`})});
 if (!response.ok) throw new Error('Authorization failed');
 return (await response.json()).access_token;
}
Deno.serve(async (request: Request) => {
 const secret = Deno.env.get('PUSH_WEBHOOK_SECRET');
 if (!secret || request.headers.get('x-push-secret') !== secret) return new Response('Unauthorized',{status:401});
 if (request.method !== 'POST') return new Response('Method not allowed',{status:405});
 try {
  const p = await request.json();
  if(p.type !== 'INSERT' || p.table !== 'notifications' || p.schema !== 'public' || !/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(p.record?.id ?? '')) return new Response('Invalid webhook',{status:400});
  const url = Deno.env.get('SUPABASE_URL');
  const key = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY');
  if (!url || !key) throw new Error('Missing configuration');
  const headers = {apikey:key,Authorization:`Bearer ${key}`};
  const row = await fetch(`${url}/rest/v1/notifications?id=eq.${p.record.id}&select=*`,{headers});
  if (!row.ok) throw new Error('Lookup failed');
  const n = (await row.json())[0];
  if (!n || n.read) return new Response('No unread notification');
  const lookup = await fetch(`${url}/rest/v1/push_devices?user_id=eq.${encodeURIComponent(n.user_id)}&select=token`,{headers});
  if (!lookup.ok) throw new Error('Device lookup failed');
  const devices: {token: string}[] = await lookup.json();
  if (!devices.length) return new Response('No registered devices');
  const account: Account = JSON.parse(Deno.env.get('FIREBASE_SERVICE_ACCOUNT') ?? '{}');
  const bearer = await authorize(account);
  let failures = 0;
  for (const d of devices) {
   const response = await fetch(`https://fcm.googleapis.com/v1/projects/${account.project_id}/messages:send`,{method:'POST',headers:{Authorization:`Bearer ${bearer}`,'Content-Type':'application/json'},body:JSON.stringify({message:{token:d.token,android:{priority:'HIGH',ttl:'86400s'},data:{id:n.id,user_id:n.user_id,type:n.type,title:n.title ?? '',message:n.message ?? '',created_at:n.created_at ?? ''}}})});
   if (!response.ok) {
    const error = await response.json();
    if (error.error?.details?.some((x: {errorCode?: string})=>x.errorCode==='UNREGISTERED')) {
     const deletion = await fetch(`${url}/rest/v1/push_devices?token=eq.${encodeURIComponent(d.token)}&user_id=eq.${encodeURIComponent(n.user_id)}`,{method:'DELETE',headers});
     if (!deletion.ok) failures++;
    } else failures++;
   }
  }
  return Response.json({devices:devices.length,failures},{status:failures ? 502 : 200});
 } catch {
  console.error('Push delivery failed; check server configuration and provider availability.');
  return new Response('Push delivery failed',{status:500});
 }
});
