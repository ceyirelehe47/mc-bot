import test from 'node:test';
import assert from 'node:assert/strict';
import { BodyClient, BridgeError, validateBaseUrl, requestKey, stableJson } from '../src/client.mjs';
const TOKEN = 'test-token-'.repeat(5);
const lease = {token:'private-control-token', owner:'session',lease_epoch:'l1',expires_in_ms:30000,event_epoch:'e1',event_sequence:4};
const ok = data => new Response(JSON.stringify({ok:true,data}), {headers:{'Content-Type':'application/json'}});
const bad = (status,error) => new Response(JSON.stringify({ok:false,error}),{status});
function make(fetchImpl){return new BodyClient({baseUrl:'http://127.0.0.1:8765',token:TOKEN,owner:'session',fetchImpl});}

test('restricts bridge to explicit IPv4 loopback with no credentials or path',()=>{
  assert.equal(validateBaseUrl('http://127.0.0.1:8765'),'http://127.0.0.1:8765');
  for(const url of ['http://example.com:8765','http://localhost:8765','https://127.0.0.1:8765','http://user@127.0.0.1:8765','http://127.0.0.1:8765/path','http://127.0.0.1:8765/?x=1']) assert.throws(()=>validateBaseUrl(url));
});
test('stable request identity scopes same tool-call id to one session',()=>{
  assert.equal(requestKey('s1','c1'),requestKey('s1','c1'));
  assert.notEqual(requestKey('s1','c1'),requestKey('s2','c1'));
  assert.throws(()=>requestKey('','c1'));
});
test('canonical argument encoding and rejects lossy JSON',()=>{
  assert.equal(stableJson({z:1,a:{b:'中文',a:true}}),'{'+'"a":{"a":true,"b":"中文"},"z":1}');
  for(const value of [NaN,Infinity,undefined,BigInt(1),new Date(),{x:undefined}])assert.throws(()=>stableJson(value));
  const cycle={};cycle.x=cycle;assert.throws(()=>stableJson(cycle));
});
test('lease token stays out of model-facing connect return and enumerable client state',async()=>{
  const c=make(async(url,opts)=>{assert.equal(opts.redirect,'error');assert.equal(opts.headers.Authorization,`Bearer ${TOKEN}`);return ok(lease);});
  const result=await c.connect();assert.ok(c.connected);
  assert.equal(result.token,undefined);assert.ok(!JSON.stringify(c).includes(TOKEN));assert.ok(!JSON.stringify(result).includes(lease.token));
});
test('lost acceptance is unknown, no blind retry, query unlocks next action',async()=>{
  let calls=0, posts=0;
  const c=make(async(url,opts)=>{calls++;
    if(url.endsWith('/lease'))return ok(lease);
    if(url.includes('/requests/'))return ok({request_id:'r1',execution_id:'x1',state:'running'});
    posts++;if(posts===1)throw new TypeError('socket closed');return ok({state:'accepted'});
  });
  await c.connect();const r=await c.execute('gather',{count:4},'r1');assert.equal(r.state,'outcome_unknown');assert.equal(posts,1);
  await assert.rejects(()=>c.execute('gather',{count:4},'r2'),/Resolve request/);assert.equal(posts,1);
  await c.lookup('r1');assert.equal(c.uncertainRequest,null);await c.execute('gather',{count:4},'r2');assert.equal(posts,2);
});
test('aborted call before admission does not perform mutation',async()=>{
  let mutations=0;const c=make(async(url)=>{if(url.endsWith('/lease'))return ok(lease);mutations++;return ok({});});
  await c.connect();const a=new AbortController();a.abort(new Error('cancelled before submit'));
  await assert.rejects(()=>c.execute('eat',{},'r',a.signal),/cancelled before submit/);assert.equal(mutations,0);
});
test('explicit 4xx rejection is not represented as success or unknown',async()=>{
  const c=make(async(url)=>url.endsWith('/lease')?ok(lease):bad(409,'execution_in_progress'));
  await c.connect();await assert.rejects(()=>c.execute('eat',{},'r'),e=>e instanceof BridgeError && e.code==='execution_in_progress');assert.equal(c.uncertainRequest,null);
});
test('5xx mutation remains unknown and lease rejection clears local ownership',async()=>{
  const c=make(async(url)=>url.endsWith('/lease')?ok(lease):url.endsWith('/renew')?bad(409,'control_lease_invalid'):bad(503,'server_stopping'));
  await c.connect();assert.equal((await c.execute('eat',{},'r')).state,'outcome_unknown');
  await assert.rejects(()=>c.renew(),BridgeError);assert.equal(c.connected,false);
});
test('mutations carry actual lease and stable JSON, release drops lease even on network failure',async()=>{
  const c=make(async(url,opts)=>{
    if(opts.method==='DELETE')throw new TypeError('lost release');
    if(url.endsWith('/lease'))return ok(lease);
    assert.equal(opts.headers['X-Control-Token'],lease.token);assert.equal(opts.headers['X-Request-Id'],'r');assert.equal(opts.body,'{"a":2,"z":1}');return ok({state:'accepted'});
  });await c.connect();await c.execute('gather',{z:1,a:2},'r');await assert.rejects(()=>c.release());assert.equal(c.connected,false);
});
test('oversized or malformed successful transport response is not trusted',async()=>{
  const c=make(async()=>new Response('x',{headers:{'content-length':'99999999'}}));await assert.rejects(()=>c.status(),/too large/);
  const d=make(async()=>new Response('{'));await assert.rejects(()=>d.status());
});
