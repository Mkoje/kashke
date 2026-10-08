import { init } from './reconciled-fixture.mjs';
import fs from 'node:fs';
import assert from 'node:assert/strict';
const root = new URL('../../', import.meta.url);
for (const mode of ['fresh','hosted']) {
const db = await init(mode);
let checks = 0;
const actor = id => db.exec(`reset role;set role ${id ? 'authenticated' : 'anon'};select set_config('request.jwt.claim.sub','${id || ''}',false)`);
const value = async sql => (await db.query(sql)).rows[0].value;
const check = async (sql, expected) => { assert.deepEqual(await value(sql), expected); checks++; };
const fails = async sql => { await assert.rejects(() => db.exec(sql)); checks++; };
try {
  await db.exec(fs.readFileSync(new URL('supabase/migrations/202610050001_android_contract_reconciliation.sql',root),'utf8'));
  await db.exec(fs.readFileSync(new URL('supabase/migrations/202610060005_settings_controls.sql', root), 'utf8'));
  await db.exec(fs.readFileSync(new URL('supabase/migrations/202610060005_settings_controls.sql', root), 'utf8'));
  const a = await value('select gen_random_uuid() as value'), b = await value('select gen_random_uuid() as value'), c = await value('select gen_random_uuid() as value');
  await db.query(`insert into auth.users(id,email) values($1,'a@example.test'),($2,'b@example.test'),($3,'c@example.test')`, [a,b,c]);
  await actor(a); await db.exec("select public.ensure_current_profile()"); await db.exec("select public.complete_member_profile('Alice','alice','a@example.test','0715501556')");
  await actor(b); await db.exec("select public.ensure_current_profile()"); await db.exec("select public.complete_member_profile('Bob','bobby','b@example.test','0715501557')");
  await actor(c); await db.exec("select public.ensure_current_profile()"); await db.exec("select public.complete_member_profile('Carol','carol','c@example.test','0715501558')");
  await actor(a);
  const connection = await value(`select public.send_connection_request('${b}') as value`);
  await actor(b); await db.exec(`select public.respond_connection_request('${connection}',true)`);
  await db.exec(`select public.set_support_request_mute('${a}',true)`);
  await fails(`select public.set_support_request_mute('${b}',true)`);
  await check('select count(*)::int as value from public.support_request_mutes',1);
  await actor(c); await check('select count(*)::int as value from public.support_request_mutes',0);
  await fails(`select public.disconnect_member('${a}')`);
  await actor(a); await db.exec("select public.create_loan_request(100,'Test','Community request',30)");
  await actor(b); await check("select count(*)::int as value from public.notifications where type='NEW_LOAN_REQUEST'",0);
  await actor(a); await db.exec(`select public.create_loan_request(100,'Test','Direct request',30,'${b}',false)`);
  await actor(b); await check("select count(*)::int as value from public.notifications where type='DIRECT_REQUEST'",0);
  await db.exec(`select public.set_support_request_mute('${a}',false)`);
  await actor(a); await db.exec(`select public.create_loan_request(100,'Test','Unmuted request',30,'${b}',false)`);
  await actor(b); await check("select count(*)::int as value from public.notifications where type='DIRECT_REQUEST'",1);
  await db.exec(`select public.set_support_request_mute('${a}',true)`);
  await check("select count(*)::int as value from public.notifications where type='DIRECT_REQUEST'",0);
  await db.exec(`select public.disconnect_member('${a}')`);
  await check(`select status as value from public.connections where id='${connection}'`,'REMOVED');
  await actor(a); await fails(`select public.create_loan_request(100,'Test','Disconnected request',30,'${b}',false)`);
  await db.exec(`select public.send_connection_request('${b}')`);
  await check(`select status as value from public.connections where id='${connection}'`,'PENDING');
  await fails(`select public.send_connection_request('${b}')`);
  await actor(null); await fails(`select public.set_support_request_mute('${a}',true)`); await fails(`select public.disconnect_member('${a}')`);
  console.log(`${mode}: ${checks} settings SQL checks passed; reconciled schema and repeat installation passed.`);
} catch(e) { console.error(e.message); process.exitCode = 1; } finally { await db.close(); }

}
