import { createHash } from 'node:crypto';

export class BridgeError extends Error {
  constructor(status, code) { super(code); this.name = 'BridgeError'; this.status = status; this.code = code; }
}
export function validateBaseUrl(value) {
  const url = new URL(value);
  if (url.protocol !== 'http:' || url.hostname !== '127.0.0.1' || !url.port ||
      url.username || url.password || url.search || url.hash || url.pathname !== '/') {
    throw new Error('Bridge URL must be http://127.0.0.1:PORT (use an SSH tunnel for remote servers)');
  }
  return url.origin;
}
export function requestKey(sessionId, callId) {
  if (typeof sessionId !== 'string' || !sessionId || typeof callId !== 'string' || !callId) throw new Error('DSH execution identity required');
  return 'dsh-' + createHash('sha256').update(sessionId + '\0' + callId).digest('hex');
}
export function ownerKey(sessionId) { return 'dsh-session-' + createHash('sha256').update(sessionId).digest('hex'); }

/** Stable JSON bytes are required when retrying the SAME request id. Reject lossful JSON. */
export function stableJson(value, depth = 0) {
  if (depth > 16) throw new Error('Arguments too deeply nested');
  if (value === null || typeof value === 'boolean' || typeof value === 'string') return JSON.stringify(value);
  if (typeof value === 'number') { if (!Number.isFinite(value)) throw new Error('Non-finite number'); return JSON.stringify(value); }
  if (Array.isArray(value)) return '[' + value.map(v => stableJson(v, depth + 1)).join(',') + ']';
  if (typeof value === 'object' && [Object.prototype, null].includes(Object.getPrototypeOf(value))) {
    return '{' + Object.keys(value).sort().map(k => JSON.stringify(k) + ':' + stableJson(value[k], depth + 1)).join(',') + '}';
  }
  throw new Error('Arguments must be lossless JSON');
}
async function readBoundedJson(response, maxBytes = 2 * 1024 * 1024) {
  const declared = response.headers.get('content-length');
  if (declared && Number(declared) > maxBytes) { await response.body?.cancel(); throw new Error('Bridge response too large'); }
  if (!response.body) throw new Error('Empty bridge response');
  const reader = response.body.getReader(); const chunks = []; let total = 0;
  try {
    for (;;) {
      const { done, value } = await reader.read(); if (done) break;
      total += value.byteLength; if (total > maxBytes) { await reader.cancel(); throw new Error('Bridge response too large'); }
      chunks.push(value);
    }
  } finally { reader.releaseLock(); }
  return JSON.parse(Buffer.concat(chunks).toString('utf8'));
}

/** Plain HTTP adapter: no model calls, no automatic retry of side-effecting requests. */
export class BodyClient {
  #token;
  #lease;
  #fetch;
  constructor({ baseUrl, token, owner, fetchImpl = fetch, timeoutMs = 5000 }) {
    this.baseUrl = validateBaseUrl(baseUrl); this.#token = token; this.owner = owner;
    if (typeof token !== 'string' || !/^[A-Za-z0-9_-]{32,256}$/.test(token)) throw new Error('AIBOT_BRIDGE_TOKEN must be 32..256 URL-safe characters');
    this.#fetch = fetchImpl; this.timeoutMs = timeoutMs; this.uncertainRequest = null;
  }
  get connected() { return this.#lease !== undefined; }
  async request(method, path, { signal, timeoutMs = this.timeoutMs, requestId, body, owner } = {}) {
    const headers = { Authorization: `Bearer ${this.#token}` };
    if (this.#lease) headers['X-Control-Token'] = this.#lease.token;
    if (requestId) headers['X-Request-Id'] = requestId;
    if (owner) headers['X-Owner-Id'] = owner;
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    const signals = [AbortSignal.timeout(timeoutMs)]; if (signal) signals.push(signal);
    const response = await this.#fetch(this.baseUrl + path, {
      method, headers, body, signal: AbortSignal.any(signals), redirect: 'error',
    });
    const envelope = await readBoundedJson(response);
    if (!response.ok || !envelope?.ok) throw new BridgeError(response.status, String(envelope?.error ?? 'bridge_error'));
    return envelope.data;
  }
  async connect(signal) {
    const lease = await this.request('POST', '/v1/lease', { owner: this.owner, signal });
    if (!lease?.token || !lease?.event_epoch || !Number.isSafeInteger(lease.event_sequence)) throw new Error('Malformed lease response');
    this.#lease = lease;
    // The secret token is never returned to the model or persisted to the cursor file.
    return { owner: lease.owner, lease_epoch: lease.lease_epoch, expires_in_ms: lease.expires_in_ms,
      event_epoch: lease.event_epoch, event_sequence: lease.event_sequence };
  }
  async renew(signal) {
    this.requireConnected();
    try { return await this.request('POST', '/v1/lease/renew', { signal }); }
    catch (e) { if (e instanceof BridgeError && e.status === 409) this.#lease = undefined; throw e; }
  }
  requireConnected() { if (!this.#lease) throw new Error('No control lease. Call mc_connect first.'); }
  status(signal) { return this.request('GET', '/v1/status', { signal }); }
  observe(signal) { return this.request('GET', '/v1/observe', { signal }); }
  execution(id, signal) { return this.request('GET', '/v1/executions/' + encodeURIComponent(id), { signal }); }
  async lookup(requestId, signal) {
    const result = await this.request('GET', '/v1/requests/' + encodeURIComponent(requestId), { signal });
    if (this.uncertainRequest === requestId) this.uncertainRequest = null;
    return result;
  }
  async execute(operation, args, requestId, signal) {
    this.requireConnected();
    if (this.uncertainRequest && this.uncertainRequest !== requestId) {
      throw new Error(`Resolve request ${this.uncertainRequest} with mc_request_status before submitting another action.`);
    }
    const body = stableJson(args);
    if (Buffer.byteLength(body) > 16384) throw new Error('Arguments too large');
    if (signal?.aborted) throw signal.reason ?? new Error('Aborted before submission');
    try {
      const result = await this.request('POST', '/v1/executions/' + encodeURIComponent(operation), { body, requestId, signal });
      this.uncertainRequest = null; return result;
    } catch (e) {
      if (e instanceof BridgeError && e.status < 500) throw e;
      this.uncertainRequest = requestId;
      return { state: 'outcome_unknown', request_id: requestId, lookup_required: true,
        reason: 'Transport did not confirm acceptance. Query this request id; do not repeat with a new id.' };
    }
  }
  async control(id, action, requestId, signal) {
    this.requireConnected();
    if (signal?.aborted) throw signal.reason ?? new Error('Aborted before submission');
    try { return await this.request('POST', `/v1/executions/${encodeURIComponent(id)}/${encodeURIComponent(action)}`, { requestId, signal }); }
    catch (e) {
      if (e instanceof BridgeError && e.status < 500) throw e;
      this.uncertainRequest = requestId;
      return { state: 'outcome_unknown', request_id: requestId, lookup_required: true, reason: 'Control acknowledgement was lost; query request status.' };
    }
  }
  events(cursor, signal, waitMs = 20000) {
    return this.request('GET', `/v1/events?epoch=${encodeURIComponent(cursor.epoch)}&after=${cursor.sequence}&wait_ms=${waitMs}`,
      { signal, timeoutMs: waitMs + 5000 });
  }
  async release() {
    if (!this.#lease) return { released: false };
    try { return await this.request('DELETE', '/v1/lease', { timeoutMs: 2000 }); }
    finally { this.#lease = undefined; }
  }
}
