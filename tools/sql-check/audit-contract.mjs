// Read-only project audit; all database mutations occur in disposable memory.
import { PGlite } from '@electric-sql/pglite';
import fs from 'node:fs';
import assert from 'node:assert/strict';
const read=p=>fs.readFileSync(p,'utf8');
const db=new PGlite();
const results=[];
await db.exec(`create role anon;create role authenticated;create role service_role bypassrls;
create schema auth;create table auth.users(id uuid primary key,email text,phone text,raw_user_meta_data jsonb default '{}'::jsonb,email_confirmed_at timestamptz);
create function auth.uid() returns uuid language sql stable as $$select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid$$;
grant usage on schema auth to anon,authenticated,service_role;
alter default privileges in schema public grant all on tables to anon,authenticated,service_role;`);
// Preserve the original audit's pre-reconciliation reproduction and catalog.
for(const n of fs.readdirSync('supabase/migrations').filter(n=>n.endsWith('.sql')&&n<'202610050001_android_contract_reconciliation.sql').sort()) await db.exec(read('supabase/migrations/'+n).replace('create extension if not exists pgcrypto;',''));
const query=async q=>(await db.query(q)).rows;
const val=async q=>(await query(q))[0].value;
const actor=async id=>db.exec(`reset role;set role authenticated;select set_config('request.jwt.claim.sub','${id}',false)`);
const admin=()=>db.exec("reset role;select set_config('request.jwt.claim.sub','',false)");
const attempt=async(label,q)=>{try{await db.exec(q);results.push({label,result:'success'});}catch(e){results.push({label,result:'rejected',code:e.code,message:e.message});}};
results.push({label:'Migration-only missing app RPCs',rows:await query("select name,to_regprocedure(signature)::text as installed from (values ('edit_loan_offer','public.edit_loan_offer(uuid,bigint,numeric,integer)'),('cancel_loan_offer','public.cancel_loan_offer(uuid)'),('accept_loan_offer_reviewed','public.accept_loan_offer_reviewed(uuid,bigint,numeric,integer)')) v(name,signature)")});
const deployment=[...read('supabase/AGREEMENT_SIGNUP_REPORT.md').matchAll(/```sql\r?\n([\s\S]*?)```/g)][0][1];
await db.exec(deployment);
results.push({label:'Historical report replay removes current profile API',value:await val("select to_regprocedure('public.complete_member_profile(text,text,text,text)') is null as value")});
await db.exec(read('supabase/migrations/202610040006_email_signup_profile_phone.sql'));
const ids=[];
for(let i=0;i<3;i++){const id=await val('select gen_random_uuid() as value');ids.push(id);await db.query('insert into auth.users(id,email,raw_user_meta_data) values($1,$2,$3)',[id,`audit${i}@example.test`,{full_name:`Audit ${i}`,username:`audit_${i}`,phone:`+25471234560${i}`}]);await actor(id);await db.exec(`select public.complete_member_profile('Audit ${i}','audit_${i}','audit${i}@example.test','+25471234560${i}')`);await admin();}
const [b,l,l2]=ids;
await db.exec(`insert into public.connections(requester_id,recipient_id,status) values('${b}','${l}','ACCEPTED'),('${b}','${l2}','ACCEPTED');update public.wallets set balance=10000;`);
await actor(b);const r=await val("select public.create_loan_request(1000,'audit','audit',30) as value");
await actor(l);const o=await val(`select public.create_loan_offer('${r}',500,7,30) as value`);
await actor(l2);const o2=await val(`select public.create_loan_offer('${r}',500,7,30) as value`);
await actor(b);
await attempt('Direct profile update used by ProfileRepository',`update public.profiles set bio='audit',full_name='Audit borrower' where id='${b}'`);
await attempt('First terms request',`select public.request_loan_offer_terms('${o}',6,60)`);
await attempt('Second terms request same offer pending',`select public.request_loan_offer_terms('${o}',5,90)`);
await attempt('Terms on a different eligible offer',`select public.request_loan_offer_terms('${o2}',6,60)`);
await actor(l);await attempt('Lender accepts period beyond original request preference',`select public.respond_loan_offer_terms('${o}',true)`);
await actor(b);await attempt('Terms after lender response',`select public.request_loan_offer_terms('${o}',5,90)`);
await admin();await db.exec(`update public.loan_requests set expires_at=now()-interval '1 day' where id='${r}'`);
await actor(l);await attempt('Terms response on expired parent before maintenance',`select public.respond_loan_offer_terms('${o}',false)`);
await actor(b);await attempt('Terms request on expired parent before maintenance',`select public.request_loan_offer_terms('${o}',4,90)`);
await admin();await db.exec(`update public.profiles set available_limit=100 where id='${b}'`);
await actor(b);const above=await val(`select public.create_loan_request(500,'audit','audit',30,'${l}',true) as value`);
await attempt('Edit above-limit request down to within limit',`select public.edit_loan_request('${above}',50,'audit','audit',30)`);
results.push({label:'Edited request disclosure',rows:await query(`select requested_amount,above_limit_requested from public.loan_requests where id='${above}'`)});
const payable=await val(`select public.create_loan_request(50,'audit','audit',365,'${l}',false) as value`);
await actor(l);const payableOffer=await val(`select public.create_loan_offer('${payable}',50,7,365,false) as value`);
await actor(b);const loan=await val(`select public.accept_loan_offer_reviewed('${payableOffer}',50,7,365) as value`);
await actor(l2);const reserveRequest=await val("select public.create_loan_request(10000,'audit','audit',30) as value");
await actor(b);await db.exec(`select public.create_loan_offer('${reserveRequest}',10000,0,30)`);
await attempt('Repayment when total wallet covers payment but available wallet does not',`select public.repay_loan_once('${loan}',54,gen_random_uuid())`);
await admin();results.push({label:'Live-local overloads',rows:await query("select proname,pg_get_function_arguments(p.oid) args from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname='public' and proname in ('complete_member_profile','request_loan_offer_terms')")});
await db.query(read('audit/hosted-database-diagnostic.sql'));
results.push({label:'Consolidated hosted metadata query executes locally',result:'success'});
fs.mkdirSync('audit',{recursive:true});fs.writeFileSync('audit/local-reproduction.json',JSON.stringify(results,null,2)+'\n');
const catalog={
 functions:await query("select p.proname,pg_get_function_arguments(p.oid) arguments,pg_get_function_result(p.oid) result,p.prosecdef security_definer,p.proconfig settings,p.proacl::text acl,pg_get_functiondef(p.oid) definition from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname='public' and p.prokind='f' order by p.proname,p.oid"),
 columns:await query("select table_name,column_name,data_type,is_nullable,column_default from information_schema.columns where table_schema='public' order by table_name,ordinal_position"),
 constraints:await query("select c.relname table_name,con.conname,pg_get_constraintdef(con.oid) definition from pg_constraint con join pg_class c on c.oid=con.conrelid join pg_namespace n on n.oid=c.relnamespace where n.nspname='public' order by c.relname,con.conname"),
 policies:await query("select * from pg_policies where schemaname='public'"),
 triggers:await query("select c.relname table_name,t.tgname,pg_get_triggerdef(t.oid) definition from pg_trigger t join pg_class c on c.oid=t.tgrelid join pg_namespace n on n.oid=c.relnamespace where n.nspname='public' and not t.tgisinternal")
};
fs.writeFileSync('audit/local-contract-catalog.json',JSON.stringify(catalog,null,2)+'\n');
const walk=dir=>fs.readdirSync(dir,{withFileTypes:true}).flatMap(e=>e.isDirectory() ? (['node_modules','build','.gradle'].includes(e.name)||e.name.startsWith('build-')?[]:walk(dir+'/'+e.name)):[dir+'/'+e.name]);
const sourceFiles=['app/src','supabase','tools'].flatMap(walk).filter(p=>/\.(kt|sql|md|mjs|cjs)$/.test(p)&&!p.endsWith('audit-contract.mjs'));
let inventory='# Source database-operation inventory\n\nGenerated from project sources; fixtures, historical SQL and rollback definitions are evidence, not deployment recommendations. See DATABASE_CONTRACT_AUDIT.md for callers, parameters, DTOs, downstream features and findings.\n\n## Production Kotlin operation sites\n\n';
for(const file of sourceFiles.filter(p=>p.startsWith('app/src/main')&&p.endsWith('.kt'))){
 const lines=read(file).split(/\r?\n/);const matches=lines.flatMap((line,i)=>/postgrest\.rpc|client\.from\(|operation\(|launchOperation\(|rows\("|auth\.(signUpWith|signInWith|signOut|resetPasswordForEmail|clearSession|awaitInitialization|currentSessionOrNull)/.test(line)?[`${file}:${i+1} — ${line.trim()}`]:[]);
 if(matches.length)inventory+='### '+file+'\n\n```text\n'+matches.join('\n')+'\n```\n\n';
}
inventory+='## Serializable/database mapping files\n\n';
for(const file of sourceFiles.filter(p=>p.endsWith('.kt'))){if(/@Serializable|@SerialName|object DatabaseRecords/.test(read(file)))inventory+='- '+file+'\n';}
inventory+='\n## SQL function definition history (including report rollback blocks)\n\n';
for(const file of sourceFiles.filter(p=>/\.(sql|md)$/.test(p))){const src=read(file);const matches=[...src.matchAll(/create\s+(?:or\s+replace\s+)?function\s+([\w.]+)\s*\(([\s\S]*?)\)\s*returns/gi)];for(const m of matches)inventory+=`- ${file}:${src.slice(0,m.index).split('\n').length} — ${m[1]}(${m[2].replace(/\s+/g,' ').trim()})\n`;}
inventory+='\n## SQL mutation sites (historical, operational and fixture files)\n\n';
for(const file of sourceFiles.filter(p=>/\.(sql|md)$/.test(p))){const lines=read(file).split(/\r?\n/);for(let i=0;i<lines.length;i++){const m=lines[i].match(/\b(insert\s+into|update|delete\s+from)\s+((?:public|auth|agreement_release_backup)\.\w+)/i);if(m)inventory+=`- ${file}:${i+1} — ${m[1].toUpperCase()} ${m[2]}\n`;}}
inventory+='\n## Reconstructed final required fields without column defaults\n\nThis is the agreement deployment followed by the final email migration. BEFORE INSERT triggers can provide a required value; see the report. Hosted extra columns are unknown.\n\n';
for(const table of [...new Set(catalog.columns.map(c=>c.table_name))]){const cols=catalog.columns.filter(c=>c.table_name===table&&c.is_nullable==='NO'&&c.column_default===null);inventory+='- '+table+': '+cols.map(c=>c.column_name+' ('+c.data_type+')').join(', ')+'\n';}
inventory+='\n## Final function signatures and return contracts\n\n';
for(const f of catalog.functions)inventory+='- '+f.proname+'('+f.arguments+') → '+f.result+'; SECURITY DEFINER='+f.security_definer+'; ACL='+f.acl+'\n';
fs.writeFileSync('audit/source-operation-inventory.md',inventory);
const finding=label=>results.find(r=>r.label===label);
assert(finding('Migration-only missing app RPCs').rows.every(r=>r.installed===null));
assert.equal(finding('Historical report replay removes current profile API').value,true);
assert.equal(finding('Second terms request same offer pending').code,'P0001');
assert.equal(finding('Terms on a different eligible offer').result,'success');
assert.equal(finding('Terms after lender response').result,'success');
assert.equal(finding('Lender accepts period beyond original request preference').result,'success');
assert.equal(finding('Terms request on expired parent before maintenance').result,'success');
assert.equal(finding('Terms response on expired parent before maintenance').result,'success');
assert.equal(finding('Edited request disclosure').rows[0].above_limit_requested,true);
assert.equal(finding('Edited request disclosure').rows[0].requested_amount,50);
assert.equal(finding('Repayment when total wallet covers payment but available wallet does not').code,'23514');
console.log(JSON.stringify(results,null,2));await db.close();
