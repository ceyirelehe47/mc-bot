#!/usr/bin/env node
// F4 实测：点开 Settings，dump 设置面板 DOM 结构 + 截图
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

// 开 drawer → 点 Settings
await send('Runtime.evaluate', { expression: `document.querySelector('.dsh-ma-fab')?.click()` });
await new Promise((r) => setTimeout(r, 1000));
await send('Runtime.evaluate', { expression: `(() => { for (const b of document.querySelectorAll('button')) { if ((b.getAttribute('aria-label') || '').trim() === 'Settings') { b.click(); return true; } } return false; })()` });
await new Promise((r) => setTimeout(r, 1500));

const diag = await send('Runtime.evaluate', {
  returnByValue: true,
  expression: `(() => {
    const de = document.documentElement;
    // 找 z-index 1000 的 fixed overlay
    let overlay = null;
    for (const el of document.body.children) {
      const cs = getComputedStyle(el);
      if (cs.position === 'fixed' && (Number(cs.zIndex) || 0) >= 1000 && el !== document.body) { overlay = el; break; }
    }
    if (!overlay) return { error: 'no settings overlay', bodyChildren: Array.from(document.body.children).map((e) => e.tagName + '.' + String(e.className).slice(0, 30)) };
    const panel = overlay.querySelector(':scope > div:not([class*="mask"])');
    const describe = (el, depth) => {
      if (!el || depth > 2) return null;
      return { tag: el.tagName, cls: String(el.className).slice(0, 44), kids: Array.from(el.children).slice(0, 8).map((c) => describe(c, depth + 1)).filter(Boolean) };
    };
    const r = panel.getBoundingClientRect();
    const nav = panel.children[0];
    const navR = nav?.getBoundingClientRect();
    const content = panel.querySelector(':scope > div:nth-child(2)');
    const cR = content?.getBoundingClientRect();
    return {
      overlayCls: String(overlay.className).slice(0, 40), overlayIsBodyChild: overlay.parentElement === document.body,
      panelCls: String(panel.className).slice(0, 40), panelRect: { w: Math.round(r.width), h: Math.round(r.height) },
      panelChildOrder: Array.from(panel.children).map((c) => String(c.className).slice(0, 36)),
      navRect: navR ? { w: Math.round(navR.width), h: Math.round(navR.height) } : null,
      contentRect: cR ? { w: Math.round(cR.width), h: Math.round(cR.height) } : null,
      tree: describe(panel, 0),
      pageHOverflow: de.scrollWidth > de.clientWidth,
    };
  })()`,
});
console.log(JSON.stringify(diag.result.value, null, 2).slice(0, 3000));

const s = await send('Page.captureScreenshot', { format: 'png' });
writeFileSync('/root/dsh-mobile-adaptation/f4-settings.png', Buffer.from(s.data, 'base64'));

await fetch(`${CDP}/json/close/${target.id}`, { method: 'PUT' }).catch(() => {});
ws.close();
console.log('done');
