#!/usr/bin/env node
// F2 消息内容防溢出验证：向会话滚动区注入长代码行/宽表格/长URL/大图的测试节点，
// 测量横向溢出并截图。
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

// 注入测试内容：复用官方 markdown 容器类名，走真实渲染样式
const inject = await send('Runtime.evaluate', {
  returnByValue: true,
  expression: `(() => {
    const scroll = document.querySelector('[data-conversation-scroll]');
    if (!scroll) return { error: 'no conversation scroll container' };
    const md = document.createElement('div');
    md.className = '_markdown_1nba0_5';
    md.style.padding = '12px 8px';
    md.innerHTML = [
      '<p>长链接测试：https://example.com/very/long/path/that/should/not/overflow/the/container/boundary/at/390px/width/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa</p>',
      '<pre><code>' + 'const veryLongLine = "this is a very long code line without spaces that would normally overflow the container width on mobile devices";'.repeat(2) + '</code></pre>',
      '<div class="_tableScroll_1nba0_174"><table><thead><tr><th>列一</th><th>列二</th><th>列三</th><th>列四</th><th>列五</th><th>列六</th></tr></thead><tbody><tr><td>数据内容比较长的一些单元格文本</td><td>数据内容比较长的一些单元格文本</td><td>数据内容比较长的一些单元格文本</td><td>数据内容比较长的一些单元格文本</td><td>数据内容比较长的一些单元格文本</td><td>数据内容比较长的一些单元格文本</td></tr></tbody></table></div>',
      '<p><img src="data:image/svg+xml;base64,' + btoa('<svg xmlns="http://www.w3.org/2000/svg" width="1200" height="300"><rect width="1200" height="300" fill="#4a90d9"/><text x="20" y="150" font-size="40" fill="#fff">1200px wide test image</text></svg>') + '" alt="wide"></p>',
    ].join('');
    scroll.appendChild(md);
    return { injected: true };
  })()`,
});
console.log(JSON.stringify(inject.result.value));
await new Promise((r) => setTimeout(r, 800));

const diag = await send('Runtime.evaluate', {
  returnByValue: true,
  expression: `(() => {
    const de = document.documentElement;
    const scroll = document.querySelector('[data-conversation-scroll]');
    const md = scroll?.querySelector('._markdown_1nba0_5');
    const pre = md?.querySelector('pre');
    const table = md?.querySelector('table');
    const img = md?.querySelector('img');
    const r = (el) => { if (!el) return null; const b = el.getBoundingClientRect(); return { w: Math.round(b.width), right: Math.round(b.right), scrollW: el.scrollWidth, clientW: el.clientWidth, overflowX: getComputedStyle(el).overflowX }; };
    return {
      pageHOverflow: de.scrollWidth > de.clientWidth,
      scrollW: de.scrollWidth,
      clientW: de.clientWidth,
      md: r(md), pre: r(pre), table: r(table), img: r(img),
    };
  })()`,
});
console.log(JSON.stringify(diag.result.value, null, 2));

const s = await send('Page.captureScreenshot', { format: 'png' });
writeFileSync(`${out}/f2-content.png`, Buffer.from(s.data, 'base64'));

await fetch(`${CDP}/json/close/${target.id}`, { method: 'PUT' }).catch(() => {});
ws.close();
console.log('done');
