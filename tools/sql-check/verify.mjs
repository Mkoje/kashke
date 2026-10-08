import { PGlite } from '@electric-sql/pglite';
import fs from 'node:fs';
import assert from 'node:assert/strict';
const root = new URL('../../', import.meta.url);
const read = path => fs.readFileSync(new URL(path,root),'utf8');
const db = new PGlite();
// Minimal Supabase Auth contract. Hosted GoTrue/HTTP/Realtime are not emulated.
await db.exec(`create role anon; create role authenticated; create role service_role bypassrls;
create schema auth;
create table auth.users(id uuid primary key,email text,raw_user_meta_data jsonb default '{}'::jsonb,email_confirmed_at timestamptz);
create function auth.uid() returns uuid language sql stable as $$ select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid $$;
grant usage on schema auth to anon,authenticated,service_role;
alter default privileges in schema public grant all on tables to anon,authenticated,service_role;`);
// PGlite has gen_random_uuid built in; the optional pgcrypto extension is unavailable.
const sql = text => text.replace('create extension if not exists pgcrypto;', '');
let tests=0;
const check = async (query, expected, message) => {
    const result=await db.query(query);assert.deepEqual(result.rows[0].value,expected,message);tests++;
};
const fails=async query=>{let rejected=false;try{await db.exec(query);}catch{rejected=true;}assert(rejected,`Expected rejection: ${query}`);tests++;};
const actor=async id=>db.exec(`reset role; set role ${id?'authenticated':'anon'}; select set_config('request.jwt.claim.sub','${id||''}',false);`);
const admin=async()=>db.exec('reset role;');
const uuid=async()=> (await db.query('select gen_random_uuid() as id')).rows[0].id;
const call=async expr=>(await db.query(`select ${expr} as value`)).rows[0].value;
try {
    const dir=new URL('supabase/migrations/',root);
    // Historical interest-upgrade scenarios deliberately replay the old dashboard.
    // Current fresh/hosted reconciliation is tested by verify-reconciliation.mjs.
    for(const name of fs.readdirSync(dir).filter(n=>n.endsWith('.sql') && n<'202610050001_android_contract_reconciliation.sql').sort()) {
        await db.exec(sql(fs.readFileSync(new URL(name,dir),'utf8')));
        console.log(`Applied ${name}`);
    }
    // Reproduce the hosted legacy check that rejected the logcat's 7% offer.
    await db.exec(`alter table public.loan_offers add constraint loan_offer_interest_valid check (interest_rate between 0 and 5) not valid;
        alter table public.loans add constraint loan_interest_valid check (interest_rate between 0 and 5) not valid;`);
    // Also reproduce an installed RPC and preference check still capped at 5%.
    const offerDefinition = (await db.query(
        "select pg_get_functiondef('public.create_loan_offer(uuid,bigint,numeric,integer,boolean)'::regprocedure) as definition"
    )).rows[0].definition;
    assert(offerDefinition.includes('then 15 else 7 end'));
    await db.exec(offerDefinition.replace('then 15 else 7 end', 'then 5 else 5 end'));
    await db.exec(`alter table public.lender_preferences
        drop constraint lender_preferences_default_interest_rate_check;
        alter table public.lender_preferences add constraint lender_preferences_default_interest_rate_check
        check (default_interest_rate between 0 and 5) not valid;`);
    await db.exec(sql(read('supabase/dashboard_setup.sql')));
    await db.exec(sql(read('supabase/dashboard_setup.sql')));
    tests++;
    console.log('Dashboard setup rerun twice: passed');
    await db.exec(read('supabase/tests/support_interest.sql'));
    tests++;
    console.log('Legacy interest upgrade, rates 0–15, acceptance and request ceilings: passed');
    await db.exec(read('supabase/tests/immediate_signup.sql'));
    tests++;
    await db.exec(read('supabase/tests/close_competing_offers.sql'));
    tests++;
    console.log('Competing offer closure, refunds and notifications: passed');
    await db.exec(read('supabase/tests/trust_reliability.sql'));
    tests++;
    console.log('Trust milestones, daily catch-up, decline tiers, grace, reliability and privacy: passed');
    await db.exec(read('supabase/tests/member_profile_details.sql'));
    tests++;
    console.log('Member identity, debt totals, history ranges, live reliability, privacy and overflow: passed');
    const a=await uuid(),b=await uuid(),c=await uuid();
    for(const [id,name,phone] of [[a,'borrower',''],[b,'lender','0712345678'],[c,'outsider','']])await db.query(`insert into auth.users(id,email,raw_user_meta_data) values($1,$2,$3)`,[id,`${name}@example.test`,{full_name:name,username:name,phone}]);
    const legacy=await uuid();
    await db.query('insert into auth.users(id,email) values($1,$2)',[legacy,'legacy@example.test']);
    await check(`select count(*)::int as value from public.profiles where id='${legacy}'`,1,'Legacy metadata fallback');
    await actor(a);
    await db.exec('select public.ensure_current_profile(); select public.ensure_current_profile();');
    await check(`select count(*)::int as value from public.profiles where id='${a}'`,1,'Profile idempotence');
    await check(`select count(*)::int as value from public.profiles where id='${b}'`,0,'Direct reads hide other profiles');
    await check(`select count(*)::int as value from public.wallets where user_id='${b}'`,0,'Wallet reads are owner-only');
    await check(`select count(*)::int as value from public.public_profile_directory('lender',array[]::text[],array[]::uuid[]) where id='${b}' and phone=''`,1,'Search returns only the public profile projection');
    await check(`select count(*)::int as value from public.public_profile_directory('',array['+254 712 345 678']::text[],array[]::uuid[]) where id='${b}' and matched_phone='254712345678'`,1,'Directory contact matching normalizes Kenyan numbers');
    await check(`select count(*)::int as value from public.public_profile_directory('',array[]::text[],array['${b}'::uuid]) where id='${b}'`,1,'Profile route lookup is ID-scoped');
    await check(`select pg_get_function_identity_arguments('public.public_profile_directory(text,text[],uuid[])'::regprocedure) as value`,'p_query text, p_phone_numbers text[], p_profile_ids uuid[]','Directory RPC keeps the Android argument names and PostgreSQL types');
    await check(`select count(*)::int as value from information_schema.parameters where specific_schema='public' and specific_name like 'public_profile_directory%' and parameter_name in ('borrowing_limit','available_limit')`,0,'Directory RPC does not expose financial limits');
    await fails(`update public.profiles set trust_score=1000 where id='${a}'`);
    await fails(`update public.wallets set balance=99999 where user_id='${a}'`);
    await fails(`update public.notifications set title='Forged' where user_id='${a}'`);
    await db.exec(`update public.profiles set full_name='Not allowed' where id='${b}'`);
    await fails(`select public.provision_confirmed_user('${c}')`);
    await actor(null);
    await fails('select public.ensure_current_profile()');
    await fails('select count(*)::int as value from public.profiles');
    await fails(`select * from public.public_profile_directory('lender',array[]::text[],array[]::uuid[])`);
    await actor(a);
    const connection=await call(`public.send_connection_request('${b}')`);
    await fails(`select public.respond_connection_request('${connection}',true)`);
    await actor(b);
    await check(`select full_name as value from public.profiles where id='${b}'`,'lender','Cannot change another profile');
    await db.exec(`select public.respond_connection_request('${connection}',true)`);
    // Screenshot regression: KSh 2,480 can reserve a KSh 2,000 offer at 2% for 30 days.
    await admin();
    await db.exec(`update public.wallets set balance=2480 where user_id='${b}'`);
    await actor(a);
    const screenshotRequest=await call(`public.create_loan_request(2000,'Fees','School fees',30)`);
    await actor(b);
    const screenshotOffer=await call(`public.create_loan_offer(p_request_id => '${screenshotRequest}', p_amount => 2000, p_interest_rate => 2, p_period_days => 30, p_approve_above_limit => false)`);
    await check(`select (balance-locked_balance)::int as value from public.wallets where user_id='${b}'`,480,'Screenshot offer succeeds with sufficient available balance');
    await actor(a);
    await call(`public.decline_loan_offer('${screenshotOffer}')`);
    await call(`public.cancel_loan_request('${screenshotRequest}')`);
    await admin();
    await db.exec(`update public.wallets set balance=20000 where user_id='${b}'`);
    await actor(a);
    const request=await call(`public.create_loan_request(1000,'Fees','School fees',30)`);
    await actor(c);
    await check(`select count(*)::int as value from public.loan_requests where id='${request}'`,0,'Unconnected request privacy');
    await fails(`select public.create_loan_offer('${request}',500,2,30)`);
    await actor(b);
    const offer=await call(`public.create_loan_offer('${request}',500,2,30)`);
    await check(`select locked_balance::int as value from public.wallets where user_id='${b}'`,500,'Offer reserves funds');
    await fails(`select public.accept_loan_offer('${offer}')`);
    await actor(a);
    await db.exec(`select public.request_loan_offer_terms('${offer}',1,40)`);
    await actor(b);
    await db.exec(`select public.respond_loan_offer_terms('${offer}',true)`);
    await actor(a);
    const loan=await call(`public.accept_loan_offer('${offer}')`);
    await fails(`select public.accept_loan_offer('${offer}')`);
    await check(`select remaining_amount::int as value from public.loan_requests where id='${request}'`,500,'Partial funding');
    await check(`select total_borrowed::int as value from public.profiles where id='${a}'`,500,'Borrowing stats');
    await actor(c);
    await check(`select count(*)::int as value from public.loans where id='${loan}'`,0,'Loan privacy');
    await check('select count(*)::int as value from public.transactions',0,'Transaction privacy');
    await fails(`select public.repay_loan('${loan}',100)`);
    await actor(a);
    const payment=await uuid();
    await db.exec(`select public.repay_loan_once('${loan}',100,'${payment}'); select public.repay_loan_once('${loan}',100,'${payment}');`);
    await check(`select amount_paid::int as value from public.loans where id='${loan}'`,100,'Partial repayment');
    await fails(`select public.repay_loan_once('${loan}',200,'${payment}')`);
    await actor(b);
    const decline=await call(`public.create_loan_offer('${request}',200,2,30)`);
    await actor(a);
    await db.exec(`select public.decline_loan_offer('${decline}')`);
    await actor(b);
    await check(`select locked_balance::int as value from public.wallets where user_id='${b}'`,0,'Decline releases reservation');
    await actor(a);
    const cancel=await call(`public.create_loan_request(200,'Bills','Electricity',30)`);
    await actor(b);
    await call(`public.create_loan_offer('${cancel}',200,2,30)`);
    await actor(a);
    await db.exec(`select public.cancel_loan_request('${cancel}')`);
    await actor(b);
    await check(`select locked_balance::int as value from public.wallets where user_id='${b}'`,0,'Cancel releases reservation');
    await actor(a);
    const edited=await call(`public.create_loan_request(300,'Bills','Electricity',30)`);
    await actor(b);
    await call(`public.create_loan_offer('${edited}',200,2,30)`);
    await actor(a);
    await db.exec(`select public.edit_loan_request('${edited}',100,'Bills','Updated bill',30)`);
    await actor(b);
    await check(`select locked_balance::int as value from public.wallets where user_id='${b}'`,0,'Editing releases obsolete offers');
    await fails(`select public.create_loan_offer('${edited}',100,1.5,30)`);
    await actor(a);
    const direct=await call(`public.create_loan_request(100,'Bills','Private bill',30,'${b}',false)`);
    await actor(c);
    await check(`select count(*)::int as value from public.loan_requests where id='${direct}'`,0,'Private direct request');
    await actor(a);
    const expire=await call(`public.create_loan_request(200,'Bills','Electricity',30)`);
    await actor(b);
    await call(`public.create_loan_offer('${expire}',200,2,30)`);
    await admin();
    await db.exec(`update public.loan_requests set expires_at=now()-interval '1 day' where id='${expire}'; update public.loans set due_date=now()-interval '1 day' where id='${loan}';`);
    await actor(b);
    await db.exec('select public.refresh_account_state(); select public.refresh_account_state();');
    await check(`select locked_balance::int as value from public.wallets where user_id='${b}'`,0,'Expiry releases reservation');
    await check(`select status as value from public.loans where id='${loan}'`,'OVERDUE','Server-derived overdue');
    await actor(a);
    await check(`select count(*)::int as value from public.trust_events where related_loan_id='${loan}' and event_type='OVERDUE'`,1,'Overdue events idempotent');
    await fails(`select public.grant_grace_period('${loan}',7)`);
    await actor(b);
    await db.exec(`select public.grant_grace_period('${loan}',7)`);
    await actor(a);
    await check(`select borrowing_frozen as value from public.profiles where id='${a}'`,false,'Grace unfreezes account');
    await admin();
    await db.exec(`update public.wallets set balance=10000 where user_id='${a}'`);
    await actor(a);
    await db.exec(`select public.repay_loan_once('${loan}',10000,'${await uuid()}')`);
    await check(`select status as value from public.loans where id='${loan}'`,'REPAID','Full repayment');
    await check(`select completed_loans as value from public.profiles where id='${a}'`,1,'Completed stats');
    await check(`select borrowing_frozen as value from public.profiles where id='${a}'`,false,'Repayment leaves account unfrozen');
    await fails(`select public.repay_loan('${loan}',1)`);
    await admin();
    // Rerunning complete setup on accounts with real activity must preserve v2
    // points/limits, rather than briefly applying the old overdue formula.
    const beforeRerun = (await db.query(`select id,trust_score,borrowing_limit,available_limit from public.profiles order by id`)).rows;
    await db.exec(sql(read('supabase/dashboard_setup.sql')));
    assert.deepEqual((await db.query(`select id,trust_score,borrowing_limit,available_limit from public.profiles order by id`)).rows, beforeRerun);
    tests++;
    await check(`select bool_and(relrowsecurity) as value from pg_class where oid in ('public.profiles'::regclass,'public.wallets'::regclass,'public.connections'::regclass,'public.loan_requests'::regclass,'public.loan_offers'::regclass,'public.loans'::regclass,'public.loan_payments'::regclass,'public.trust_events'::regclass,'public.transactions'::regclass,'public.notifications'::regclass,'public.lender_preferences'::regclass)`,true,'All user tables have RLS');
    console.log(`${tests} database checks passed (PostgreSQL/PGlite; Auth shim).`);
    const fixture = {};
    fixture.member_profiles = (await db.query(`select * from public.member_profile_details('${a}')`)).rows;
    for (const table of ['profiles','connections','loan_requests','loan_offers','loans','trust_events','transactions','notifications']) {
        fixture[table] = (await db.query(`select * from public.${table} order by id`)).rows;
    }
    const resources = new URL('app/src/test/resources/',root);
    fs.mkdirSync(resources,{recursive:true});
    fs.writeFileSync(new URL('database_rows.json',resources),JSON.stringify(fixture,null,2));
} catch(error) {
    console.error(error.message);
    if(error.query)console.error(error.query.slice(0,1600));
    process.exitCode=1;
} finally { await db.close(); }
