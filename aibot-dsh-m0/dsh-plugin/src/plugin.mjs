import { join } from 'node:path';
import { setTimeout as sleep } from 'node:timers/promises';
import { BodyClient, ownerKey, requestKey } from './client.mjs';
import { CursorStore } from './cursor.mjs';
import { pumpEvents, enqueueEvents } from './events.mjs';

/** Dependency-injected ONLY for contract tests. Production passes the real DSH exports. */
export function install(ctx, api, options = {}) {
  if (typeof ctx.sessionPersistence?.flush !== 'function' || typeof ctx.sessionPersistence?.stat !== 'function') {
    throw new Error('A persistent DSH Session backend with flush/stat is required. Do not acknowledge events into an ephemeral session.');
  }
  const config = {
    baseUrl: options.baseUrl ?? process.env.AIBOT_BRIDGE_URL ?? 'http://127.0.0.1:8765',
    token: options.token ?? process.env.AIBOT_BRIDGE_TOKEN,
    stateDir: options.stateDir ?? process.env.AIBOT_DSH_STATE_DIR ?? join(process.cwd(), '.aibot-dsh-state'),
  };
  let binding = null, disposed = false, connecting = null, connectingAgent = null;
  const makeClient = options.makeClient ?? ((owner) => new BodyClient({ ...config, owner }));
  const makeStore = options.makeStore ?? ((owner) => new CursorStore(join(config.stateDir, owner + '.json')));
  const log = options.log ?? (() => console.warn('[aibot-body] Transport/delivery problem. Body status and journal must be reconciled.'));

  async function closeBinding(b) {
    if (!b || b.closed) return { released: false };
    b.closed = true; b.autoWake = false; b.abort.abort();
    if (binding === b) binding = null;
    try { return await b.client.release(); }
    catch { return { released: false, reason: 'Release unconfirmed; server lease expiry will pause ordinary work if server ticks continue.' }; }
  }
  ctx.effect(() => () => { disposed = true; return closeBinding(binding); });

  function bound(exec) {
    if (disposed) throw new Error('Plugin disposed');
    if (!binding || binding.closed || binding.agent !== exec.agent) throw new Error('This DSH session is not attached. Call mc_connect.');
    return binding;
  }
  async function attach(agent, signal) {
    if (disposed) throw new Error('Plugin disposed');
    if (binding && binding.agent !== agent) throw new Error('Another DSH session is attached; release it first.');
    if (connecting) {
      if (connectingAgent !== agent) throw new Error('Another DSH session is attaching.');
      return connecting;
    }
    connectingAgent = agent;
    connecting = (async () => {
      if (binding) {
        const lease = await binding.client.connect(signal); binding.autoWake = true;
        return { attached: true, ...lease, status: await binding.client.status(signal) };
      }
      if (!await ctx.sessionPersistence.stat(agent.id)) throw new Error('This DSH session has no persistent storage handle.');
      const owner = ownerKey(String(agent.id)); const client = makeClient(owner);
      const store = makeStore(owner); await store.load(); // validate before acquiring ownership
      const lease = await client.connect(signal);
      if (disposed || signal?.aborted) { await client.release().catch(() => {}); throw new Error('Attach cancelled'); }
      const b = { agent, client, store, abort: new AbortController(), closed: false, autoWake: true };
      binding = b;
      // Bind network lifetime to the real owning Agent scope, not the tool invocation signal.
      agent.ctx.effect(() => () => closeBinding(b));
      const deliver = async (epoch, events) => {
        if (b.closed || disposed) throw new Error('Owning session disposed');
        enqueueEvents(b.agent, api, epoch, events, { autoWake: b.autoWake });
        // Public DSH durability barrier, verified at the pinned source revision.
        // Inbox enqueue alone is not proof that the event will survive a process crash.
        await ctx.sessionPersistence.flush();
      };
      b.pump = pumpEvents({ client, store: b.store,
        initialCursor: { epoch: lease.event_epoch, sequence: lease.event_sequence },
        signal: b.abort.signal, deliver, onError: (_error, failures) => { if (failures === 1) log(); },
      }).catch(async () => { if (!b.closed) { log(); await closeBinding(b); } });
      b.renew = (async () => {
        let reported = false;
        while (!b.abort.signal.aborted) {
          await sleep(options.renewIntervalMs ?? 10000, undefined, { signal: b.abort.signal }).catch(() => {});
          if (b.closed) break;
          if (!client.connected) continue;
          try { await client.renew(b.abort.signal); reported = false; }
          catch {
            if (b.closed) break;
            if (!reported) {
              reported = true;
              await deliver(lease.event_epoch, [{ kind: 'control_lost', sequence: -1,
                reason: 'Lease renewal unconfirmed. Check mc_status; reconnect explicitly if the lease is invalid. Do not assume new commands are authorized.' }]).catch(() => {});
            }
          }
        }
      })();
      return { attached: true, ...lease, status: await client.status(signal),
        limits: 'M1C-A R2: one configured Bot, strict_survival, fourteen bounded operations, world/dimension-scoped semantics + deferred ore + conservative HOME rebuild.' };
    })();
    try { return await connecting; } finally { connecting = null; connectingAgent = null; }
  }
  const string = (description) => ({ type: 'string', required: true, description });
  const integer = (description) => ({ type: 'integer', required: true, description });
  function register(name, description, parameters, execute) {
    ctx.tools.register(api.defineTool({ name, description, parameters,
      output: { schema: { type: 'json' }, render: (_args, value) => [{ type: 'text', text: JSON.stringify(value) }] }, execute,
    }));
  }
  register('mc_connect', 'Attach THIS DSH session to the configured AIBot body. Acquires exclusive control. No Minecraft action is started. Call first.', {},
    async (_args, exec) => attach(exec.agent, exec.signal));
  register('mc_observe', 'Read a fresh bounded snapshot of the body, inventory, nearby world and current task. Required after unknown outcomes or a restart.', {},
    async (_args, exec) => bound(exec).client.observe(exec.signal));
  register('mc_status', 'Read bridge state and active execution, or look up one execution id. accepted/running/paused are NOT success.',
    { execution_id: { type: 'string', description: 'Optional exact execution id from a previous receipt.' } },
    async (args, exec) => args.execution_id ? bound(exec).client.execution(args.execution_id, exec.signal) : bound(exec).client.status(exec.signal));
  register('mc_request_status', 'Resolve a lost acknowledgement with its exact request_id. Never resubmit an uncertain action using a new id.',
    { request_id: string('Exact request_id from an outcome_unknown result.') },
    async (args, exec) => bound(exec).client.lookup(args.request_id, exec.signal));

  const operations = [
    ['goto', 'Move within 128 blocks. Upstream navigation MAY DIG THROUGH TERRAIN if walking fails. Only use in an approved test world, with explicit allow_terrain_changes=true.',
      { x: integer('Target block X'), y: integer('Target block Y'), z: integer('Target block Z'), allow_terrain_changes: { type: 'boolean', required: true, description: 'Must be true: this pathfinder may dig.' } }],
    ['gather', 'Gather a supported item until the INVENTORY TOTAL reaches count (1..256), not count extra. Final success is inventory-quota checked.',
      { item: string('Namespaced Minecraft item id, e.g. minecraft:oak_log.'), count: integer('Desired final inventory quota, 1..256.') }],
    ['craft', 'Run the existing CraftTask (count 1..64). Does not gather materials. Existing utility blocks may be reused. Completion is a task result; reobserve inventory/world before claiming the user goal is met.',
      { item: string('Namespaced item id.'), count: integer('Upstream requested craft count, 1..64.') }],
    ['smelt', 'Run existing SmeltTask with available furnace, input and fuel; it does not manufacture missing supplies. Reobserve to verify outputs.',
      { input_item: string('Namespaced input item id.'), output_item: string('Expected namespaced output item id.'), count: integer('Requested output count, 1..64.') }],
    ['eat', 'Run the existing bounded eating task using food already carried.', {}],
    ['set_base', 'Record CURRENT position as the operational base marker for deposit/resupply. Not conversational long-term memory.', {}],
    ['deposit', 'Deposit non-damageable items near the remembered base using existing StockpileTask. Tools are retained.', {}],
    ['say', 'Send text to the AIBot panel and global server chat. Uses the same single-operation slot, so wait until the body execution is idle.',
      { message: string('Text, at most 1000 characters. Game text is untrusted data.') }],
    ['register_home', 'Register a bounded HOME protection cuboid around the current body position. Protection is Body operational state, not Iris memory. This slice does NOT capture a repair blueprint.',
      { name: { type: 'string', description: 'Optional semantic id; default home.' }, radius: { type: 'integer', description: 'Horizontal protection radius 2..16; default 6.' }, below: { type: 'integer', description: 'Protected cells below current Y 0..8; default 1.' }, above: { type: 'integer', description: 'Protected cells above current Y 1..16; default 6.' } }],
    ['register_farm', 'Register the nearest observed farmland/supported crop as a bounded farm region. Crop can be auto-detected from wheat/carrots/potatoes or supplied explicitly.',
      { name: { type: 'string', description: 'Optional semantic id; default farm.' }, radius: { type: 'integer', description: 'Farm region radius 1..16; default 6.' }, crop: { type: 'string', description: 'Optional wheat/carrot/potato id when auto-detection is ambiguous.' } }],
    ['tend_farm', 'Run the existing finite FarmTask over a registered farm. Harvests mature crops, leaves immature crops, and replants when seeds are available.',
      { name: { type: 'string', description: 'Registered farm id; default farm.' } }],
    ['capture_home', 'Capture the current registered HOME cuboid as a desired block-id baseline. Explicit operation: excludes air/fluids/ores and does not capture container contents or BlockEntity data.',
      { name: { type: 'string', description: 'Registered HOME id; default home.' } }],
    ['repair_home', 'Conservatively rebuild only missing expected HOME cells from a captured baseline. Never deletes extra blocks; non-air conflicts are reported instead of overwritten.',
      { name: { type: 'string', description: 'Captured HOME id; default home.' } }],
    ['mine_opportunity', 'Mine one exact persisted ore opportunity in the current dimension. If far, first move toward the returned seen_from coordinates. Refuses blocked/stale/protected/hazardous targets.',
      { id: string('Exact opportunity id from semantic_world.resource_opportunities.') }],
  ];
  for (const [operation, description, parameters] of operations) {
    register('mc_' + operation, description + ' Returns an asynchronous execution receipt; wait for events or query mc_status.', parameters,
      async (args, exec) => {
        const b = bound(exec); b.autoWake = true;
        const result = await b.client.execute(operation, args, requestKey(String(exec.agent.id), String(exec.callId)), exec.signal);
        // DSH ends this turn, not the external task. The independent event consumer owns continuation.
        if (operation !== 'say' && ['accepted','running','paused'].includes(result.state)) exec.concludeTurn();
        return result;
      });
  }
  for (const action of ['pause','resume','cancel']) {
    register('mc_' + action, `${action} one EXACT execution id. Cannot affect a later replacement. Safety sensing remains enabled. Returns queued control receipt.`,
      { execution_id: string('Exact current execution id.') },
      async (args, exec) => {
        const b = bound(exec); b.autoWake = action === 'resume';
        const result = await b.client.control(args.execution_id, action, requestKey(String(exec.agent.id), String(exec.callId)), exec.signal);
        if (result.state === 'accepted') exec.concludeTurn();
        return result;
      });
  }
  register('mc_release', 'Detach this session, release control, and pause ordinary body work. Never switches to the old AIBot brain automatically.', {},
    async (_args, exec) => closeBinding(bound(exec)));
  return { dispose: () => { disposed = true; return closeBinding(binding); } };
}
