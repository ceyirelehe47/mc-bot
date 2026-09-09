#!/usr/bin/env node
// F4 调研（只读）：移动端弹层表现 —— Select model 菜单、settings 入口、modal 尺寸
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

async function layerDiag(label) {
  const r = await send('Runtime.evaluate', {
    returnByValue: true,
    expression: `(() => {
      const de = document.documentElement;
      const out = { label: ${JSON.stringify(label)}, pageHOverflow: de.scrollWidth > de.clientWidth, layers: [] };
      // 高层浮层：popover/list/menu/modal 等 z-index>=90 的可见元素
      for (const el of document.querySelectorAll('body *')) {
        const cs = getComputedStyle(el);
        const z = Number(cs.zIndex) || 0;
        if (z < 90 || cs.display === 'none' || cs.visibility === 'hidden') continue;
        const r = el.getBoundingClientRect();
        if (r.width < 8 || r.height < 8) continue;
        // 只报叶子级容器（子元素少的大块）
        out.layers.push({ tag: el.tagName, cls: String(el.className).slice(0, 50), z, x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height), offscreen: r.right > 391 || r.left < -1 });
      }
      return out;
    })()`,
  });
  return r.result.value;
}

// 1) 点 Select model 弹菜单
await send('Runtime.evaluate', { expression: `(() => { for (const b of document.querySelectorAll('button')) { if ((b.textContent || '').includes('Select model')) { b.click(); return true; } } return false; })()` });
await new Promise((r) => setTimeout(r, 1500));
const menu = await layerDiag('select-model-menu');
console.log(JSON.stringify(menu, null, 2).slice(0, 2500));
const s1 = await send('Page.captureScreenshot', { format: 'png' });
writeFileSync('/root/dsh-mobile-adaptation/f4-menu.png', Buffer.from(s1.data, 'base64'));
// 关闭菜单（Escape）
await send('Runtime.evaluate', { expression: `document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))` });
await new Promise((r) => setTimeout(r, 600));

// 2) 打开 drawer 找设置入口
await send('Runtime.evaluate', { expression: `document.querySelector('.dsh-ma-fab')?.click()` });
await new Promise((r) => setTimeout(r, 1000));
const sidebarBtns = await send('Runtime.evaluate', {
  returnByValue: true,
  expression: `(() => Array.from(document.querySelectorAll('#root button')).filter(b => { const r = b.getBoundingClientRect(); return r.width > 0; }).map(b => ({ label: (b.getAttribute('aria-label') || b.textContent || '').trim().slice(0, 26), cls: String(b.className).slice(0, 36) })))()`,
});
console.log('SIDEBAR BUTTONS:', JSON.stringify(sidebarBtns.result.value, null, 2).slice(0, 1800));

const s2 = await send('Page.captureScreenshot', { format: 'png' });
writeFileSync('/root/dsh-mobile-adaptation/f4-drawer.png', Buffer.from(s2.data, 'base64'));

await fetch(`${CDP}/json/close/${target.id}`, { method: 'PUT' }).catch(() => {});
ws.close();
console.log('done');
