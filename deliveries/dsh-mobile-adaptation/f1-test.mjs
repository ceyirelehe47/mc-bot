#!/usr/bin/env node
// F1 侧栏 drawer 验证：收起态截图 → 点汉堡 → 展开态截图 → 点遮罩 → 验证关闭
import { writeFileSync } from 'node:fs';

const CDP = 'http://127.0.0.1:9333';
const out = '/root/dsh-mobile-adaptation';

const target = await (await fetch(`${CDP}/json/new?about:blank`, { method: 'PUT' })).json();
const ws = new WebSocket(target.webSocketDebuggerUrl);
let seq = 0;
const pending = new Map();
const events = [];

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
  } else if (data.method) events.push(data);
};

await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = reject; });
await send('Page.enable');
await send('Runtime.enable');
await send('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 2, mobile: true });
await send('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 5 });
await send('Page.navigate', { url: 'http://127.0.0.1:3080/' });
await new Promise((r) => setTimeout(r, 9000));

async function shot(name) {
  const s = await send('Page.captureScreenshot', { format: 'png' });
  writeFileSync(`${out}/${name}.png`, Buffer.from(s.data, 'base64'));
}
async function diag(label) {
  const r = await send('Runtime.evaluate', {
    returnByValue: true,
    expression: `(() => {
      const frame = document.querySelector('#root > div > div[style*="grid-template-columns"]');
      const fab = document.querySelector('.dsh-ma-fab');
      const backdrop = document.querySelector('.dsh-ma-backdrop');
      const sideCol = frame?.querySelector(':scope > div');
      const r = sideCol?.getBoundingClientRect();
      return {
        label: ${JSON.stringify(label)},
        frameGrid: frame ? getComputedStyle(frame).gridTemplateColumns : null,
        sidebarCollapsedAttr: frame?.hasAttribute('data-sidebar-collapsed'),
        fabVisible: fab ? getComputedStyle(fab).display : 'missing',
        fabRect: fab ? JSON.stringify(fab.getBoundingClientRect().toJSON()) : null,
        backdropDisplay: backdrop ? getComputedStyle(backdrop).display : 'missing',
        sideCol: r ? { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height), position: getComputedStyle(sideCol).position } : null,
        bodyOverflow: getComputedStyle(document.body).overflow,
        hOverflow: document.documentElement.scrollWidth > document.documentElement.clientWidth,
      };
    })()`,
  });
  return r.result.value;
}

const results = [];
results.push(await diag('closed-initial'));
await shot('f1-closed');
console.log(JSON.stringify(results[0], null, 2));

// 点击汉堡按钮
await send('Runtime.evaluate', { expression: `document.querySelector('.dsh-ma-fab')?.click()` });
await new Promise((r) => setTimeout(r, 1200));
results.push(await diag('open-after-fab-click'));
await shot('f1-open');
console.log(JSON.stringify(results[1], null, 2));

// 点击遮罩关闭
await send('Runtime.evaluate', { expression: `document.querySelector('.dsh-ma-backdrop')?.click()` });
await new Promise((r) => setTimeout(r, 1200));
results.push(await diag('closed-after-backdrop-click'));
console.log(JSON.stringify(results[2], null, 2));

writeFileSync(`${out}/f1-results.json`, JSON.stringify(results, null, 2));
await fetch(`${CDP}/json/close/${target.id}`, { method: 'PUT' }).catch(() => {});
ws.close();
console.log('done');
