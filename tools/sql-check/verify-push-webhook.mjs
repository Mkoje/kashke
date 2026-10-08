import fs from 'node:fs';
import vm from 'node:vm';
import { stripTypeScriptTypes } from 'node:module';
import assert from 'node:assert/strict';
const source = fs.readFileSync(new URL('../../supabase/functions/send-push/index.ts', import.meta.url), 'utf8');
let handler;
const requests = [];
const env = { PUSH_WEBHOOK_SECRET: 'test-only-webhook-secret', SUPABASE_URL: 'https://example.test',
  SUPABASE_SERVICE_ROLE_KEY: 'test-only-server-key' };
vm.runInNewContext(stripTypeScriptTypes(source), {
  Deno: { env: { get: name => env[name] }, serve: fn => { handler = fn; } },
  console, Response, URLSearchParams, TextEncoder, Uint8Array, crypto, btoa, atob,
  fetch: async url => {
    requests.push(url);
    if (url.includes('/notifications?')) return Response.json([{ id: '11111111-1111-1111-1111-111111111111',
      user_id: 'canonical-user', read: false }]);
    if (url.includes('/push_devices?')) return Response.json([]);
    throw new Error('Unexpected outgoing request');
  },
});
assert.equal((await handler(new Request('https://example.test', { method: 'POST' }))).status, 401);
assert.equal(requests.length, 0);
assert.equal((await handler(new Request('https://example.test', { headers: { 'x-push-secret': env.PUSH_WEBHOOK_SECRET } }))).status, 405);
const invoke = payload => handler(new Request('https://example.test', { method: 'POST',
  headers: { 'x-push-secret': env.PUSH_WEBHOOK_SECRET, 'Content-Type': 'application/json' },
  body: JSON.stringify(payload) }));
assert.equal((await invoke({ type: 'UPDATE', table: 'notifications', schema: 'public' })).status, 400);
assert.equal((await invoke({ type: 'INSERT', table: 'notifications', schema: 'public', record: { id: 'bad' } })).status, 400);
const result = await invoke({ type: 'INSERT', table: 'notifications', schema: 'public',
  record: { id: '11111111-1111-1111-1111-111111111111', user_id: 'spoofed-recipient' } });
assert.equal(result.status, 200);
assert.equal(await result.text(), 'No registered devices');
assert.equal(requests.length, 2);
assert.ok(requests[1].includes('user_id=eq.canonical-user'));
assert.ok(!requests[1].includes('spoofed-recipient'));
console.log('PASS: TypeScript parses; webhook rejects unauthenticated/invalid calls and resolves recipients from canonical database records');
