import fs from 'node:fs';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { init } from './reconciled-fixture.mjs';
process.chdir(fileURLToPath(new URL('../../', import.meta.url)));
for (const mode of ['fresh', 'hosted']) {
  const db = await init(mode);
  try {
    await db.exec(fs.readFileSync('supabase/migrations/202610050001_android_contract_reconciliation.sql', 'utf8'));
    const sql = fs.readFileSync('supabase/migrations/202610050005_push_devices.sql', 'utf8');
    await db.exec(sql);
    await db.exec(sql);
    const users = [];
    for (let i = 0; i < 2; i++) {
      const id = (await db.query('select gen_random_uuid() as id')).rows[0].id;
      await db.query('insert into auth.users(id,email,raw_user_meta_data) values($1,$2,$3)',
        [id, `push${i}@example.test`, { full_name: 'Push tester', username: `push_${i}` }]);
      await db.query("select set_config('request.jwt.claim.sub',$1,false)", [id]);
      await db.exec('set role authenticated; select public.ensure_current_profile(); reset role');
      users.push(id);
    }
    const token = 'a-valid-test-device-token-with-sufficient-length';
    for (const user of users) {
      await db.query("select set_config('request.jwt.claim.sub',$1,false)", [user]);
      await db.exec('set role authenticated');
      await db.query('select public.register_push_device($1)', [token]);
      await assert.rejects(() => db.query('select token from public.push_devices'), /permission denied/);
      await assert.rejects(() => db.query("select public.register_push_device('short')"), /Invalid push token/);
      await db.exec('reset role');
      const rows = (await db.query('select user_id from public.push_devices')).rows;
      assert.equal(rows.length, 1);
      assert.equal(rows[0].user_id, user);
    }
    await db.query("select set_config('request.jwt.claim.sub','',false)");
    await db.exec('set role authenticated');
    await assert.rejects(() => db.query('select public.register_push_device($1)', [token]), /Sign in first/);
    await db.exec('reset role; set role anon');
    await assert.rejects(() => db.query('select public.register_push_device($1)', [token]), /permission denied/);
    console.log(`PASS ${mode}: registration uses authenticated identity, account switch reassigns token, tokens are private, anon and invalid tokens rejected`);
  } finally { await db.close(); }
}
