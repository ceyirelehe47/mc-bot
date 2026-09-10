import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { setTimeout as sleep } from 'node:timers/promises';
import { BodyClient, BridgeError } from '../src/client.mjs';
import { install } from '../src/plugin.mjs';
const TOKEN='integration-test-token-not-a-real-secret-123456';
const root=fileURLToPath(new URL('../../',import.meta.url));
const classes=resolve(root,'.build');
async function waitUntil(fn,timeout=4000){const end=Date.now()+timeout;while(Date.now()<end){const v=await fn();if(v)return v;await sleep(20);}throw new Error('condition timeout');}
function scope(){const ds=[];return{effect:f=>ds.push(f()),async dispose(){for(const d of ds)await d?.();}};}

test('production Java HTTP + kernel + journal interoperates with real Node client (FAKE world)',{timeout:20000},async t=>{
  const dir=await mkdtemp(join(tmpdir(),'aibot-http-'));
  const child=spawn('java',['-cp',classes,'io.github.zoyluo.aibot.external.FakeBridgeServer',join(dir,'bridge.journal'),TOKEN],{stdio:['ignore','pipe','pipe']});
  let stderr='';child.stderr.on('data',d=>stderr+=d.toString());
  t.after(async()=>{
    // Windows 上 node 的 kill('SIGTERM') 只派发模拟 exit 事件,不会终止子进程;残留的 java 持有 stdio 管道导致测试进程无法退出
    if(process.platform==='win32'&&child.pid)spawn('taskkill',['/PID',String(child.pid),'/T','/F'],{stdio:'ignore'});
    else child.kill('SIGTERM');
    await Promise.race([new Promise(r=>child.once('exit',r)),sleep(2000)]);
    if(child.exitCode===null&&process.platform!=='win32')child.kill('SIGKILL');
    await rm(dir,{recursive:true,force:true});
  });
  const port=await new Promise((resolve,reject)=>{let output='';const timer=setTimeout(()=>reject(new Error('Java server did not start: '+stderr)),5000);
    child.stdout.on('data',d=>{output+=d.toString();const m=output.match(/READY (\d+)/);if(m){clearTimeout(timer);resolve(Number(m[1]));}});
    child.once('error',reject);child.once('exit',code=>{clearTimeout(timer);reject(new Error('Java exited '+code+': '+stderr));});});
  const baseUrl=`http://127.0.0.1:${port}`,headers={Authorization:`Bearer ${TOKEN}`};
  const c=new BodyClient({baseUrl,token:TOKEN,owner:'integration-s1'});
  t.after(()=>c.release().catch(()=>{}));

  await t.test('rejects missing auth, wrong auth and browser Origin without changing the body',async()=>{
    assert.equal((await fetch(baseUrl+'/v1/status')).status,401);
    assert.equal((await fetch(baseUrl+'/v1/status',{headers:{Authorization:'Bearer wrong'}})).status,401);
    assert.equal((await fetch(baseUrl+'/v1/status',{headers:{...headers,Origin:'https://example.org'}})).status,403);
    assert.equal((await c.status()).body_ready,true);
  });
  await t.test('rejects duplicate query parameters, non-JSON mutation and oversized body',async()=>{
    assert.equal((await fetch(baseUrl+'/v1/events?after=0&after=1',{headers})).status,400);
    assert.equal((await fetch(baseUrl+'/v1/executions/gather',{method:'POST',headers,body:'{}'})).status,415);
    assert.equal((await fetch(baseUrl+'/v1/executions/gather',{method:'POST',headers:{...headers,'Content-Type':'application/json'},body:'x'.repeat(17000)})).status,413);
  });
  const attached=await c.connect();
  await t.test('a second session cannot acquire the body; credentials are not returned by status',async()=>{
    const other=new BodyClient({baseUrl,token:TOKEN,owner:'integration-s2'});
    await assert.rejects(()=>other.connect(),e=>e instanceof BridgeError && e.status===409);
    const sameOwnerOtherProcess=new BodyClient({baseUrl,token:TOKEN,owner:'integration-s1'});
    await assert.rejects(()=>sameOwnerOtherProcess.connect(),e=>e instanceof BridgeError && e.status===409);
    assert.equal((await c.connect()).lease_epoch,attached.lease_epoch);
    const publicStatus=JSON.stringify(await c.status());assert.ok(!publicStatus.includes(TOKEN));assert.ok(!publicStatus.includes('token'));
  });
  let execution;
  await t.test('concurrent duplicate submissions run exactly one fake physical start',async()=>{
    await c.observe();const receipts=await Promise.all(Array.from({length:8},()=>c.execute('gather',{item:'minecraft:oak_log',count:4},'same-request')));
    const ids=new Set(receipts.map(x=>x.execution_id));assert.equal(ids.size,1);execution=receipts[0].execution_id;assert.ok(execution);
    // observation 缓存刷新落后派发 start 的 tick 一个周期,须等到缓存确认物理启动,否则在快回环(Windows)上断言会砸进 20ms 窗口
    await waitUntil(async()=>((await c.observe()).observation.physical_starts??0)>=1 && (await c.execution(execution)).state==='running');
    assert.equal((await c.observe()).observation.physical_starts,1);
  });
  await t.test('pause/resume control receipts settle on game thread and hold the same execution',async()=>{
    await c.control(execution,'pause','pause-request');await waitUntil(async()=>(await c.lookup('pause-request')).state==='applied');
    assert.equal((await c.execution(execution)).state,'paused');await sleep(100);assert.equal((await c.execution(execution)).state,'paused');
    await c.control(execution,'resume','resume-request');await waitUntil(async()=>(await c.lookup('resume-request')).state==='applied');
    await waitUntil(async()=>(await c.execution(execution)).state==='completed');
    assert.equal((await c.observe()).observation.physical_starts,1);
  });
  await t.test('journal returns terminal event, replay is non-consuming and duplicate completed call does not rerun',async()=>{
    const events=await c.events({epoch:attached.event_epoch,sequence:attached.event_sequence},undefined,0);
    assert.ok(events.events.some(e=>e.kind==='execution'&&e.state==='completed'&&e.execution_id===execution));
    const again=await c.events({epoch:attached.event_epoch,sequence:attached.event_sequence},undefined,0);
    assert.deepEqual(again.events,events.events);
    assert.equal((await c.execute('gather',{item:'minecraft:oak_log',count:4},'same-request')).execution_id,execution);
    assert.equal((await c.observe()).observation.physical_starts,1);
  });
  await c.release();
  await t.test('MC-2A0 cognitive queries: stable hash, fail-closed refs, bounded local, busy-safe',async()=>{
    // Re-acquire the body for the query-only phase (previous lease was released).
    await c.connect();
    const view1=await c.view(),view2=await c.view();
    assert.equal(view1.schema,'mc.cognitive_view.v0');
    assert.ok(view1.scene.world.world_id==='fake-world-1');
    assert.equal(view1.meta.scene_hash,view2.meta.scene_hash); // clock-only change keeps the hash
    assert.ok(view2.meta.generated_server_tick>=view1.meta.generated_server_tick);
    // forged / malformed / foreign / kind-mismatch refs all fail closed
    await assert.rejects(()=>c.inspect('not-a-ref'),e=>e instanceof BridgeError&&e.status===400);
    await assert.rejects(()=>c.inspect('mc://other-world/minecraft%3Aoverworld/structure/fake_home'),e=>e instanceof BridgeError&&e.status===404);
    await assert.rejects(()=>c.inspect('mc://fake-world-1/minecraft%3Aoverworld/farm/fake_home'),e=>e instanceof BridgeError&&e.status===404);
    const summary=await c.inspect('mc://fake-world-1/minecraft%3Aoverworld/structure/fake_home');
    assert.equal(summary.detail,'summary');assert.equal(summary.evidence.object_id,'fake_home');
    const baseline=await c.inspect('mc://fake-world-1/minecraft%3Aoverworld/structure/fake_home','baseline');
    assert.equal(baseline.evidence.baseline_cells,9);
    await assert.rejects(()=>c.inspect('mc://fake-world-1/minecraft%3Aoverworld/structure/fake_home','cells'),e=>e instanceof BridgeError&&e.status===400);
    // local: radius out of range is rejected before queueing; valid radius runs on the tick thread
    await assert.rejects(()=>c.inspectLocal(17),e=>e instanceof BridgeError&&e.status===400);
    const local=await c.inspectLocal(16,'blocks');
    assert.equal(local.snapshot.radius_effective,8);assert.equal(local.snapshot.detail,'blocks');
    // busy-safe: a paused ordinary execution keeps its identity across all three queries
    const busy=await c.execute('gather',{item:'minecraft:oak_log',count:4},'busy-query');
    await c.control(busy.execution_id,'pause','busy-pause');
    await waitUntil(async()=>(await c.execution(busy.execution_id)).state==='paused');
    await c.view();
    await c.inspect('mc://fake-world-1/minecraft%3Aoverworld/structure/fake_home');
    await c.inspectLocal(4,'summary');
    const still=(await c.execution(busy.execution_id));
    assert.equal(still.state,'paused');assert.equal(still.execution_id,busy.execution_id);
    const status=await c.status();
    assert.equal(status.active_execution.execution_id,busy.execution_id); // query did not replace/steal the owner
    await c.control(busy.execution_id,'resume','busy-resume');
    await waitUntil(async()=>(await c.execution(busy.execution_id)).state==='completed');
    await c.release();
  });
  await t.test('actual plugin transport worker enqueues a terminal wake into its fake DSH owner',async()=>{
    const ctx=scope(),agentScope=scope(),tools=new Map(),messages=[];let flushes=0;ctx.sessionPersistence={async stat(){return {};},async flush(){flushes++;}};ctx.tools={register:tool=>tools.set(tool.name,tool)};
    const agent={id:'integration-dsh',ctx:agentScope,status:'idle',followup:m=>messages.push(['followup',m]),steer:m=>messages.push(['steer',m]),inject:m=>messages.push(['inject',m])};
    const impl=install(ctx,{defineTool:t=>t,createUserMessage:m=>m},{baseUrl,token:TOKEN,stateDir:join(dir,'dsh-state'),renewIntervalMs:100,log:()=>{}});
    const call=(name,args={},callId=name)=>tools.get(name).execute(args,{agent,callId,concludeTurn(){},signal:new AbortController().signal});
    try{
      await call('mc_connect');await call('mc_observe');const r=await call('mc_gather',{item:'minecraft:oak_log',count:4});assert.equal(r.state,'accepted');
      await waitUntil(()=>messages.find(([,m])=>m.content[0].text.includes(r.execution_id)&&m.content[0].text.includes('completed')));
      assert.ok(flushes>=1);assert.equal(messages.at(-1)[0],'followup');assert.equal(messages.at(-1)[1].source.kind,'plugin');
      await agentScope.dispose();await waitUntil(async()=>(await c.status()).control_active===false);
    }finally{await impl.dispose();}
  });
  assert.equal(stderr,'');
});
