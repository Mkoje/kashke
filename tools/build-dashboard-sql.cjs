const fs = require('fs');
const dir = 'supabase/migrations';
// Existing hosted project: dashboard_setup.sql contains ONLY the latest update.
// Historical files stay in versioned migration history for clean installs/tests.
const incrementalMigration = '202610040005_support_interest_complete_fix.sql';
if (!process.argv.includes('--full')) {
    fs.writeFileSync('supabase/dashboard_setup.sql',
        `-- Incremental support interest checks and RPC fix; safe to rerun on the existing project.\nbegin;\n${fs.readFileSync(`${dir}/${incrementalMigration}`, 'utf8')}\ncommit;\n`);
    process.exit(0);
}
// An explicitly requested fresh-install script goes to a separate file so the
// user's incremental deployment file can never accidentally replay old policy.
let sql = "begin;\ndrop function if exists public.public_profile_directory(text, text[], uuid[]);\ndrop function if exists public.get_direct_request_borrower_summary(uuid);\n";
for (const name of fs.readdirSync(dir).filter(n=>n.endsWith('.sql')).sort()) {
    let text = fs.readFileSync(`${dir}/${name}`,'utf8');
    // Older maintenance must not impose superseded penalties on a v2 rerun.
    if (name === '202610010004_financial_guards.sql') {
        text = text.replace('    perform public.maintain_account_data(null);', '    -- Maintenance uses the final policy after all migrations.');
    }
    text = text.replace(/create table public\./g,'create table if not exists public.');
    text = text.replace(/create (unique )?index (?!if not exists)/g,(_,u)=>`create ${u||''}index if not exists `);
    text = text.replace(/create policy "([^"]+)" on (public\.\w+)/g,(_,name,table)=>`drop policy if exists "${name}" on ${table};\ncreate policy "${name}" on ${table}`);
    text = text.replace(/create trigger (\w+) (after|before) ([\s\S]*?)on (public\.\w+|auth\.\w+)/g,(_,name,timing,event,table)=>`drop trigger if exists ${name} on ${table};\ncreate trigger ${name} ${timing} ${event}on ${table}`);
    sql += `\n-- ${name}\n${text}\n`;
}
sql += 'commit;\n';
fs.writeFileSync('supabase/bootstrap_setup.sql',sql);
