import { PGlite } from '@electric-sql/pglite';
import fs from 'node:fs';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
process.chdir(fileURLToPath(new URL('../../',import.meta.url)));
const read=p=>fs.readFileSync(p,'utf8');
const snapshot=JSON.parse(read('audit/hosted-contract-snapshot.json'))[0].database_contract_audit;
const migration='202610050001_android_contract_reconciliation.sql';
const proposal=read('supabase/migrations/'+migration);
let checks=0;
const quote=s=>'"'+s.replaceAll('"','""')+'"';
const expected=[
 'is_username_available(text)','ensure_current_profile()','complete_member_profile(text,text,text,text)',
 'refresh_account_state()','public_profile_directory(text,text[],uuid[])','member_profile_details(uuid)',
 'get_direct_request_borrower_summary(uuid)','send_connection_request(uuid)','respond_connection_request(uuid,boolean)',
 'create_loan_request(bigint,text,text,integer,uuid,boolean)','edit_loan_request(uuid,bigint,text,text,integer)',
 'cancel_loan_request(uuid)','create_loan_offer(uuid,bigint,numeric,integer,boolean)',
 'request_loan_offer_terms(uuid,numeric,integer)','respond_loan_offer_terms(uuid,boolean)',
 'edit_loan_offer(uuid,bigint,numeric,integer)','cancel_loan_offer(uuid)',
 'accept_loan_offer_reviewed(uuid,bigint,numeric,integer)','decline_loan_offer(uuid)',
 'repay_loan_once(uuid,bigint,uuid)','grant_grace_period(uuid,integer)','mark_recovered_externally(uuid)'];
const productionCallNames=new Set(['SupabaseRepository.kt','SupabaseDataRepository.kt'].flatMap(file=>
 [...read('app/src/main/java/com/kashke/app/data/repository/'+file).matchAll(/(?:postgrest\.rpc|operation|launchOperation)\(\s*"([a-z_]+)"/g)].map(m=>m[1])));
assert.deepEqual([...productionCallNames].sort(),[...new Set(expected.map(s=>s.split('(')[0]))].sort(),'Expected contract covers actual Kotlin RPC calls');checks++;
async function init(mode){
 const db=new PGlite();
 await db.exec(`create role anon;create role authenticated;create role service_role bypassrls;
 create schema auth;create table auth.users(id uuid primary key,email text,phone text,raw_user_meta_data jsonb default '{}'::jsonb,email_confirmed_at timestamptz);
 create function auth.uid() returns uuid language sql stable as $$select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid$$;
 grant usage on schema auth to anon,authenticated,service_role;`);
 if(mode==='fresh'){
  await db.exec('alter default privileges in schema public grant all on tables to anon,authenticated,service_role');
  for(const n of fs.readdirSync('supabase/migrations').filter(n=>n.endsWith('.sql')&&n<migration).sort())
   await db.exec(read('supabase/migrations/'+n).replace('create extension if not exists pgcrypto;',''));
 }else{
  for(const t of snapshot.tables){
   const cols=snapshot.columns.filter(c=>c.table_name===t.table).map(c=>`${quote(c.column_name)} ${c.udt_name==='timestamptz'?'timestamptz':c.udt_name}${c.is_nullable==='NO'?' NOT NULL':''}${c.column_default?' DEFAULT '+c.column_default:''}`);
   await db.exec(`CREATE TABLE public.${quote(t.table)}(${cols.join(',')});ALTER TABLE public.${quote(t.table)} ENABLE ROW LEVEL SECURITY`);
  }
  for(const c of [...snapshot.constraints].sort((a,b)=>(a.type==='f')-(b.type==='f')))await db.exec(`ALTER TABLE public.${quote(c.table)} ADD CONSTRAINT ${quote(c.name)} ${c.definition}`);
  for(const f of snapshot.functions)await db.exec(f.definition);
  for(const i of snapshot.indexes)if(!snapshot.constraints.some(c=>c.name===i.indexname))await db.exec(i.indexdef);
  for(const t of snapshot.triggers)await db.exec(t.definition);
  for(const p of snapshot.policies)await db.exec(`CREATE POLICY ${quote(p.policyname)} ON public.${quote(p.tablename)} AS ${p.permissive} FOR ${p.cmd} TO ${p.roles.map(quote).join(',')}${p.qual?' USING ('+p.qual+')':''}${p.with_check?' WITH CHECK ('+p.with_check+')':''}`);
  for(const g of snapshot.table_grants)if(['anon','authenticated','service_role','PUBLIC'].includes(g.grantee))await db.exec(`GRANT ${g.privilege_type} ON public.${quote(g.table_name)} TO ${g.grantee}`);
  for(const f of snapshot.functions){
   await db.exec(`REVOKE ALL ON FUNCTION public.${f.signature} FROM PUBLIC,anon,authenticated`);
   if(f.anon_execute)await db.exec(`GRANT EXECUTE ON FUNCTION public.${f.signature} TO anon`);
   if(f.authenticated_execute)await db.exec(`GRANT EXECUTE ON FUNCTION public.${f.signature} TO authenticated`);
  }
  // Inferred only from the hosted old repayment body, not captured metadata.
  // Exercises the migration's receipt bridge; preflight checks it on deployment.
  await db.exec(`CREATE TABLE public.repayment_operations(operation_id text primary key,loan_id uuid not null,borrower_id uuid not null,amount integer not null)`);
 }
 return db;
}
async function run(mode){
 const db=await init(mode);
 const rows=async(q,args)=>(await db.query(q,args)).rows;
 const val=async q=>(await rows(q))[0].value;
 const call=async q=>val('select '+q+' as value');
 const admin=()=>db.exec("reset role;select set_config('request.jwt.claim.sub','',false)");
 const actor=id=>db.exec(`reset role;set role ${id?'authenticated':'anon'};select set_config('request.jwt.claim.sub','${id||''}',false)`);
 const check=async(q,want,label)=>{assert.deepEqual(await val(q),want,mode+': '+label);checks++};
 const fails=async(q,label,code)=>{let e;try{await db.exec(q)}catch(err){e=err}assert(e,mode+': expected rejection '+label);if(code)assert.equal(e.code,code);checks++};
 try{
  // Real hosted bodies must agree with Android's named JSON keys; integer JSON
  // does not disambiguate PostgREST numeric/integer overloads.
  if(mode==='hosted'){
   assert.equal(snapshot.functions.filter(f=>f.name==='request_loan_offer_terms').length,2);checks++;
   await check("select count(*)::int as value from pg_proc where proname='request_loan_offer_terms' and proargnames=array['p_offer_id','p_interest_rate','p_period_days']",2,'Ambiguous key sets before reconciliation');
  }
  // Seed known-existing financial state before deployment, never repair it.
  const oldBorrower=await call('gen_random_uuid()'),oldLender=await call('gen_random_uuid()');
  await db.query('insert into auth.users(id,email,raw_user_meta_data) values($1,$2,$3),($4,$5,$6)',[
   oldBorrower,'oldb@example.test',{full_name:'Old borrower',username:'oldborrower',phone:'+254712340000'},
   oldLender,'oldl@example.test',{full_name:'Old lender',username:'oldlender',phone:'+254712340001'}]);
  for(const [id,name] of [[oldBorrower,'oldborrower'],[oldLender,'oldlender']]){
   await db.query('insert into public.profiles(id,full_name,username,phone) values($1,$2,$2,$3) on conflict(id) do nothing',[id,name,id===oldBorrower?'+254712340000':'+254712340001']);
   await db.query('insert into public.wallets(user_id,balance,locked_balance) values($1,1000,0) on conflict(user_id) do update set balance=1000',[id]);
  }
  const oldRequest=(await rows(`insert into public.loan_requests(borrower_id,requested_amount,remaining_amount,purpose,description) values('${oldBorrower}',100,100,'old','old') returning id`))[0].id;
  await db.exec(`insert into public.loan_offers(loan_request_id,borrower_id,lender_id,amount,interest_rate,repayment_period_days,total_repayment) values('${oldRequest}','${oldBorrower}','${oldLender}',100,0,30,100);update public.wallets set locked_balance=100 where user_id='${oldLender}'`);
  if(mode==='hosted')await db.exec(`insert into public.loans(borrower_id,lender_id,loan_request_id,principal,interest_rate,interest_amount,total_repayment,lender_contribution,due_date,original_due_date) values('${oldBorrower}','${oldLender}','${oldRequest}',100,0,0,100,100,now()+interval '30 days',now()+interval '30 days')`);
  const dataSnapshot=async()=>{
   const output={};for(const t of snapshot.tables)output[t.table]=await rows(`select to_jsonb(t)-'profile_email'-'grace_granted_at'-'grace_days' row from public.${quote(t.table)} t order by to_jsonb(t)::text`);return output;
  };
  const before=await dataSnapshot();
  const trustNames=['record_trust_event','apply_daily_trust_penalties','loan_reliability','refresh_profile_limit','refresh_profile_statistics','public_profile_directory','member_profile_details','get_direct_request_borrower_summary'];
  const definitions=await rows(`select proname,prosrc from pg_proc where proname=any($1::text[]) order by proname`,[trustNames]);
  // Fail-closed rollback on an unexpected required field, before replacing APIs.
  await db.exec('alter table public.transactions add column audit_extra text not null default \'fixture\';alter table public.transactions alter column audit_extra drop default');
  await fails(proposal,'Unreviewed required field must abort');await db.exec('rollback;alter table public.transactions drop column audit_extra');
  await db.exec("create function public.audit_unreviewed_definer() returns void language plpgsql security definer as $$begin return;end$$");
  await fails(proposal,'Unreviewed callable definer must abort');await db.exec('rollback;drop function public.audit_unreviewed_definer()');
  await db.exec(proposal);
  assert.deepEqual(await dataSnapshot(),before,mode+': existing data unchanged by deployment');checks++;
  assert.deepEqual(await rows(`select proname,prosrc from pg_proc where proname=any($1::text[]) order by proname`,[trustNames]),definitions,mode+': Trust/reliability/public projection bodies unchanged');checks++;
  await db.exec(proposal);assert.deepEqual(await dataSnapshot(),before,mode+': repeat deployment unchanged');checks++;
  if(process.env.KASH_FIRST_MILESTONES==='1') await db.exec(read('supabase/migrations/202610050002_first_trust_milestones.sql'));
  for(const sig of expected)await check(`select to_regprocedure('public.${sig}') is not null as value`,true,'RPC '+sig);
  for(const name of expected.map(s=>s.split('(')[0]))await check(`select count(*)::int as value from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname='public' and p.proname='${name}'`,name==='complete_member_profile'?2:1,'No unintended overload '+name);
  for(const name of ['record_trust_event','apply_daily_trust_penalties','refresh_profile_limit','refresh_profile_statistics','maintain_account_data','mark_loan_overdue','repay_loan','member_profile_complete','release_request_offers'])
   await check(`select bool_and(not has_function_privilege('anon',oid,'EXECUTE') and not has_function_privilege('authenticated',oid,'EXECUTE')) as value from pg_proc where proname='${name}'`,true,'Internal ACL '+name);
  const user=async(i)=>{await admin();const id=await call('gen_random_uuid()');await db.query('insert into auth.users(id,email,raw_user_meta_data) values($1,$2,$3)',[id,`reconcile${i}@example.test`,{full_name:`User ${i}`,username:`reconcile_${i}`,phone:`+25471234560${i}`,profile_email:`reconcile${i}@example.test`}]);await actor(id);await db.exec('select public.ensure_current_profile()');await db.exec(`select public.complete_member_profile('User ${i}','reconcile_${i}','reconcile${i}@example.test','+25471234560${i}')`);await admin();await db.query('update public.wallets set balance=100000 where user_id=$1',[id]);return id};
  const b=await user(0),l=await user(1),l2=await user(2),out=await user(3);
  await actor(b);await check(`select (public.complete_member_profile(p_email=>'reconcile0@example.test',p_full_name=>'User 0',p_username=>'reconcile_0')).id='${b}' as value`,true,'Intentional legacy named profile compatibility');
  await actor(b);const connection=await call(`public.send_connection_request('${l}')`);await actor(l);await db.exec(`select public.respond_connection_request('${connection}',true)`);
  await actor(b);const c2=await call(`public.send_connection_request('${l2}')`);await actor(l2);await db.exec(`select public.respond_connection_request('${c2}',true)`);
  await actor(b);const request=await call("public.create_loan_request(1000,'purpose','description',30)");
  await actor(l);const offer=await call(`public.create_loan_offer('${request}',500,7,30,false)`);
  await check(`select borrower_id='${b}' as value from public.loan_offers where id='${offer}'`,true,'Explicit borrower');
  await fails(`select public.create_loan_offer('${request}',100,8,30,false)`,'Normal rate over 7');
  await actor(l2);const second=await call(`public.create_loan_offer('${request}',500,7,30,false)`);
  await actor(b);await db.exec(`select public.request_loan_offer_terms('${offer}',6,60)`);
  await fails(`select public.request_loan_offer_terms('${offer}',5,90)`,'Pending second terms remains rejected','P0001');
  await db.exec(`select public.request_loan_offer_terms('${second}',5,60)`);
  await check(`select count(*)::int as value from public.loan_offers where id in ('${offer}','${second}') and status='TERMS_REQUESTED'`,2,'Independent offer negotiation');
  await actor(l);await db.exec(`select public.respond_loan_offer_terms('${offer}',true)`);
  await db.exec(`select public.edit_loan_offer('${offer}',600,7,30)`);await check(`select locked_balance as value from public.wallets where user_id='${l}'`,600,'Edit increase delta');
  await db.exec(`select public.edit_loan_offer('${offer}',400,7,30)`);await check(`select locked_balance as value from public.wallets where user_id='${l}'`,400,'Edit decrease delta');
  await actor(out);await fails(`select public.accept_loan_offer_reviewed('${offer}',400,7,30)`,'Unauthorized acceptance');
  await actor(b);await fails(`select public.accept_loan_offer_reviewed('${offer}',500,7,30)`,'Stale reviewed amount');
  const loan=await call(`public.accept_loan_offer_reviewed('${offer}',400,7,30)`);
  await check(`select lender_contribution=principal and original_due_date=due_date and principal=400 as value from public.loans where id='${loan}'`,true,'Contribution/deadline');
  await check(`select status='PARTIALLY_FUNDED' and remaining_amount=600 and funded_amount=400 as value from public.loan_requests where id='${request}'`,true,'Partial funding');
  await actor(l2);await check(`select locked_balance as value from public.wallets where user_id='${l2}'`,0,'Competing reservation released on partial');
  await actor(b);await check(`select status as value from public.loan_offers where id='${second}'`,'DECLINED','Competing terms closed');
  await actor(l);const finalOffer=await call(`public.create_loan_offer('${request}',600,0,30,false)`);
  await actor(b);const finalLoan=await call(`public.accept_loan_offer_reviewed('${finalOffer}',600,0,30)`);
  await check(`select status='FULLY_FUNDED' and remaining_amount=0 and funded_amount=1000 as value from public.loan_requests where id='${request}'`,true,'Full funding');
  const op=await call('gen_random_uuid()');await db.exec(`select public.repay_loan_once('${loan}',100,'${op}')`);
  await check(`select principal_paid as value from public.loans where id='${loan}'`,98,'Preserved interest-first allocation (2 interest)');
  await db.exec(`select public.repay_loan_once('${loan}',100,'${op}')`);await check(`select amount_paid as value from public.loans where id='${loan}'`,100,'Receipt deduplication');
  await fails(`select public.repay_loan_once('${loan}',101,'${op}')`,'Receipt argument reuse');
  if(mode==='hosted'){
   const legacy=await call('gen_random_uuid()');await admin();await db.exec(`insert into public.repayment_operations values('${legacy}','${loan}','${b}',100)`);
   await actor(b);await db.exec(`select public.repay_loan_once('${loan}',100,'${legacy}')`);await check(`select amount_paid as value from public.loans where id='${loan}'`,100,'Legacy receipt bridge no recharge');
  }
  await db.exec(`select public.repay_loan_once('${loan}',302,gen_random_uuid());select public.repay_loan_once('${finalLoan}',600,gen_random_uuid())`);
  await db.exec(`select public.repay_loan_once('${loan}',100,'${op}')`);
  await check(`select amount_paid as value from public.loans where id='${loan}'`,402,'Receipt retry after complete repayment');
  await check(`select status as value from public.loans where id='${loan}'`,'REPAID','Full repayment');
  await check(`select count(*)::int as value from public.trust_events where related_loan_id='${loan}' and event_type='LOAN_COMPLETED'`,1,'One completion reward with trigger');
  await check(`select reliability_score as value from public.profiles where id='${b}'`,100,'Reliability completion');
  await check(`select (public.member_profile_details('${b}')).current_debt_total as value`,0,'Member debt summary');
  await check(`select count(*)::int as value from public.public_profile_directory('',array[]::text[],array['${l}'::uuid])`,1,'Public directory');
  await check(`select count(*)::int as value from public.profiles where id='${l}'`,0,'Private profile hidden');
  await fails(`update public.profiles set trust_score=999 where id='${b}'`,'Direct Trust tampering');
  await actor(l);await check(`select trust_score as value from public.profiles where id='${l}'`,process.env.KASH_FIRST_MILESTONES==='1'?202:4,'Two support rewards preserved');
  await actor(b);const cancelRequest=await call("public.create_loan_request(100,'cancel','cancel',30)");
  await actor(l);const cancelled=await call(`public.create_loan_offer('${cancelRequest}',100,0,30,false)`);
  await db.exec(`select public.cancel_loan_offer('${cancelled}')`);await check(`select locked_balance as value from public.wallets where user_id='${l}'`,0,'Cancellation release');
  await fails(`select public.cancel_loan_offer('${cancelled}')`,'Duplicate release rejected');
  await check(`select status as value from public.loan_offers where id='${cancelled}'`,'EXPIRED','Cancellation preserves offer record');
  await actor(b);await admin();await db.exec(`update public.profiles set available_limit=100 where id='${b}'`);await actor(b);
  const direct=await call(`public.create_loan_request(500,'direct','direct',30,'${l}',true)`);
  await actor(out);await check(`select count(*)::int as value from public.loan_requests where id='${direct}'`,0,'Private direct request');
  await actor(l);await fails(`select public.create_loan_offer('${direct}',500,15,30,false)`,'Explicit override required');
  await fails(`select public.create_loan_offer('${direct}',500,16,30,true)`,'Above-limit rate over 15');
  const directOffer=await call(`public.create_loan_offer('${direct}',500,15,30,true)`);
  await actor(b);const directLoan=await call(`public.accept_loan_offer_reviewed('${directOffer}',500,15,30)`);
  await check(`select interest_rate::integer as value from public.loans where id='${directLoan}'`,15,'Approved direct maximum');
  await actor(out);await fails(`select public.edit_loan_offer('${directOffer}',500,0,30)`,'Unauthorized edit');
  await actor(null);await fails(`select public.create_loan_offer('${direct}',10,0,30,false)`,'Anonymous financial call','42501');
  await fails(`select public.maintain_account_data(null)`,'Anonymous maintenance','42501');
  await actor(b);await fails(`insert into public.loan_offers(loan_request_id,borrower_id,lender_id,amount,repayment_period_days,total_repayment) values('${direct}','${b}','${out}',1,30,1)`,'Direct financial insert denied');
  await admin();await db.exec(`update public.loans set due_date=now()+interval '1 day',original_due_date=now()+interval '1 day' where id='${directLoan}'`);
  // Original deadline trigger preserves prior original date; choose a fixture
  // at insert for grace rather than bypassing it.
  const grace=(await rows(`insert into public.loans(loan_request_id,borrower_id,lender_id,principal,interest_rate,interest_amount,total_repayment,lender_contribution,due_date,original_due_date) values('${direct}','${b}','${l}',10,0,0,10,10,now()+interval '1 day',now()+interval '1 day') returning id`))[0].id;
  await actor(l);await db.exec(`select public.grant_grace_period('${grace}',7)`);await fails(`select public.grant_grace_period('${grace}',7)`,'Grace once');
  await check(`select grace_days as value from public.loans where id='${grace}'`,7,'Grace fields');
  await admin();await db.exec(`update public.loans set due_date=now()-interval '1 day' where id='${grace}'`);await actor(b);await db.exec('select public.refresh_account_state()');
  await check(`select status as value from public.loans where id='${grace}'`,'DEFAULTED','Grace expiry default');
  await db.exec(`select public.repay_loan_once('${grace}',10,gen_random_uuid())`);await check(`select status as value from public.loans where id='${grace}'`,'REPAID','Defaulted repayment');
  await admin();
  // Reuse the full Trust policy assertions with current required-field fixtures
  // and a deadline inside the current grace window. All changes roll back.
  const trustFixture=read('supabase/tests/trust_reliability.sql')
   .replaceAll('total_repayment,due_date)','total_repayment,lender_contribution,due_date)')
   .replaceAll('100,0,0,100,now()', '100,0,0,100,100,now()')
   .replace("now()+interval '6 days'", "now()+interval '2 days'")
   .replace('    select trust_score into value from public.profiles where id=a;',
    "    perform set_config('request.jwt.claim.sub',a::text,true);perform public.ensure_current_profile();\n    perform set_config('request.jwt.claim.sub',b::text,true);perform public.ensure_current_profile();\n    perform set_config('request.jwt.claim.sub','',true);\n    update public.profiles set borrowing_limit=32000,available_limit=32000 where id in(a,b);\n    select trust_score into value from public.profiles where id=a;");
  if(process.env.KASH_FIRST_MILESTONES!=='1'){await db.exec(trustFixture);checks++;}
  console.log(mode+': reconciliation contract and behavior checks passed');
 } finally {await db.close();}
}
if(process.env.KASH_FIRST_MILESTONES!=='1') await run('fresh');await run('hosted');
fs.writeFileSync(process.env.KASH_FIRST_MILESTONES==='1'?'audit/milestone-regression-evidence.json':'audit/reconciliation-test-evidence.json',JSON.stringify({checks,migration,sha256:createHash('sha256').update(proposal).digest('hex'),historicalSuiteChecks:process.env.KASH_FIRST_MILESTONES==='1'?0:144,engines:process.env.KASH_FIRST_MILESTONES==='1'?['PGlite hosted metadata reconstruction']:['PGlite fresh canonical','PGlite hosted metadata reconstruction'],milestoneMigration:process.env.KASH_FIRST_MILESTONES==='1'?'202610050002_first_trust_milestones.sql':null,hostedApplied:false,legacyReceiptSchema:'inferred and prerequisite checked; not authoritative',limitations:['No HTTP PostgREST runtime','No multi-session PostgreSQL concurrency','No hosted Auth/Cron emulation']},null,2)+'\n');
console.log(checks+' reconciliation checks passed. No hosted connection was made.');
