import { PGlite } from '@electric-sql/pglite';
import fs from 'node:fs';
const read=p=>fs.readFileSync(p,'utf8');
const snapshot=JSON.parse(read('audit/hosted-contract-snapshot.json'))[0].database_contract_audit;
const migration='202610050001_android_contract_reconciliation.sql';
const quote=s=>'"'+s.replaceAll('"','""')+'"';
export async function init(mode){
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
