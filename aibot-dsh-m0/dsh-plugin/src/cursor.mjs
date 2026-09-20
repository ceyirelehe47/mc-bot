import { mkdir, open, rename, readFile } from 'node:fs/promises';
import { dirname } from 'node:path';
import { randomUUID } from 'node:crypto';

/** Checkpoint only AFTER delivery awaits DSH inbox enqueue AND sessionPersistence.flush(). Crash duplicates remain possible. */
export class CursorStore {
  constructor(path) { this.path = path; this.queue = Promise.resolve(); }
  async load() {
    try {
      const value = JSON.parse(await readFile(this.path, 'utf8'));
      if (value.version !== 1 || typeof value.epoch !== 'string' || !Number.isSafeInteger(value.sequence) || value.sequence < 0) {
        throw new Error('Invalid cursor file. Preserve it for diagnosis; do not silently reset.');
      }
      return { epoch: value.epoch, sequence: value.sequence };
    } catch (e) { if (e.code === 'ENOENT') return null; throw e; }
  }
  save(cursor) {
    const value = { version: 1, epoch: cursor.epoch, sequence: cursor.sequence };
    const pending = this.queue.then(async () => {
      await mkdir(dirname(this.path), { recursive: true, mode: 0o700 });
      const temp = this.path + '.' + randomUUID() + '.tmp';
      const fd = await open(temp, 'wx', 0o600);
      try { await fd.writeFile(JSON.stringify(value) + '\n'); await fd.sync(); } finally { await fd.close(); }
      await rename(temp, this.path);
      // Directory sync is best-effort on platforms which do not support opening directories.
      let dir;
      try { dir = await open(dirname(this.path), 'r'); await dir.sync(); } catch (e) {
        if (!['EINVAL','EPERM','EISDIR','ENOTSUP','EBADF'].includes(e.code)) throw e;
      } finally { await dir?.close(); }
    });
    this.queue = pending.catch(() => {}); return pending;
  }
}
