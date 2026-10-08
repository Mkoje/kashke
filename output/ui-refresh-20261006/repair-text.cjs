const fs=require('fs'),path=require('path');
const decoder=new TextDecoder('windows-1252'); const inverse=new Map();
for(let i=0;i<256;i++)inverse.set(decoder.decode(Uint8Array.of(i)),i);
let changed=0; const report=[];
function repair(s){ for(let pass=0;pass<4;pass++){const next=s.replace(/[^\x00-\x7f]+/g,chunk=>{if(!/[\u00c2\u00c3\u00e2\u00f0]/.test(chunk))return chunk;const chars=[...chunk];if(chars.some(c=>!inverse.has(c)))return chunk;const bytes=Buffer.from(chars.map(c=>inverse.get(c)));const text=bytes.toString('utf8');return text.includes('\ufffd')?chunk:text;});if(next===s)break;s=next;}return s;}
function walk(dir){for(const item of fs.readdirSync(dir,{withFileTypes:true})){const p=path.join(dir,item.name);if(item.isDirectory())walk(p);else if(/\.(kt|java|xml|ts|sql|md)$/.test(p)){const old=fs.readFileSync(p,'utf8'),next=repair(old);if(next!==old){const backup=path.join('output/ui-refresh-20261006/source-backup',p);fs.mkdirSync(path.dirname(backup),{recursive:true});fs.copyFileSync(p,backup);fs.writeFileSync(p,next);report.push(p);changed++;}}}}
for(const dir of ['app/src','supabase/functions'])walk(dir);
fs.writeFileSync('output/ui-refresh-20261006/text-repair-files.json',JSON.stringify(report,null,2)); console.log('Repaired files:',report.join(', '));
