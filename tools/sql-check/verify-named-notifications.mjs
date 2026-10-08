import fs from 'node:fs';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';

process.chdir(fileURLToPath(new URL('../../', import.meta.url)));
const { init } = await import('./reconciled-fixture.mjs');
for (const mode of ['fresh', 'hosted']) {
  const db = await init(mode);
  try {
    await db.exec(fs.readFileSync('supabase/migrations/202610050001_android_contract_reconciliation.sql', 'utf8'));
    const namingMigration = fs.readFileSync('supabase/migrations/202610060004_named_notifications.sql', 'utf8');
    await db.exec(namingMigration);
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
        [id, `offer${i}@example.test`, { full_name: (i === 1 ? 'Martha' : 'John'), username: `offer_${i}`, phone: `+25471234560${i}`, profile_email: `offer${i}@example.test` }]);
      await actor(id);
      await db.exec('select public.ensure_current_profile()');
      await db.exec(`select public.complete_member_profile('${i === 1 ? 'Martha' : 'John'}','offer_${i}','offer${i}@example.test','+25471234560${i}')`);
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

    const call = async (sql,args=[]) => (await db.query(sql,args)).rows[0].id;
    const saved = async id => (await db.query('select * from public.loan_offers where id=$1',[id])).rows[0];
    const offer = await call('select public.create_loan_offer($1,1000,7,30,false) as id',[request]);
    const flatMigration=fs.readFileSync('supabase/migrations/202610060003_flat_support_interest.sql','utf8');
    await db.exec('reset role');
    await db.exec(flatMigration);
    await db.exec(flatMigration);
    assert.equal(Number((await saved(offer)).total_repayment),1070,'Existing pending offer repaired');
    for(let rate=0;rate<=15;rate++) {
      assert.equal(Number((await db.query('select public.support_interest_amount(6000,$1) as amount',[rate])).rows[0].amount),60*rate);
    }
    assert.equal(Number((await db.query('select public.support_interest_amount(50,7) as amount')).rows[0].amount),4,'Half-shilling rounds up');
    await actor(lender);
    await db.query('select public.edit_loan_offer($1,600,7,30)',[offer]);
    assert.equal(Number((await saved(offer)).total_repayment),642);
    await actor(borrower);
    await db.query('select public.request_loan_offer_terms($1,5,14)',[offer]);
    await actor(lender);
    await db.query('select public.respond_loan_offer_terms($1,true)',[offer]);
    assert.equal(Number((await saved(offer)).total_repayment),630);
    await db.query('select public.cancel_loan_offer($1)',[offer]);
    await db.exec('reset role');
    await db.query('update public.profiles set borrowing_limit=100000,available_limit=100000 where id=$1',[borrower]);
    await actor(borrower);
    const fullRequest=await call("select public.create_loan_request(6000,'Support','Percentage regression',365,$1,false) as id",[lender]);
    for(let rate=0;rate<=7;rate++) {
      await actor(lender);
      const id=await call('select public.create_loan_offer($1,6000,$2,30,false) as id',[fullRequest,rate]);
      assert.equal(Number((await saved(id)).total_repayment),6000+60*rate);
      await db.query('select public.cancel_loan_offer($1)',[id]);
    }
    for(const days of [7,30,365]) {
      await actor(lender);
      const id=await call('select public.create_loan_offer($1,6000,7,$2,false) as id',[fullRequest,days]);
      assert.equal(Number((await saved(id)).total_repayment),6420,'7% of 6000 for '+days+' days');
      if(days<365) await db.query('select public.cancel_loan_offer($1)',[id]);
      else {
        await actor(borrower);
        const loan=await call('select public.accept_loan_offer_reviewed($1,6000,7,365) as id',[id]);
        const row=(await db.query('select * from public.loans where id=$1',[loan])).rows[0];
        assert.equal(Number(row.interest_amount),420);
        assert.equal(Number(row.total_repayment),6420);
        await db.exec('reset role');
        await db.exec(flatMigration);
        assert.equal(Number((await db.query('select interest_amount from public.loans where id=$1',[loan])).rows[0].interest_amount),420);
        await actor(borrower);
        const operation=await call('select gen_random_uuid() as id');
        await db.query('select public.repay_loan_once($1,1000,$2)',[loan,operation]);
        await db.query('select public.repay_loan_once($1,1000,$2)',[loan,operation]);
        const paid=(await db.query('select amount_paid,principal_paid from public.loans where id=$1',[loan])).rows[0];
        assert.equal(Number(paid.amount_paid),1000,'Repeated repayment is not charged twice');
        assert.equal(Number(paid.principal_paid),580,'Corrected interest allocated before principal');
      }
    }
    await db.exec('reset role');
    const messages = (await db.query('select type,user_id,message from public.notifications')).rows;
    assert(messages.some(n => n.type === 'LOAN_ACCEPTED' && n.user_id === borrower && n.message === 'KSh 6000 was funded by John.'));
    assert(messages.some(n => n.type === 'LOAN_PAYMENT' && n.user_id === lender && n.message === 'KSh 1000 received from Martha.'));
    assert(messages.some(n => n.type === 'CONNECTION_REQUEST' && n.message === 'Martha sent you a connection request.'));
    assert(messages.some(n => n.type === 'CONNECTION_ACCEPTED' && n.message === 'John accepted your connection request.'));
    assert(messages.some(n => n.type === 'LOAN_OFFER_CANCELLED' && n.message.startsWith('John cancelled')));
    assert(messages.every(n => !/your supporter|your lender|the borrower|a supporter|Kash\.ke member/i.test(n.message)));
    const loanId = (await db.query('select id from public.loans limit 1')).rows[0].id;
    for (const [type, recipient, original, expected] of [
      ['LOAN_DUE_SOON', borrower, 'Your repayment is due within three days.', 'Your repayment to John is due within three days.'],
      ['LOAN_DEFAULTED', lender, 'The unpaid agreement defaulted. No further grace is available.', "Martha's unpaid agreement defaulted. No further grace is available."],
      ['GRACE_PERIOD_GRANTED', borrower, 'Your lender granted 7 additional days, until tomorrow.', 'John granted 7 additional days, until tomorrow.'],
      ['GRACE_PERIOD_GRANTED', lender, 'You granted 7 additional days.', 'You granted 7 additional days to Martha.'],
      ['RECOVERED_EXTERNALLY', borrower, 'Your lender marked this debt as settled outside Kash.ke.', 'John marked this debt as settled outside Kash.ke.'],
      ['LOAN_PAYMENT', borrower, 'KSh 100 repaid.', 'KSh 100 repaid to John.'],
      ['LOAN_OVERDUE', borrower, 'Your supporter has an overdue agreement.', 'John has an overdue agreement.'],
      ['LOAN_ACCEPTED', borrower, 'Kash.ke member funded the agreement.', 'John funded the agreement.'],
    ]) {
      const result = await db.query('insert into public.notifications(user_id,type,title,message,related_entity_id) values($1,$2,$3,$4,$5) returning message',
        [recipient, type, 'Simulation', original, loanId]);
      assert.equal(result.rows[0].message, expected, type);
    }
    // Reproduce an old stored notification, then exercise the backfill twice.
    await db.exec('alter table public.notifications disable trigger name_notification_participants');
    await db.query("update public.notifications set message='KSh 6000 was funded by your supporter.' where type='LOAN_ACCEPTED' and user_id=$1",[borrower]);
    await db.exec('alter table public.notifications enable trigger name_notification_participants');
    await db.exec(namingMigration);
    const once = (await db.query('select id,message,read,created_at from public.notifications order by id')).rows;
    await db.exec(namingMigration);
    assert.deepEqual((await db.query('select id,message,read,created_at from public.notifications order by id')).rows, once);
    assert(once.some(n => n.message === 'KSh 6000 was funded by John.'));
    console.log(mode + ': participant names, actual RPC flows, historical backfill and idempotency passed');
    console.log(mode+': flat percentages, rounding, pending repair, edits, negotiation, reviewed acceptance, and reapplication passed');
  } finally { await db.close(); }
}

