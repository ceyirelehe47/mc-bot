#!/usr/bin/env node
// F2 返修复测：:where() 降权后，官方 cover 布局（附件缩略图/gallery）应恢复 height:100%，
// 裸 img 仍受 max-width 保护；原有 pre/table/长链接回归。
import { writeFileSync } from 'node:fs';

const CDP = 'http://127.0.0.1:9333';
const out = '/root/dsh-mobile-adaptation';

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

const wideImg = 'data:image/svg+xml;base64,' + btoa('<svg xmlns="http://www.w3.org/2000/svg" width="800" height="300"><rect width="800" height="300" fill="#c34"/><text x="10" y="150" font-size="30" fill="#fff">800x300</text></svg>');
const tallImg = 'data:image/svg+xml;base64,' + btoa('<svg xmlns="http://www.w3.org/2000/svg" width="300" height="800"><rect width="300" height="800" fill="#3c4"/><text x="10" y="400" font-size="30" fill="#fff">300x800</text></svg>');

const inject = await send('Runtime.evaluate', {
  returnByValue: true,
  expression: `(() => {
    const scroll = document.querySelector('[data-conversation-scroll]');
    if (!scroll) return { error: 'no scroll' };
    const host = document.createElement('div');
    host.style.display = 'flex';
    host.style.gap = '8px';
    host.style.padding = '12px 8px';
    host.innerHTML = [
      // 官方附件缩略图结构（composer 附件 rail）：64x64 容器 + cover img
      '<div class="_thumbnail_1hk8w_35" style="width:64px;height:64px;overflow:hidden;flex:none"><img data-test="thumb" src="${tallImg}" style="width:100%;height:100%;object-fit:cover"></div>',
      // 官方 gallery frame 结构
      '<div class="_frame_nrncx_18" style="width:120px;height:90px;overflow:hidden;flex:none"><img data-test="gallery" src="${wideImg}" style="width:100%;height:100%;object-fit:cover"></div>',
      // 裸 img（markdown 之外的兜底对象）
      '<div style="flex:none"><img data-test="bare" src="${wideImg}"></div>',
      // markdown 大图（官方 ._image_1nba0_229）
      '<div class="_markdown_1nba0_5" style="width:340px"><img class="_image_1nba0_229" data-test="md" src="${wideImg}"></div>',
    ].join('');
    scroll.appendChild(host);
    return { injected: true };
  })()`,
});
console.log(JSON.stringify(inject.result.value));
await new Promise((r) => setTimeout(r, 800));

const diag = await send('Runtime.evaluate', {
  returnByValue: true,
  expression: `(() => {
    const out = {};
    for (const key of ['thumb', 'gallery', 'bare', 'md']) {
      const img = document.querySelector('[data-test="' + key + '"]');
      if (!img) { out[key] = 'missing'; continue; }
      const cs = getComputedStyle(img);
      const b = img.getBoundingClientRect();
      out[key] = { height: cs.height, maxWidth: cs.maxWidth, w: Math.round(b.width), h: Math.round(b.height) };
    }
    const de = document.documentElement;
    out.pageHOverflow = de.scrollWidth > de.clientWidth;
    return out;
  })()`,
});
console.log(JSON.stringify(diag.result.value, null, 2));

const s = await send('Page.captureScreenshot', { format: 'png' });
writeFileSync(`${out}/f2-retest.png`, Buffer.from(s.data, 'base64'));

await fetch(`${CDP}/json/close/${target.id}`, { method: 'PUT' }).catch(() => {});
ws.close();
console.log('done');
