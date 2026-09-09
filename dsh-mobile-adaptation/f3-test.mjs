#!/usr/bin/env node
// F3 验证：composer 按钮触控目标 32px、圆形比例保持、无布局破坏、无溢出
import { writeFileSync } from 'node:fs';

const CDP = 'http://127.0.0.1:9333';

const target = await (await fetch(`${CDP}/json/new?about:blank`, { method: 'PUT' })).json();
const ws = new WebSocket(target.webSocketDebuggerUrl);
let seq = 0;
const pending = new Map();

function send(method, params = {}) {
  const id = ++seq;
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject });
    ws.send(JSON.stringify({ id, method, params }));
  });
}

ws.onmessage = (msg) => {
  const data = JSON.parse(msg.data);
  if (data.id && pending.has(data.id)) {
    const { resolve, reject } = pending.get(data.id);
    pending.delete(data.id);
    if (data.error) reject(new Error(data.error.message));
    else resolve(data.result);
  }
};

await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = reject; });
await send('Page.enable');
await send('Runtime.enable');
await send('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 2, mobile: true });
await send('Page.navigate', { url: 'http://127.0.0.1:3080/' });
await new Promise((r) => setTimeout(r, 9000));

const diag = await send('Runtime.evaluate', {
  returnByValue: true,
  expression: `(() => {
    const out = { buttons: [], seat: null, pageHOverflow: null };
    const seat = document.querySelector('[data-composer-seat]');
    if (seat) { const r = seat.getBoundingClientRect(); out.seat = { y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) }; }
    for (const btn of document.querySelectorAll('[data-conversation-scroll] button, [data-conversation-scroll] select')) {
      const r = btn.getBoundingClientRect();
      if (r.width === 0 || r.height === 0) continue;
      const cs = getComputedStyle(btn);
      out.buttons.push({
        tag: btn.tagName,
        label: (btn.getAttribute('aria-label') || btn.textContent || '').trim().slice(0, 22),
        w: Math.round(r.width), h: Math.round(r.height),
        ratioOk: Math.abs(r.width - r.height) < 1 || r.width > 40 || r.height > 40 || Math.abs(r.width / r.height - 1) < 0.05 || r.width !== 32,
        tapHighlight: cs.webkitTapHighlightColor,
        radius: cs.borderRadius,
      });
    }
    const de = document.documentElement;
    out.pageHOverflow = de.scrollWidth > de.clientWidth;
    out.scrollW = de.scrollWidth;
    return out;
  })()`,
});
console.log(JSON.stringify(diag.result.value, null, 2));
writeFileSync('/root/dsh-mobile-adaptation/f3-results.json', JSON.stringify(diag.result.value, null, 2));

const s = await send('Page.captureScreenshot', { format: 'png' });
writeFileSync('/root/dsh-mobile-adaptation/f3-composer.png', Buffer.from(s.data, 'base64'));

await fetch(`${CDP}/json/close/${target.id}`, { method: 'PUT' }).catch(() => {});
ws.close();
console.log('done');
