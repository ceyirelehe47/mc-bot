#!/usr/bin/env node
// dsh webui 移动端适配辅助：CDP 截图 + 布局诊断
// 用法: node shot.mjs <outPrefix> [width] [height] [url] [waitMs]
import { writeFileSync } from 'node:fs';

const CDP = 'http://127.0.0.1:9333';
const outPrefix = process.argv[2] ?? '/tmp/shot';
const width = Number(process.argv[3] ?? 390);
const height = Number(process.argv[4] ?? 844);
const url = process.argv[5] ?? 'http://127.0.0.1:3080/';
const waitMs = Number(process.argv[6] ?? 6000);

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

const loadFired = new Promise((resolve) => {
  const timer = setInterval(() => {
    if (events.some((e) => e.method === 'Page.loadEventFired')) {
      clearInterval(timer);
      resolve();
    }
  }, 100);
});

ws.onmessage = (msg) => {
  const data = JSON.parse(msg.data);
  if (data.id && pending.has(data.id)) {
    const { resolve, reject } = pending.get(data.id);
    pending.delete(data.id);
    if (data.error) reject(new Error(data.error.message));
    else resolve(data.result);
  } else if (data.method) {
    events.push(data);
  }
};

await new Promise((resolve, reject) => {
  ws.onopen = resolve;
  ws.onerror = reject;
});

await send('Page.enable');
await send('Runtime.enable');
await send('Emulation.setDeviceMetricsOverride', {
  width,
  height,
  deviceScaleFactor: 2,
  mobile: true,
});
await send('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 5 });
await send('Page.navigate', { url });
await Promise.race([loadFired, new Promise((r) => setTimeout(r, 12000))]);
await new Promise((r) => setTimeout(r, waitMs));

const shot = await send('Page.captureScreenshot', { format: 'png' });
writeFileSync(`${outPrefix}.png`, Buffer.from(shot.data, 'base64'));

// 布局诊断：横向溢出、关键容器宽度
const diag = await send('Runtime.evaluate', {
  returnByValue: true,
  expression: `(() => {
    const de = document.documentElement;
    const out = {
      viewport: { w: innerWidth, h: innerHeight },
      scroll: { w: de.scrollWidth, h: de.scrollHeight },
      horizontalOverflow: de.scrollWidth > de.clientWidth,
      panels: [],
    };
    const frame = document.querySelector('[data-shell-frame], .pI_x6G_frame, [class*="frame"]');
    for (const el of document.querySelectorAll('[data-conversation-scroll], [data-composer-seat], [data-conversation-composer-overlay], [data-sidebar], nav, aside')) {
      const r = el.getBoundingClientRect();
      out.panels.push({ sel: (el.dataset && Object.keys(el.dataset).join(',')) || el.tagName, cls: String(el.className).slice(0, 60), x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) });
    }
    if (frame) {
      const cs = getComputedStyle(frame);
      out.frameGrid = cs.gridTemplateColumns;
      out.frameAttrs = Object.keys(frame.dataset);
    }
    return out;
  })()`,
});
writeFileSync(`${outPrefix}.diag.json`, JSON.stringify(diag.result.value, null, 2));
console.log(JSON.stringify(diag.result.value, null, 2));

// 找出所有超出视口宽度的元素（横向溢出元凶）
const overflow = await send('Runtime.evaluate', {
  returnByValue: true,
  expression: `(() => {
    const vw = innerWidth;
    const bad = [];
    for (const el of document.querySelectorAll('*')) {
      const r = el.getBoundingClientRect();
      if (r.width > 0 && (r.right > vw + 1 || r.left < -1) && !el.closest('[data-conversation-scroll]')) {
        bad.push({ tag: el.tagName, cls: String(el.className).slice(0, 70), left: Math.round(r.left), right: Math.round(r.right), w: Math.round(r.width), text: (el.textContent || '').trim().slice(0, 40) });
        if (bad.length >= 25) break;
      }
    }
    return bad;
  })()`,
});
writeFileSync(`${outPrefix}.overflow.json`, JSON.stringify(overflow.result.value, null, 2));

await fetch(`${CDP}/json/close/${target.id}`, { method: 'PUT' }).catch(() => {});
ws.close();
console.log(`saved: ${outPrefix}.png / .diag.json / .overflow.json`);
