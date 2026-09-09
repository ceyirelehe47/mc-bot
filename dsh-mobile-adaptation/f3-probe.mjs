#!/usr/bin/env node
// F3 调研（只读）：390px 下 composer/会话 header/消息区的按钮触控目标与间距现状
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
    const out = { buttons: [], keyRects: {} };
    // composer 卡与 header 的位置
    for (const sel of ['.wSkVaW_header', '.bqrRRG_card', '.wSkVaW_composerSeat']) {
      const el = document.querySelector(sel);
      if (el) { const r = el.getBoundingClientRect(); out.keyRects[sel] = { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) }; }
    }
    // 所有可见 button 的尺寸分布（composer + header + sidebar 外的一切）
    for (const btn of document.querySelectorAll('button')) {
      const r = btn.getBoundingClientRect();
      if (r.width === 0 || r.height === 0) continue;
      const inSidebar = btn.closest('[style*="grid-template-columns"] > div:first-child') !== null;
      out.buttons.push({
        w: Math.round(r.width), h: Math.round(r.height),
        inSidebar,
        label: (btn.getAttribute('aria-label') || btn.textContent || '').trim().slice(0, 24),
        cls: String(btn.className).slice(0, 46),
      });
    }
    const visible = out.buttons.filter((b) => !b.inSidebar);
    out.summary = {
      total: out.buttons.length,
      visibleOutsideSidebar: visible.length,
      below40px: visible.filter((b) => b.h < 40).length,
      below32px: visible.filter((b) => b.h < 32).length,
    };
    out.visibleButtons = visible.slice(0, 30);
    delete out.buttons;
    return out;
  })()`,
});
console.log(JSON.stringify(diag.result.value, null, 2));
writeFileSync('/root/dsh-mobile-adaptation/f3-probe.json', JSON.stringify(diag.result.value, null, 2));

await fetch(`${CDP}/json/close/${target.id}`, { method: 'PUT' }).catch(() => {});
ws.close();
console.log('done');
