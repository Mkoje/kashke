import { PGlite } from '@electric-sql/pglite';
import fs from 'node:fs';
import assert from 'node:assert/strict';
const root=new URL('../../',import.meta.url);
const read=p=>fs.readFileSync(new URL(p,root),'utf8');
const report=read('supabase/AGREEMENT_SIGNUP_REPORT.md');
const blocks=[...report.matchAll(/```sql\r?\n([\s\S]*?)```/g)].map(m=>m[1]);
assert.equal(blocks.length,2);
const db=new PGlite();let checks=0;
const admin=()=>db.exec("reset role; select set_config('request.jwt.claim.sub','',false)");
const actor=id=>db.exec(`reset role;set role ${id?'authenticated':'anon'};select set_config('request.jwt.claim.sub','${id||''}',false)`);
const value=async q=>(await db.query(q)).rows[0].value;
const check=async(q,want,label)=>{assert.deepEqual(await value(q),want,label);checks++};
const fails=async(q,label)=>{let error;try{await db.exec(q)}catch(e){error=e}assert(error,label||q);checks++};
const call=async expr=>value(`select ${expr} as value`);
const user=async(phone,name)=>{await admin();const id=await call('gen_random_uuid()');await db.query('insert into auth.users(id,phone,raw_user_meta_data) values($1,$2,$3)',[id,phone,{full_name:name,username:name}]);return id};
try{
 await db.exec(`create role anon;create role authenticated;create role service_role bypassrls;create schema auth;
 create table auth.users(id uuid primary key,email text,phone text,raw_user_meta_data jsonb default '{}'::jsonb,email_confirmed_at timestamptz);
 create function auth.uid() returns uuid language sql stable as $$ select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid $$;
 grant usage on schema auth to anon,authenticated,service_role;
 alter default privileges in schema public grant all on tables to anon,authenticated,service_role;`);
 // This historical report test reconstructs its pre-deployment four-field API.
 // The later email/profile upgrade is tested separately by verify-email-profile.mjs.
 // Pin the historical deployment/rollback scenario; new reconciliation has its own suite.
 for (const name of fs.readdirSync(new URL('supabase/migrations/',root)).filter(n=>n.endsWith('.sql') && n<'202610040006_email_signup_profile_phone.sql').sort()) await db.exec(read('supabase/migrations/'+name).replace('create extension if not exists pgcrypto;',''));
 await db.exec(read('supabase/dashboard_setup.sql').replace('create extension if not exists pgcrypto;',''));
 // Upgrade an already-graced agreement even when no old audit event is present.
 const legacyBorrower=await call('gen_random_uuid()'),legacyLender=await call('gen_random_uuid()');
 await db.exec(`insert into auth.users(id,email,raw_user_meta_data) values
 ('${legacyBorrower}','legacyborrower@example.test','{"full_name":"Legacy borrower","username":"legacyborrower"}'),
 ('${legacyLender}','legacylender@example.test','{"full_name":"Legacy lender","username":"legacylender"}')`);
 const legacyRequest=(await db.query(`insert into public.loan_requests(borrower_id,requested_amount,remaining_amount,purpose,description) values('${legacyBorrower}',100,100,'purpose','description') returning id`)).rows[0].id;
 const legacyLoan=(await db.query(`insert into public.loans(loan_request_id,borrower_id,lender_id,principal,interest_rate,interest_amount,total_repayment,due_date,status)
 values('${legacyRequest}','${legacyBorrower}','${legacyLender}',100,0,0,100,now()+interval '2 days','GRACE_PERIOD') returning id`)).rows[0].id;
 await db.exec(`create function public.complete_member_profile(p_full_name text,p_username text,p_bio text,p_location text)
 returns void language plpgsql as $$ begin raise exception 'Old four-field API'; end $$`);
 await db.exec(blocks[0]);await db.exec(blocks[0]);checks++;
 await check(`select to_regprocedure('public.complete_member_profile(text,text,text,text)') is null as value`,true,'Upgrade removes old four-field profile API');
 await check(`select public.member_profile_complete('${legacyBorrower}') as value`,true,'Legacy account email backfilled for completion');
 await check(`select grace_granted_at is not null as value from public.loans where id='${legacyLoan}'`,true,'Legacy grace consumes one extension');
 await actor(legacyLender);await fails(`select public.grant_grace_period('${legacyLoan}',1)`,'Legacy grace cannot be repeated');
 const borrower=await user('+254712345601','borrower'), lender=await user('+254712345602','lender'), outsider=await user('+254712345603','outsider');
 await check(`select full_name as value from public.profiles where id='${borrower}'`,'','Phone signup starts incomplete');
 await fails(`insert into auth.users(id,phone) values(gen_random_uuid(),'0712345601')`,'Canonical phone duplicates rejected atomically');
 await actor(borrower);
 await fails(`select public.create_loan_request(100,'purpose','description',30)`,'Incomplete borrower blocked');
 await fails(`select public.send_connection_request('${lender}')`,'Incomplete connection request blocked');
 await fails(`select public.complete_member_profile('Name','abc','')`,'Incomplete profile save blocked');
 await fails(`select public.complete_member_profile('Name','abc','invalid-email')`,'Invalid email blocked');
 await db.exec(`select public.complete_member_profile('Borrower','borrower','borrower@example.test')`);
 await admin();
 await check(`select public.member_profile_complete('${borrower}') as value`,true,'Full name, username and email complete the profile without bio or location');
 await check(`select profile_email as value from public.profiles where id='${borrower}'`,'borrower@example.test','Profile email persisted');
 await actor(borrower);
 await fails(`update public.profiles set phone='+254712345610' where id='${borrower}'`,'Login phone protected');
 await admin();
 await db.exec(`insert into public.connections(requester_id,recipient_id,status) values('${borrower}','${lender}','ACCEPTED');update public.wallets set balance=10000 where user_id in ('${borrower}','${lender}');`);
 await actor(lender);await db.exec(`select public.complete_member_profile('Lender','lender','lender@example.test')`);
 await actor(borrower);const request=await call("public.create_loan_request(1000,'purpose','description',30)");
 await actor(lender);const offer=await call(`public.create_loan_offer('${request}',500,5,30)`);
 await db.exec(`select public.edit_loan_offer('${offer}',600,7,14)`);
 await check(`select locked_balance::int as value from public.wallets where user_id='${lender}'`,600,'Increasing edit reserves delta');
 await db.exec(`select public.edit_loan_offer('${offer}',400,2,7)`);
 await check(`select locked_balance::int as value from public.wallets where user_id='${lender}'`,400,'Decreasing edit refunds delta');
 await check(`select total_repayment::int as value from public.loan_offers where id='${offer}'`,400,'Interest recalculated');
 for(const args of ['null,1,7','100,8,7','100,1,31','100,1.5,7','0,1,7','1001,1,7'])await fails(`select public.edit_loan_offer('${offer}',${args})`);
 await actor(outsider);await fails(`select public.edit_loan_offer('${offer}',100,1,7)`);await fails(`select public.cancel_loan_offer('${offer}')`);
 await actor(borrower);await fails(`select public.cancel_loan_offer('${offer}')`);
 await check(`select count(*)::int as value from public.notifications where user_id='${borrower}' and type='LOAN_OFFER_UPDATED'`,2,'Borrower sees edits');
 await actor(lender);await db.exec(`select public.cancel_loan_offer('${offer}')`);
 await check(`select count(*)::int as value from public.loan_offers where id='${offer}'`,0,'Lender offer deleted');
 await check(`select locked_balance::int as value from public.wallets where user_id='${lender}'`,0,'Cancellation releases funds');
 await fails(`select public.cancel_loan_offer('${offer}')`,'Repeat cancel cannot refund twice');
 await actor(borrower);await check(`select count(*)::int as value from public.loan_offers where id='${offer}'`,0,'Borrower offer deleted');
 await check(`select count(*)::int as value from public.notifications where type='LOAN_OFFER_CANCELLED'`,1,'Cancellation notification retained');
 await check(`select count(*)::int as value from public.notifications where related_entity_id='${offer}'`,0,'Stale action notifications removed');
 await fails(`select public.accept_loan_offer('${offer}')`,'Cancelled offer cannot be accepted');
 // A separate pending reservation must survive edits and cancellations.
 await actor(lender);const keep=await call(`public.create_loan_offer('${request}',100,0,7)`),cancel=await call(`public.create_loan_offer('${request}',200,0,7)`);
 await actor(borrower);await db.exec(`select public.request_loan_offer_terms('${cancel}',0,7)`);
 await actor(lender);await fails(`select public.edit_loan_offer('${cancel}',300,0,7)`,'Negotiating offer is not editable');await db.exec(`select public.cancel_loan_offer('${cancel}')`);
 await check(`select locked_balance::int as value from public.wallets where user_id='${lender}'`,100,'Other reservations preserved');
 await actor(borrower);await fails(`select public.accept_loan_offer_reviewed('${keep}',99,0,7)`,'Stale displayed terms rejected');
 const accepted=await call(`public.accept_loan_offer_reviewed('${keep}',100,0,7)`);
 await actor(lender);await fails(`select public.cancel_loan_offer('${keep}')`);await fails(`select public.edit_loan_offer('${keep}',200,0,7)`);
 const createLoan=async(offset,status='ACTIVE')=>{await admin();return (await db.query(`insert into public.loans(loan_request_id,borrower_id,lender_id,principal,interest_rate,interest_amount,total_repayment,due_date,status) values($1,$2,$3,100,0,0,100,now()+$4::interval,$5) returning id`,[request,borrower,lender,offset,status])).rows[0].id};
 const early=await createLoan('4 days');await actor(lender);await fails(`select public.grant_grace_period('${early}',7)`,'Before window rejected');
 const due=await createLoan('2 days');await actor(borrower);await fails(`select public.grant_grace_period('${due}',7)`);await actor(outsider);await fails(`select public.grant_grace_period('${due}',7)`);
 await actor(lender);for(const days of ['null','0','31'])await fails(`select public.grant_grace_period('${due}',${days})`);
 await db.exec(`select public.grant_grace_period('${due}',30)`);
 await check(`select status as value from public.loans where id='${due}'`,'GRACE_PERIOD','Before-deadline grace works');
 await check(`select extract(epoch from due_date-original_due_date)::int as value from public.loans where id='${due}'`,30*86400,'Maximum 30 days applied');
 await fails(`select public.grant_grace_period('${due}',1)`,'Second grace rejected');
 await check(`select count(*)::int as value from public.notifications where related_entity_id='${due}' and type='GRACE_PERIOD_GRANTED'`,1,'Lender receives confirmation');
 await actor(borrower);await check(`select count(*)::int as value from public.notifications where related_entity_id='${due}' and type='GRACE_PERIOD_GRANTED'`,1,'Borrower receives confirmation');
 const overdue=await createLoan('-2 days','OVERDUE');await actor(lender);await db.exec(`select public.grant_grace_period('${overdue}',1)`);
 await check(`select due_date>now() as value from public.loans where id='${overdue}'`,true,'Late grant starts from now');
 const missed=await createLoan('-3 days','OVERDUE');await actor(lender);await fails(`select public.grant_grace_period('${missed}',7)`,'Closed window rejected');
 await db.exec('select public.refresh_account_state()');
 await check(`select status as value from public.loans where id='${missed}'`,'DEFAULTED','After 3 days default is permanent for grace');
 await db.exec('select public.refresh_account_state()');await check(`select count(*)::int as value from public.notifications where related_entity_id='${missed}' and type='LOAN_DEFAULTED'`,1,'Default notification idempotent');
 await fails(`select public.grant_grace_period('${missed}',7)`);
 await admin();await db.exec(`update public.loans set due_date=now()-interval '1 second' where id='${overdue}'`);
 await actor(lender);await db.exec('select public.refresh_account_state()');await check(`select status as value from public.loans where id='${overdue}'`,'DEFAULTED','Expired grace defaults immediately');
 await actor(borrower);const receipt=await call('gen_random_uuid()');await db.exec(`select public.repay_loan_once('${missed}',100,'${receipt}');select public.repay_loan_once('${missed}',100,'${receipt}')`);
 await check(`select status as value from public.loans where id='${missed}'`,'REPAID','Defaulted debt can be repaid');
 await actor(null);for(const rpc of [`public.grant_grace_period('${due}',7)`,`public.cancel_loan_offer('${keep}')`,`public.edit_loan_offer('${keep}',100,0,7)`,`public.complete_member_profile('x','xxx','x@example.test')`])await fails(`select ${rpc}`,'Anonymous mutation denied');
 await admin();
 const snapshot=(await db.query('select signature,definition from agreement_release_backup.functions order by signature')).rows;
 await db.exec(blocks[1]);checks++;
 for(const {signature,definition} of snapshot){
   const actual=await value(`select pg_get_functiondef('${signature}'::regprocedure) as value`);
   if(signature.includes('repay_loan(')) assert(actual.includes("'DEFAULTED'"),'Unpaid default keeps improved repayment during rollback');
   else assert.equal(actual,definition,'Rollback restores exact saved function');checks++;
 }
 await check(`select count(*)::int as value from pg_trigger where tgname in ('require_member_profile','guard_member_phone')`,0,'Rollback removes new triggers');
 await fails(`select public.edit_loan_offer('${keep}',100,0,7)`,'Rollback removes new API');
 await actor(borrower);await db.exec(`select public.repay_loan_once('${overdue}',100,gen_random_uuid())`);
 await check(`select status as value from public.loans where id='${overdue}'`,'REPAID','Default debt remains payable after rollback');
 await admin();await db.exec(blocks[1]);
 for(const {signature,definition} of snapshot){assert.equal(await value(`select pg_get_functiondef('${signature}'::regprocedure) as value`),definition,'Settled defaults allow full repayment rollback');checks++;}
 // Redeployment after rollback is also supported.
 await db.exec(blocks[0]);checks++;
 console.log(`${checks} agreement/signup SQL checks passed, including deployment rerun, rollback and redeployment (PGlite; hosted Auth not emulated).`);
}catch(e){console.error(e.message); console.error("position="+e.position+" internal="+e.internalQuery); if(e.position && e.query) console.error(e.query.slice(Number(e.position)-150,Number(e.position)+150));if(e.query)console.error(e.query.slice(0,1000));process.exitCode=1}finally{await db.close()}

