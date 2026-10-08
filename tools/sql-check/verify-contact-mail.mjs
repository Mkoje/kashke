import fs from 'node:fs';
import assert from 'node:assert/strict';
import {stripTypeScriptTypes} from 'node:module';
const source=stripTypeScriptTypes(fs.readFileSync('supabase/functions/send-contact-mail/index.ts','utf8'));
const config={CONTACT_WEBHOOK_SECRET:'test-secret',SUPABASE_URL:'https://project.test',SUPABASE_SERVICE_ROLE_KEY:'service-test',RESEND_API_KEY:'provider-test',CONTACT_MAIL_FROM:'support@example.test',CONTACT_MAIL_TO:'private@example.test'};
const row={id:'00000000-0000-0000-0000-000000000001',email_lease_id:'lease',full_name:"Anne O'Brien",reply_email:'anne@example.test',subject:'Account help',message:'Please help with my account.',created_at:'2026-10-06'};
async function fixture(providerOk=true,settings=config){let handler;const calls=[];new Function('Deno','fetch',source)({env:{get:key=>settings[key]},serve:fn=>{handler=fn;}},async(url,options)=>{calls.push({url,...options,body:JSON.parse(options.body)});if(url.endsWith('claim_contact_email_batch'))return Response.json([row]);if(url==='https://api.resend.com/emails')return Response.json(providerOk?{id:'sent-test'}:{error:'unavailable'},{status:providerOk?200:503});return new Response(null,{status:204});});return {handler,calls};}
let checks=0;
for(const success of [true,false]){const {handler,calls}=await fixture(success);assert.equal((await handler(new Request('https://worker.test',{method:'POST',headers:{'x-contact-secret':'test-secret'}}))).status,200);checks++;const email=calls.find(c=>c.url.includes('resend.com'));assert.deepEqual(email.body.to,['private@example.test']);assert.equal(email.body.reply_to,row.reply_email);assert.equal(email.body.html,undefined);assert.equal(email.headers['Idempotency-Key'],'contact/'+row.id);checks+=4;const finish=calls.find(c=>c.url.endsWith('finish_contact_email'));assert.equal(finish.body.p_sent,success);checks++;}
const {handler,calls}=await fixture();assert.equal((await handler(new Request('https://worker.test',{method:'POST'}))).status,401);assert.equal(calls.length,0);checks+=2;
const missing=await fixture(true,{...config,RESEND_API_KEY:undefined});assert.equal((await missing.handler(new Request('https://worker.test',{method:'POST',headers:{'x-contact-secret':'test-secret'}}))).status,503);assert.equal(missing.calls.length,0);checks+=2;
console.log(`${checks} contact email worker checks passed; no real emails sent.`);
