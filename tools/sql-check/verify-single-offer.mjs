import fs from 'node:fs';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { init } from './reconciled-fixture.mjs';

process.chdir(fileURLToPath(new URL('../../', import.meta.url)));
for (const mode of ['fresh', 'hosted']) {
  const db = await init(mode);
  try {
    await db.exec(fs.readFileSync('supabase/migrations/202610050001_android_contract_reconciliation.sql', 'utf8'));
    const migration = fs.readFileSync('supabase/migrations/202610050003_single_pending_support_offer.sql', 'utf8');
    await db.exec(migration);
    await db.exec(migration);
    const editMigration = fs.readFileSync('supabase/migrations/202610050004_edit_pending_support_offer.sql', 'utf8');
    await db.exec(editMigration);
    await db.exec(editMigration);
    const actor = async id => {
      await db.exec('reset role');
      await db.query("select set_config('request.jwt.claim.sub',$1,false)", [id]);
      await db.exec('set role authenticated');
    };
    const user = async i => {
      await db.exec('reset role');
      const id = (await db.query('select gen_random_uuid() as id')).rows[0].id;
      await db.query('insert into auth.users(id,email,raw_user_meta_data) values($1,$2,$3)',
        [id, `offer${i}@example.test`, { full_name: `Member ${i}`, username: `offer_${i}`, phone: `+25471234560${i}`, profile_email: `offer${i}@example.test` }]);
      await actor(id);
      await db.exec('select public.ensure_current_profile()');
      await db.exec(`select public.complete_member_profile('Member ${i}','offer_${i}','offer${i}@example.test','+25471234560${i}')`);
      await db.exec('reset role');
      await db.query('update public.wallets set balance=10000 where user_id=$1', [id]);
      return id;
    };
    const borrower = await user(1);
    const lender = await user(2);
    await actor(borrower);
    const connection = (await db.query('select public.send_connection_request($1) as id', [lender])).rows[0].id;
    await actor(lender);
    await db.query('select public.respond_connection_request($1,true)', [connection]);
    await actor(borrower);
    const request = (await db.query("select public.create_loan_request(1000,'Support','Test request',30,$1,false) as id", [lender])).rows[0].id;
    await actor(lender);
    const create = () => db.query('select public.create_loan_offer($1,500,7,30,false) as id', [request]);
    const offer = (await create()).rows[0].id;
    await assert.rejects(create, /already have a pending offer/);
    await assert.rejects(() => db.query('select public.edit_loan_offer($1,600,8,30)', [offer]), /Invalid offer terms/);
    await assert.rejects(() => db.query('select public.edit_loan_offer($1,600,7,31)', [offer]), /Invalid offer terms/);
    await assert.rejects(() => db.query('select public.edit_loan_offer($1,1001,7,30)', [offer]), /Invalid offer terms/);
    await db.query('select public.edit_loan_offer($1,600,7,30)', [offer]);
    await db.exec('reset role');
    assert.equal(Number((await db.query('select locked_balance from public.wallets where user_id=$1', [lender])).rows[0].locked_balance), 600);
    assert.equal((await db.query('select count(*)::int as count from public.loan_offers where loan_request_id=$1', [request])).rows[0].count, 1);
    await actor(borrower);
    await assert.rejects(() => db.query('select public.edit_loan_offer($1,700,2,14)', [offer]), /Offer unavailable/);
    await assert.rejects(() => db.query('select public.cancel_loan_offer($1)', [offer]), /Offer unavailable/);
    await db.query('select public.request_loan_offer_terms($1,2,14)', [offer]);
    await actor(lender);
    await assert.rejects(create, /already have a pending offer/);
    await db.query('select public.edit_loan_offer($1,700,2,14)', [offer]);
    const saved = (await db.query('select * from public.loan_offers where id=$1', [offer])).rows[0];
    assert.equal(saved.status, 'OFFERED');
    assert.equal(Number(saved.amount), 700);
    assert.equal(Number(saved.interest_rate), 2);
    assert.equal(saved.repayment_period_days, 14);
    assert.equal(saved.requested_interest_rate, null);
    assert.equal(saved.requested_repayment_period_days, null);
    // Re-enter the session and read through RLS, as a reopened app would.
    await actor(lender);
    assert.equal((await db.query("select count(*)::int as count from public.loan_offers where loan_request_id=$1 and lender_id=$2 and status in ('OFFERED','TERMS_REQUESTED')", [request, lender])).rows[0].count, 1);
    await db.query('select public.cancel_loan_offer($1)', [offer]);
    assert.equal((await db.query("select count(*)::int as count from public.loan_offers where loan_request_id=$1 and lender_id=$2 and status in ('OFFERED','TERMS_REQUESTED')", [request, lender])).rows[0].count, 0);
    assert.equal(Number((await db.query('select locked_balance from public.wallets where user_id=$1', [lender])).rows[0].locked_balance), 0);
    assert.ok((await create()).rows[0].id);
    console.log(`${mode}: duplicate offers rejected, edit limits enforced, wallet reservation updated, cancellation permits replacement`);
  } finally {
    await db.close();
  }
}
