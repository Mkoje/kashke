import fs from 'node:fs';
import assert from 'node:assert/strict';
import {init} from './reconciled-fixture.mjs';
const db=await init('hosted');const read=p=>fs.readFileSync(p,'utf8');let checks=0;
const query=async(q,args)=>(await db.query(q,args)).rows;
const value=async q=>(await query('select '+(/\sfrom\s/i.test(q)?q.replace(/\sfrom\s/i,' as value from '):q+' as value')))[0].value;
const admin=()=>db.exec("reset role;select set_config('request.jwt.claim.sub','',false)");
const actor=id=>db.exec(`reset role;set role authenticated;select set_config('request.jwt.claim.sub','${id}',false)`);
const eq=(a,b,label)=>{assert.deepEqual(a,b,label);checks++};
const fails=async q=>{await assert.rejects(()=>db.exec(q));checks++};
await db.exec(read('supabase/migrations/202610050001_android_contract_reconciliation.sql'));
const baseline=await query("select proname,prosrc from pg_proc where pronamespace='public'::regnamespace and proname<>'record_trust_event' order by oid");
await db.exec(read('supabase/migrations/202610050002_first_trust_milestones.sql'));
eq(await query("select proname,prosrc from pg_proc where pronamespace='public'::regnamespace and proname<>'record_trust_event' order by oid"),baseline,'All other RPCs unchanged');
let n=0;
async function user(){await admin();const i=n++;const id=await value('gen_random_uuid()');await db.query('insert into auth.users(id,email,phone,raw_user_meta_data) values($1,$2,$3,$4)',[id,`milestone${i}@example.test`,`+25471234600${i}`,{username:`milestone_${i}`,full_name:'Member',phone:`+25471234600${i}`}]);await actor(id);await db.exec(`select public.ensure_current_profile();select public.complete_member_profile('Member ${i}','milestone_${i}','milestone${i}@example.test','+25471234600${i}')`);await admin();await db.query('update public.wallets set balance=100000 where user_id=$1',[id]);return id}
const b=await user(),l=await user(),b2=await user(),l2=await user();
// Minimal new-signup metadata, retaining Auth UUID and the four-key completion.
for (const phoneSignup of [false,true]) {
 await admin();const i=n++;const id=await value('gen_random_uuid()');const phone=`+25471234600${i}`;const email=`minimal${i}@example.test`;
 const metadata={full_name:'Member',username:'user_'+id.replaceAll('-','').slice(0,19),...(phoneSignup?{phone}:{profile_email:email})};
 await db.query('insert into auth.users(id,email,phone,raw_user_meta_data) values($1,$2,$3,$4)',[id,phoneSignup?null:email,phoneSignup?phone:null,metadata]);
 await actor(id);await db.exec('select public.ensure_current_profile()');
 eq(await value(`id::text from public.profiles where id='${id}'`),id,'Minimal signup retains Auth UUID');
 eq(await value(`phone from public.profiles where id='${id}'`),phoneSignup?phone:'','Known phone metadata retained');
 eq(await value(`profile_email from public.profiles where id='${id}'`),phoneSignup?'':email,'Known email metadata retained');
 await db.exec(`select public.complete_member_profile(p_full_name=>'Minimal member',p_username=>'minimal_${i}',p_email=>'${email}',p_phone=>'${phone}')`);
 await admin();eq(await value(`public.member_profile_complete('${id}')`),true,'Four-argument completion for minimal signup');
}

async function connect(a,b){await actor(a);const c=await value(`public.send_connection_request('${b}')`);await actor(b);await db.exec(`select public.respond_connection_request('${c}',true)`)}
await connect(b,l);await connect(b2,l2);
async function request(b,amount=1000){await actor(b);return value(`public.create_loan_request(${amount},'test','test',30)`)}
async function offer(l,r,amount=1000,rate=7){await actor(l);return value(`public.create_loan_offer('${r}',${amount},${rate},30,false)`)}
async function accept(b,o,amount=1000,rate=7){await actor(b);return value(`public.accept_loan_offer_reviewed('${o}',${amount},${rate},30)`)}
async function score(id){await actor(id);return value(`trust_score from public.profiles where id='${id}'`)}
async function events(id,type){await actor(id);return query(`select points from public.trust_events where user_id='${id}' and event_type='${type}' order by created_at,id`)}
const r=await request(b),o=await offer(l,r);
eq(await score(l),0,'Creation gives no support reward');
await actor(b);await fails(`select public.accept_loan_offer_reviewed('${o}',999,7,30)`);
eq(await score(l),0,'Failed acceptance gives no reward');
const loan=await accept(b,o);eq(await score(l),200,'First funded offer exactly 200');
await actor(b);await fails(`select public.accept_loan_offer_reviewed('${o}',1000,7,30)`);
eq(await score(l),200,'Acceptance retry no reward');
const total=await value(`total_repayment from public.loans where id='${loan}'`);
await actor(b);const op=await value('gen_random_uuid()');await db.exec(`select public.repay_loan_once('${loan}',500,'${op}')`);
eq(await score(b),0,'First partial no milestone');
await actor(b);eq(await value(`principal_paid from public.loans where id='${loan}'`),500-(total-1000),'Interest-first allocation');
await db.exec(`select public.repay_loan_once('${loan}',500,'${op}')`);eq(await score(b),0,'Partial receipt retry no milestone');
await actor(b);await db.exec(`select public.repay_loan_once('${loan}',400,gen_random_uuid())`);eq(await score(b),0,'Second partial no milestone');
await actor(b);const finalOp=await value('gen_random_uuid()');await db.exec(`select public.repay_loan_once('${loan}',${total-900},'${finalOp}')`);
eq(await score(b),400,'Final partial gives exactly 400');eq(await events(b,'LOAN_COMPLETED'),[{points:400}],'Trigger and RPC produce one event');
await actor(b);await db.exec(`select public.repay_loan_once('${loan}',${total-900},'${finalOp}');select public.refresh_account_state();select public.refresh_account_state()`);eq(await score(b),400,'Completed retry and refresh no duplicate');
const r2=await request(b,100),o2=await offer(l,r2,100,0),loan2=await accept(b,o2,100,0);eq(await score(l),202,'Later support preserves +2');
await actor(b);await db.exec(`select public.repay_loan_once('${loan2}',100,gen_random_uuid())`);eq(await score(b),402,'Later completed repayment preserves +2');
eq((await events(l,'COMMUNITY_SUPPORT')).map(e=>e.points).sort((a,b)=>a-b),[2,200],'One support milestone');
await actor(b);eq(await value(`reliability_score from public.profiles where id='${b}'`),100,'Reliability unchanged');eq(await value(`available_limit=borrowing_limit from public.profiles where id='${b}'`),true,'Capacity restored');
const r3=await request(b2,100),o3=await offer(l2,r3,100,0),loan3=await accept(b2,o3,100,0);
await actor(b2);const oneOp=await value('gen_random_uuid()');await db.exec(`select public.repay_loan_once('${loan3}',100,'${oneOp}')`);eq(await score(b2),400,'One-shot first completion 400');
await actor(b2);await db.exec(`select public.repay_loan_once('${loan3}',100,'${oneOp}')`);eq(await score(b2),400,'One-shot retry deduped');
// Historical records without Trust events still consume lifetime eligibility.
await admin();await db.exec(`delete from public.trust_events where user_id in ('${b2}','${l2}')`);
const r4=await request(b2,100),o4=await offer(l2,r4,100,0),loan4=await accept(b2,o4,100,0);eq(await score(l2),202,'Historical funding without event cannot re-award');
await actor(b2);await db.exec(`select public.repay_loan_once('${loan4}',100,gen_random_uuid())`);eq(await score(b2),402,'Historical completed loan without event cannot re-award');
// Unfunded declined/cancelled/expired offers never qualify.
const r5=await request(b,100),declined=await offer(l,r5,100,0);await actor(b);await db.exec(`select public.decline_loan_offer('${declined}')`);eq(await score(l),202,'Decline no reward');
const cancelled=await offer(l,r5,100,0);await actor(l);await db.exec(`select public.cancel_loan_offer('${cancelled}')`);eq(await score(l),202,'Cancel no reward');
const expired=await offer(l,r5,100,0);await admin();await db.exec(`update public.loan_requests set expires_at=now()-interval '1 second' where id='${r5}'`);await actor(b);await db.exec('select public.refresh_account_state()');eq(await score(l),202,'Expiry no reward');
await actor(l);eq(await value(`locked_balance from public.wallets where user_id='${l}'`),0,'Reservations released');
await admin();await fails(`select public.record_trust_event('${l}','COMMUNITY_SUPPORT',200,'invalid',gen_random_uuid())`);
const prior=await score(b);await admin();await db.exec(`select public.record_trust_event('${b}','DAILY_DELAY',0,'delay','${loan}')`);eq(await score(b),prior-10,'Existing delay penalty preserved');
await admin();eq(await value(`bool_and(not has_function_privilege('authenticated',oid,'EXECUTE') and not has_function_privilege('anon',oid,'EXECUTE')) from pg_proc where proname='record_trust_event'`),true,'Internal ACL retained');

// Later grace/on-time/late rewards remain the installed +1/+2/+0 policy.
for (const grace of [true,false]) {
 const req=await request(b,100),off=await offer(l,req,100,0),lid=await accept(b,off,100,0);
 await admin();
 const deadlineTriggers=await query("select tgname from pg_trigger where tgrelid='public.loans'::regclass and tgfoid='public.preserve_original_deadline()'::regprocedure");
 for(const t of deadlineTriggers) await db.exec('alter table public.loans disable trigger '+JSON.stringify(t.tgname));
 await db.exec(`update public.loans set original_due_date=now()-interval '1 day', due_date=now()+interval '${grace?'1 day':'-1 day'}', status='${grace?'GRACE_PERIOD':'OVERDUE'}', grace_granted_at=${grace?'now()':'null'} where id='${lid}'`);
 for(const t of deadlineTriggers) await db.exec('alter table public.loans enable trigger '+JSON.stringify(t.tgname));
 await actor(b);await db.exec(`select public.repay_loan_once('${lid}',100,gen_random_uuid())`);
 eq(await value(`points from public.trust_events where related_loan_id='${lid}' and event_type='LOAN_COMPLETED'`),grace?1:0,'Later completion timing rule preserved');
}
// The first milestone still participates in the existing 1000-point cycle.
const cycleBorrower=await user(),cycleLender=await user();await connect(cycleBorrower,cycleLender);
await admin();await db.exec(`update public.profiles set trust_score=850 where id='${cycleLender}';update public.kash_trust_state set cycle_peak=850,clean_cycle=true where user_id='${cycleLender}'`);
const cycleRequest=await request(cycleBorrower,100),cycleOffer=await offer(cycleLender,cycleRequest,100,0);
await accept(cycleBorrower,cycleOffer,100,0);eq(await score(cycleLender),50,'Milestone retains 1000-point rollover');
await actor(cycleLender);eq(await value(`borrowing_limit from public.profiles where id='${cycleLender}'`),2080,'Existing clean-cycle limit increases retained');
await db.close();fs.writeFileSync('audit/first-trust-milestone-evidence.json',JSON.stringify({checks,passed:true,hostedModified:false,fixture:'inspected hosted snapshot plus reconciled contract',limitations:['PGlite serial execution; concurrent lock contention not simulated']},null,2));console.log(`${checks} milestone checks passed. No hosted writes.`);
