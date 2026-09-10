import test from 'node:test';
import assert from 'node:assert/strict';
import { install } from '../src/plugin.mjs';
import { requestKey } from '../src/client.mjs';
const api={defineTool:x=>x,createUserMessage:x=>x};
function scope(){const disposers=[];return {disposers,effect(fn){disposers.push(fn());},async dispose(){await Promise.all(disposers.map(f=>f?.()));}};}
function setup(){
  const ctx=scope(),tools=new Map();ctx.sessionPersistence={async stat(){return {};},async flush(){}};ctx.tools={register(t){tools.set(t.name,t);}};
  let releases=0,connects=0,concludes=0;const submissions=[];
  const client={connected:false,async connect(){connects++;this.connected=true;return {event_epoch:'e',event_sequence:0};},
    async release(){releases++;this.connected=false;return {released:true};},async status(){return {body_ready:true};},async observe(){return {health:20};},
    async view(){return {schema:'mc.cognitive_view.v0',meta:{scene_hash:'sha256:x'},scene:{world:{}}};},
    async inspect(ref,detail){return {schema:'mc.evidence.v0',ref,detail:detail??'summary'};},
    async inspectLocal(radius,detail){return {schema:'mc.local_view.v0',radius_effective:Math.min(radius,8),detail:detail??'summary'};},
    async renew(){return{};},async execute(...args){submissions.push(args);return{state:'accepted',execution_id:'x'};},async control(...args){submissions.push(args);return{state:'accepted'};},
    events(_cursor,signal){return new Promise((_,reject)=>{if(signal.aborted)reject(signal.reason);else signal.addEventListener('abort',()=>reject(signal.reason),{once:true});});}};
  const store={value:null,async load(){return this.value;},async save(value){this.value={...value};}};
  const impl=install(ctx,api,{makeClient:()=>client,makeStore:()=>store,renewIntervalMs:100000,log:()=>{}});
  function makeAgent(id){const calls=[];return{id,ctx:scope(),status:'idle',calls,followup:m=>calls.push(m),steer:m=>calls.push(m),inject:m=>calls.push(m)};}
  const a=makeAgent('s1'),b=makeAgent('s2');
  const call=(name,args={},owner=a,callId='call1')=>tools.get(name).execute(args,{agent:owner,callId,concludeTurn(){concludes++;},signal:new AbortController().signal});
  return{ctx,tools,impl,a,b,call,client,store,submissions,get releases(){return releases;},get connects(){return connects;},get concludes(){return concludes;}};
}
test('native tools return canonical output values and expose bounded operations only',async()=>{
  const f=setup();try{
    assert.equal(f.tools.size,25);for(const t of f.tools.values()){assert.equal(t.output.schema.type,'json');assert.equal(typeof t.execute,'function');}
    assert.ok(!f.tools.has('mc_shell'));assert.ok(!f.tools.has('mc_achieve_goal'));
    assert.ok(f.tools.has('mc_register_home'));assert.ok(f.tools.has('mc_capture_home'));assert.ok(f.tools.has('mc_repair_home'));assert.ok(f.tools.has('mc_register_farm'));assert.ok(f.tools.has('mc_tend_farm'));assert.ok(f.tools.has('mc_mine_opportunity'));
    assert.ok(f.tools.has('mc_view'));assert.ok(f.tools.has('mc_inspect'));assert.ok(f.tools.has('mc_inspect_local'));
    await assert.rejects(()=>f.call('mc_observe'),/not attached/);
    await f.call('mc_connect');assert.equal((await f.call('mc_observe')).health,20);
    assert.equal((await f.call('mc_gather',{item:'minecraft:oak_log',count:4})).state,'accepted');
    assert.equal(f.submissions[0][2],requestKey('s1','call1'));assert.equal(f.concludes,1);
  }finally{await f.impl.dispose();}
});
test('cognitive query tools are read-only: no turn conclusion, no execution slot, no lease mutation',async()=>{
  const f=setup();try{
    await assert.rejects(()=>f.call('mc_view'),/not attached/); // still requires an attached session
    await f.call('mc_connect');
    const view=await f.call('mc_view');
    assert.equal(view.schema,'mc.cognitive_view.v0');
    const inspected=await f.call('mc_inspect',{ref:'mc://w/minecraft%3Aoverworld/structure/r2home',detail:'baseline'});
    assert.equal(inspected.detail,'baseline');
    const local=await f.call('mc_inspect_local',{radius:16,detail:'blocks'});
    assert.equal(local.radius_effective,8); // clamped by perception policy, not the request
    await f.call('mc_inspect_local',{}); // defaults: radius 4, detail summary
    assert.equal(f.submissions.length,0);  // queries never occupy the mutation execution slot
    assert.equal(f.concludes,0);           // queries never conclude the DSH turn
    assert.equal(f.releases,0);
  }finally{await f.impl.dispose();}
});
test('one DSH session owns the body and other sessions cannot submit or detach it',async()=>{
  const f=setup();try{
    await f.call('mc_connect');await assert.rejects(()=>f.call('mc_connect',{},f.b),/Another DSH session/);
    await assert.rejects(()=>f.call('mc_gather',{item:'minecraft:oak_log',count:1},f.b),/not attached/);
    await assert.rejects(()=>f.call('mc_release',{},f.b),/not attached/);assert.equal(f.releases,0);
    await f.a.ctx.dispose();assert.equal(f.releases,1);await f.call('mc_connect',{},f.b);assert.equal(f.connects,2);
  }finally{await f.impl.dispose();}
});
test('concurrent attach calls cannot give ownership to two sessions',async()=>{
  const f=setup();try{
    const attaching=f.call('mc_connect');await assert.rejects(()=>f.call('mc_connect',{},f.b),/attaching|attached/);
    await attaching;assert.equal(f.connects,1);
  }finally{await f.impl.dispose();}
});
test('plugin disposal releases once and prevents new calls',async()=>{
  const f=setup();await f.call('mc_connect');await f.impl.dispose();await f.impl.dispose();await f.a.ctx.dispose();assert.equal(f.releases,1);
  await assert.rejects(()=>f.call('mc_observe'),/disposed/);
});
test('malformed cursor prevents acquiring a lease',async()=>{
  const ctx=scope();ctx.sessionPersistence={async stat(){return {};},async flush(){}};const tools=new Map();ctx.tools={register:t=>tools.set(t.name,t)};let connects=0;
  const impl=install(ctx,api,{makeClient:()=>({async connect(){connects++;}}),makeStore:()=>({async load(){throw new Error('bad cursor');}})});
  try{await assert.rejects(()=>tools.get('mc_connect').execute({}, {agent:{id:'s',ctx:scope()},callId:'c',signal:new AbortController().signal}),/bad cursor/);assert.equal(connects,0);}finally{await impl.dispose();}
});

test('an ephemeral host without a persistence barrier is rejected',()=>{
  const ctx=scope();ctx.tools={register(){}};
  assert.throws(()=>install(ctx,api),/persistent DSH Session backend/);
});

test('DSH flush failure keeps the incoming event cursor before the uncommitted message',async()=>{
  const f=setup();let enter;const entered=new Promise(resolve=>enter=resolve);let first=true;
  f.ctx.sessionPersistence.flush=async()=>{enter();throw new Error('DSH persistence unavailable');};
  const blocked=f.client.events;
  f.client.events=async(cursor,signal)=>{if(first){first=false;return{epoch:'e',last_sequence:1,gap:false,events:[{sequence:1,kind:'execution',state:'completed',payload:'{"operation":"gather"}'}]};}return blocked(cursor,signal);};
  try{await f.call('mc_connect');await entered;await new Promise(r=>setTimeout(r,5));assert.equal(f.store.value.sequence,0);assert.equal(f.a.calls.length,1);}
  finally{await f.impl.dispose();}
});
