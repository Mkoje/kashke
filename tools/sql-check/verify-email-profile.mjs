import { PGlite } from '@electric-sql/pglite';
import fs from 'node:fs';
import assert from 'node:assert/strict';
const root = new URL('../../', import.meta.url);
const read = p => fs.readFileSync(new URL(p, root), 'utf8');
const migration = '202610040006_email_signup_profile_phone.sql';
const db = new PGlite();
let checks = 0;
const admin = () => db.exec("reset role;select set_config('request.jwt.claim.sub','',false)");
const actor = id => db.exec(`reset role;set role ${id ? 'authenticated' : 'anon'};select set_config('request.jwt.claim.sub','${id || ''}',false)`);
const value = async q => (await db.query(q)).rows[0].value;
const check = async (q, want) => { assert.deepEqual(await value(q), want); checks++; };
const fails = async q => { await assert.rejects(() => db.exec(q)); checks++; };
try {
    await db.exec(`create role anon;create role authenticated;create role service_role bypassrls;create schema auth;
    create table auth.users(id uuid primary key,email text,phone text,raw_user_meta_data jsonb default '{}'::jsonb,email_confirmed_at timestamptz);
    create function auth.uid() returns uuid language sql stable as $$select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid$$;
    grant usage on schema auth to anon,authenticated,service_role;
    alter default privileges in schema public grant all on tables to anon,authenticated,service_role;`);
    // Reconstruct this historical profile upgrade; current reconciliation is separate.
    for (const name of fs.readdirSync(new URL('supabase/migrations/', root)).filter(n => n.endsWith('.sql') && n < migration).sort()) {
        await db.exec(read('supabase/migrations/' + name).replace('create extension if not exists pgcrypto;', ''));
    }
    // Reproduce the existing deployed profile API and phone guard.
    const deployment = [...read('supabase/AGREEMENT_SIGNUP_REPORT.md').matchAll(/```sql\r?\n([\s\S]*?)```/g)][0][1];
    await db.exec(deployment);
    const sql = read('supabase/migrations/' + migration);
    await db.exec(sql);
    await db.exec(sql); // Deployment must be rerunnable.
    const user = await value('select gen_random_uuid() as value');
    const other = await value('select gen_random_uuid() as value');
    await db.query('insert into auth.users(id,email) values($1,$2),($3,$4)', [user, 'jane@example.test', other, 'other@example.test']);
    await check(`select phone as value from public.profiles where id='${user}'`, '');
    await check(`select public.member_profile_complete('${user}') as value`, false);
    await check(`select count(*)::int as value from public.wallets where user_id='${user}'`, 1);
    await actor(user);
    await fails(`select public.complete_member_profile('Jane','jane','jane@example.test','')`);
    await fails(`select public.complete_member_profile('Jane','jane','jane@example.test','+15551234567')`);
    await fails(`select public.complete_member_profile('Jane','jane','invalid','0715501556')`);
    await fails(`select public.create_loan_request(100,'purpose','description',30)`);
    await db.exec(`select public.complete_member_profile('Jane','jane','jane@example.test','0715501556')`);
    await admin();
    await check(`select phone as value from public.profiles where id='${user}'`, '+254715501556');
    await check(`select public.member_profile_complete('${user}') as value`, true);
    await actor(user);
    await db.exec(`select public.complete_member_profile('Jane','jane','jane@example.test','254715501556')`);
    await fails(`select public.complete_member_profile('Jane','jane','jane@example.test','0115501556')`);
    await fails(`update public.profiles set phone='' where id='${user}'`);
    await actor(other);
    await fails(`select public.complete_member_profile('Other','other','other@example.test','+254715501556')`);
    await admin();
    await check(`select phone as value from public.profiles where id='${other}'`, '');
    await actor(other);
    await db.exec(`select public.complete_member_profile('Other','other','other@example.test','0115501556')`);
    await admin();
    await check(`select phone as value from public.profiles where id='${other}'`, '+254115501556');
    await check(`select count(*)::int as value from public.wallets where user_id in ('${user}','${other}')`, 2);
    await check(`select email as value from auth.users where id='${user}'`, 'jane@example.test');
    await actor(null);
    await fails(`select public.complete_member_profile('X','xxx','x@example.test','0715501557')`);
    console.log(`${checks} email/profile SQL checks passed (PGlite; hosted Auth not emulated).`);
} finally { await db.close(); }
