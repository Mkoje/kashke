import fs from 'node:fs';
import assert from 'node:assert/strict';
import {init} from './reconciled-fixture.mjs';
for(const mode of ['fresh','hosted']) {
 const db=await init(mode); let checks=0;
 const actor=async id=>{await db.exec('reset role');await db.query("select set_config('request.jwt.claim.sub',$1,false)",[id||'']);await db.exec(`set role ${id?'authenticated':'anon'}`);};
 const value=async q=>(await db.query(q)).rows[0].value;
 const check=async(q,want)=>{assert.deepEqual(await value(q),want);checks++;};
 const fails=async q=>{await assert.rejects(()=>db.exec(q));checks++;};
 try {
  await db.exec(fs.readFileSync('supabase/migrations/202610050001_android_contract_reconciliation.sql','utf8'));
  const sql=fs.readFileSync('supabase/migrations/202610060006_contact_requests.sql','utf8');await db.exec(sql);await db.exec(sql);
  const a=await value('select gen_random_uuid() as value'),b=await value('select gen_random_uuid() as value');
  await db.query("insert into auth.users(id,email) values($1,'contact-a@example.test'),($2,'contact-b@example.test')",[a,b]);
  for(const id of [a,b]){await actor(id);await db.exec('select public.ensure_current_profile()');}
  const id=await value('select gen_random_uuid() as value');
  const submit=(message='Please help with my account.',email='anne+help@example.test',subject='Account help',name="Anne O'Brien",submission=id)=>db.query('select public.submit_contact_request($1,$2,$3,$4,$5) as value',[submission,name,email,subject,message]);
  await actor(a);assert.equal((await submit()).rows[0].value,id);checks++;
  assert.equal((await submit()).rows[0].value,id);checks++;
  await fails('select * from public.contact_requests');await fails("select * from public.claim_contact_email_batch(10)");
  await actor(b);await assert.rejects(()=>submit());checks++;
  const invalids=[['<script>alert(1)</script>','valid@example.test','Help','Anne'],['Normal message for support','x@example.test\r\nBcc:x@test.com','Help','Anne'],['Normal message for support','x@localhost','Help','Anne'],['a'.repeat(4001),'a@example.test','Help','Anne'],['Normal message for support','a@example.test','Help','Anne\nBcc'],['Normal\u202E message for support','a@example.test','Help','Anne']];
  for(const args of invalids){await assert.rejects(()=>submit(...args,crypto.randomUUID()));checks++;}
  await actor(a);await assert.rejects(()=>submit('Please help with a second message.','a@example.test','Help','Anne',crypto.randomUUID()));checks++;
  await db.exec('reset role');await check('select count(*)::int as value from public.contact_requests',1);
  // Injection-like punctuation remains inert data, with no SQL construction from the payload.
  await db.exec("update public.contact_requests set created_at=now()-interval '1 minute'");await actor(a);
  await submit("Hello'; DROP TABLE contact_requests; -- I need help.",'a@example.test','Account help','Anne',crypto.randomUUID());
  await db.exec('reset role');await check('select count(*)::int as value from public.contact_requests',2);
  const batch=(await db.query('select * from public.claim_contact_email_batch(10)')).rows;assert.equal(batch.length,2);checks++;
  await check('select count(*)::int as value from public.claim_contact_email_batch(10)',0);
  const row=batch[0];await db.query('select public.finish_contact_email($1,$2,true,$3)',[row.id,crypto.randomUUID(),'fake']);await check(`select email_status as value from public.contact_requests where id='${row.id}'`,'SENDING');
  await db.query('select public.finish_contact_email($1,$2,true,$3)',[row.id,row.email_lease_id,'provider-test']);await check(`select email_status as value from public.contact_requests where id='${row.id}'`,'SENT');
  const second=batch[1];await db.query('select public.finish_contact_email($1,$2,false,null)',[second.id,second.email_lease_id]);await check(`select email_status as value from public.contact_requests where id='${second.id}'`,'PENDING');
  await actor(null);await fails('select public.claim_contact_email_batch(10)');await assert.rejects(()=>submit());checks++;
  await db.exec('reset role');await db.exec("update public.contact_requests set email_status='SENDING',email_attempts=8,email_lease_until=now()-interval '1 minute' where email_status='PENDING'");await db.exec('select public.claim_contact_email_batch(10)');await check(`select email_status as value from public.contact_requests where id='${second.id}'`,'FAILED');
  // Emulate extension APIs to exercise dispatch SQL without making network calls.
  await db.exec(`create schema vault;create table vault.secrets(id uuid,name text);create table vault.decrypted_secrets(name text,decrypted_secret text);
    create schema net;create table net.requests(url text,headers jsonb,body jsonb);
    create function net.http_post(url text,headers jsonb,body jsonb,timeout_milliseconds integer) returns bigint language plpgsql as $$begin insert into net.requests values(url,headers,body);return 1;end$$;
    create schema cron;create table cron.job(jobname text primary key);
    create function cron.unschedule(text) returns boolean language plpgsql as $$begin delete from cron.job where jobname=$1;return true;end$$;
    create function cron.schedule(text,text,text) returns bigint language plpgsql as $$begin insert into cron.job values($1);return 1;end$$;`);
  const dispatch=fs.readFileSync('supabase/migrations/202610060007_contact_email_dispatch.sql','utf8').replace(/^create extension.*;$/gm,'');
  await db.exec(dispatch);await db.exec(dispatch);await check('select count(*)::int as value from cron.job',1);
  await db.exec('select public.dispatch_contact_email()');await check('select count(*)::int as value from net.requests',0);
  await db.exec("insert into vault.decrypted_secrets values('contact_mail_function_url','https://worker.test'),('contact_mail_webhook_secret','private-test-secret')");
  await db.exec('select public.dispatch_contact_email()');await check('select count(*)::int as value from net.requests',1);
  await actor(a);await fails('select public.dispatch_contact_email()');await fails('select * from vault.decrypted_secrets');
  await db.exec('reset role');await db.exec("update public.contact_requests set created_at=now()-interval '2 minutes'");await actor(a);
  await submit('Another message for our support team.','a@example.test','Account help','Anne',crypto.randomUUID());
  await db.exec('reset role');await check('select count(*)::int as value from net.requests',2);
  console.log(`${mode}: ${checks} contact validation, privacy, idempotency and delivery-queue checks passed.`);
 } finally {await db.close();}
}
