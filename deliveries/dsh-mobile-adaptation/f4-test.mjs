#!/usr/bin/env node
// F4 注入式验证：构造官方结构的设置弹窗 DOM（官方类名命中官方 CSS），
// 验证窄屏下侧导航横化、内容区宽度恢复。
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

const inject = await send('Runtime.evaluate', {
  returnByValue: true,
  expression: `(() => {
    // 按官方 JSX 结构构造：overlay > mask + panel(role=dialog) > nav(navTitle+navList) + content(header+close)
    const overlay = document.createElement('div');
    overlay.className = 'VOzbGW_overlay';
    overlay.innerHTML = [
      '<div class="VOzbGW_mask"></div>',
      '<div class="VOzbGW_panel" role="dialog" aria-modal="true">',
      '  <nav class="VOzbGW_nav">',
      '    <div class="VOzbGW_navTitle">Settings</div>',
      '    <div class="VOzbGW_navList">',
      '      <button type="button" class="VOzbGW_navCell">General</button>',
      '      <button type="button" class="VOzbGW_navCell">Models</button>',
      '      <button type="button" class="VOzbGW_navCell">Plugins</button>',
      '      <button type="button" class="VOzbGW_navCell">Usage</button>',
      '      <button type="button" class="VOzbGW_navCell">Advanced</button>',
      '    </div>',
      '  </nav>',
      '  <div class="VOzbGW_content"><div class="VOzbGW_header"><div class="VOzbGW_actions"></div><button class="VOzbGW_close">✕</button></div><div style="padding:16px">设置内容区域：这里放具体的设置表单项，验证内容区在窄屏下的可用宽度。</div></div>',
      '</div>',
    ].join('');
    document.body.appendChild(overlay);
    return { injected: true };
  })()`,
});
console.log(JSON.stringify(inject.result.value));
await new Promise((r) => setTimeout(r, 800));

const diag = await send('Runtime.evaluate', {
  returnByValue: true,
  expression: `(() => {
    const panel = document.querySelector('[role="dialog"][aria-modal="true"]');
    const nav = panel?.querySelector(':scope > nav');
    const navList = nav?.querySelector(':scope > div:last-child');
    const content = panel?.querySelector(':scope > div:nth-child(2)');
    const rect = (el) => { if (!el) return null; const r = el.getBoundingClientRect(); const cs = getComputedStyle(el); return { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height), direction: cs.flexDirection, display: cs.display }; };
    return { panel: rect(panel), nav: rect(nav), navList: rect(navList), content: rect(content) };
  })()`,
});
console.log(JSON.stringify(diag.result.value, null, 2));

const s = await send('Page.captureScreenshot', { format: 'png' });
writeFileSync('/root/dsh-mobile-adaptation/f4-modal.png', Buffer.from(s.data, 'base64'));

await fetch(`${CDP}/json/close/${target.id}`, { method: 'PUT' }).catch(() => {});
ws.close();
console.log('done');
