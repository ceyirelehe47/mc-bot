#!/usr/bin/env node
/** Read-only diagnostic client (observe also acknowledges a need-to-reconcile gate). */
import { BodyClient } from '../dsh-plugin/src/client.mjs';
const c=new BodyClient({baseUrl:process.env.AIBOT_BRIDGE_URL ?? 'http://127.0.0.1:8765',token:process.env.AIBOT_BRIDGE_TOKEN,owner:'diagnostic-read-only'});
const [operation='status',id]=process.argv.slice(2);
try {
  let value;
  if(operation==='status')value=await c.status();
  else if(operation==='observe')value=await c.observe();
  else if(operation==='execution' && id)value=await c.execution(id);
  else if(operation==='request' && id)value=await c.lookup(id);
  else throw new Error('Usage: node inspect_bridge.mjs [status|observe|execution ID|request ID]');
  console.log(JSON.stringify(value,null,2));
} catch(e){console.error(e.message);process.exitCode=1;}
